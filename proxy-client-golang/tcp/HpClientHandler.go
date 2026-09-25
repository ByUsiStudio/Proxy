package tcp

import (
	"encoding/json"
	"errors"
	"net"
	"strings"
	"sync"
	"sync/atomic"

	"proxy-client-golang/Protol"
	"proxy-client-golang/hpMessage"
)

// maxChannelPerTunnel 单个隧道允许的最大并发内网连接数，用于防止资源耗尽。
const maxChannelPerTunnel = 4096

// maxRemoteMsgRunes 来自穿透服务端的文本在进入控制台日志前的最大字符数。
const maxRemoteMsgRunes = 512

// HandlerStats 是单个隧道的实时运行数据快照。
type HandlerStats struct {
	Active     bool
	InBytes    int64
	OutBytes   int64
	ConnCount  int64
	TotalConns int64
}

type HpClientHandler struct {
	Port         int
	Password     string
	Username     string
	Domain       string
	MessageType  hpMessage.HpMessage_MessageType
	ProxyAddress string
	ProxyPort    int
	CallMsg      func(message string)

	// Conn 是云端的控制连接。所有写入都必须经过 writeFrame/connMu，
	// 因为每条内网连接的读 goroutine 都会并发调用 WriteToRemote。
	Conn net.Conn

	// 运行时状态。所有字段都通过原子操作或 sync.Map 访问，
	// 因为读写发生在多个 goroutine（HTTP 控制台、读循环、内网连接）中。
	active     atomic.Bool
	conns      sync.Map // channelId(string) -> net.Conn
	connCount  atomic.Int64
	totalConns atomic.Int64
	inBytes    atomic.Int64
	outBytes   atomic.Int64

	// connMu 串行化对 Conn 的写入。
	// 【安全修复】原来所有内网读 goroutine 直接并发写同一个连接，
	// 大 DATA 帧会互相交错，破坏协议流（数据错乱 / 连接被服务端断开）。
	connMu sync.Mutex
}

func (h *HpClientHandler) callMsg(message string) {
	if h.CallMsg != nil {
		h.CallMsg(message)
	}
}

// sanitizeRemote 净化来自穿透服务端（不可信来源）的文本：
//   - 去掉 CR/LF 等控制字符，避免服务端伪造/拆分控制台日志行；
//   - 截断超长内容，避免刷屏；
//   - 统一加来源前缀，使“隧道已停止”这类本地语义无法被服务端仿冒。
func sanitizeRemote(message string) string {
	cleaned := strings.Map(func(r rune) rune {
		if r == '\t' {
			return ' '
		}
		if r < 0x20 || r == 0x7f {
			return -1
		}
		return r
	}, message)
	cleaned = strings.TrimSpace(cleaned)
	if cleaned == "" {
		return ""
	}
	if runes := []rune(cleaned); len(runes) > maxRemoteMsgRunes {
		cleaned = string(runes[:maxRemoteMsgRunes]) + "…"
	}
	return "[远端] " + cleaned
}

// callMsgRemote 输出远端来源的消息，先净化再加来源前缀。
func (h *HpClientHandler) callMsgRemote(message string) {
	if cleaned := sanitizeRemote(message); cleaned != "" {
		h.callMsg(cleaned)
	}
}

// SetConn 保存云端连接（由 ChannelActive 在连接建立时调用）。
func (h *HpClientHandler) SetConn(conn net.Conn) {
	h.connMu.Lock()
	h.Conn = conn
	h.connMu.Unlock()
}

// RemoteConn 返回当前云端连接，可能为 nil。
func (h *HpClientHandler) RemoteConn() net.Conn {
	h.connMu.Lock()
	defer h.connMu.Unlock()
	return h.Conn
}

// writeFrame 串行地把一帧数据写到云端连接，保证多 goroutine 写入不交错。
func (h *HpClientHandler) writeFrame(frame []byte) error {
	if len(frame) == 0 {
		return errors.New("待发送的协议帧为空")
	}
	h.connMu.Lock()
	defer h.connMu.Unlock()
	if h.Conn == nil {
		return net.ErrClosed
	}
	_, err := h.Conn.Write(frame)
	return err
}

// IsActive 返回隧道是否处于已连接状态。
func (h *HpClientHandler) IsActive() bool {
	return h.active.Load()
}

// Stats 返回当前隧道的实时统计。
func (h *HpClientHandler) Stats() HandlerStats {
	return HandlerStats{
		Active:     h.active.Load(),
		InBytes:    h.inBytes.Load(),
		OutBytes:   h.outBytes.Load(),
		ConnCount:  h.connCount.Load(),
		TotalConns: h.totalConns.Load(),
	}
}

// ChannelActive 连接激活时，发送注册信息给云端
func (h *HpClientHandler) ChannelActive(conn net.Conn) {
	if conn == nil {
		return
	}
	h.SetConn(conn)
	h.active.Store(true)
	message := &hpMessage.HpMessage{
		Type: hpMessage.HpMessage_REGISTER,
		MetaData: &hpMessage.HpMessage_MetaData{
			Port:     int32(h.Port),
			Username: h.Username,
			Password: h.Password,
			Domain:   h.Domain,
		},
	}
	message.MetaData.Type = h.MessageType
	if err := h.writeFrame(Protol.Encode(message)); err != nil {
		h.callMsg("注册信息发送失败：" + err.Error())
	}
}

func (h *HpClientHandler) ChannelRead(conn net.Conn, data interface{}) {
	message, ok := data.(*hpMessage.HpMessage)
	if !ok || message == nil {
		return
	}
	switch message.Type {
	case hpMessage.HpMessage_REGISTER_RESULT:
		if message.MetaData != nil {
			h.callMsgRemote(message.MetaData.Reason)
		}
	case hpMessage.HpMessage_CONNECTED:
		h.connected(message)
	case hpMessage.HpMessage_DISCONNECTED:
		if message.MetaData != nil {
			h.Close(message.MetaData.ChannelId)
		}
	case hpMessage.HpMessage_DATA:
		h.writeData(message)
	case hpMessage.HpMessage_KEEPALIVE:
		h.callMsg("服务器端返回心跳数据")
		if err := h.writeFrame(Protol.Encode(&hpMessage.HpMessage{Type: hpMessage.HpMessage_KEEPALIVE})); err != nil {
			h.callMsg("心跳响应发送失败：" + err.Error())
		}
	default:
		if marshal, err := json.Marshal(message.MetaData); err == nil {
			h.callMsgRemote("未知类型数据：" + string(marshal))
		} else {
			h.callMsgRemote("未知类型数据")
		}
	}
}

func (h *HpClientHandler) ChannelInactive(conn net.Conn) {
	h.active.Store(false)
	// 底层连接断开时释放该隧道持有的全部内网连接，避免句柄泄漏。
	h.CloseAll()
}

func (h *HpClientHandler) connected(message *hpMessage.HpMessage) {
	if message.MetaData == nil {
		return
	}
	//如果是TCP数据包，我们就连接本地的TCP服务器
	if message.MetaData.Type == hpMessage.HpMessage_TCP {
		NewTcpConnection().Connect(h.ProxyAddress, h.ProxyPort, false, &LocalProxyHandler{
			HpClientHandler: h,
			RemoteChannelId: message.MetaData.ChannelId,
		}, h.CallMsg)
	}
	if message.MetaData.Type == hpMessage.HpMessage_UDP {
		NewUdpConnection().Connect(h.ProxyAddress, h.ProxyPort, &LocalProxyUdpHandler{
			HpClientHandler: h,
			RemoteChannelId: message.MetaData.ChannelId,
		}, h.CallMsg)
	}
}

// Add 登记一条内网连接。超过并发上限时拒绝并立即关闭连接。
func (h *HpClientHandler) Add(channelId string, conn net.Conn) {
	if channelId == "" || conn == nil {
		return
	}
	// 并发上限用 CAS 循环保证“检查 + 占位”是原子的，
	// 否则多个 goroutine 可以同时通过检查，让实际连接数超过上限。
	for {
		cur := h.connCount.Load()
		if cur >= maxChannelPerTunnel {
			h.callMsg("并发连接数已达上限，拒绝新连接")
			_ = conn.Close()
			return
		}
		if h.connCount.CompareAndSwap(cur, cur+1) {
			break
		}
	}
	// LoadOrStore：channelId 重复时不能覆盖旧连接（旧连接会失去引用而泄漏 fd，
	// 且 connCount 会与实际数量不一致）。让后来者失败并关闭。
	if _, loaded := h.conns.LoadOrStore(channelId, conn); loaded {
		h.connCount.Add(-1)
		h.callMsg("重复的连接通道ID，已关闭重复连接")
		_ = conn.Close()
		return
	}
	h.totalConns.Add(1)
}

func (h *HpClientHandler) Close(channelId string) {
	load, ok := h.conns.LoadAndDelete(channelId)
	if !ok {
		return
	}
	h.connCount.Add(-1)
	if conn, ok := load.(net.Conn); ok && conn != nil {
		_ = conn.Close()
	}
}

func (h *HpClientHandler) CloseAll() {
	h.conns.Range(func(key, value interface{}) bool {
		if conn, ok := value.(net.Conn); ok && conn != nil {
			_ = conn.Close()
		}
		h.conns.Delete(key)
		return true
	})
	h.connCount.Store(0)
}

// WriteToRemote 把内网数据封装后写入云端连接，并累计上行流量。
func (h *HpClientHandler) WriteToRemote(dataType hpMessage.HpMessage_MessageType, channelId string, data []byte) error {
	if h.RemoteConn() == nil {
		return net.ErrClosed
	}
	message := &hpMessage.HpMessage{
		Type: hpMessage.HpMessage_DATA,
		Data: data,
		MetaData: &hpMessage.HpMessage_MetaData{
			Type:      dataType,
			ChannelId: channelId,
		},
	}
	// 单写者 + 互斥锁：与 web.go 的 WebSocket 推送保持一致的做法。
	err := h.writeFrame(Protol.Encode(message))
	if err == nil {
		h.outBytes.Add(int64(len(data)))
	}
	return err
}

func (h *HpClientHandler) writeData(message *hpMessage.HpMessage) {
	if message.MetaData == nil {
		return
	}
	if message.MetaData.Type != hpMessage.HpMessage_TCP && message.MetaData.Type != hpMessage.HpMessage_UDP {
		return
	}
	load, ok := h.conns.Load(message.MetaData.ChannelId)
	if !ok {
		return
	}
	conn, ok := load.(net.Conn)
	if !ok || conn == nil {
		return
	}
	n, err := conn.Write(message.Data)
	if err != nil {
		h.callMsg("写入内网服务失败：" + err.Error())
		return
	}
	h.inBytes.Add(int64(n))
}

package tcp

import (
	"encoding/json"
	"net"
	"sync"
	"sync/atomic"

	"proxy-client-golang/Protol"
	"proxy-client-golang/hpMessage"
)

// maxChannelPerTunnel 单个隧道允许的最大并发内网连接数，用于防止资源耗尽。
const maxChannelPerTunnel = 4096

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
	Conn         net.Conn

	// 运行时状态。所有字段都通过原子操作或 sync.Map 访问，
	// 因为读写发生在多个 goroutine（HTTP 控制台、读循环、内网连接）中。
	active     atomic.Bool
	conns      sync.Map // channelId(string) -> net.Conn
	connCount  atomic.Int64
	totalConns atomic.Int64
	inBytes    atomic.Int64
	outBytes   atomic.Int64
}

func (h *HpClientHandler) callMsg(message string) {
	if h.CallMsg != nil {
		h.CallMsg(message)
	}
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
	h.Conn = conn
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
	if _, err := conn.Write(Protol.Encode(message)); err != nil {
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
			h.callMsg(message.MetaData.Reason)
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
		if conn != nil {
			if _, err := conn.Write(Protol.Encode(&hpMessage.HpMessage{Type: hpMessage.HpMessage_KEEPALIVE})); err != nil {
				h.callMsg("心跳响应发送失败：" + err.Error())
			}
		}
	default:
		marshal, _ := json.Marshal(message.MetaData)
		h.callMsg("未知类型数据：" + string(marshal))
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
	if h.connCount.Load() >= maxChannelPerTunnel {
		h.callMsg("并发连接数已达上限，拒绝新连接")
		_ = conn.Close()
		return
	}
	h.conns.Store(channelId, conn)
	h.connCount.Add(1)
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
	if h.Conn == nil {
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
	_, err := h.Conn.Write(Protol.Encode(message))
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

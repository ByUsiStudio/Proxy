package web

import (
	"bytes"
	"crypto/rand"
	"crypto/subtle"
	"crypto/tls"
	"embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"path"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	HpMessage "proxy-client-golang/hpMessage"
	"proxy-client-golang/pkg/logger"
	"proxy-client-golang/tcp"

	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"
	qrcode "github.com/skip2/go-qrcode"
)

//
//go:embed *.html common img
var staticFs embed.FS

const (
	LogLevelDebug = iota
	LogLevelInfo
	LogLevelWarn
	LogLevelError
)

const (
	sessionCookieName = "proxy_console_token"
	// tokenHeaderName 控制台令牌请求头名称。
	tokenHeaderName = "X-Proxy-Token"
	// maxFormBytes 控制台表单请求体的最大字节数。
	maxFormBytes = 64 << 10
	// sessionTTLSeconds 会话有效期（秒）。
	sessionTTLSeconds = 12 * 3600
	// defaultWebPort 默认监听端口。
	defaultWebPort = 10240
	// defaultWebHost 默认只监听回环地址，避免把控制台暴露到局域网。
	defaultWebHost = "127.0.0.1"
	// wsMessageLimit WebSocket 单条消息上限。
	wsMessageLimit = 8 << 10
	// maxProxyBodyBytes 反向代理请求体上限。
	maxProxyBodyBytes = 16 << 20
	// maxWSPushBytes 推送给前端单条日志的上限，避免刷爆界面。
	maxWSPushBytes = 2 << 10
	// maxTunnels 单进程隧道数量上限，防止资源被无限占用。
	maxTunnels = 512
	// maxQRBytes 二维码可承载的最大字节数（QR 版本 40 / 纠错级别 M）。
	maxQRBytes = 2000
)

var (
	logLevel     = LogLevelInfo
	log          logger.Logger
	ApiUrl       = ""
	deviceID     = "NO_ID"
	CORE_VERSION = "1.0"
)

var (
	// ConnGroup 记录当前所有隧道：domain -> *tunnel
	ConnGroup   = sync.Map{}
	ConnWsGroup = sync.Map{} // *wsClient 集合

	upGrader = websocket.Upgrader{
		ReadBufferSize:   4096,
		WriteBufferSize:  4096,
		HandshakeTimeout: 10 * time.Second,
		CheckOrigin:      webSocketOriginAllowed,
	}

	// consoleToken 控制台访问令牌，进程启动时随机生成（或由 WEB_TOKEN 指定）。
	consoleToken string
	// tokenEnforced 表示用户显式配置了令牌，此时非本机访问必须携带令牌。
	tokenEnforced bool
	boundPort     int

	tunnelMu     sync.Mutex
	addLimiter   = newRateLimiter(30, time.Minute)
	apiClient    = &http.Client{Timeout: 15 * time.Second}
	apiTransport = &http.Transport{
		Proxy:                 http.ProxyFromEnvironment,
		DialContext:           (&net.Dialer{Timeout: 8 * time.Second, KeepAlive: 30 * time.Second}).DialContext,
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          64,
		MaxIdleConnsPerHost:   16,
		IdleConnTimeout:       60 * time.Second,
		TLSHandshakeTimeout:   8 * time.Second,
		ExpectContinueTimeout: 1 * time.Second,
		ResponseHeaderTimeout: 20 * time.Second,
	}
)

// ServerInfo 是隧道列表项。字段名保持与旧版本一致（大写），
// 仅追加新字段，保证既有调用方不被破坏。
type ServerInfo struct {
	Domain      string `json:"Domain"`
	Server      string `json:"Server"`
	ProxyServer string `json:"ProxyServer"`
	Status      bool   `json:"Status"`

	Type       string `json:"Type"`
	Target     string `json:"Target"`
	RemotePort int    `json:"RemotePort"`
	InBytes    int64  `json:"InBytes"`
	OutBytes   int64  `json:"OutBytes"`
	ConnCount  int64  `json:"ConnCount"`
	TotalConns int64  `json:"TotalConns"`
	Uptime     int64  `json:"Uptime"`
	Source     string `json:"Source"`
	CreatedAt  string `json:"CreatedAt"`
}

// Log 是推送给前端的实时日志。
type Log struct {
	Domain string `json:"domain"`
	Msg    string `json:"msg"`
	Level  string `json:"level"`
	Time   string `json:"time"`
}

type Res struct {
	Code int    `json:"Code"`
	Msg  string `json:"Msg"`
}

type DeviceInfo struct {
	Username   string `json:"username"`
	Password   string `json:"password"`
	UserHost   string `json:"userHost"`
	ServerHost string `json:"serverHost"`
	Type       string `json:"type"`
	Domain     string `json:"domain"`
	Port       string `json:"port"`
}

type DeviceData struct {
	Code int           `json:"code"`
	Msg  string        `json:"msg"`
	Data []*DeviceInfo `json:"data"`
}

type CoreVersion struct {
	Id            string `json:"id"`
	VersionCode   string `json:"versionCode"`
	UpdateContent string `json:"updateContent"`
	CreateTime    string `json:"createTime"`
}

type CoreData struct {
	Code int          `json:"code"`
	Msg  string       `json:"msg"`
	Data *CoreVersion `json:"data"`
}

// CoreVersionRes 是 /core/version 的响应。
// 与旧版本相比不再返回 HTML 片段，改为结构化字段，避免把服务端字符串直接注入页面。
type CoreVersionRes struct {
	Code          int    `json:"Code"`
	Msg           string `json:"Msg"`
	Current       string `json:"Current"`
	Latest        string `json:"Latest"`
	NeedUpdate    bool   `json:"NeedUpdate"`
	UpdateContent string `json:"UpdateContent"`
	CreateTime    string `json:"CreateTime"`
}

// TunnelMeta 描述一条隧道的静态配置。
type TunnelMeta struct {
	Domain     string
	Type       string
	ServerHost string
	ServerPort int
	TargetHost string
	TargetPort int
	Username   string
	RemotePort int
	CreatedAt  time.Time
	Source     string

	// password 只保存在内存中，不参与任何序列化。
	password string
}

type tunnel struct {
	meta   TunnelMeta
	client *tcp.HpClient
}

// ExportedTunnel 是配置导出的单条记录。
// password 仅在显式请求导出凭据时才会被填充。
type ExportedTunnel struct {
	Domain     string `json:"domain"`
	Type       string `json:"type"`
	ServerHost string `json:"serverHost"`
	ServerPort int    `json:"serverPort"`
	TargetHost string `json:"targetHost"`
	TargetPort int    `json:"targetPort"`
	RemotePort int    `json:"remotePort"`
	Username   string `json:"username,omitempty"`
	Password   string `json:"password,omitempty"`
}

type ExportPayload struct {
	Version    string           `json:"version"`
	ExportedAt string           `json:"exportedAt"`
	DeviceID   string           `json:"deviceId,omitempty"`
	WithSecret bool             `json:"withSecret"`
	Tunnels    []ExportedTunnel `json:"tunnels"`
}

func SetLogLevel(level int) {
	logLevel = level
}

func SetLogger(l logger.Logger) {
	log = l
}

// ---------------------------------------------------------------------------
// 认证 / CSRF
// ---------------------------------------------------------------------------

func generateToken() (string, error) {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return hex.EncodeToString(buf), nil
}

func constantTimeEqual(a, b string) bool {
	if a == "" || b == "" || len(a) != len(b) {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}

// requestToken 依次从请求头、查询参数与 Cookie 中提取控制台令牌。
func requestToken(c *gin.Context) string {
	if v := c.GetHeader(tokenHeaderName); v != "" {
		return v
	}
	if v := c.Query("token"); v != "" {
		return v
	}
	if ck, err := c.Cookie(sessionCookieName); err == nil && ck != "" {
		return ck
	}
	return ""
}

func isAuthorized(c *gin.Context) bool {
	return constantTimeEqual(requestToken(c), consoleToken)
}

// isSameSite 判断请求是否来自同源页面或非浏览器客户端。
// 浏览器会为所有请求附带 Sec-Fetch-Site，跨站请求无法伪造该头部。
func isSameSite(c *gin.Context) bool {
	switch strings.ToLower(c.GetHeader("Sec-Fetch-Site")) {
	case "", "same-origin", "none":
	default:
		return false
	}
	origin := c.GetHeader("Origin")
	if origin == "" {
		return true
	}
	u, err := url.Parse(origin)
	if err != nil {
		return false
	}
	return strings.EqualFold(u.Host, c.Request.Host)
}

func clientIsLoopback(c *gin.Context) bool {
	ip := net.ParseIP(c.ClientIP())
	return ip != nil && ip.IsLoopback()
}

func setSessionCookie(c *gin.Context) {
	http.SetCookie(c.Writer, &http.Cookie{
		Name:     sessionCookieName,
		Value:    consoleToken,
		Path:     "/",
		MaxAge:   sessionTTLSeconds,
		HttpOnly: true,
		SameSite: http.SameSiteStrictMode,
		Secure:   c.Request.TLS != nil,
	})
}

// establishSession 尝试建立控制台会话：同源 + （持有令牌 或 本机访问）。
func establishSession(c *gin.Context) bool {
	if !isSameSite(c) {
		return false
	}
	if isAuthorized(c) {
		setSessionCookie(c)
		return true
	}
	if clientIsLoopback(c) {
		setSessionCookie(c)
		return true
	}
	return false
}

func abortJSON(c *gin.Context, status int, msg string) {
	c.AbortWithStatusJSON(status, Res{Code: -1, Msg: msg})
}

// requireSession 保护所有控制类接口。
func requireSession() gin.HandlerFunc {
	return func(c *gin.Context) {
		if !isSameSite(c) {
			abortJSON(c, http.StatusForbidden, "跨站请求已被拒绝")
			return
		}
		if !isAuthorized(c) {
			abortJSON(c, http.StatusUnauthorized, "未授权：请通过控制台地址访问，或携带访问令牌")
			return
		}
		c.Next()
	}
}

func webSocketOriginAllowed(r *http.Request) bool {
	origin := r.Header.Get("Origin")
	if origin == "" {
		// 非浏览器客户端（脚本、命令行）没有 Origin。
		return true
	}
	u, err := url.Parse(origin)
	if err != nil {
		return false
	}
	return strings.EqualFold(u.Host, r.Host)
}

// ---------------------------------------------------------------------------
// 限流
// ---------------------------------------------------------------------------

type rateLimiter struct {
	mu     sync.Mutex
	limit  int
	window time.Duration
	hits   map[string][]time.Time
}

func newRateLimiter(limit int, window time.Duration) *rateLimiter {
	return &rateLimiter{limit: limit, window: window, hits: make(map[string][]time.Time)}
}

func (r *rateLimiter) allow(key string) bool {
	now := time.Now()
	r.mu.Lock()
	defer r.mu.Unlock()

	if len(r.hits) > 1024 {
		for k, v := range r.hits {
			if len(v) == 0 || now.Sub(v[len(v)-1]) > r.window {
				delete(r.hits, k)
			}
		}
	}

	kept := r.hits[key][:0]
	for _, t := range r.hits[key] {
		if now.Sub(t) <= r.window {
			kept = append(kept, t)
		}
	}
	r.hits[key] = kept
	if len(kept) >= r.limit {
		return false
	}
	r.hits[key] = append(r.hits[key], now)
	return true
}

// ---------------------------------------------------------------------------
// 隧道管理
// ---------------------------------------------------------------------------

func wsSend(msg Log) {
	if msg.Time == "" {
		msg.Time = time.Now().Format("15:04:05")
	}
	if len(msg.Msg) > maxWSPushBytes {
		msg.Msg = msg.Msg[:maxWSPushBytes] + "…"
	}
	ConnWsGroup.Range(func(key, _ interface{}) bool {
		client, ok := key.(*wsClient)
		if !ok || client == nil {
			return true
		}
		if err := client.writeJSON(msg); err != nil {
			ConnWsGroup.Delete(key)
			_ = client.Close()
		}
		return true
	})
}

// Proxy 创建隧道，保持旧签名与布尔返回值以兼容既有调用方。
func Proxy(messageType HpMessage.HpMessage_MessageType, server_ip string, server_port int, username string, password string, domain string, remote_port int, ip string, port int) bool {
	meta := TunnelMeta{
		Domain:     domain,
		Type:       messageTypeName(messageType),
		ServerHost: server_ip,
		ServerPort: server_port,
		TargetHost: ip,
		TargetPort: port,
		Username:   username,
		RemotePort: remote_port,
		CreatedAt:  time.Now(),
		Source:     "console",
		password:   password,
	}
	_, err := createTunnel(meta, messageType)
	return err == nil
}

func messageTypeName(messageType HpMessage.HpMessage_MessageType) string {
	switch messageType {
	case HpMessage.HpMessage_TCP:
		return "TCP"
	case HpMessage.HpMessage_UDP:
		return "UDP"
	case HpMessage.HpMessage_TCP_UDP:
		return "TCP_UDP"
	default:
		return "UNKNOWN"
	}
}

// createTunnel 在并发安全的前提下创建并注册一条隧道。
func createTunnel(meta TunnelMeta, messageType HpMessage.HpMessage_MessageType) (*tunnel, error) {
	if meta.Domain == "" {
		return nil, errors.New("域名不能为空")
	}

	tunnelMu.Lock()
	if _, exists := ConnGroup.Load(meta.Domain); exists {
		tunnelMu.Unlock()
		return nil, fmt.Errorf("域名 %s 已经被使用", meta.Domain)
	}
	if countTunnels() >= maxTunnels {
		tunnelMu.Unlock()
		return nil, fmt.Errorf("隧道数量已达上限 %d，请先停止部分隧道", maxTunnels)
	}
	t := &tunnel{meta: meta}
	ConnGroup.Store(meta.Domain, t)
	tunnelMu.Unlock()

	// 获取SSL配置
	var tlsConfig *tls.Config
	if sslCfg := tcp.GetSSLConfig(); sslCfg != nil && sslCfg.Enable {
		tlsConfig, _ = sslCfg.NewTLSConfig()
	}

	domain := meta.Domain
	hpClient := tcp.NewHpClient(func(message string) {
		log.Infof("[%s] %s", domain, message)
		wsSend(Log{Domain: domain, Msg: message, Level: "info"})
	})
	t.client = hpClient

	connect := func() {
		if tlsConfig != nil {
			hpClient.ConnectWithTLS(messageType, meta.ServerHost, meta.ServerPort, meta.Username, meta.password, meta.Domain, meta.RemotePort, meta.TargetHost, meta.TargetPort, tlsConfig)
			return
		}
		hpClient.Connect(messageType, meta.ServerHost, meta.ServerPort, meta.Username, meta.password, meta.Domain, meta.RemotePort, meta.TargetHost, meta.TargetPort)
	}
	connect()

	go func() {
		for {
			if hpClient.IsKill() {
				log.Infof("代理连接 %s 已终止", domain)
				if cur, ok := ConnGroup.Load(domain); ok && cur == t {
					ConnGroup.Delete(domain)
				}
				wsSend(Log{Domain: domain, Msg: "隧道已停止", Level: "warn"})
				return
			}
			if !hpClient.GetStatus() {
				log.Warnf("代理连接 %s 断开，尝试重连...", domain)
				connect()
				wsSend(Log{Domain: domain, Msg: "连接已断开，正在重连", Level: "warn"})
			}
			time.Sleep(5 * time.Second)
		}
	}()

	log.Infof("创建新的代理连接: 类型=%s 服务器=%s:%d 域名=%s 远程端口=%d 目标=%s:%d",
		meta.Type, meta.ServerHost, meta.ServerPort, meta.Domain, meta.RemotePort, meta.TargetHost, meta.TargetPort)
	return t, nil
}

func stopTunnel(domain string) bool {
	load, ok := ConnGroup.Load(domain)
	if !ok {
		return false
	}
	if t, ok := load.(*tunnel); ok && t != nil && t.client != nil {
		t.client.Kill()
	}
	ConnGroup.Delete(domain)
	return true
}

func snapshotTunnels() []ServerInfo {
	ret := make([]ServerInfo, 0)
	now := time.Now()
	ConnGroup.Range(func(key, value interface{}) bool {
		t, ok := value.(*tunnel)
		if !ok || t == nil || t.client == nil {
			return true
		}
		stats := t.client.Stats()
		ret = append(ret, ServerInfo{
			Domain:      t.meta.Domain,
			Server:      t.client.GetServer(),
			ProxyServer: t.client.GetProxyServer(),
			Status:      stats.Active,
			Type:        t.meta.Type,
			Target:      net.JoinHostPort(t.meta.TargetHost, strconv.Itoa(t.meta.TargetPort)),
			RemotePort:  t.meta.RemotePort,
			InBytes:     stats.InBytes,
			OutBytes:    stats.OutBytes,
			ConnCount:   stats.ConnCount,
			TotalConns:  stats.TotalConns,
			Uptime:      int64(now.Sub(t.meta.CreatedAt).Seconds()),
			Source:      t.meta.Source,
			CreatedAt:   t.meta.CreatedAt.Format("2006-01-02 15:04:05"),
		})
		return true
	})
	return ret
}

// ---------------------------------------------------------------------------
// 输入校验
// ---------------------------------------------------------------------------

var hostRe = regexp.MustCompile(`^[a-zA-Z0-9]([a-zA-Z0-9\-\.]{0,251}[a-zA-Z0-9])?$`)

func validHost(s string) bool {
	if s == "" || len(s) > 253 {
		return false
	}
	if net.ParseIP(s) != nil {
		return true
	}
	return hostRe.MatchString(s)
}

// splitHostPort 解析 host:port，允许 IPv6 字面量。
func splitHostPort(s string) (string, int, error) {
	host, portStr, err := net.SplitHostPort(strings.TrimSpace(s))
	if err != nil {
		return "", 0, errors.New("格式错误，请填写 ip:端口")
	}
	host = strings.Trim(host, "[]")
	if !validHost(host) {
		return "", 0, errors.New("主机地址不合法")
	}
	port, err := strconv.Atoi(portStr)
	if err != nil || port <= 0 || port > 65535 {
		return "", 0, errors.New("端口必须是 1-65535")
	}
	return host, port, nil
}

func parsePort(s string, allowZero bool) (int, error) {
	port, err := strconv.Atoi(strings.TrimSpace(s))
	if err != nil {
		return 0, errors.New("端口必须是数字")
	}
	if port == 0 && allowZero {
		return 0, nil
	}
	if port <= 0 || port > 65535 {
		return 0, errors.New("端口必须是 1-65535")
	}
	return port, nil
}

func credOK(s string) bool {
	return len(s) <= 200
}

// parseDomainList 解析逗号或换行分隔的域名列表。
func parseDomainList(raw string) []string {
	list := make([]string, 0)
	for _, d := range strings.FieldsFunc(raw, func(r rune) bool {
		return r == ',' || r == '\n' || r == '\r' || r == ';'
	}) {
		if d = strings.TrimSpace(d); d != "" {
			list = append(list, d)
		}
	}
	return list
}

// ---------------------------------------------------------------------------
// 隧道控制接口
// ---------------------------------------------------------------------------

func handleAddProxy(c *gin.Context) {
	if !addLimiter.allow(c.ClientIP()) {
		c.JSON(http.StatusOK, Res{Code: -1, Msg: "操作过于频繁，请稍后再试"})
		return
	}
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, maxFormBytes)

	ip := strings.TrimSpace(c.PostForm("ip"))
	portStr := strings.TrimSpace(c.PostForm("port"))
	serverInfo := strings.TrimSpace(c.PostForm("server_info"))
	username := c.PostForm("username")
	domain := strings.TrimSpace(c.PostForm("domain"))
	remotePortStr := strings.TrimSpace(c.PostForm("remote_port"))
	password := c.PostForm("password")
	proxyType := strings.ToUpper(strings.TrimSpace(c.PostForm("type")))

	if !credOK(username) || !credOK(password) {
		c.JSON(http.StatusOK, Res{Code: -1, Msg: "账号或密码长度超出限制"})
		return
	}

	var messageType HpMessage.HpMessage_MessageType
	switch proxyType {
	case "TCP":
		messageType = HpMessage.HpMessage_TCP
	case "UDP":
		messageType = HpMessage.HpMessage_UDP
	case "TCP_UDP":
		messageType = HpMessage.HpMessage_TCP_UDP
	default:
		c.JSON(http.StatusOK, Res{Code: -1, Msg: "穿透类型不合法"})
		return
	}

	targetPort, err := parsePort(portStr, false)
	if err != nil {
		c.JSON(http.StatusOK, Res{Code: -1, Msg: "内网端口不合法：" + err.Error()})
		return
	}
	if !validHost(ip) {
		c.JSON(http.StatusOK, Res{Code: -1, Msg: "内网IP不合法"})
		return
	}

	remotePort, err := parsePort(remotePortStr, true)
	if err != nil {
		if remotePortStr == "" {
			remotePort = 0
		} else {
			c.JSON(http.StatusOK, Res{Code: -1, Msg: "外网端口不合法：" + err.Error()})
			return
		}
	}

	if serverInfo == "" {
		c.JSON(http.StatusOK, Res{Code: -1, Msg: "未选择穿透的服务器，请选择后重试"})
		return
	}
	serverHost, serverPort, err := splitHostPort(serverInfo)
	if err != nil {
		c.JSON(http.StatusOK, Res{Code: -1, Msg: "穿透服务器地址不合法：" + err.Error()})
		return
	}

	if proxyType == "UDP" {
		domain = "udp:" + ip + ":" + strconv.Itoa(targetPort)
	} else {
		if domain == "" {
			c.JSON(http.StatusOK, Res{Code: -1, Msg: "域名不能为空，如果还没有添加，请菜单里添加域名，然后刷新配置后重试"})
			return
		}
		if !validHost(domain) {
			c.JSON(http.StatusOK, Res{Code: -1, Msg: "域名格式不合法"})
			return
		}
	}

	meta := TunnelMeta{
		Domain:     domain,
		Type:       proxyType,
		ServerHost: serverHost,
		ServerPort: serverPort,
		TargetHost: ip,
		TargetPort: targetPort,
		Username:   username,
		RemotePort: remotePort,
		CreatedAt:  time.Now(),
		Source:     "console",
		password:   password,
	}
	if _, err := createTunnel(meta, messageType); err != nil {
		log.Warnf("添加穿透失败: domain=%s 原因=%v", domain, err)
		c.JSON(http.StatusOK, Res{Code: -1, Msg: "添加失败！" + err.Error()})
		return
	}

	log.Infof("成功添加穿透: domain=%s server=%s target=%s:%d", domain, serverInfo, ip, targetPort)
	c.JSON(http.StatusOK, Res{Code: 200, Msg: "添加成功"})
}

func handleStopProxy(c *gin.Context) {
	domain := strings.TrimSpace(c.Query("domain"))
	if domain == "" {
		domain = strings.TrimSpace(c.PostForm("domain"))
	}
	if domain == "" {
		c.JSON(http.StatusOK, Res{Code: -1, Msg: "域名不能为空"})
		return
	}
	if stopTunnel(domain) {
		log.Infof("成功停止穿透: domain=%s", domain)
		c.JSON(http.StatusOK, Res{Code: 200, Msg: "已停止"})
		return
	}
	c.JSON(http.StatusOK, Res{Code: 200, Msg: "隧道不存在或已停止"})
}

// handleBatchStop 支持一次停止多条隧道，前端批量操作不再逐个发请求。
func handleBatchStop(c *gin.Context) {
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, maxFormBytes)

	domains := parseDomainList(c.PostForm("domains"))
	if len(domains) == 0 {
		domains = parseDomainList(c.Query("domains"))
	}
	if len(domains) == 0 {
		var payload struct {
			Domains []string `json:"domains"`
		}
		if err := json.NewDecoder(io.LimitReader(c.Request.Body, maxFormBytes)).Decode(&payload); err == nil {
			for _, d := range payload.Domains {
				if d = strings.TrimSpace(d); d != "" {
					domains = append(domains, d)
				}
			}
		}
	}
	if len(domains) == 0 {
		c.JSON(http.StatusOK, Res{Code: -1, Msg: "未指定要停止的隧道"})
		return
	}
	if len(domains) > 200 {
		c.JSON(http.StatusOK, Res{Code: -1, Msg: "单次最多停止 200 条隧道"})
		return
	}

	stopped, missing := 0, make([]string, 0)
	for _, d := range domains {
		if stopTunnel(d) {
			stopped++
		} else {
			missing = append(missing, d)
		}
	}
	c.JSON(http.StatusOK, gin.H{
		"Code":    200,
		"Msg":     fmt.Sprintf("已停止 %d 条隧道", stopped),
		"Stopped": stopped,
		"Missing": missing,
	})
}

func handleServerInfo(c *gin.Context) {
	c.JSON(http.StatusOK, snapshotTunnels())
}

func handleConsoleInfo(c *gin.Context) {
	tunnels := snapshotTunnels()
	var in, out, conns int64
	for _, t := range tunnels {
		in += t.InBytes
		out += t.OutBytes
		conns += t.ConnCount
	}
	c.JSON(http.StatusOK, gin.H{
		"Code": 200,
		"Msg":  "ok",
		"Data": gin.H{
			"coreVersion":    CORE_VERSION,
			"deviceId":       deviceID,
			"apiUrl":         ApiUrl,
			"tokenEnforced":  tokenEnforced,
			"tunnelCount":    len(tunnels),
			"onlineCount":    countOnline(tunnels),
			"inBytes":        in,
			"outBytes":       out,
			"activeConns":    conns,
			"consoleAddress": consoleAddress(),
		},
	})
}

func countOnline(tunnels []ServerInfo) int {
	n := 0
	for _, t := range tunnels {
		if t.Status {
			n++
		}
	}
	return n
}

func consoleAddress() string {
	host := os.Getenv("WEB_HOST")
	if host == "" || host == "0.0.0.0" || host == "::" {
		host = defaultWebHost
	}
	return fmt.Sprintf("http://%s:%d/", host, boundPort)
}

// ---------------------------------------------------------------------------
// 配置导出 / 导入
// ---------------------------------------------------------------------------

func handleConfigExport(c *gin.Context) {
	withSecret := c.Query("secrets") == "1"
	payload := ExportPayload{
		Version:    CORE_VERSION,
		ExportedAt: time.Now().Format("2006-01-02 15:04:05"),
		DeviceID:   deviceID,
		WithSecret: withSecret,
		Tunnels:    make([]ExportedTunnel, 0),
	}
	ConnGroup.Range(func(_, value interface{}) bool {
		t, ok := value.(*tunnel)
		if !ok || t == nil {
			return true
		}
		item := ExportedTunnel{
			Domain:     t.meta.Domain,
			Type:       t.meta.Type,
			ServerHost: t.meta.ServerHost,
			ServerPort: t.meta.ServerPort,
			TargetHost: t.meta.TargetHost,
			TargetPort: t.meta.TargetPort,
			RemotePort: t.meta.RemotePort,
			Username:   t.meta.Username,
		}
		if withSecret {
			item.Password = t.meta.password
		}
		payload.Tunnels = append(payload.Tunnels, item)
		return true
	})

	if c.Query("download") == "1" {
		c.Header("Content-Disposition", fmt.Sprintf("attachment; filename=proxy-tunnels-%s.json", time.Now().Format("20060102-150405")))
	}
	c.JSON(http.StatusOK, payload)
}

func handleConfigImport(c *gin.Context) {
	if !addLimiter.allow(c.ClientIP()) {
		c.JSON(http.StatusOK, Res{Code: -1, Msg: "操作过于频繁，请稍后再试"})
		return
	}
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 1<<20)

	// 先把请求体完整读入（受限长度），再决定按 JSON 还是表单解析。
	// 直接对 Body 做 json.Decode 会在失败时把表单体消耗掉，导致表单回退永远拿到空值。
	body, err := io.ReadAll(http.MaxBytesReader(c.Writer, c.Request.Body, 1<<20))
	if err != nil {
		c.JSON(http.StatusOK, Res{Code: -1, Msg: "配置内容读取失败或超出大小限制"})
		return
	}
	trimmed := bytes.TrimSpace(body)
	if len(trimmed) == 0 {
		c.JSON(http.StatusOK, Res{Code: -1, Msg: "配置内容为空"})
		return
	}

	// 表单参数（当请求体是 application/x-www-form-urlencoded 时可用）
	formValues, _ := url.ParseQuery(string(trimmed))

	var payload ExportPayload
	if trimmed[0] == '[' {
		// 兼容直接把隧道数组作为请求体
		if err := json.Unmarshal(trimmed, &payload.Tunnels); err != nil {
			c.JSON(http.StatusOK, Res{Code: -1, Msg: "配置内容不是合法的 JSON"})
			return
		}
	} else if err := json.Unmarshal(trimmed, &payload); err != nil {
		// 回退：表单字段 tunnels=<JSON>
		raw := formValues.Get("tunnels")
		if raw == "" || json.Unmarshal([]byte(raw), &payload.Tunnels) != nil {
			c.JSON(http.StatusOK, Res{Code: -1, Msg: "配置内容不是合法的 JSON"})
			return
		}
	}
	if len(payload.Tunnels) == 0 {
		c.JSON(http.StatusOK, Res{Code: -1, Msg: "配置中没有可导入的隧道"})
		return
	}
	if len(payload.Tunnels) > 200 {
		c.JSON(http.StatusOK, Res{Code: -1, Msg: "单次最多导入 200 条隧道"})
		return
	}

	fallbackUser := strings.TrimSpace(c.PostForm("username"))
	fallbackPass := c.PostForm("password")
	if fallbackUser == "" {
		fallbackUser = strings.TrimSpace(formValues.Get("username"))
	}
	if fallbackPass == "" {
		fallbackPass = formValues.Get("password")
	}

	created, skipped := 0, 0
	failures := make([]string, 0)
	for _, item := range payload.Tunnels {
		item.Domain = strings.TrimSpace(item.Domain)
		item.ServerHost = strings.TrimSpace(item.ServerHost)
		item.TargetHost = strings.TrimSpace(item.TargetHost)
		proxyType := strings.ToUpper(strings.TrimSpace(item.Type))
		if proxyType == "" {
			proxyType = "TCP"
		}

		var messageType HpMessage.HpMessage_MessageType
		switch proxyType {
		case "TCP":
			messageType = HpMessage.HpMessage_TCP
		case "UDP":
			messageType = HpMessage.HpMessage_UDP
		case "TCP_UDP":
			messageType = HpMessage.HpMessage_TCP_UDP
		default:
			failures = append(failures, fmt.Sprintf("%s：穿透类型不合法", item.Domain))
			continue
		}
		if !validHost(item.ServerHost) || item.ServerPort <= 0 || item.ServerPort > 65535 {
			failures = append(failures, fmt.Sprintf("%s：穿透服务器不合法", item.Domain))
			continue
		}
		if !validHost(item.TargetHost) || item.TargetPort <= 0 || item.TargetPort > 65535 {
			failures = append(failures, fmt.Sprintf("%s：内网服务不合法", item.Domain))
			continue
		}
		if proxyType != "UDP" && !validHost(item.Domain) {
			failures = append(failures, fmt.Sprintf("%s：域名不合法", item.Domain))
			continue
		}
		if proxyType == "UDP" {
			item.Domain = "udp:" + item.TargetHost + ":" + strconv.Itoa(item.TargetPort)
		}
		if item.RemotePort < 0 || item.RemotePort > 65535 {
			item.RemotePort = 0
		}

		username := item.Username
		password := item.Password
		if username == "" {
			username = fallbackUser
		}
		if password == "" {
			password = fallbackPass
		}

		meta := TunnelMeta{
			Domain:     item.Domain,
			Type:       proxyType,
			ServerHost: item.ServerHost,
			ServerPort: item.ServerPort,
			TargetHost: item.TargetHost,
			TargetPort: item.TargetPort,
			Username:   username,
			RemotePort: item.RemotePort,
			CreatedAt:  time.Now(),
			Source:     "import",
			password:   password,
		}
		if _, err := createTunnel(meta, messageType); err != nil {
			if strings.Contains(err.Error(), "已经被使用") {
				skipped++
				continue
			}
			failures = append(failures, fmt.Sprintf("%s：%v", item.Domain, err))
			continue
		}
		created++
	}

	c.JSON(http.StatusOK, gin.H{
		"Code":     200,
		"Msg":      fmt.Sprintf("导入完成：新增 %d 条，跳过 %d 条，失败 %d 条", created, skipped, len(failures)),
		"Created":  created,
		"Skipped":  skipped,
		"Failures": failures,
	})
}

// ---------------------------------------------------------------------------
// 云端相关接口
// ---------------------------------------------------------------------------

func handleCoreVersion(c *gin.Context) {
	res := CoreVersionRes{Code: -1, Msg: "检查更新失败", Current: CORE_VERSION}
	if strings.TrimSpace(ApiUrl) == "" {
		res.Msg = "未配置云端地址"
		c.JSON(http.StatusOK, res)
		return
	}

	resp, err := apiClient.Get(ApiUrl + "/app/getCoreVersion")
	if err != nil {
		log.Errorf("获取内核版本失败: %v", err)
		res.Msg = "检查更新失败：云端不可达"
		c.JSON(http.StatusOK, res)
		return
	}
	defer func() { _ = resp.Body.Close() }()

	body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		log.Errorf("读取内核版本响应失败: %v", err)
		res.Msg = "检查更新失败"
		c.JSON(http.StatusOK, res)
		return
	}
	if resp.StatusCode != http.StatusOK {
		log.Errorf("内核版本接口返回状态码 %d", resp.StatusCode)
		res.Msg = "检查更新失败：云端返回异常"
		c.JSON(http.StatusOK, res)
		return
	}

	data := &CoreData{}
	if err := json.Unmarshal(body, data); err != nil || data.Data == nil {
		log.Errorf("解析内核版本数据失败: %v", err)
		res.Msg = "检查更新失败"
		c.JSON(http.StatusOK, res)
		return
	}

	res.Code = 200
	res.Latest = data.Data.VersionCode
	res.UpdateContent = data.Data.UpdateContent
	res.CreateTime = data.Data.CreateTime
	if strings.Compare(data.Data.VersionCode, CORE_VERSION) == 0 {
		res.NeedUpdate = false
		res.Msg = "当前版本 " + CORE_VERSION + " 已是最新版"
	} else {
		res.NeedUpdate = true
		res.Msg = fmt.Sprintf("发现新版本 %s（当前 %s）", data.Data.VersionCode, CORE_VERSION)
	}
	log.Infof("内核版本检查: 当前=%s 最新=%s 需要更新=%t", CORE_VERSION, res.Latest, res.NeedUpdate)
	c.JSON(http.StatusOK, res)
}

// handleApiProxy 把控制台请求反向代理到云端 API。
func handleApiProxy(reverseProxy *httputil.ReverseProxy) gin.HandlerFunc {
	return func(c *gin.Context) {
		if reverseProxy == nil {
			abortJSON(c, http.StatusBadGateway, "云端地址未配置")
			return
		}
		c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, maxProxyBodyBytes)
		reverseProxy.ServeHTTP(c.Writer, c.Request)
	}
}

func newApiReverseProxy() (*httputil.ReverseProxy, error) {
	target, err := url.Parse(ApiUrl)
	if err != nil || target.Scheme == "" || target.Host == "" {
		return nil, fmt.Errorf("云端地址不合法: %q", ApiUrl)
	}
	proxy := httputil.NewSingleHostReverseProxy(target)
	proxy.Transport = apiTransport
	originalDirector := proxy.Director
	proxy.Director = func(req *http.Request) {
		// 保留入站路径后再交给默认 Director 改写，最后把 /hp 前缀去掉，
		// 与旧版本行为一致：/hp/user/login -> 云端 /user/login。
		inboundPath := req.URL.Path
		inboundQuery := req.URL.RawQuery
		originalDirector(req)
		req.URL.Path = strings.TrimPrefix(inboundPath, "/hp")
		if req.URL.Path == "" {
			req.URL.Path = "/"
		}
		req.URL.RawPath = ""
		req.URL.RawQuery = inboundQuery
		req.Host = target.Host
		// 控制台令牌与 Cookie 属于本机凭据，绝不能转发给云端。
		req.Header.Del(tokenHeaderName)
		req.Header.Del("Cookie")
	}
	proxy.ErrorHandler = func(w http.ResponseWriter, r *http.Request, err error) {
		log.Errorf("反向代理失败: %v", err)
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.WriteHeader(http.StatusBadGateway)
		_, _ = w.Write([]byte(`{"code":-1,"msg":"云端服务不可达"}`))
	}
	return proxy, nil
}

func countTunnels() int {
	n := 0
	ConnGroup.Range(func(_, _ interface{}) bool {
		n++
		return true
	})
	return n
}

// handleShareQR 生成配置分享二维码（PNG）。
// 前端通过 <img> 加载，因此同时接受 Cookie 与查询参数中的令牌。
func handleShareQR(c *gin.Context) {
	data := c.Query("data")
	if data == "" {
		data = c.PostForm("data")
	}
	if data == "" {
		abortJSON(c, http.StatusBadRequest, "缺少二维码内容")
		return
	}
	if len(data) > maxQRBytes {
		abortJSON(c, http.StatusRequestEntityTooLarge,
			fmt.Sprintf("内容过长（%d 字节），二维码最多承载 %d 字节，请改用复制或下载", len(data), maxQRBytes))
		return
	}
	png, err := qrcode.Encode(data, qrcode.Medium, 320)
	if err != nil {
		log.Errorf("生成二维码失败: %v", err)
		abortJSON(c, http.StatusInternalServerError, "二维码生成失败")
		return
	}
	c.Header("Cache-Control", "no-store")
	c.Data(http.StatusOK, "image/png", png)
}

func handleDeviceInfo(c *gin.Context) {
	c.JSON(http.StatusOK, deviceID)
}

// ---------------------------------------------------------------------------
// 静态资源
// ---------------------------------------------------------------------------

func contentTypeFor(name string) string {
	switch path.Ext(name) {
	case ".html":
		return "text/html; charset=utf-8"
	case ".css":
		return "text/css; charset=utf-8"
	case ".js":
		return "application/javascript; charset=utf-8"
	case ".json":
		return "application/json; charset=utf-8"
	case ".svg":
		return "image/svg+xml"
	case ".png":
		return "image/png"
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".ico":
		return "image/x-icon"
	case ".woff2":
		return "font/woff2"
	}
	if ct := mime.TypeByExtension(path.Ext(name)); ct != "" {
		return ct
	}
	return "application/octet-stream"
}

func handleStatic(c *gin.Context) {
	name := strings.TrimPrefix(c.Param("filepath"), "/")
	if name == "" {
		// 不做目录列举：空路径统一返回控制台入口页，避免暴露资源清单。
		name = "login.html"
	}
	name = path.Clean(name)
	if name == "." || strings.HasPrefix(name, "..") || strings.Contains(name, "\\") {
		c.Status(http.StatusNotFound)
		return
	}
	data, err := staticFs.ReadFile(name)
	if err != nil {
		c.Status(http.StatusNotFound)
		return
	}
	c.Header("X-Content-Type-Options", "nosniff")
	if path.Ext(name) == ".html" {
		c.Header("Cache-Control", "no-store")
	} else {
		c.Header("Cache-Control", "public, max-age=300")
	}
	c.Data(http.StatusOK, contentTypeFor(name), data)
}

// ---------------------------------------------------------------------------
// WebSocket
// ---------------------------------------------------------------------------

type wsClient struct {
	conn *websocket.Conn
	mu   sync.Mutex
}

func (w *wsClient) writeJSON(v interface{}) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	_ = w.conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
	return w.conn.WriteJSON(v)
}

func (w *wsClient) writeControl(messageType int, data []byte) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	return w.conn.WriteControl(messageType, data, time.Now().Add(5*time.Second))
}

func (w *wsClient) Close() error {
	return w.conn.Close()
}

func handleWebSocket(c *gin.Context) {
	conn, err := upGrader.Upgrade(c.Writer, c.Request, nil)
	if err != nil {
		log.Errorf("WebSocket升级失败: %v", err)
		return
	}
	client := &wsClient{conn: conn}
	ConnWsGroup.Store(client, nil)
	log.Infof("新的WebSocket连接建立，当前连接数=%d", countWS())

	defer func() {
		ConnWsGroup.Delete(client)
		_ = conn.Close()
		log.Infof("WebSocket连接关闭，当前连接数=%d", countWS())
	}()

	conn.SetReadLimit(wsMessageLimit)
	_ = conn.SetReadDeadline(time.Now().Add(70 * time.Second))
	conn.SetPongHandler(func(string) error {
		return conn.SetReadDeadline(time.Now().Add(70 * time.Second))
	})

	done := make(chan struct{})
	go func() {
		ticker := time.NewTicker(25 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				if err := client.writeControl(websocket.PingMessage, nil); err != nil {
					return
				}
			case <-done:
				return
			}
		}
	}()
	defer close(done)

	for {
		mt, message, err := conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseAbnormalClosure, websocket.CloseNormalClosure) {
				log.Errorf("WebSocket读取错误: %v", err)
			}
			return
		}
		_ = conn.SetReadDeadline(time.Now().Add(70 * time.Second))
		if mt != websocket.TextMessage {
			continue
		}
		// 只回显心跳，不转发任意内容，避免被用作回显放大。
		if string(message) == "ping" {
			if err := client.writeJSON(Log{Domain: "system", Msg: "pong", Level: "debug"}); err != nil {
				return
			}
		}
	}
}

func countWS() int {
	n := 0
	ConnWsGroup.Range(func(_, _ interface{}) bool {
		n++
		return true
	})
	return n
}

// ---------------------------------------------------------------------------
// 启动
// ---------------------------------------------------------------------------

func securityHeaders() gin.HandlerFunc {
	const csp = "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; " +
		"script-src 'self'; connect-src 'self' ws: wss:; font-src 'self' data:; " +
		"object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"
	return func(c *gin.Context) {
		h := c.Writer.Header()
		h.Set("Content-Security-Policy", csp)
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Referrer-Policy", "no-referrer")
		h.Set("Cross-Origin-Opener-Policy", "same-origin")
		h.Set("Cross-Origin-Resource-Policy", "same-origin")
		h.Set("Permissions-Policy", "geolocation=(), microphone=(), camera=(), payment=()")
		c.Next()
	}
}

func gatePage(c *gin.Context, message string) {
	c.Header("Content-Type", "text/html; charset=utf-8")
	c.Header("Cache-Control", "no-store")
	c.String(http.StatusUnauthorized, `<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>需要访问令牌 - Proxy</title>
<style>
body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
background:#0f172a;color:#e2e8f0;font-family:system-ui,-apple-system,"Microsoft YaHei",sans-serif}
.card{max-width:32rem;margin:1rem;padding:1.75rem;border-radius:1rem;background:#1e293b;
border:1px solid #334155;box-shadow:0 20px 40px rgba(0,0,0,.45);line-height:1.7}
h1{margin:0 0 .75rem;font-size:1.25rem}code{background:#0f172a;padding:.15rem .4rem;border-radius:.35rem;
font-family:ui-monospace,Consolas,monospace;word-break:break-all}p{margin:.5rem 0;color:#94a3b8}
</style></head><body><div class="card">
<h1>需要访问令牌</h1>
<p>`+message+`</p>
<p>控制台默认只允许本机访问。若要从其它设备访问，请在启动时指定令牌：</p>
<p><code>proxy-client -webHost 0.0.0.0 -webToken 你的令牌</code></p>
<p>然后使用 <code>http://主机地址:端口/?token=你的令牌</code> 打开控制台。</p>
</div></body></html>`)
}

func StartWeb(webPort int, coreVersion string, logger logger.Logger) {
	SetLogger(logger)
	CORE_VERSION = coreVersion
	gin.SetMode(gin.ReleaseMode)
	gin.DefaultWriter = io.Discard
	gin.DefaultErrorWriter = io.Discard

	// 生成控制台访问令牌。WEB_TOKEN 允许用户自行指定，用于远程访问。
	if envToken := strings.TrimSpace(os.Getenv("WEB_TOKEN")); envToken != "" {
		if len(envToken) < 16 {
			log.Warnf("WEB_TOKEN 长度不足 16 位，安全性较弱，建议使用更长的随机字符串")
		}
		consoleToken = envToken
		tokenEnforced = true
	} else {
		token, err := generateToken()
		if err != nil {
			log.Errorf("生成控制台令牌失败: %v", err)
			token = strconv.FormatInt(time.Now().UnixNano(), 36)
		}
		consoleToken = token
	}

	if webPort <= 0 {
		if envPort := strings.TrimSpace(os.Getenv("WEB_PORT")); envPort != "" {
			if p, err := strconv.Atoi(envPort); err == nil && p > 0 && p <= 65535 {
				webPort = p
			}
		}
	}
	if webPort <= 0 {
		webPort = defaultWebPort
	}
	boundPort = webPort

	bindHost := strings.TrimSpace(os.Getenv("WEB_HOST"))
	if bindHost == "" {
		bindHost = defaultWebHost
	}

	log.Infof("启动Web服务，地址=%s:%d，核心版本=%s", bindHost, webPort, coreVersion)
	if tokenEnforced {
		log.Infof("控制台访问令牌: %s", consoleToken)
	}
	log.Infof("控制台地址: http://%s:%d/", func() string {
		if bindHost == "0.0.0.0" || bindHost == "::" {
			return "127.0.0.1"
		}
		return bindHost
	}(), webPort)

	e := gin.New()
	// 控制台是本机服务，不信任任何代理头，避免伪造客户端 IP。
	_ = e.SetTrustedProxies(nil)
	e.Use(gin.Recovery())
	e.Use(securityHeaders())
	e.Use(func(c *gin.Context) {
		start := time.Now()
		reqPath := c.Request.URL.Path
		c.Next()
		// 静态资源访问不记录，避免日志噪音。
		if strings.HasPrefix(reqPath, "/static/") {
			return
		}
		log.Infof("%s %s %d %v | %s", c.Request.Method, reqPath, c.Writer.Status(), time.Since(start), c.ClientIP())
	})

	// 静态资源（公开，不包含任何业务数据）
	e.GET("/static/*filepath", handleStatic)
	e.GET("/favicon.ico", func(c *gin.Context) { c.Status(http.StatusNoContent) })

	// apiAddress 常量脚本，必须早于鉴权加载。
	e.GET("/api.js", func(c *gin.Context) {
		c.Header("Cache-Control", "no-store")
		// 必须使用 application/javascript，配合 nosniff 才能被浏览器执行。
		c.Data(http.StatusOK, "application/javascript; charset=utf-8", []byte("var apiAddress = \"/hp\";"))
	})

	// 会话建立：同源 + 本机访问，或携带正确令牌。
	e.GET("/console/session", func(c *gin.Context) {
		if !isSameSite(c) {
			abortJSON(c, http.StatusForbidden, "跨站请求已被拒绝")
			return
		}
		if establishSession(c) {
			c.JSON(http.StatusOK, gin.H{
				"Code": 200,
				"Msg":  "ok",
				"Data": gin.H{
					"token":       consoleToken,
					"coreVersion": CORE_VERSION,
					"deviceId":    deviceID,
				},
			})
			return
		}
		c.JSON(http.StatusUnauthorized, Res{Code: -1, Msg: "需要访问令牌"})
	})

	// 需要鉴权的接口
	auth := e.Group("", requireSession())
	{
		auth.POST("/server/proxy", handleAddProxy)
		auth.POST("/server/stop", handleStopProxy)
		auth.GET("/server/stop", handleStopProxy) // 兼容旧脚本调用
		auth.POST("/server/batchStop", handleBatchStop)
		auth.GET("/server/info", handleServerInfo)
		auth.GET("/console/info", handleConsoleInfo)
		auth.GET("/console/config/export", handleConfigExport)
		auth.POST("/console/config/import", handleConfigImport)
		auth.GET("/console/share/qr", handleShareQR)
		auth.GET("/core/version", handleCoreVersion)
		auth.GET("/device/info", handleDeviceInfo)
		auth.GET("/ws", handleWebSocket)
	}

	// 云端 API 反向代理
	if proxy, err := newApiReverseProxy(); err != nil {
		log.Warnf("反向代理未启用: %v", err)
		e.Any("/hp/*url", func(c *gin.Context) { abortJSON(c, http.StatusBadGateway, "云端地址未配置") })
	} else {
		handler := handleApiProxy(proxy)
		e.Any("/hp/*url", requireSession(), handler)
	}

	e.GET("/", func(c *gin.Context) {
		if !isSameSite(c) {
			abortJSON(c, http.StatusForbidden, "跨站请求已被拒绝")
			return
		}
		if !establishSession(c) {
			gatePage(c, "当前请求来自非本机地址，且没有携带有效的访问令牌。")
			return
		}
		c.Redirect(http.StatusFound, "/static/login.html")
	})

	// 健康探测：部分客户端（如安卓 WebView 容器）用 HEAD / 判断服务是否就绪，
	// 这里始终返回 200，不泄露任何业务数据。
	e.HEAD("/", func(c *gin.Context) {
		c.Status(http.StatusOK)
	})

	e.NoRoute(func(c *gin.Context) {
		if strings.HasPrefix(c.Request.URL.Path, "/static/") || strings.HasPrefix(c.Request.URL.Path, "/console/share/qr") {
			c.Status(http.StatusNotFound)
			return
		}
		abortJSON(c, http.StatusNotFound, "接口不存在")
	})

	addr := net.JoinHostPort(bindHost, strconv.Itoa(webPort))
	server := &http.Server{
		Addr:              addr,
		Handler:           e,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       30 * time.Second,
		// WebSocket 与反向代理需要长连接，因此不设置写超时。
		WriteTimeout:   0,
		IdleTimeout:    90 * time.Second,
		MaxHeaderBytes: 1 << 16,
	}
	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		log.Errorf("启动Web服务失败: %v", err)
	}
}

func InitCloudDevice(apiAddress string, deviceId string, level int, logger logger.Logger) {
	SetLogger(logger)
	SetLogLevel(level)
	ApiUrl = apiAddress

	defer func() {
		if err := recover(); err != nil {
			log.Errorf("云端资源读取失败: %v", err)
		}
	}()

	if deviceId == "NO_ID" {
		log.Warnf("未获取到设备ID，不能加载云端资源")
		return
	}
	matched, _ := regexp.MatchString("^[0-9a-zA-Z]+$", deviceId)
	if !matched || !(len(deviceId) >= 10 && len(deviceId) <= 36) {
		log.Errorf("设备ID无效: %s (只能是数字和字母组成同时大于10-36位)", deviceId)
		return
	}
	deviceID = deviceId
	log.Infof("初始化云设备连接，API地址=%s，设备ID=%s", apiAddress, deviceId)

	if strings.TrimSpace(apiAddress) == "" {
		log.Errorf("云端地址为空，跳过设备配置加载")
		return
	}

	resp, err := apiClient.Get(ApiUrl + "/config/listDevice?deviceId=" + url.QueryEscape(deviceId))
	if err != nil {
		log.Errorf("获取设备配置失败: %v", err)
		return
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		log.Errorf("获取设备配置失败: 云端返回状态码 %d", resp.StatusCode)
		return
	}

	body, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		log.Errorf("读取设备配置响应失败: %v", err)
		return
	}

	data := &DeviceData{}
	if err := json.Unmarshal(body, data); err != nil {
		log.Errorf("解析设备配置失败: %v", err)
		return
	}

	for i := range data.Data {
		info := data.Data[i]
		if info == nil {
			continue
		}
		var hpType HpMessage.HpMessage_MessageType
		switch info.Type {
		case "TCP":
			hpType = HpMessage.HpMessage_TCP
		case "UDP":
			hpType = HpMessage.HpMessage_UDP
		case "TCP_UDP":
			hpType = HpMessage.HpMessage_TCP_UDP
		default:
			log.Errorf("穿透类型未知: %s", info.Type)
			continue
		}

		serverHost, serverPort, err := splitHostPort(info.ServerHost)
		if err != nil {
			log.Errorf("穿透服务器地址不合法: %s", info.ServerHost)
			continue
		}
		userHost, userPort, err := splitHostPort(info.UserHost)
		if err != nil {
			log.Errorf("内网服务地址不合法: %s", info.UserHost)
			continue
		}
		remotePort, err := parsePort(info.Port, true)
		if err != nil {
			log.Errorf("外网端口不合法: %s", info.Port)
			continue
		}
		domain := info.Domain
		if hpType == HpMessage.HpMessage_UDP {
			domain = "udp:" + userHost + ":" + strconv.Itoa(userPort)
		} else if !validHost(domain) {
			log.Errorf("域名不合法: %s", domain)
			continue
		}

		meta := TunnelMeta{
			Domain:     domain,
			Type:       info.Type,
			ServerHost: serverHost,
			ServerPort: serverPort,
			TargetHost: userHost,
			TargetPort: userPort,
			Username:   info.Username,
			RemotePort: remotePort,
			CreatedAt:  time.Now(),
			Source:     "cloud",
			password:   info.Password,
		}
		if _, err := createTunnel(meta, hpType); err != nil {
			log.Errorf("内网服务启动失败: %s -> %s 原因=%v", info.UserHost, domain, err)
		} else {
			log.Infof("内网服务启动成功: %s -> %s", info.UserHost, domain)
		}
	}
}

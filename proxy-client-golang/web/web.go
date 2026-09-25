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
	"sync/atomic"
	"time"

	HpMessage "proxy-client-golang/hpMessage"
	"proxy-client-golang/pkg/events"
	"proxy-client-golang/pkg/logger"
	"proxy-client-golang/pkg/logsink"
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
	// maxWSClients 日志 WebSocket 的最大并发连接数。
	// 每个连接都会占用隧道数据协程的推送预算，必须设上限。
	maxWSClients = 64
	// wsOutboundQueue 单个 WebSocket 客户端的出站缓冲队列长度。
	// 队列满说明前端消费不过来，日志直接丢弃，绝不阻塞隧道协程。
	wsOutboundQueue = 128
	// minWebTokenLength WEB_TOKEN 的最小长度，低于该长度视为弱令牌并拒绝使用。
	minWebTokenLength = 16
	// maxAuthFailuresPerWindow 同一 IP 在 authFailureWindow 内允许的令牌校验失败次数。
	maxAuthFailuresPerWindow = 10
	// maxRateLimiterKeys 限流器 map 的键数量上限，超出后淘汰最旧的键，保证内存有界。
	maxRateLimiterKeys = 1024
	// maxLogTailLines /console/logs/tail 允许的最大行数（与 logsink.MaxTailLines 对齐）。
	maxLogTailLines = logsink.MaxTailLines
	// maxEventsPerQuery /console/events 单次返回的最大条数。
	maxEventsPerQuery = 200
	// eventsCapacity 失败事件环形缓冲容量。
	eventsCapacity = events.DefaultCapacity
	// diagnoseMaxTunnels 一键诊断最多探测的隧道数量（有界工作量的关键）。
	diagnoseMaxTunnels = 50
	// diagnoseBudget 一键诊断的整体截止时间，保证接口不会挂住。
	diagnoseBudget = 10 * time.Second
	// diagnoseDialTimeout 单条隧道目标地址的 TCP 拨号超时。
	diagnoseDialTimeout = 2 * time.Second
	// diagnoseCloudTimeout 云端可达性探测超时。
	diagnoseCloudTimeout = 3 * time.Second
	// diagnoseWorkers 诊断探测的并发 worker 数量（够小，避免瞬间打出大量连接）。
	diagnoseWorkers = 8
	// failureEventCooldown 同一 (kind, domain) 失败事件的最小记录间隔，避免刷屏式失败
	// 把环形缓冲冲干净（只影响事件记录，不影响日志与界面推送）。
	failureEventCooldown = 30 * time.Second
	// maxFailureEventKeys 冷却表的最大键数量，保证内存有界。
	maxFailureEventKeys = 256
)

// authFailureWindow 令牌校验失败的统计窗口。
const authFailureWindow = 5 * time.Minute

var (
	// logLevel 是当前日志级别（0=Debug 1=Info 2=Warn 3=Error）。
	//
	// 【并发修复】原实现是一个裸 int，被隧道创建协程、重连协程、HTTP 协程同时读写，
	// 在运行时可被 /console/log-level 修改，属于数据竞争（-race 可复现）。
	// 现在改为 atomic.Int32，读取点统一走 LogLevelValue()。
	logLevel atomic.Int32

	// log 默认使用丢弃式实现：即使宿主忘记调用 SetLogger（或测试直接调用处理函数），
	// 任何 log.Warnf 也不会 panic。StartWeb / InitCloudDevice 会用真实实现覆盖它。
	log          logger.Logger = discardLogger{}
	ApiUrl                     = ""
	deviceID                   = "NO_ID"
	CORE_VERSION               = "1.0"

	// logSink 日志落盘接收器，由 main.go 通过 SetLogSink 注入；可能为 nil。
	logSink atomic.Pointer[logsink.Manager]

	// failureEvents 失败事件环形缓冲（有界，只记录失败/异常事件）。
	failureEvents = events.New(eventsCapacity)
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

	tunnelMu   sync.Mutex
	addLimiter = newRateLimiter(30, time.Minute)
	// authFailLimiter 记录令牌校验失败次数，防止令牌被在线暴力枚举。
	authFailLimiter = newRateLimiter(maxAuthFailuresPerWindow, authFailureWindow)
	// logQueryLimiter 限制日志查询/下载接口频率，避免被用来反复砸磁盘。
	logQueryLimiter = newRateLimiter(60, time.Minute)
	// diagnoseLimiter 限制一键诊断频率：每次诊断都会真正拨号，必须严格限量。
	diagnoseLimiter = newRateLimiter(10, time.Minute)
	// eventsLimiter 限制失败事件接口频率。
	eventsLimiter = newRateLimiter(60, time.Minute)
	// wsErrEventMu 保护 failureEventAt（失败事件冷却表），避免重连循环把环形缓冲刷爆。
	wsErrEventMu   sync.Mutex
	failureEventAt = make(map[string]time.Time)
	apiClient      = &http.Client{Timeout: 15 * time.Second}
	apiTransport   = &http.Transport{
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
	meta TunnelMeta

	// mu 保护 client。控制台协程（snapshotTunnels / stopTunnel）
	// 与创建协程会并发访问该字段。
	mu     sync.Mutex
	client *tcp.HpClient
}

// setClient 绑定隧道客户端。必须在写入 ConnGroup 之前调用。
func (t *tunnel) setClient(client *tcp.HpClient) {
	t.mu.Lock()
	t.client = client
	t.mu.Unlock()
}

// getClient 返回隧道客户端，可能为 nil。
func (t *tunnel) getClient() *tcp.HpClient {
	t.mu.Lock()
	defer t.mu.Unlock()
	return t.client
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

// SetLogLevel 设置进程日志级别（0=Debug 1=Info 2=Warn 3=Error）。
//
// 同时做三件事，缺一不可：
//  1. 更新本包原子变量（控制台过滤用）；
//  2. 更新 zerolog 全局级别（gen_logger 写出的日志用）；
//  3. 文件 sink 通过 LogLevelValue 读取同一个原子变量，因此自动同步，无需额外动作。
//
// 保持既有签名不变（main.go / android/android.go 都在用）。
func SetLogLevel(level int) {
	level = logsink.SetGlobalLevel(level)
	logLevel.Store(int32(level))
}

// LogLevelValue 返回当前级别编号。可在任意 goroutine 安全调用。
func LogLevelValue() int {
	return int(logLevel.Load())
}

// LogLevelName 返回当前级别名称（debug/info/warn/error）。
func LogLevelName() string { return logsink.LevelName(LogLevelValue()) }

// ShouldLog 判断给定级别名（debug/info/warn/error）在当前级别下是否应当输出。
// 控制台事件在推送给浏览器与写入文件之前统一用它过滤，保证「界面看到的」与
// 「文件里落盘的」在级别口径上完全一致。
func ShouldLog(level string) bool {
	return logsink.LevelValueOf(level) >= LogLevelValue()
}

// SetLogSink 注入日志落盘接收器（由 main.go 调用）。
//
// 这里只保存指针，不重新创建 sink：STDOUT 与文件两个 sink 必须共用同一个轮转器，
// 否则控制台事件会写到另一个文件里去。
func SetLogSink(sink *logsink.Manager) {
	logSink.Store(sink)
}

// currentLogSink 返回当前日志接收器，可能为 nil（例如 Android 宿主未注入）。
func currentLogSink() *logsink.Manager {
	if sink := logSink.Load(); sink != nil {
		return sink
	}
	return nil
}

// ensureLogSink 在未注入时按环境变量惰性创建一个默认接收器。
// 用于独立运行 web 包（测试、嵌入式宿主）时不至于完全没有日志落盘能力。
func ensureLogSink() *logsink.Manager {
	if sink := currentLogSink(); sink != nil {
		return sink
	}
	cfg := logsink.Config{
		Dir:      envOr("LOG_DIR", logsink.DefaultDirName),
		BaseName: envOr("LOG_FILE", logsink.DefaultFileName),
		MaxBytes: int64(envIntOr("LOG_MAX_MB", logsink.DefaultMaxMB)) << 20,
		Keep:     envIntOr("LOG_KEEP", logsink.DefaultKeepFiles),
	}
	sink := logsink.NewManager(cfg, LogLevelValue)
	// 并发首次调用时可能重复创建：CompareAndSwap 保证只有一个生效，另一个被丢弃。
	if !logSink.CompareAndSwap(nil, sink) {
		return currentLogSink()
	}
	return sink
}

// envOr 读取环境变量，为空时返回默认值。
func envOr(name, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(name)); v != "" {
		return v
	}
	return fallback
}

// envIntOr 读取整型环境变量，缺失或非法时返回默认值。
func envIntOr(name string, fallback int) int {
	raw := strings.TrimSpace(os.Getenv(name))
	if raw == "" {
		return fallback
	}
	v, err := strconv.Atoi(raw)
	if err != nil || v <= 0 {
		return fallback
	}
	return v
}

// recordFailure 记录一条失败事件。
//
// 【为什么只记失败】整条日志流是高频数据，塞进内存既浪费又淹没有用信号；
// 控制台需要的只是「最近出了哪些问题」。这里统一走环形缓冲，有界且并发安全。
func recordFailure(kind, domain, message string) {
	failureEvents.Record(kind, domain, message)
}

// mirrorConsoleEvent 把一条控制台事件同步写入日志文件。
//
// 目的：控制台页面上的隧道事件（连接断开、重连、隧道停止等）过去只走 wsSend 发给
// 浏览器，刷新即丢；现在同样落盘，重启后仍可在日志页里查到。
// 级别过滤与界面保持一致；任何失败都不影响调用方。
func mirrorConsoleEvent(domain, level, message string) {
	if !ShouldLog(level) {
		return
	}
	sink := currentLogSink()
	if sink == nil {
		return
	}
	label := domain
	if label == "" {
		label = "system"
	}
	sink.WriteMessage(level, fmt.Sprintf("[%s] %s", label, message))
}

// emitFailure 记录失败事件并同时反映到控制台与日志文件。
// 三类失败（隧道创建、重连、云端不可达等）统一走这里，避免各写一套。
//
// 【为什么要冷却】重连循环每 5 秒就会失败一次，如果每次都记事件，200 条的环形
// 缓冲会被同一条“连接已断开”瞬间冲干净，真正有价值的失败反而被挤掉。
// 因此同一 (kind, domain) 在 failureEventCooldown 内只记一条。
func emitFailure(kind, domain, message string) {
	if allowFailureEvent(kind, domain) {
		recordFailure(kind, domain, message)
	}
	// 事件同时写入日志文件：诊断/排障时文件里能看到与界面一致的时间线。
	mirrorConsoleEvent(domain, "warn", fmt.Sprintf("[%s] %s", kind, message))
}

// failureEventCooldown 返回某个 (kind, domain) 是否已过冷却期。
func failureEventCooldownKey(kind, domain string) string {
	return kind + "|" + domain
}

func allowFailureEvent(kind, domain string) bool {
	now := time.Now()
	key := failureEventCooldownKey(kind, domain)

	wsErrEventMu.Lock()
	defer wsErrEventMu.Unlock()
	if last, ok := failureEventAt[key]; ok && now.Sub(last) < failureEventCooldown {
		return false
	}
	failureEventAt[key] = now
	// 键数量有界：超过上限时清理过期项，仍然超额就整体重置（极端情况下的兜底）。
	if len(failureEventAt) > maxFailureEventKeys {
		for k, at := range failureEventAt {
			if now.Sub(at) > failureEventCooldown {
				delete(failureEventAt, k)
			}
		}
		if len(failureEventAt) > maxFailureEventKeys {
			failureEventAt = make(map[string]time.Time)
			failureEventAt[key] = now
		}
	}
	return true
}

// emitConsoleEvent 把「只发给浏览器」的事件补一份到日志文件（不记入失败事件）。
func emitConsoleEvent(domain, level, message string) {
	mirrorConsoleEvent(domain, level, message)
}

// discardLogger 是 log 为 nil 时的兜底实现。
//
// 【为什么需要】嵌入式宿主（android/android.go、第三方集成）可能忘记调用 SetLogger，
// 此时任何 log.Warnf 都会 panic —— 而日志调用恰好散布在所有错误路径上，
// 等于把“日志没初始化”放大成“控制台崩溃”。这里统一兜底为丢弃式 logger。
type discardLogger struct{}

func (discardLogger) Debugf(string, ...interface{}) {}
func (discardLogger) Infof(string, ...interface{})  {}
func (discardLogger) Warnf(string, ...interface{})  {}
func (discardLogger) Errorf(string, ...interface{}) {}

// SetLogger 注入日志实现；传入 nil 时使用丢弃式实现，保证任何调用点都不会 panic。
func SetLogger(l logger.Logger) {
	if l == nil {
		l = discardLogger{}
	}
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

// generateConsoleToken 生成不可预测的控制台令牌。
// 【安全修复】原实现在 crypto/rand 失败时退化成 strconv.FormatInt(time.Now().UnixNano(), 36)，
// 这是可以暴力猜解的可预测令牌，等于没有鉴权。现在只重试 crypto/rand，
// 仍然失败则返回错误，由调用方拒绝启动控制台（fail-closed）。
func generateConsoleToken() (string, error) {
	var lastErr error
	for i := 0; i < 3; i++ {
		token, err := generateToken()
		if err == nil {
			return token, nil
		}
		lastErr = err
	}
	return "", fmt.Errorf("生成控制台令牌失败（crypto/rand 不可用）: %w", lastErr)
}

// maskToken 只保留令牌前缀，用于日志（绝不把完整令牌写进日志文件）。
func maskToken(token string) string {
	if len(token) <= 4 {
		return "****"
	}
	return token[:4] + "****"
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
// 注意：Origin 与 Host 都可能被攻击者控制，因此还必须做 Host 白名单校验。
func isSameSite(c *gin.Context) bool {
	if !hostAllowed(c) {
		return false
	}
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

// normalizeHost 把 Host / Host:Port / [IPv6]:Port 统一成小写、去端口、去方括号的主机名。
func normalizeHost(raw string) string {
	host := strings.TrimSpace(raw)
	if host == "" {
		return ""
	}
	if h, _, err := net.SplitHostPort(host); err == nil {
		host = h
	}
	return strings.ToLower(strings.Trim(strings.TrimSpace(host), "[]"))
}

// configuredAllowedHosts 返回除回环地址外仍允许访问控制台的主机名：
//  1. WEB_HOST 指定的真实绑定主机名（0.0.0.0 / :: 表示监听全部网卡，本身不是主机名）；
//  2. WEB_ALLOWED_HOSTS 中以逗号分隔的额外主机（例如通过反向代理域名访问时）。
//
// 端口一律不参与比较（请求侧已经去掉了端口）。
func configuredAllowedHosts() []string {
	hosts := make([]string, 0, 4)
	if h := normalizeHost(os.Getenv("WEB_HOST")); h != "" && h != "0.0.0.0" && h != "::" {
		hosts = append(hosts, h)
	}
	for _, item := range strings.Split(os.Getenv("WEB_ALLOWED_HOSTS"), ",") {
		if h := normalizeHost(item); h != "" {
			hosts = append(hosts, h)
		}
	}
	return hosts
}

// hostAllowed 校验请求的 Host 是否属于受信任的访问地址。
//
// 【安全修复·DNS 重绑定】isSameSite 过去只比较 Origin 与 c.Request.Host，
// 而这两个值都由请求方控制：攻击者在自己的域名（http://evil.tld:10240）上放一个页面，
// 把该域名解析到 127.0.0.1，浏览器发出的请求就是 Host=evil.tld、Origin=http://evil.tld，
// 两者一致，于是“同源 + 回环地址”两道检查同时被骗过，控制台令牌就被交给了攻击者。
// 因此必须用服务端自己的白名单校验 Host：Host 由浏览器按地址栏填写，
// 重绑定域名不会出现在白名单里，攻击就无法成立。
// 没有 Origin 的非浏览器客户端同样要过这一关（这正是本检查的意义）。
func hostAllowed(c *gin.Context) bool {
	return hostInAllowlist(c.Request.Host)
}

// hostInAllowlist 判断主机名（可带端口）是否在白名单内。
func hostInAllowlist(rawHost string) bool {
	host := normalizeHost(rawHost)
	if host == "" {
		return false
	}
	// 本机名称与 IPv6 回环
	if host == "localhost" || host == "::1" {
		return true
	}
	// 所有回环地址：net.IP.IsLoopback 覆盖 ::1 与整个 127.0.0.0/8（即任意 127.x.x.x）
	if ip := net.ParseIP(host); ip != nil && ip.IsLoopback() {
		return true
	}
	for _, allowed := range configuredAllowedHosts() {
		if host == allowed {
			return true
		}
	}
	return false
}

// hostAllowlistHint 返回给运维看的提示：Host 不在白名单时如何放行。
func hostAllowlistHint() string {
	return "若确实需要通过其它主机名访问，请用 WEB_ALLOWED_HOSTS=主机名1,主机名2（或 -webHost 指定真实绑定主机名）配置白名单。"
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

// clearSessionCookie 删除控制台会话 Cookie。
// 注意 http.Cookie 的 MaxAge：负数才会输出 Max-Age=0（立即过期），0 表示“不写该属性”。
func clearSessionCookie(c *gin.Context) {
	http.SetCookie(c.Writer, &http.Cookie{
		Name:     sessionCookieName,
		Value:    "",
		Path:     "/",
		MaxAge:   -1,
		HttpOnly: true,
		SameSite: http.SameSiteStrictMode,
		Secure:   c.Request.TLS != nil,
	})
}

// establishSession 尝试建立控制台会话：Host 白名单 + 同源 + （持有令牌 或 本机访问）。
func establishSession(c *gin.Context) bool {
	if !hostAllowed(c) {
		return false
	}
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
		// Host 白名单是第一道闸门：DNS 重绑定的请求在这里就被拦下。
		if !hostAllowed(c) {
			log.Warnf("拒绝 Host 不在白名单内的请求: host=%s ip=%s %s", c.Request.Host, c.ClientIP(), hostAllowlistHint())
			abortJSON(c, http.StatusForbidden, "访问地址不在允许列表内")
			return
		}
		if !isSameSite(c) {
			abortJSON(c, http.StatusForbidden, "跨站请求已被拒绝")
			return
		}
		// 令牌校验失败限流：只对“携带了错误令牌”的请求计数，
		// 正确令牌与本机免令牌访问都不会被拦（避免把正常用户锁在门外）。
		if !isAuthorized(c) && requestToken(c) != "" {
			authFailLimiter.fail(c.ClientIP())
			if authFailLimiter.exceeded(c.ClientIP()) {
				abortJSON(c, http.StatusTooManyRequests, "认证失败次数过多，请稍后再试")
				return
			}
		}
		if !isAuthorized(c) {
			abortJSON(c, http.StatusUnauthorized, "未授权：请通过控制台地址访问，或携带访问令牌")
			return
		}
		c.Next()
	}
}

func webSocketOriginAllowed(r *http.Request) bool {
	// WebSocket 同样要过 Host 白名单，否则恶意页面可以借 DNS 重绑定订阅实时日志。
	if !hostInAllowlist(r.Host) {
		return false
	}
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

// pruneLocked 在 map 超过上限时先清理过期键，再淘汰最旧的键，保证内存有界。
// 【安全修复】原实现只在 len > 1024 时清理“已过期”的键：攻击者只要在窗口内
// 用海量不同 IP 打过来，就没有任何键会过期，map 会无上限增长直到 OOM。
// 淘汰目标是“给即将写入的新键留出位置”，因此这里收窄到 maxRateLimiterKeys 以下。
// 必须在持有 r.mu 时调用。
func (r *rateLimiter) pruneLocked(now time.Time) {
	if len(r.hits) < maxRateLimiterKeys {
		return
	}
	for k, v := range r.hits {
		if len(v) == 0 || now.Sub(v[len(v)-1]) > r.window {
			delete(r.hits, k)
		}
	}
	for len(r.hits) >= maxRateLimiterKeys {
		oldestKey := ""
		var oldest time.Time
		for k, v := range r.hits {
			if len(v) == 0 {
				oldestKey = k
				break
			}
			if last := v[len(v)-1]; oldestKey == "" || last.Before(oldest) {
				oldest = last
				oldestKey = k
			}
		}
		if oldestKey == "" {
			break
		}
		delete(r.hits, oldestKey)
	}
}

func (r *rateLimiter) allow(key string) bool {
	now := time.Now()
	r.mu.Lock()
	defer r.mu.Unlock()

	r.pruneLocked(now)

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

// exceeded 只读检查：key 在窗口内已记录的次数是否达到上限（不消耗配额）。
func (r *rateLimiter) exceeded(key string) bool {
	now := time.Now()
	r.mu.Lock()
	defer r.mu.Unlock()
	count := 0
	for _, t := range r.hits[key] {
		if now.Sub(t) <= r.window {
			count++
		}
	}
	return count >= r.limit
}

// fail 记录一次失败（认证失败计数专用）。
func (r *rateLimiter) fail(key string) {
	now := time.Now()
	r.mu.Lock()
	defer r.mu.Unlock()
	r.pruneLocked(now)
	r.hits[key] = append(r.hits[key], now)
}

// limiterKey 按“客户端 IP + 路由”生成限流键。
// 【安全修复】原实现只按 IP 限流，/server/proxy 的调用会把
// /console/config/import 等其它接口的额度一起吃光（互相挤兑）。
func limiterKey(c *gin.Context) string {
	return c.ClientIP() + "|" + c.FullPath()
}

// ---------------------------------------------------------------------------
// 隧道管理
// ---------------------------------------------------------------------------

// wsSend 把一条日志推送给所有控制台连接，并同步写入日志文件。
//
// 【为什么在这里落盘】隧道事件（连接断开、重连、隧道停止）过去只走 WebSocket，
// 浏览器刷新即丢，排查问题只能靠运气。统一在本函数落盘可以保证「界面上看到的」
// 与「日志文件里的」是同一条时间线，且不必在每个调用点重复写两遍。
//
// 【安全修复】原实现直接在隧道的数据协程里同步遍历所有客户端写 socket
// （每个客户端还有 10 秒写超时），客户端数量无上限，任何一个慢客户端
// 都能把隧道协程卡住，进而拖死整条隧道。现在改为“有界队列 + 非阻塞投递”：
// 队列满就丢弃该条日志，绝不等待。
func wsSend(msg Log) {
	if msg.Time == "" {
		msg.Time = time.Now().Format("15:04:05")
	}
	if len(msg.Msg) > maxWSPushBytes {
		msg.Msg = msg.Msg[:maxWSPushBytes] + "…"
	}
	// 先落盘（内部有级别过滤，且失败只记录不返回），再做非阻塞推送。
	level := msg.Level
	if level == "" {
		level = "info"
	}
	mirrorConsoleEvent(msg.Domain, level, msg.Msg)

	ConnWsGroup.Range(func(key, _ interface{}) bool {
		client, ok := key.(*wsClient)
		if !ok || client == nil {
			return true
		}
		client.enqueue(msg)
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
func createTunnel(meta TunnelMeta, messageType HpMessage.HpMessage_MessageType) (t *tunnel, err error) {
	if meta.Domain == "" {
		return nil, errors.New("域名不能为空")
	}

	// SSL 配置必须在写入 ConnGroup 之前解析完毕。
	// 【安全修复】原实现 `tlsConfig, _ = sslCfg.NewTLSConfig()` 吞掉了错误：
	// 一旦配置失败，tlsConfig 为 nil，代码会掉进明文分支把账号密码明文发出去，
	// 而日志还写着已启用 SSL。这里直接中止创建，绝不降级为明文。
	var tlsConfig *tls.Config
	if sslCfg := tcp.GetSSLConfig(); sslCfg != nil && sslCfg.Enable {
		cfg, err := sslCfg.NewTLSConfig()
		if err != nil {
			log.Errorf("SSL 配置初始化失败，已中止隧道创建（拒绝明文降级）: %v", err)
			emitFailure("tls-config", meta.Domain, fmt.Sprintf("SSL 配置初始化失败，已中止创建（拒绝明文降级）：%v", err))
			return nil, fmt.Errorf("SSL 配置初始化失败，已中止创建以防止明文降级: %w", err)
		}
		if cfg == nil {
			log.Errorf("SSL 配置初始化未返回有效配置，已中止隧道创建（拒绝明文降级）")
			emitFailure("tls-config", meta.Domain, "SSL 配置初始化失败：未返回有效的 TLS 配置")
			return nil, errors.New("SSL 配置初始化失败：未返回有效的 TLS 配置")
		}
		tlsConfig = cfg
	}

	// 兜底：任何失败路径都不能把半成品留在 ConnGroup 里，
	// 否则前端会看到一条既不在运行、又“停止不了”的僵尸隧道。
	defer func() {
		if err != nil && t != nil {
			if cur, ok := ConnGroup.Load(meta.Domain); ok && cur == t {
				ConnGroup.Delete(meta.Domain)
			}
			if client := t.getClient(); client != nil {
				client.Kill()
			}
		}
	}()

	tunnelMu.Lock()
	if _, exists := ConnGroup.Load(meta.Domain); exists {
		tunnelMu.Unlock()
		emitFailure("tunnel-conflict", meta.Domain, "域名已被其它隧道占用")
		return nil, errors.New("域名 " + meta.Domain + " 已经被使用")
	}
	if countTunnels() >= maxTunnels {
		tunnelMu.Unlock()
		emitFailure("tunnel-limit", meta.Domain, fmt.Sprintf("隧道数量已达上限 %d，请先停止部分隧道", maxTunnels))
		return nil, fmt.Errorf("隧道数量已达上限 %d，请先停止部分隧道", maxTunnels)
	}

	domain := meta.Domain
	hpClient := tcp.NewHpClient(func(message string) {
		log.Infof("[%s] %s", domain, message)
		wsSend(Log{Domain: domain, Msg: message, Level: "info"})
	})
	// 【安全修复】client 必须在 Store 之前绑定：
	// 原实现先 Store 再赋值 t.client，stopTunnel 若恰好落在两者之间，
	// 会读到 nil 并报告“已停止”，而真正的客户端随后继续运行、继续重连，再也停不掉。
	t = &tunnel{meta: meta}
	t.setClient(hpClient)
	ConnGroup.Store(meta.Domain, t)
	tunnelMu.Unlock()

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
				emitFailure("reconnect", domain, "连接已断开，正在重连")
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
	t, ok := load.(*tunnel)
	if !ok || t == nil {
		ConnGroup.Delete(domain)
		return false
	}
	// 先在锁内重新读取 client 再 Kill：client 在写入 ConnGroup 之前就已绑定，
	// 因此这里不可能出现“报告已停止、客户端却还在重连”的窗口。
	if client := t.getClient(); client != nil {
		client.Kill()
	}
	ConnGroup.Delete(domain)
	return true
}

func snapshotTunnels() []ServerInfo {
	ret := make([]ServerInfo, 0)
	now := time.Now()
	ConnGroup.Range(func(key, value interface{}) bool {
		t, ok := value.(*tunnel)
		if !ok || t == nil {
			return true
		}
		client := t.getClient()
		if client == nil {
			return true
		}
		stats := client.Stats()
		ret = append(ret, ServerInfo{
			Domain:      t.meta.Domain,
			Server:      client.GetServer(),
			ProxyServer: client.GetProxyServer(),
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
	if !addLimiter.allow(limiterKey(c)) {
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
	if !addLimiter.allow(limiterKey(c)) {
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

	// 请求体在函数开头已被完整读入，c.PostForm 无法再解析（Body 已消费），
	// 这里只能使用前面解析出的表单值。
	fallbackUser := strings.TrimSpace(formValues.Get("username"))
	fallbackPass := formValues.Get("password")

	created, skipped := 0, 0
	failures := make([]string, 0)
	// addFailure 记录一条导入失败：既回给页面，也进入失败事件缓冲，
	// 这样「导入完成：失败 N 条」之后还能在事件面板里查到具体原因。
	addFailure := func(domain, reason string) {
		line := fmt.Sprintf("%s：%s", domain, reason)
		failures = append(failures, line)
		emitFailure("config-import", domain, "导入失败："+reason)
	}
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
			addFailure(item.Domain, "穿透类型不合法")
			continue
		}
		if !validHost(item.ServerHost) || item.ServerPort <= 0 || item.ServerPort > 65535 {
			addFailure(item.Domain, "穿透服务器不合法")
			continue
		}
		if !validHost(item.TargetHost) || item.TargetPort <= 0 || item.TargetPort > 65535 {
			addFailure(item.Domain, "内网服务不合法")
			continue
		}
		if proxyType != "UDP" && !validHost(item.Domain) {
			addFailure(item.Domain, "域名不合法")
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
			// 导入失败必须能被运维看到：批量导入时页面上只显示条数，
			// 具体哪条因为什么失败要靠失败事件面板/日志页。
			emitFailure("config-import", item.Domain, fmt.Sprintf("导入失败：%v", err))
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
		emitFailure("cloud-api", "", fmt.Sprintf("内核版本检查失败：云端不可达（%v）", err))
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
		emitFailure("cloud-api", "", fmt.Sprintf("云端反向代理失败：%v", err))
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

	// send 是有界出站队列，只由写协程消费。
	// 队列满时 wsSend 直接丢弃消息，从而保证推送绝不会阻塞隧道协程。
	send chan Log
	// done 在客户端关闭时被关闭（closeOnce 保证只关一次）。
	// 关闭后写协程退出、enqueue 不再投递；send 本身永不关闭，
	// 避免出现 “send on closed channel” 的 panic。
	done      chan struct{}
	closeOnce sync.Once
}

func newWSClient(conn *websocket.Conn) *wsClient {
	return &wsClient{
		conn: conn,
		send: make(chan Log, wsOutboundQueue),
		done: make(chan struct{}),
	}
}

// stop 通知写协程退出，只会生效一次。
func (w *wsClient) stop() {
	w.closeOnce.Do(func() { close(w.done) })
}

// enqueue 非阻塞投递：队列满或已关闭时直接丢弃日志。
func (w *wsClient) enqueue(msg Log) {
	select {
	case <-w.done:
		return
	default:
	}
	select {
	case w.send <- msg:
	default:
		// 前端消费不过来：丢日志，绝不阻塞调用方（隧道数据协程）。
	}
}

// handleWebSocket 建立日志推送连接。
// 单个客户端只允许一个写协程（websocket 不允许并发写），
// 读循环与写协程通过 done 通道互相退出，避免 goroutine / fd 泄漏。
func handleWebSocket(c *gin.Context) {
	if countWS() >= maxWSClients {
		log.Warnf("WebSocket 连接数已达上限 %d，拒绝新连接: ip=%s", maxWSClients, c.ClientIP())
		emitFailure("ws-channel", "", fmt.Sprintf("日志通道连接数已达上限 %d", maxWSClients))
		abortJSON(c, http.StatusServiceUnavailable, "日志连接数已达上限，请稍后再试")
		return
	}
	conn, err := upGrader.Upgrade(c.Writer, c.Request, nil)
	if err != nil {
		log.Errorf("WebSocket升级失败: %v", err)
		emitFailure("ws-channel", "", fmt.Sprintf("WebSocket 升级失败：%v", err))
		return
	}
	client := newWSClient(conn)
	ConnWsGroup.Store(client, nil)
	log.Infof("新的WebSocket连接建立，当前连接数=%d", countWS())

	var writerWG sync.WaitGroup
	defer func() {
		ConnWsGroup.Delete(client)
		client.stop()
		_ = conn.Close()
		writerWG.Wait()
		log.Infof("WebSocket连接关闭，当前连接数=%d", countWS())
	}()

	conn.SetReadLimit(wsMessageLimit)
	_ = conn.SetReadDeadline(time.Now().Add(70 * time.Second))
	conn.SetPongHandler(func(string) error {
		return conn.SetReadDeadline(time.Now().Add(70 * time.Second))
	})

	// 唯一的写协程：同时负责出站日志队列与 ping 保活。
	writerWG.Add(1)
	go func() {
		defer writerWG.Done()
		defer func() { _ = conn.Close() }()
		ticker := time.NewTicker(25 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-client.done:
				return
			case msg := <-client.send:
				_ = conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
				if err := conn.WriteJSON(msg); err != nil {
					return
				}
			case <-ticker.C:
				if err := conn.WriteControl(websocket.PingMessage, nil, time.Now().Add(5*time.Second)); err != nil {
					return
				}
			}
		}
	}()

	for {
		mt, message, err := conn.ReadMessage()
		if err != nil {
			if websocket.IsUnexpectedCloseError(err, websocket.CloseGoingAway, websocket.CloseAbnormalClosure, websocket.CloseNormalClosure) {
				log.Errorf("WebSocket读取错误: %v", err)
				emitFailure("ws-channel", "", fmt.Sprintf("日志通道读取错误：%v", err))
			}
			return
		}
		_ = conn.SetReadDeadline(time.Now().Add(70 * time.Second))
		if mt != websocket.TextMessage {
			continue
		}
		// 只回显心跳，不转发任意内容，避免被用作回显放大。
		if string(message) == "ping" {
			client.enqueue(Log{Domain: "system", Msg: "pong", Level: "debug"})
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
	// img-src 额外允许 blob: —— 二维码改成用 fetch 带令牌取回后以 blob: 对象 URL 渲染，
	// 这样控制台令牌不会再出现在 <img src> 的 URL（会被历史记录/日志记录）里。
	const csp = "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; " +
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

// StartWeb 启动控制台并阻塞在当前协程（保持既有导出签名与语义不变）。
//
// 路由装配被拆到 newConsoleEngine：既让 StartWeb 保持“启动即阻塞”的既有语义，
// 也让路由表可以被测试直接驱动（httptest），避免为了验证一条接口而真的占用端口。
func StartWeb(webPort int, coreVersion string, logger logger.Logger) {
	e, addr, ok := newConsoleEngine(webPort, coreVersion, logger)
	if !ok {
		return
	}
	log.Infof("控制台地址: http://%s/", addr)
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

// newConsoleEngine 完成令牌生成、监听地址解析与路由装配，返回可服务的 gin 引擎。
// 第二个返回值是监听地址（host:port），第三个表示初始化是否成功（失败时调用方应放弃启动）。
func newConsoleEngine(webPort int, coreVersion string, logger logger.Logger) (*gin.Engine, string, bool) {
	SetLogger(logger)
	CORE_VERSION = coreVersion
	gin.SetMode(gin.ReleaseMode)
	gin.DefaultWriter = io.Discard
	gin.DefaultErrorWriter = io.Discard

	// 生成控制台访问令牌。WEB_TOKEN 允许用户自行指定，用于远程访问。
	if envToken := strings.TrimSpace(os.Getenv("WEB_TOKEN")); envToken != "" {
		if len(envToken) < minWebTokenLength {
			// 【安全修复】过短的令牌可以被在线爆破，等于没有鉴权。
			// 这里直接拒绝使用它，改为随机生成，同时保持 tokenEnforced=true（仍然强制鉴权）。
			log.Errorf("WEB_TOKEN 长度不足 %d 位（当前 %d 位），已拒绝使用该弱令牌并改为随机生成；请设置更长的随机令牌",
				minWebTokenLength, len(envToken))
			token, err := generateConsoleToken()
			if err != nil {
				log.Errorf("生成控制台令牌失败，出于安全考虑拒绝启动控制台: %v", err)
				return nil, "", false
			}
			consoleToken = token
			tokenEnforced = true
		} else {
			consoleToken = envToken
			tokenEnforced = true
		}
	} else {
		token, err := generateConsoleToken()
		if err != nil {
			log.Errorf("生成控制台令牌失败，出于安全考虑拒绝启动控制台: %v", err)
			return nil, "", false
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
		// 【安全修复】绝不把令牌原文写进日志：日志会落盘、被打包上传或分享。
		// 只输出掩码前缀，并说明去哪里取完整令牌。
		log.Infof("控制台访问令牌已启用（令牌前缀 %s，完整令牌见 WEB_TOKEN 环境变量 / -webToken 参数）", maskToken(consoleToken))
	}
	if bindHost == "0.0.0.0" || bindHost == "::" {
		// 监听全部网卡时，请求的 Host 会是局域网 IP 或自定义域名，
		// 必须显式配置白名单，否则会被 Host 校验拒绝（防止 DNS 重绑定）。
		log.Warnf("控制台监听全部网卡，请用 WEB_ALLOWED_HOSTS=主机名1,主机名2 显式声明允许访问的主机名（默认只允许回环地址），%s", hostAllowlistHint())
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

	// 会话建立：Host 白名单 + 同源 + （本机访问 或 携带正确令牌）。
	e.GET("/console/session", func(c *gin.Context) {
		if !hostAllowed(c) {
			log.Warnf("拒绝 Host 不在白名单内的会话请求: host=%s ip=%s %s", c.Request.Host, c.ClientIP(), hostAllowlistHint())
			abortJSON(c, http.StatusForbidden, "访问地址不在允许列表内")
			return
		}
		if !isSameSite(c) {
			abortJSON(c, http.StatusForbidden, "跨站请求已被拒绝")
			return
		}
		// 令牌校验失败限流：只对“携带了错误令牌”的请求计数，避免把正常用户锁在门外。
		if !isAuthorized(c) && requestToken(c) != "" {
			authFailLimiter.fail(c.ClientIP())
			if authFailLimiter.exceeded(c.ClientIP()) {
				abortJSON(c, http.StatusTooManyRequests, "认证失败次数过多，请稍后再试")
				return
			}
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

	// 退出控制台会话：清除 HttpOnly 会话 Cookie（属性与 setSessionCookie 保持一致）。
	e.POST("/console/logout", requireSession(), func(c *gin.Context) {
		clearSessionCookie(c)
		c.JSON(http.StatusOK, Res{Code: 200, Msg: "已退出控制台会话"})
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

		// 日志落盘：历史文件枚举 / 尾部查询 / 下载（全部只接受文件名，绝不接受路径）
		auth.GET("/console/logs/files", handleLogFiles)
		auth.GET("/console/logs/tail", handleLogTail)
		auth.GET("/console/logs/download", handleLogDownload)
		// 运行时日志级别：读写都要求同源会话
		auth.GET("/console/log-level", handleGetLogLevel)
		auth.POST("/console/log-level", handleSetLogLevel)
		// 失败事件汇总（内存环形缓冲，非持久化）
		auth.GET("/console/events", handleEvents)
		auth.POST("/console/events/clear", handleClearEvents)
		// 一键诊断（限流最严：会真的拨号）
		auth.GET("/console/diagnose", handleDiagnose)
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
		if !hostAllowed(c) {
			log.Warnf("拒绝 Host 不在白名单内的访问: host=%s ip=%s %s", c.Request.Host, c.ClientIP(), hostAllowlistHint())
			gatePage(c, "当前访问地址不在允许列表内。"+hostAllowlistHint())
			return
		}
		if !isSameSite(c) {
			abortJSON(c, http.StatusForbidden, "跨站请求已被拒绝")
			return
		}
		// 令牌校验失败限流：只对“携带了错误令牌”的请求计数，避免把正常用户锁在门外。
		if !isAuthorized(c) && requestToken(c) != "" {
			authFailLimiter.fail(c.ClientIP())
			if authFailLimiter.exceeded(c.ClientIP()) {
				gatePage(c, "认证失败次数过多，请稍后再试。")
				return
			}
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
	return e, addr, true
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

	// 【安全修复配套】云端 /config/listDevice 现在要求账号凭据，
	// 否则任何人都能凭一个设备ID拿到该账号的明文口令与内网配置。
	// 首次启动（尚未登录控制台）时凭据只能来自启动参数/环境变量：
	//   -apiUser <账号> -apiPass <口令>
	// 未提供时不再发起请求，只给出一条明确的操作提示（而不是静默失败）。
	apiUser := strings.TrimSpace(os.Getenv("API_USER"))
	apiPass := os.Getenv("API_PASS")
	if apiUser == "" || apiPass == "" {
		log.Warnf("未提供云端账号凭据，跳过自动穿透配置加载。" +
			"如需开机自动创建隧道，请使用 -apiUser <账号> -apiPass <口令> 启动（或设置 API_USER/API_PASS）")
		return
	}

	resp, err := apiClient.Get(ApiUrl + "/config/listDevice?deviceId=" + url.QueryEscape(deviceId) +
		"&username=" + url.QueryEscape(apiUser) + "&password=" + url.QueryEscape(apiPass))
	if err != nil {
		log.Errorf("获取设备配置失败: %v", err)
		emitFailure("cloud-api", "", fmt.Sprintf("获取设备配置失败：云端不可达（%v）", err))
		return
	}
	defer func() { _ = resp.Body.Close() }()

	if resp.StatusCode != http.StatusOK {
		log.Errorf("获取设备配置失败: 云端返回状态码 %d", resp.StatusCode)
		emitFailure("cloud-api", "", fmt.Sprintf("获取设备配置失败：云端返回状态码 %d", resp.StatusCode))
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

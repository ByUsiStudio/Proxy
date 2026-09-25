package web

// ============================================================================
// 控制台「日志 / 事件 / 一键诊断」接口
//
// 本文件集中实现四组只读或轻量接口，全部挂在 requireSession() 之后：
//
//	GET  /console/logs/files                 列出日志文件（不含路径输入，只列举）
//	GET  /console/logs/tail?file=&lines=&level=&keyword=
//	                                         从文件尾部按行读取（有行数/字节上限）
//	GET  /console/logs/download?file=        下载日志文件（附件）
//	GET  /console/log-level                  读取当前日志级别
//	POST /console/log-level  level=          运行时切换日志级别
//	GET  /console/events?limit=              失败事件（最新在前）+ count24h
//	POST /console/events/clear               清空失败事件
//	GET  /console/diagnose                   一键诊断（有界工作量，整体 10s 截止）
//
// 【安全要点】
//  1. 任何接受文件名的接口都必须过 logsink 的 Resolve（文件名形态 + filepath.Rel + 前缀复核），
//     绝不允许把客户端字符串直接拼进路径 —— 这是最经典的路径穿越漏洞。
//  2. 一律不回显客户端输入到响应头：下载文件名由服务端重新生成。
//  3. 所有接口都限流：日志查询会真的读磁盘，诊断会真的拨号，必须防止被当作
//     资源放大器使用。
//  4. 诊断结果中的所有文本都可能来自远端（域名、错误信息），前端只用 textContent 渲染。
// ============================================================================

import (
	"fmt"
	"net"
	"net/http"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"proxy-client-golang/pkg/logsink"
	"proxy-client-golang/tcp"

	"github.com/gin-gonic/gin"
)

// ---------------------------------------------------------------------------
// 日志文件枚举 / 查询 / 下载
// ---------------------------------------------------------------------------

// handleLogFiles 列出日志目录内匹配基础名的日志文件（活动文件在前，其余按时间倒序）。
func handleLogFiles(c *gin.Context) {
	if !logQueryLimiter.allow(limiterKey(c)) {
		abortJSON(c, http.StatusTooManyRequests, "操作过于频繁，请稍后再试")
		return
	}
	sink := ensureLogSink()
	if sink == nil {
		abortJSON(c, http.StatusServiceUnavailable, "日志落盘未启用")
		return
	}

	files, err := sink.Rotator().ListFiles()
	if err != nil {
		abortJSON(c, http.StatusInternalServerError, "日志目录读取失败："+err.Error())
		return
	}
	st := sink.Status()
	c.Header("Cache-Control", "no-store")
	c.JSON(http.StatusOK, gin.H{
		"dir":        st.Dir,
		"file":       st.File,
		"maxBytes":   st.MaxBytes,
		"keep":       st.Keep,
		"activeSize": st.ActiveSize,
		"files":      files,
	})
}

// handleLogTail 从文件尾部读取日志。
//
// 这里刻意不接受路径，只接受文件名，并由 logsink 做严格校验；
// 读取方式是从尾部的定长块反向扫描，内存与耗时由 lines / 字节上限决定。
func handleLogTail(c *gin.Context) {
	if !logQueryLimiter.allow(limiterKey(c)) {
		abortJSON(c, http.StatusTooManyRequests, "操作过于频繁，请稍后再试")
		return
	}
	sink := ensureLogSink()
	if sink == nil {
		abortJSON(c, http.StatusServiceUnavailable, "日志落盘未启用")
		return
	}

	name := c.Query("file")
	if strings.TrimSpace(name) == "" {
		// 未指定文件时默认读当前活动文件，方便前端首屏直接展示。
		name = sink.FileName()
	}
	lines := parseIntDefault(c.Query("lines"), logsink.DefaultTailLines)
	if lines > maxLogTailLines {
		lines = maxLogTailLines
	}
	level := strings.ToLower(strings.TrimSpace(c.Query("level")))
	switch level {
	case "", "all", "debug", "info", "warn", "error":
	default:
		abortJSON(c, http.StatusBadRequest, "级别过滤参数不合法")
		return
	}

	res, err := sink.Rotator().ReadTail(name, logsink.TailOptions{
		Lines:   lines,
		Level:   level,
		Keyword: c.Query("keyword"),
	})
	if err != nil {
		abortJSON(c, http.StatusBadRequest, err.Error())
		return
	}
	c.Header("Cache-Control", "no-store")
	c.JSON(http.StatusOK, res)
}

// handleLogDownload 以附件形式返回日志文件。
func handleLogDownload(c *gin.Context) {
	// 下载比查询更重（传输整个文件），限流可以更紧一些，但复用同一限流器足矣。
	if !logQueryLimiter.allow(limiterKey(c)) {
		abortJSON(c, http.StatusTooManyRequests, "操作过于频繁，请稍后再试")
		return
	}
	sink := ensureLogSink()
	if sink == nil {
		abortJSON(c, http.StatusServiceUnavailable, "日志落盘未启用")
		return
	}

	name := c.Query("file")
	if strings.TrimSpace(name) == "" {
		name = sink.FileName()
	}

	// 【路径穿越】先把名字解析成日志目录内的绝对路径；失败即拒绝，绝不尝试“修正”。
	path, err := sink.Rotator().Resolve(name)
	if err != nil {
		log.Warnf("拒绝非法的日志下载请求: file=%q ip=%s", name, c.ClientIP())
		abortJSON(c, http.StatusBadRequest, "日志文件名不合法")
		return
	}
	if _, err := filepath.Abs(path); err != nil {
		abortJSON(c, http.StatusBadRequest, "日志文件名不合法")
		return
	}

	safeName := logsink.SafeDownloadName(name)
	c.Header("Content-Type", "text/plain; charset=utf-8")
	// 文件名由服务端重新生成，绝不回显客户端输入（防响应头注入）。
	c.Header("Content-Disposition", fmt.Sprintf("attachment; filename=%q", safeName))
	c.Header("X-Content-Type-Options", "nosniff")
	c.Header("Cache-Control", "no-store")
	c.File(path)
}

// parseIntDefault 解析整型查询参数，失败或非正数时返回默认值。
func parseIntDefault(raw string, fallback int) int {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return fallback
	}
	v, err := strconv.Atoi(raw)
	if err != nil || v <= 0 {
		return fallback
	}
	return v
}

// ---------------------------------------------------------------------------
// 运行时日志级别
// ---------------------------------------------------------------------------

// logLevelState 生成级别状态响应体。
func logLevelState() gin.H {
	value := LogLevelValue()
	return gin.H{
		"Code": 200,
		"Msg":  "ok",
		"Data": gin.H{
			"level":      logsink.LevelName(value),
			"levelValue": value,
		},
	}
}

// handleGetLogLevel 读取当前日志级别。
func handleGetLogLevel(c *gin.Context) {
	c.Header("Cache-Control", "no-store")
	c.JSON(http.StatusOK, logLevelState())
}

// handleSetLogLevel 运行时切换日志级别。
//
// 立即生效范围：
//   - 本包原子变量（控制台事件过滤、mirrorConsoleEvent）；
//   - zerolog 全局级别（gen_logger 写出的日志）；
//   - 文件 sink（通过 LogLevelValue 回调读取同一个原子变量）。
//
// 【刻意不持久化】这是运行时排障开关，不是配置：写盘会让“临时开 debug 忘了关”
// 变成永久行为，反而放大磁盘与性能开销。因此只在内存中生效，重启回到启动参数。
func handleSetLogLevel(c *gin.Context) {
	if !eventsLimiter.allow(limiterKey(c)) {
		abortJSON(c, http.StatusTooManyRequests, "操作过于频繁，请稍后再试")
		return
	}
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, maxFormBytes)

	raw := strings.TrimSpace(c.PostForm("level"))
	if raw == "" {
		raw = strings.TrimSpace(c.Query("level"))
	}
	value, ok := logsink.ParseLevelName(raw)
	if !ok {
		abortJSON(c, http.StatusBadRequest, "日志级别不合法，只能是 debug/info/warn/error")
		return
	}

	SetLogLevel(value)
	log.Infof("控制台已切换日志级别: %s(%d) ip=%s", logsink.LevelName(value), value, c.ClientIP())
	emitConsoleEvent("system", "info", fmt.Sprintf("日志级别已切换为 %s", logsink.LevelName(value)))

	c.Header("Cache-Control", "no-store")
	c.JSON(http.StatusOK, logLevelState())
}

// ---------------------------------------------------------------------------
// 失败事件
// ---------------------------------------------------------------------------

// handleEvents 返回最新的失败事件（最新在前）与 24 小时计数。
func handleEvents(c *gin.Context) {
	if !eventsLimiter.allow(limiterKey(c)) {
		abortJSON(c, http.StatusTooManyRequests, "操作过于频繁，请稍后再试")
		return
	}
	limit := parseIntDefault(c.Query("limit"), 50)
	if limit > maxEventsPerQuery {
		limit = maxEventsPerQuery
	}

	c.Header("Cache-Control", "no-store")
	c.JSON(http.StatusOK, gin.H{
		"Code":     200,
		"Msg":      "ok",
		"count24h": failureEvents.CountSince(time.Now().Add(-24 * time.Hour)),
		"total":    failureEvents.Len(),
		"events":   failureEvents.Latest(limit),
	})
}

// handleClearEvents 清空失败事件（仅清内存缓冲，不删除日志文件）。
func handleClearEvents(c *gin.Context) {
	if !eventsLimiter.allow(limiterKey(c)) {
		abortJSON(c, http.StatusTooManyRequests, "操作过于频繁，请稍后再试")
		return
	}
	failureEvents.Clear()
	emitConsoleEvent("system", "info", "失败事件缓冲已被清空")
	c.JSON(http.StatusOK, gin.H{"Code": 200, "Msg": "已清空失败事件"})
}

// ---------------------------------------------------------------------------
// 一键诊断
// ---------------------------------------------------------------------------

// diagTunnel 是单条隧道的诊断结果。
// Status 取值 ok / warn / fail，前端据此套用 badge--ok / badge--warn / badge--down。
type diagTunnel struct {
	Domain     string `json:"domain"`
	Type       string `json:"type"`
	Server     string `json:"server"`
	Target     string `json:"target"`
	Connected  bool   `json:"connected"`
	Status     string `json:"status"`
	ConnCount  int64  `json:"connCount"`
	InBytes    int64  `json:"inBytes"`
	OutBytes   int64  `json:"outBytes"`
	Uptime     int64  `json:"uptime"`
	DialState  string `json:"dialState"`
	DialDetail string `json:"dialDetail"`
}

// handleDiagnose 执行一键诊断。
//
// 【有界工作量设计】诊断会真的去拨号，因此必须严格设限，否则一个请求就能打出
// 成千上万条连接（也可能是用户自己内网服务的压力）：
//   - 最多探测 diagnoseMaxTunnels 条隧道；
//   - 整体预算 diagnoseBudget（10s），到点立即返回已完成的部分并标注 truncated；
//   - 每次拨号 diagnoseDialTimeout（2s），并发 worker 数量很小（8）。
func handleDiagnose(c *gin.Context) {
	if !diagnoseLimiter.allow(limiterKey(c)) {
		abortJSON(c, http.StatusTooManyRequests, "诊断过于频繁，请稍后再试")
		return
	}

	deadline := time.Now().Add(diagnoseBudget)
	report := gin.H{
		"generatedAt": time.Now().Format("2006-01-02 15:04:05"),
		"budgetMs":    diagnoseBudget.Milliseconds(),
	}

	// 1. 本机服务
	tunnels := snapshotTunnels()
	report["host"] = gin.H{
		"coreVersion":    CORE_VERSION,
		"deviceId":       deviceID,
		"consoleAddress": consoleAddress(),
		"tunnelCount":    len(tunnels),
		"tunnelLimit":    maxTunnels,
		"onlineCount":    countOnline(tunnels),
		"goroutines":     runtime.NumGoroutine(),
		"wsClients":      countWS(),
	}

	// 2. 云端可达性（复用 apiClient；未配置时明确说明而不是报“失败”）
	report["cloud"] = diagnoseCloud()

	// 3. 每条隧道（含目标地址 TCP 拨号测试，有界并发）
	tunnelReport, probedAll := diagnoseTunnels(tunnels, deadline)
	report["tunnels"] = tunnelReport
	report["tunnelProbeTruncated"] = !probedAll

	// 4. 配置与环境
	report["config"] = diagnoseConfig()

	// 汇总统计，供前端头部徽章使用
	checks := 0
	ok, warn, fail := 0, 0, 0
	for _, item := range tunnelReport {
		checks++
		switch item.Status {
		case "ok":
			ok++
		case "fail":
			fail++
		default:
			warn++
		}
	}
	report["summary"] = gin.H{
		"tunnelChecks": checks,
		"ok":           ok,
		"warn":         warn,
		"fail":         fail,
		"elapsedMs":    time.Since(deadline.Add(-diagnoseBudget)).Milliseconds(),
	}

	c.Header("Cache-Control", "no-store")
	c.JSON(http.StatusOK, report)
}

// diagnoseCloud 对云端地址做一次带超时的探测。
func diagnoseCloud() gin.H {
	out := gin.H{
		"configured": strings.TrimSpace(ApiUrl) != "",
		"url":        ApiUrl,
	}
	if strings.TrimSpace(ApiUrl) == "" {
		out["status"] = "warn"
		out["detail"] = "未配置云端地址（ApiUrl 为空），跳过可达性探测"
		return out
	}

	probeURL := strings.TrimRight(ApiUrl, "/") + "/app/getCoreVersion"
	start := time.Now()
	req, err := http.NewRequest(http.MethodGet, probeURL, nil)
	if err != nil {
		out["status"] = "fail"
		out["detail"] = "云端地址不合法：" + err.Error()
		return out
	}
	client := &http.Client{Timeout: diagnoseCloudTimeout}
	resp, err := client.Do(req)
	elapsed := time.Since(start).Milliseconds()
	out["elapsedMs"] = elapsed
	if err != nil {
		out["status"] = "fail"
		out["detail"] = fmt.Sprintf("云端不可达（耗时 %dms）：%v", elapsed, err)
		emitFailure("cloud-api", "", "诊断：云端不可达")
		return out
	}
	defer func() { _ = resp.Body.Close() }()
	out["statusCode"] = resp.StatusCode
	if resp.StatusCode >= 200 && resp.StatusCode < 400 {
		out["status"] = "ok"
		out["detail"] = fmt.Sprintf("云端可达，HTTP %d，耗时 %dms", resp.StatusCode, elapsed)
		return out
	}
	out["status"] = "warn"
	out["detail"] = fmt.Sprintf("云端返回异常状态码 %d，耗时 %dms", resp.StatusCode, elapsed)
	return out
}

// diagnoseTunnelMeta 是从 ConnGroup 里取出的诊断用快照。
// 【并发说明】TunnelMeta 在 createTunnel 写入 ConnGroup 之前就已填好，之后不再修改，
// 因此在锁外读取是安全的；这里不做任何写操作。
type diagnoseTunnelMeta struct {
	domain    string
	proxyType string
	server    string
	target    string
	connected bool
	connCount int64
	inBytes   int64
	outBytes  int64
	uptime    int64
}

// collectTunnelMetas 汇总所有隧道的静态配置与实时统计。
func collectTunnelMetas(limit int) ([]diagnoseTunnelMeta, int) {
	metas := make([]diagnoseTunnelMeta, 0, limit)
	total := 0
	ConnGroup.Range(func(key, value interface{}) bool {
		t, ok := value.(*tunnel)
		if !ok || t == nil {
			return true
		}
		total++
		if len(metas) >= limit {
			return false
		}
		meta := diagnoseTunnelMeta{
			domain:    t.meta.Domain,
			proxyType: t.meta.Type,
			server:    net.JoinHostPort(t.meta.ServerHost, strconv.Itoa(t.meta.ServerPort)),
			target:    net.JoinHostPort(t.meta.TargetHost, strconv.Itoa(t.meta.TargetPort)),
			uptime:    int64(time.Since(t.meta.CreatedAt).Seconds()),
		}
		if client := t.getClient(); client != nil {
			stats := client.Stats()
			meta.connected = stats.Active
			meta.connCount = stats.ConnCount
			meta.inBytes = stats.InBytes
			meta.outBytes = stats.OutBytes
			if server := client.GetServer(); server != "" {
				meta.server = server
			}
		}
		metas = append(metas, meta)
		return true
	})
	// 稳定排序，保证同样输入产生同样顺序的报告（便于复制粘贴对比）。
	sort.Slice(metas, func(i, j int) bool { return metas[i].domain < metas[j].domain })
	return metas, total
}

// diagnoseTunnels 对每条隧道的目标地址做一次 TCP 拨号测试。
//
// 返回 (结果列表, 是否全部探测完成)。deadline 到点后不再发起新的拨号。
func diagnoseTunnels(tunnels []ServerInfo, deadline time.Time) ([]diagTunnel, bool) {
	metas, total := collectTunnelMetas(diagnoseMaxTunnels)
	results := make([]diagTunnel, len(metas))
	truncated := total > len(metas)

	// 统计域名冲突：同名隧道在 ConnGroup 中是覆盖式的，理论上不会出现；
	// 但 UDP 隧道会以 "udp:host:port" 作为域名，与显式域名冲突时值得提示。
	seen := make(map[string]int, len(metas))
	for _, m := range metas {
		seen[m.domain]++
	}

	// 小并发池拨号：既限制瞬时连接数，又让整体耗时接近单次超时而不是 N 倍。
	sem := make(chan struct{}, diagnoseWorkers)
	var wg sync.WaitGroup
	for i := range metas {
		meta := metas[i]
		results[i] = diagTunnel{
			Domain:    meta.domain,
			Type:      meta.proxyType,
			Server:    meta.server,
			Target:    meta.target,
			Connected: meta.connected,
			ConnCount: meta.connCount,
			InBytes:   meta.inBytes,
			OutBytes:  meta.outBytes,
			Uptime:    meta.uptime,
		}
		wg.Add(1)
		go func(idx int, target string) {
			defer wg.Done()
			// 预算已耗尽：不再拨号，直接标注跳过（接口必须能按时返回）。
			if time.Now().After(deadline) {
				results[idx].DialState = "skip"
				results[idx].DialDetail = "诊断预算已用尽，未执行探测"
				results[idx].Status = "warn"
				return
			}
			sem <- struct{}{}
			defer func() { <-sem }()

			start := time.Now()
			conn, err := net.DialTimeout("tcp", target, diagnoseDialTimeout)
			elapsed := time.Since(start).Milliseconds()
			if err != nil {
				results[idx].DialState = "fail"
				results[idx].DialDetail = fmt.Sprintf("目标不可达（%dms）：%v", elapsed, err)
				results[idx].Status = "fail"
				return
			}
			_ = conn.Close()
			results[idx].DialState = "ok"
			results[idx].DialDetail = fmt.Sprintf("目标可连（%dms）", elapsed)
			results[idx].Status = "ok"
		}(i, meta.target)
	}
	wg.Wait()

	// 综合结论：连接断开 / 域名冲突优先于拨号结果，因为那才是更根本的问题。
	for i := range results {
		reasons := make([]string, 0, 3)
		status := results[i].Status
		if results[i].Domain != "" && seen[results[i].Domain] > 1 {
			status = "fail"
			reasons = append(reasons, "域名与其它隧道冲突")
		}
		if !results[i].Connected {
			if status == "ok" {
				status = "warn"
			}
			reasons = append(reasons, "连接已被服务端关闭（正在重连）")
		}
		if results[i].DialState == "fail" {
			reasons = append(reasons, "目标不可达")
		} else if results[i].DialState == "skip" {
			reasons = append(reasons, "未探测")
		}
		if len(reasons) == 0 {
			reasons = append(reasons, "隧道在线且目标可连")
		}
		results[i].Status = status
		results[i].DialDetail = strings.Join(reasons, "；") + "（" + results[i].DialDetail + "）"
	}

	return results, !truncated
}

// diagnoseConfig 检查配置与环境。
func diagnoseConfig() gin.H {
	out := gin.H{}

	// 日志目录可写性
	if sink := ensureLogSink(); sink != nil {
		st := sink.Status()
		out["logDir"] = st.Dir
		out["logFile"] = st.File
		out["logWritable"] = st.Writable
		out["logLevel"] = st.Level
		out["logMaxBytes"] = st.MaxBytes
		out["logKeep"] = st.Keep
		if !st.Writable {
			out["logError"] = "日志目录不可写，日志将只输出到 STDOUT"
			emitFailure("log-sink", "", "诊断：日志目录不可写（"+st.Dir+"）")
		} else if st.RotateError != "" {
			out["logError"] = st.RotateError
		}
	} else {
		out["logWritable"] = false
		out["logError"] = "日志落盘未启用"
	}

	// SSL 状态
	if sslCfg := tcpSSLState(); sslCfg != nil {
		out["sslEnabled"] = sslCfg.Enable
		out["sslServerName"] = sslCfg.ServerName
		out["sslInsecureSkip"] = sslCfg.InsecureSkip
		if sslCfg.Enable && sslCfg.InsecureSkip {
			out["sslWarn"] = "已跳过证书验证，仅应用于测试环境"
		}
	} else {
		out["sslEnabled"] = false
	}

	out["tokenEnforced"] = tokenEnforced
	// 回环绑定判断：0.0.0.0 / :: 表示监听全部网卡（不再是回环）。
	bindHost := strings.TrimSpace(envOr("WEB_HOST", ""))
	out["bindHost"] = bindHost
	out["loopbackOnly"] = bindHost == "" || bindHost == defaultWebHost || bindHost == "localhost" || bindHost == "::1"
	allowed := configuredAllowedHosts()
	out["allowedHosts"] = allowed
	out["allowedHostsConfigured"] = len(allowed) > 0

	// 结论性提示，便于前端直接展示
	hints := make([]string, 0, 3)
	if !out["loopbackOnly"].(bool) && len(allowed) == 0 {
		hints = append(hints, "已监听非回环地址但未配置 WEB_ALLOWED_HOSTS，远程访问会被 Host 白名单拒绝")
	}
	if !tokenEnforced {
		hints = append(hints, "未启用访问令牌，控制台仅应在本机使用")
	}
	if writable, _ := out["logWritable"].(bool); !writable {
		hints = append(hints, "日志目录不可写，问题排查将缺少落盘记录")
	}
	out["hints"] = hints

	return out
}

// tcpSSLState 单独抽一层，便于测试替换（默认返回 tcp 包的全局 SSL 配置）。
var tcpSSLState = func() *tcp.SSLConfig { return tcp.GetSSLConfig() }

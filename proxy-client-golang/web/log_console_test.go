package web

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"proxy-client-golang/pkg/events"
	"proxy-client-golang/pkg/logsink"

	"github.com/gin-gonic/gin"
)

// newTestSink 在 t.TempDir() 中创建一个独立的日志接收器并注入本包。
// 测试结束后恢复原来的 sink 与级别，避免影响其它用例。
func newTestSink(t *testing.T) *logsink.Manager {
	t.Helper()
	previous := currentLogSink()
	previousLevel := LogLevelValue()

	sink := logsink.NewManager(logsink.Config{
		Dir:      t.TempDir(),
		BaseName: "web-test.log",
		MaxBytes: 1 << 20,
		Keep:     3,
	}, LogLevelValue)
	SetLogSink(sink)
	SetLogLevel(LogLevelDebug)

	t.Cleanup(func() {
		SetLogSink(previous)
		SetLogLevel(previousLevel)
		_ = sink.Rotator().Close()
	})
	return sink
}

// TestSetLogLevelIsAtomicAndClamped 验证日志级别设置：越界值被夹紧、读取即时可见。
func TestSetLogLevelIsAtomicAndClamped(t *testing.T) {
	previous := LogLevelValue()
	t.Cleanup(func() { SetLogLevel(previous) })

	cases := []struct {
		in   int
		want int
	}{
		{LogLevelDebug, LogLevelDebug},
		{LogLevelWarn, LogLevelWarn},
		{LogLevelError, LogLevelError},
		{LogLevelInfo, LogLevelInfo},
		{-5, LogLevelInfo}, // 越界回落 Info，避免出现“什么都看不到”的静默状态
		{99, LogLevelInfo}, // 同上
	}
	for _, c := range cases {
		SetLogLevel(c.in)
		if got := LogLevelValue(); got != c.want {
			t.Errorf("SetLogLevel(%d) 后 LogLevelValue() = %d，期望 %d", c.in, got, c.want)
		}
		if got := logsink.GlobalLevel(); got != c.want {
			t.Errorf("SetLogLevel(%d) 后 zerolog 全局级别 = %d，期望 %d", c.in, got, c.want)
		}
	}
}

// TestShouldLogHonoursLevel 验证控制台事件过滤与级别一致。
func TestShouldLogHonoursLevel(t *testing.T) {
	previous := LogLevelValue()
	t.Cleanup(func() { SetLogLevel(previous) })

	SetLogLevel(LogLevelWarn)
	cases := map[string]bool{
		"debug": false,
		"info":  false,
		"warn":  true,
		"error": true,
	}
	for level, want := range cases {
		if got := ShouldLog(level); got != want {
			t.Errorf("级别 warn 下 ShouldLog(%q) = %v，期望 %v", level, got, want)
		}
	}

	SetLogLevel(LogLevelDebug)
	for _, level := range []string{"debug", "info", "warn", "error"} {
		if !ShouldLog(level) {
			t.Errorf("级别 debug 下 ShouldLog(%q) 应为 true", level)
		}
	}
}

// TestSetLogLevelConcurrent 在 -race 下验证运行时改级别不会被并发读破坏。
func TestSetLogLevelConcurrent(t *testing.T) {
	previous := LogLevelValue()
	t.Cleanup(func() { SetLogLevel(previous) })

	var wg sync.WaitGroup
	for i := 0; i < 4; i++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			for j := 0; j < 200; j++ {
				SetLogLevel((id + j) % 4)
			}
		}(i)
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 400; j++ {
				_ = LogLevelValue()
				_ = LogLevelName()
				_ = ShouldLog("debug")
			}
		}()
	}
	wg.Wait()
}

// TestMirrorConsoleEventWritesFile 验证「只走 WebSocket 的事件」也会落盘，
// 并且低于当前级别的事件被过滤掉。
func TestMirrorConsoleEventWritesFile(t *testing.T) {
	sink := newTestSink(t)
	SetLogLevel(LogLevelInfo)

	mirrorConsoleEvent("a.example.com", "warn", "连接已断开，正在重连")
	mirrorConsoleEvent("a.example.com", "debug", "这条调试信息不应落盘")
	emitConsoleEvent("system", "info", "日志级别已切换为 warn")

	if err := sink.Rotator().Close(); err != nil {
		t.Fatalf("关闭日志文件失败: %v", err)
	}
	res, err := sink.Rotator().ReadTail("web-test.log", logsink.TailOptions{Lines: 100})
	if err != nil {
		t.Fatalf("读取日志失败: %v", err)
	}
	joined := strings.Join(res.Lines, "\n")
	if !strings.Contains(joined, "连接已断开，正在重连") {
		t.Fatalf("控制台事件未落盘: %v", res.Lines)
	}
	if !strings.Contains(joined, "日志级别已切换") {
		t.Fatalf("info 事件未落盘: %v", res.Lines)
	}
	if strings.Contains(joined, "这条调试信息不应落盘") {
		t.Fatalf("低于当前级别的事件不应落盘: %v", res.Lines)
	}
	if !strings.Contains(joined, "a.example.com") || !strings.HasPrefix(res.Lines[0], "[warn]") {
		t.Fatalf("落盘行缺少域名/级别标记: %v", res.Lines)
	}
}

// TestEmitFailureRecordsEventAndCooldown 验证失败事件入环、可查询，并受冷却保护。
func TestEmitFailureRecordsEventAndCooldown(t *testing.T) {
	newTestSink(t)
	failureEvents.Clear()
	t.Cleanup(failureEvents.Clear)

	emitFailure("reconnect", "b.example.com", "连接已断开，正在重连")
	// 冷却窗口内重复失败不应重复记录（否则重连循环会瞬间冲掉整个环形缓冲）。
	emitFailure("reconnect", "b.example.com", "连接已断开，正在重连")
	emitFailure("reconnect", "b.example.com", "连接已断开，正在重连")

	if got := failureEvents.Len(); got != 1 {
		t.Fatalf("冷却窗口内重复失败被记录了 %d 条，期望 1 条", got)
	}
	eventsList := failureEvents.Latest(10)
	if len(eventsList) != 1 || eventsList[0].Kind != "reconnect" || eventsList[0].Domain != "b.example.com" {
		t.Fatalf("事件内容不符合预期: %+v", eventsList)
	}

	// 不同域名属于不同冷却键，应当各自记录。
	emitFailure("reconnect", "c.example.com", "连接已断开，正在重连")
	if got := failureEvents.Len(); got != 2 {
		t.Fatalf("不同域名的事件应分别记录，实际 %d 条", got)
	}

	// 冷却过期后应重新记录：直接改写冷却表的最后一个时间点来模拟。
	wsErrEventMu.Lock()
	failureEventAt[failureEventCooldownKey("reconnect", "b.example.com")] = time.Now().Add(-2 * failureEventCooldown)
	wsErrEventMu.Unlock()
	emitFailure("reconnect", "b.example.com", "连接已断开，正在重连")
	if got := failureEvents.Len(); got != 3 {
		t.Fatalf("冷却过期后应重新记录，实际 %d 条", got)
	}
}

// TestLogFileNameValidationInWeb 汇总验证 web 层暴露的文件名校验入口。
// 真正的路径穿越防线在 logsink.Resolve（另有 logsink 包内测试覆盖），
// 这里确保 web 层确实使用了它，而不是自己拼路径。
func TestLogFileNameValidationInWeb(t *testing.T) {
	sink := newTestSink(t)
	rot := sink.Rotator()

	valid := []string{"web-test.log", "web-test-20250102-150405.log"}
	for _, name := range valid {
		if !rot.ValidLogFileName(name) {
			t.Errorf("ValidLogFileName(%q) = false，期望 true", name)
		}
		if _, err := rot.Resolve(name); err != nil {
			t.Errorf("Resolve(%q) 失败: %v", name, err)
		}
	}

	// 覆盖任务要求的拒绝样例：..、绝对路径、a/b、NUL，以及更多变体。
	invalid := []string{
		"..", "../", "../web-test.log", "..\\web-test.log",
		"a/b", "a\\b", "/etc/passwd", "C:\\Windows\\win.ini",
		"web-test.log\x00", "web-test.log\x00.txt",
		".", "./web-test.log", "sub/../web-test.log", "", "other.log",
	}
	for _, name := range invalid {
		if rot.ValidLogFileName(name) {
			t.Errorf("ValidLogFileName(%q) = true，期望 false", name)
		}
		if _, err := rot.Resolve(name); err == nil {
			t.Errorf("Resolve(%q) 未拒绝，存在路径穿越风险", name)
		}
	}
}

// TestHandleLogDownloadRejectsTraversal 从 HTTP 层验证下载接口拒绝路径穿越。
// 即使请求里带 ../ 或绝对路径，也必须返回 400，而不是把文件发出去。
func TestHandleLogDownloadRejectsTraversal(t *testing.T) {
	newTestSink(t)
	gin.SetMode(gin.TestMode)

	router := gin.New()
	router.GET("/console/logs/download", handleLogDownload)

	// 注意：NUL 字节无法出现在合法 URL 中（httptest.NewRequest 会直接 panic），
	// 因此 NUL 的拒绝校验放在 TestLogFileNameValidationInWeb 与 logsink 包内覆盖。
	attacks := []string{
		"../web-test.log",
		"..%2Fweb-test.log",
		"a/b.log",
		"..\\web-test.log",
		"/etc/passwd",
		"../../../../windows/win.ini",
	}
	for _, name := range attacks {
		req := httptest.NewRequest(http.MethodGet, "/console/logs/download?file="+name, nil)
		rec := httptest.NewRecorder()
		router.ServeHTTP(rec, req)
		if rec.Code != http.StatusBadRequest {
			t.Errorf("file=%q 期望 400（拒绝），实际 %d", name, rec.Code)
		}
	}
}

// TestHandleLogDownloadEncodesSafeName 验证合法下载返回安全响应头。
func TestHandleLogDownloadEncodesSafeName(t *testing.T) {
	sink := newTestSink(t)
	if _, err := sink.Rotator().Write([]byte(logsink.FormatLine("info", "下载测试") + "\n")); err != nil {
		t.Fatalf("写入测试日志失败: %v", err)
	}
	if err := sink.Rotator().Close(); err != nil {
		t.Fatalf("关闭日志失败: %v", err)
	}
	gin.SetMode(gin.TestMode)

	router := gin.New()
	router.GET("/console/logs/download", handleLogDownload)

	req := httptest.NewRequest(http.MethodGet, "/console/logs/download?file=web-test.log", nil)
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("合法下载期望 200，实际 %d", rec.Code)
	}
	if got := rec.Header().Get("Content-Disposition"); !strings.Contains(got, "attachment") ||
		!strings.Contains(got, "web-test.log") {
		t.Fatalf("Content-Disposition 不符合预期: %q", got)
	}
	if got := rec.Header().Get("X-Content-Type-Options"); got != "nosniff" {
		t.Fatalf("缺少 nosniff 响应头，实际 %q", got)
	}
	if !strings.Contains(rec.Body.String(), "下载测试") {
		t.Fatalf("响应体缺少日志内容: %q", rec.Body.String())
	}
}

// TestHandleLogTailLimits 验证尾部查询参数被夹紧，且非法级别被拒绝。
func TestHandleLogTailLimits(t *testing.T) {
	sink := newTestSink(t)
	for i := 0; i < 10; i++ {
		if _, err := sink.Rotator().Write([]byte(logsink.FormatLine("info", "行内容") + "\n")); err != nil {
			t.Fatalf("写入测试日志失败: %v", err)
		}
	}
	if err := sink.Rotator().Close(); err != nil {
		t.Fatalf("关闭日志失败: %v", err)
	}
	gin.SetMode(gin.TestMode)

	router := gin.New()
	router.GET("/console/logs/tail", handleLogTail)

	// 超过硬上限：不报错，正常返回（内部夹到 MaxTailLines）。
	req := httptest.NewRequest(http.MethodGet, "/console/logs/tail?file=web-test.log&lines=999999", nil)
	rec := httptest.NewRecorder()
	router.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("超大 lines 期望 200，实际 %d", rec.Code)
	}

	// 非法级别必须拒绝，避免把任意字符串当过滤条件。
	req = httptest.NewRequest(http.MethodGet, "/console/logs/tail?file=web-test.log&level=bogus", nil)
	rec = httptest.NewRecorder()
	router.ServeHTTP(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("非法 level 期望 400，实际 %d", rec.Code)
	}

	// 非法文件名同样拒绝。
	req = httptest.NewRequest(http.MethodGet, "/console/logs/tail?file=../web-test.log", nil)
	rec = httptest.NewRecorder()
	router.ServeHTTP(rec, req)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("非法文件名期望 400，实际 %d", rec.Code)
	}
}

// TestFailureEventsRingBoundInWeb 验证 web 层使用的环形缓冲同样有界。
func TestFailureEventsRingBoundInWeb(t *testing.T) {
	failureEvents.Clear()
	t.Cleanup(failureEvents.Clear)

	for i := 0; i < events.DefaultCapacity*3; i++ {
		failureEvents.Record("config-import", "d.example.com", "导入失败")
	}
	if got := failureEvents.Len(); got != events.DefaultCapacity {
		t.Fatalf("失败事件条数 = %d，期望上限 %d", got, events.DefaultCapacity)
	}
	if got := len(failureEvents.Latest(0)); got != events.DefaultCapacity {
		t.Fatalf("Latest 返回 %d 条，期望 %d", got, events.DefaultCapacity)
	}
}

// TestDiagnoseConfigShape 验证诊断的配置段在无 sink / 有 sink 时都能给出结论字段。
func TestDiagnoseConfigShape(t *testing.T) {
	newTestSink(t)
	t.Setenv("WEB_HOST", "")
	t.Setenv("WEB_ALLOWED_HOSTS", "")

	out := diagnoseConfig()
	for _, key := range []string{"logDir", "logWritable", "logLevel", "sslEnabled", "tokenEnforced", "loopbackOnly", "allowedHostsConfigured", "hints"} {
		if _, ok := out[key]; !ok {
			t.Errorf("diagnoseConfig 缺少字段 %q", key)
		}
	}
	if writable, ok := out["logWritable"].(bool); !ok || !writable {
		t.Errorf("TempDir 作为日志目录时应可写，实际 %v", out["logWritable"])
	}
	if loop, ok := out["loopbackOnly"].(bool); !ok || !loop {
		t.Errorf("未设置 WEB_HOST 时应判定为仅回环，实际 %v", out["loopbackOnly"])
	}
}

// TestDiagnoseTunnelsBoundedAndEmpty 验证无隧道时诊断不会卡住，并返回空结果。
func TestDiagnoseTunnelsBoundedAndEmpty(t *testing.T) {
	results, complete := diagnoseTunnels(nil, time.Now().Add(diagnoseBudget))
	if len(results) != 0 {
		t.Fatalf("无隧道时应返回空结果，实际 %d 条", len(results))
	}
	if !complete {
		t.Fatal("无隧道时不应标记为截断")
	}
}

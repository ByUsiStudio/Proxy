package web

// 端到端路由冒烟测试：直接驱动 newConsoleEngine 装配出的真实路由表，
// 通过 httptest.NewServer 发真实 HTTP 请求。目的是捕获「路由没注册 /
// JSON 字段名与前端不一致 / 鉴权没生效」这类只在集成时才暴露的问题。
//
// 之所以能这样做：StartWeb 的“启动即阻塞”语义保持不变，路由装配被拆到
// newConsoleEngine，测试因此不需要真的占用端口。

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"proxy-client-golang/pkg/logsink"
)

func TestConsoleRoutesEndToEnd(t *testing.T) {
	t.Setenv("WEB_HOST", "")
	t.Setenv("WEB_ALLOWED_HOSTS", "")
	t.Setenv("WEB_TOKEN", "")
	t.Setenv("WEB_PORT", "")

	sink := newTestSink(t)
	if _, err := sink.Rotator().Write([]byte(logsink.FormatLine("warn", "端到端冒烟日志行") + "\n")); err != nil {
		t.Fatalf("写入日志失败: %v", err)
	}
	if err := sink.Rotator().Close(); err != nil {
		t.Fatalf("关闭日志失败: %v", err)
	}

	engine, _, ok := newConsoleEngine(0, "16.0-e2e", discardLogger{})
	if !ok {
		t.Fatal("控制台引擎初始化失败")
	}
	srv := httptest.NewServer(engine)
	defer srv.Close()

	client := srv.Client()
	// 控制台会话令牌：先建立会话拿到令牌，再以 X-Proxy-Token 携带。
	// 测试用 http.Client 默认跟随重定向会掩盖 401，这里显式带上令牌更贴近前端行为。
	token := ""
	get := func(path string) (int, string) {
		t.Helper()
		req, err := http.NewRequest(http.MethodGet, srv.URL+path, nil)
		if err != nil {
			t.Fatalf("构造请求 %s 失败: %v", path, err)
		}
		if token != "" {
			req.Header.Set(tokenHeaderName, token)
		}
		resp, err := client.Do(req)
		if err != nil {
			t.Fatalf("请求 %s 失败: %v", path, err)
		}
		defer func() { _ = resp.Body.Close() }()
		body, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
		if err != nil {
			t.Fatalf("读取 %s 响应失败: %v", path, err)
		}
		return resp.StatusCode, string(body)
	}

	// 建立会话（回环访问 + Host 在白名单内），并取出会话令牌
	status, body := get("/console/session")
	if status != http.StatusOK {
		t.Fatalf("/console/session = %d：%s", status, body)
	}
	var session struct {
		Data struct {
			Token string `json:"token"`
		} `json:"Data"`
	}
	if err := json.Unmarshal([]byte(body), &session); err != nil {
		t.Fatalf("会话响应不是合法 JSON: %v（%s）", err, body)
	}
	token = session.Data.Token
	if token == "" {
		t.Fatalf("会话响应未返回令牌: %s", body)
	}

	// 未携带令牌时必须 401（鉴权确实生效）
	if status, _ := getNoToken(client, srv.URL); status != http.StatusUnauthorized {
		t.Fatalf("缺少令牌时应 401，实际 %d", status)
	}

	// /console/logs/files
	status, body = get("/console/logs/files")
	if status != http.StatusOK {
		t.Fatalf("/console/logs/files = %d：%s", status, body)
	}
	var files struct {
		Dir   string `json:"dir"`
		File  string `json:"file"`
		Files []struct {
			Name    string `json:"name"`
			Size    int64  `json:"size"`
			ModTime string `json:"modTime"`
			Active  bool   `json:"active"`
		} `json:"files"`
	}
	if err := json.Unmarshal([]byte(body), &files); err != nil {
		t.Fatalf("日志列表不是合法 JSON: %v（%s）", err, body)
	}
	if files.File != "web-test.log" || len(files.Files) != 1 || !files.Files[0].Active {
		t.Fatalf("日志列表内容不符合预期: %+v", files)
	}
	if files.Files[0].Size <= 0 || files.Files[0].ModTime == "" {
		t.Fatalf("日志列表缺少 size/modTime: %+v", files.Files[0])
	}

	// /console/logs/tail（含级别前缀，前端据此着色）
	status, body = get("/console/logs/tail?file=web-test.log&lines=50")
	if status != http.StatusOK {
		t.Fatalf("/console/logs/tail = %d：%s", status, body)
	}
	var tail struct {
		File      string   `json:"file"`
		Lines     []string `json:"lines"`
		Total     int      `json:"total"`
		Truncated bool     `json:"truncated"`
	}
	if err := json.Unmarshal([]byte(body), &tail); err != nil {
		t.Fatalf("tail 不是合法 JSON: %v（%s）", err, body)
	}
	if len(tail.Lines) != 1 || !strings.HasPrefix(tail.Lines[0], "[warn] ") ||
		!strings.Contains(tail.Lines[0], "端到端冒烟日志行") {
		t.Fatalf("tail 内容不符合预期: %+v", tail)
	}

	// 路径穿越：必须被拒绝
	if status, _ := get("/console/logs/tail?file=../web-test.log"); status != http.StatusBadRequest {
		t.Fatalf("tail 非法文件名应 400，实际 %d", status)
	}
	if status, _ := get("/console/logs/download?file=../../../../etc/passwd"); status != http.StatusBadRequest {
		t.Fatalf("download 非法文件名应 400，实际 %d", status)
	}

	// /console/log-level 读取
	status, body = get("/console/log-level")
	if status != http.StatusOK {
		t.Fatalf("/console/log-level = %d：%s", status, body)
	}
	var levelRes struct {
		Code int `json:"Code"`
		Data struct {
			Level      string `json:"level"`
			LevelValue int    `json:"levelValue"`
		} `json:"Data"`
	}
	if err := json.Unmarshal([]byte(body), &levelRes); err != nil {
		t.Fatalf("log-level 不是合法 JSON: %v（%s）", err, body)
	}
	if levelRes.Code != 200 || levelRes.Data.Level == "" {
		t.Fatalf("log-level 内容不符合预期: %+v", levelRes)
	}

	// /console/events 读取
	status, body = get("/console/events?limit=5")
	if status != http.StatusOK {
		t.Fatalf("/console/events = %d：%s", status, body)
	}
	var eventsRes struct {
		Count24h int   `json:"count24h"`
		Total    int   `json:"total"`
		Events   []any `json:"events"`
	}
	if err := json.Unmarshal([]byte(body), &eventsRes); err != nil {
		t.Fatalf("events 不是合法 JSON: %v（%s）", err, body)
	}

	// /console/diagnose：必须有完整结构，且整体在预算内返回
	status, body = get("/console/diagnose")
	if status != http.StatusOK {
		t.Fatalf("/console/diagnose = %d：%s", status, body)
	}
	var diag map[string]json.RawMessage
	if err := json.Unmarshal([]byte(body), &diag); err != nil {
		t.Fatalf("diagnose 不是合法 JSON: %v（%s）", err, body)
	}
	for _, field := range []string{"generatedAt", "host", "cloud", "tunnels", "config", "summary"} {
		if _, ok := diag[field]; !ok {
			t.Fatalf("诊断报告缺少字段 %q：%s", field, body)
		}
	}
	var config map[string]any
	if err := json.Unmarshal(diag["config"], &config); err != nil {
		t.Fatalf("诊断 config 段不是对象: %v", err)
	}
	for _, field := range []string{"logWritable", "logDir", "sslEnabled", "tokenEnforced", "loopbackOnly", "allowedHostsConfigured"} {
		if _, ok := config[field]; !ok {
			t.Fatalf("诊断 config 缺少字段 %q", field)
		}
	}

	// 下载：合法文件返回附件与 nosniff
	req, err := http.NewRequest(http.MethodGet, srv.URL+"/console/logs/download?file=web-test.log", nil)
	if err != nil {
		t.Fatalf("构造下载请求失败: %v", err)
	}
	req.Header.Set(tokenHeaderName, token)
	resp, err := client.Do(req)
	if err != nil {
		t.Fatalf("下载请求失败: %v", err)
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("下载 = %d", resp.StatusCode)
	}
	if cd := resp.Header.Get("Content-Disposition"); !strings.Contains(cd, "attachment") {
		t.Fatalf("下载缺少附件头: %q", cd)
	}
	if resp.Header.Get("X-Content-Type-Options") != "nosniff" {
		t.Fatal("下载缺少 nosniff 头")
	}

	// 未知路由仍是 404 JSON
	if status, _ := get("/console/logs/nope"); status != http.StatusNotFound {
		t.Fatalf("未知路由应 404，实际 %d", status)
	}
}

// getNoToken 发一个不带令牌的请求，用于确认鉴权确实生效（而不是“谁都能读磁盘”）。
func getNoToken(client *http.Client, base string) (int, string) {
	resp, err := client.Get(base + "/console/logs/files")
	if err != nil {
		return 0, err.Error()
	}
	defer func() { _ = resp.Body.Close() }()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<16))
	return resp.StatusCode, string(body)
}

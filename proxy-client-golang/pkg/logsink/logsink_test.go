package logsink

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/rs/zerolog"
)

// newTestRotator 在 t.TempDir() 里创建一个轮转器，保证测试之间互不干扰、不碰真实日志目录。
func newTestRotator(t *testing.T, maxBytes int64, keep int) *Rotator {
	t.Helper()
	dir := t.TempDir()
	r := NewRotator(Config{Dir: dir, BaseName: "test.log", MaxBytes: maxBytes, Keep: keep})
	if r.LastError() != nil {
		t.Fatalf("初始化轮转器失败: %v", r.LastError())
	}
	t.Cleanup(func() { _ = r.Close() })
	return r
}

// countRotated 返回历史文件数量。
func countRotated(t *testing.T, dir string) int {
	t.Helper()
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("读取目录失败: %v", err)
	}
	n := 0
	for _, e := range entries {
		if e.IsDir() || e.Name() == "test.log" {
			continue
		}
		n++
	}
	return n
}

// TestRotatorHonoursSizeCap 验证：写入量超过上限时必须轮转，且单文件不会无限增长。
func TestRotatorHonoursSizeCap(t *testing.T) {
	const maxBytes = 256
	r := newTestRotator(t, maxBytes, 10)

	line := strings.Repeat("a", 60) + "\n" // 61 字节
	for i := 0; i < 12; i++ {
		if _, err := r.Write([]byte(line)); err != nil {
			t.Fatalf("第 %d 次写入失败: %v", i, err)
		}
	}
	if err := r.Close(); err != nil {
		t.Fatalf("关闭失败: %v", err)
	}

	entries, err := os.ReadDir(r.Dir())
	if err != nil {
		t.Fatalf("读取目录失败: %v", err)
	}
	rotated := 0
	for _, e := range entries {
		info, statErr := e.Info()
		if statErr != nil {
			t.Fatalf("读取文件信息失败: %v", statErr)
		}
		if e.Name() == "test.log" {
			if info.Size() > maxBytes {
				t.Fatalf("活动文件大小 %d 超过上限 %d", info.Size(), maxBytes)
			}
			continue
		}
		rotated++
		// 轮转文件允许略微超过上限（最后一条写入可能跨过阈值），但不允许翻倍。
		if info.Size() > 2*maxBytes {
			t.Fatalf("轮转文件 %s 大小 %d 异常", e.Name(), info.Size())
		}
	}
	if rotated == 0 {
		t.Fatal("超过大小上限后应当产生至少一个轮转文件")
	}
}

// TestRotatorDeletesOldest 验证：历史文件数量不会超过 keep，且删掉的是最旧的。
func TestRotatorDeletesOldest(t *testing.T) {
	const keep = 2
	r := newTestRotator(t, 64, keep)

	// 分 5 轮写入，每轮之后强制等待，保证修改时间（也是排序依据）单调递增。
	for round := 0; round < 5; round++ {
		for i := 0; i < 3; i++ {
			if _, err := r.Write([]byte(strings.Repeat("b", 40) + "\n")); err != nil {
				t.Fatalf("写入失败: %v", err)
			}
		}
		if err := r.Close(); err != nil {
			t.Fatalf("关闭失败: %v", err)
		}
		// 重新写一条以触发下一轮：这里通过再次 Write 打开文件即可。
		if _, err := r.Write([]byte(strings.Repeat("c", 40) + "\n")); err != nil {
			t.Fatalf("写入失败: %v", err)
		}
		time.Sleep(1100 * time.Millisecond) // 历史文件名精确到秒，必须跨秒才能判断“最旧”
	}
	if err := r.Close(); err != nil {
		t.Fatalf("关闭失败: %v", err)
	}

	files, err := r.ListFiles()
	if err != nil {
		t.Fatalf("列出日志文件失败: %v", err)
	}
	rotated := 0
	for _, f := range files {
		if !f.Active {
			rotated++
		}
	}
	if rotated > keep {
		t.Fatalf("历史文件数量 %d 超过 keep=%d", rotated, keep)
	}
	if rotated == 0 {
		t.Fatal("应当保留至少一个历史文件")
	}

	// 目录里也不能残留超过 keep 的历史文件。
	if got := countRotated(t, r.Dir()); got > keep {
		t.Fatalf("目录中历史文件数量 %d 超过 keep=%d", got, keep)
	}
}

// TestRotatorKeepsAppendingToActive 验证：轮转之后活动文件继续正常追加，内容不丢。
func TestRotatorKeepsAppendingToActive(t *testing.T) {
	r := newTestRotator(t, 128, 5)

	for i := 0; i < 20; i++ {
		msg := fmt.Sprintf("line-%02d\n", i)
		if _, err := r.Write([]byte(msg)); err != nil {
			t.Fatalf("写入失败: %v", err)
		}
	}
	if err := r.Close(); err != nil {
		t.Fatalf("关闭失败: %v", err)
	}

	// 活动文件必须存在且非空（说明轮转后仍在继续写入）。
	info, err := os.Stat(r.ActivePath())
	if err != nil {
		t.Fatalf("活动文件不存在: %v", err)
	}
	if info.Size() == 0 {
		t.Fatal("活动文件为空，轮转后应当继续追加")
	}

	// 读取活动文件尾部，确认最后一条确实写进去了。
	res, err := r.ReadTail("test.log", TailOptions{Lines: 5})
	if err != nil {
		t.Fatalf("读取尾部失败: %v", err)
	}
	if len(res.Lines) == 0 || !strings.Contains(res.Lines[len(res.Lines)-1], "line-19") {
		t.Fatalf("活动文件尾部内容不符合预期: %v", res.Lines)
	}
}

// TestRotatorContinuesAfterExistingFile 模拟进程重启：活动文件已有内容时继续追加，
// 并在超过上限时先轮转，避免重启后第一个文件无限制增长。
func TestRotatorContinuesAfterExistingFile(t *testing.T) {
	dir := t.TempDir()
	seed := filepath.Join(dir, "test.log")
	if err := os.WriteFile(seed, []byte(strings.Repeat("x", 100)+"\n"), 0o600); err != nil {
		t.Fatalf("准备文件失败: %v", err)
	}

	r := NewRotator(Config{Dir: dir, BaseName: "test.log", MaxBytes: 128, Keep: 3})
	defer func() { _ = r.Close() }()

	if _, err := r.Write([]byte(strings.Repeat("y", 60) + "\n")); err != nil {
		t.Fatalf("写入失败: %v", err)
	}
	if err := r.Close(); err != nil {
		t.Fatalf("关闭失败: %v", err)
	}
	if countRotated(t, dir) == 0 {
		t.Fatal("重启后首次写入越过上限时应先轮转既有文件")
	}
}

// TestValidLogFileName 是本任务最关键的路径穿越防线测试。
func TestValidLogFileName(t *testing.T) {
	r := newTestRotator(t, 1024, 3)

	ok := []string{
		"test.log",
		"test-20250102-150405.log",
		"test-20250102-150405-1.log",
	}
	for _, name := range ok {
		if !r.ValidLogFileName(name) {
			t.Errorf("ValidLogFileName(%q) = false, 期望 true", name)
		}
	}

	bad := []string{
		"",
		"..",
		".",
		"../test.log",
		"..\\test.log",
		"a/test.log",
		"a\\test.log",
		"/etc/passwd",
		"C:\\Windows\\win.ini",
		"test.log\x00",
		"test.log\x00.txt",
		"other.log",
		"test.log.bak",
		"test-2025-01-02.log",
		"test-20250102-150405.log.bak",
		".../test.log",
		"sub/../test.log",
	}
	for _, name := range bad {
		if r.ValidLogFileName(name) {
			t.Errorf("ValidLogFileName(%q) = true, 期望 false（路径穿越/非法名）", name)
		}
		if _, err := r.Resolve(name); err == nil {
			t.Errorf("Resolve(%q) 未报错，期望拒绝", name)
		}
	}
}

// TestResolveStaysInsideDir 验证解析出的路径永远落在日志目录内。
func TestResolveStaysInsideDir(t *testing.T) {
	r := newTestRotator(t, 1024, 3)
	got, err := r.Resolve("test-20250102-150405.log")
	if err != nil {
		t.Fatalf("解析合法文件名失败: %v", err)
	}
	if filepath.Dir(got) != r.Dir() {
		t.Fatalf("解析结果 %q 不在日志目录 %q 内", got, r.Dir())
	}
}

// TestReadTailReverseScan 验证从尾部读取、行数上限、级别与关键词过滤。
func TestReadTailReverseScan(t *testing.T) {
	r := newTestRotator(t, 1<<20, 3)
	lines := []string{
		FormatLine("debug", "启动参数 deviceId=abc"),
		FormatLine("info", "创建新的代理连接 domain=a.example.com"),
		FormatLine("warn", "代理连接 b.example.com 断开，尝试重连"),
		FormatLine("error", "云端不可达: dial tcp timeout"),
		FormatLine("info", "健康检查通过"),
	}
	for _, line := range lines {
		if _, err := r.Write([]byte(line + "\n")); err != nil {
			t.Fatalf("写入失败: %v", err)
		}
	}
	if err := r.Close(); err != nil {
		t.Fatalf("关闭失败: %v", err)
	}

	// 行数上限：只取最后 2 行，且顺序与文件一致（旧 → 新）。
	res, err := r.ReadTail("test.log", TailOptions{Lines: 2})
	if err != nil {
		t.Fatalf("读取尾部失败: %v", err)
	}
	if len(res.Lines) != 2 {
		t.Fatalf("期望 2 行，实际 %d 行: %v", len(res.Lines), res.Lines)
	}
	if !strings.Contains(res.Lines[0], "云端不可达") || !strings.Contains(res.Lines[1], "健康检查通过") {
		t.Fatalf("尾部顺序/内容不符合预期: %v", res.Lines)
	}
	if !res.Truncated {
		t.Error("只取部分行时 truncated 应为 true")
	}

	// 级别过滤：只保留 error。
	res, err = r.ReadTail("test.log", TailOptions{Lines: 100, Level: "error"})
	if err != nil {
		t.Fatalf("读取尾部失败: %v", err)
	}
	if len(res.Lines) != 1 || !strings.HasPrefix(res.Lines[0], "[error] ") {
		t.Fatalf("级别过滤结果不符合预期: %v", res.Lines)
	}

	// 关键词过滤（大小写不敏感）。
	res, err = r.ReadTail("test.log", TailOptions{Lines: 100, Keyword: "B.EXAMPLE.COM"})
	if err != nil {
		t.Fatalf("读取尾部失败: %v", err)
	}
	if len(res.Lines) != 1 || !strings.Contains(res.Lines[0], "b.example.com") {
		t.Fatalf("关键词过滤结果不符合预期: %v", res.Lines)
	}

	// 硬上限：请求超过 MaxTailLines 时被夹住，不报错。
	res, err = r.ReadTail("test.log", TailOptions{Lines: MaxTailLines + 10000})
	if err != nil {
		t.Fatalf("读取尾部失败: %v", err)
	}
	if len(res.Lines) != len(lines) {
		t.Fatalf("期望全部 %d 行，实际 %d 行", len(lines), len(res.Lines))
	}
}

// TestReadTailLargeFileBounded 验证大文件也能在有限行数下快速返回（不整文件读入内存）。
func TestReadTailLargeFileBounded(t *testing.T) {
	r := newTestRotator(t, 1<<30, 3) // 上限放大，保证只产生一个活动文件
	msg := strings.Repeat("payload-", 20) + "\n"
	for i := 0; i < 20000; i++ {
		if _, err := r.Write([]byte(msg)); err != nil {
			t.Fatalf("写入失败: %v", err)
		}
	}
	if err := r.Close(); err != nil {
		t.Fatalf("关闭失败: %v", err)
	}
	info, err := os.Stat(r.ActivePath())
	if err != nil {
		t.Fatalf("读取文件信息失败: %v", err)
	}
	if info.Size() < 300*1024 {
		t.Fatalf("测试文件过小（%d 字节），无法验证反向扫描", info.Size())
	}

	res, err := r.ReadTail("test.log", TailOptions{Lines: 50})
	if err != nil {
		t.Fatalf("读取尾部失败: %v", err)
	}
	if len(res.Lines) != 50 {
		t.Fatalf("期望 50 行，实际 %d 行", len(res.Lines))
	}
}

// TestSanitizeForFileStripsInjectionAndSecrets 验证日志注入与密钥泄漏防护。
func TestSanitizeForFileStripsInjectionAndSecrets(t *testing.T) {
	got := sanitizeForFile("正常消息\r\n[2025-01-02 00:00:00] INF 伪造行")
	if strings.ContainsAny(got, "\r\n") {
		t.Fatalf("CR/LF 未被清除: %q", got)
	}
	if !strings.HasPrefix(got, "正常消息") {
		t.Fatalf("清洗后内容异常: %q", got)
	}

	token := "0123456789abcdef0123456789abcdef"
	for _, in := range []string{
		"打开 http://127.0.0.1:10240/?token=" + token,
		"回调 &token=" + token + "&x=1",
		"login username=u&password=secret123",
		"token=" + token,
	} {
		out := sanitizeForFile(in)
		if strings.Contains(out, token) || strings.Contains(out, "secret123") {
			t.Fatalf("敏感值未被打码: %q", out)
		}
	}
}

// TestManagerLevelFiltering 验证文件接收器按当前级别过滤，并且级别变化立即生效。
func TestManagerLevelFiltering(t *testing.T) {
	level := LevelInfo
	m := NewManager(Config{Dir: t.TempDir(), BaseName: "m.log", MaxBytes: 1 << 20, Keep: 2},
		func() int { return level })
	defer func() { _ = m.Rotator().Close() }()

	m.WriteMessage("debug", "调试信息不应落盘")
	m.WriteMessage("info", "信息可以落盘")
	if _, err := m.WriteLevel(zerolog.DebugLevel, []byte("zerolog-debug\n")); err != nil {
		t.Fatalf("WriteLevel 失败: %v", err)
	}
	if _, err := m.WriteLevel(zerolog.InfoLevel, []byte("zerolog-info\n")); err != nil {
		t.Fatalf("WriteLevel 失败: %v", err)
	}

	level = LevelDebug
	m.WriteMessage("debug", "现在调试信息应落盘")

	if err := m.Rotator().Close(); err != nil {
		t.Fatalf("关闭失败: %v", err)
	}
	res, err := m.Rotator().ReadTail("m.log", TailOptions{Lines: 100})
	if err != nil {
		t.Fatalf("读取失败: %v", err)
	}
	joined := strings.Join(res.Lines, "\n")
	if strings.Contains(joined, "不应落盘") {
		t.Fatalf("低于当前级别的日志被写入了文件: %v", res.Lines)
	}
	if !strings.Contains(joined, "现在调试信息应落盘") {
		t.Fatalf("改级别后调试日志未落盘: %v", res.Lines)
	}
	if !strings.Contains(joined, "zerolog-info") {
		t.Fatalf("zerolog 级别写入丢失: %v", res.Lines)
	}
	// 返回长度必须等于输入长度，否则 MultiLevelWriter 会当成短写报错。
	if n, _ := m.WriteLevel(zerolog.DebugLevel, []byte("dropped")); n != len("dropped") {
		t.Fatalf("被过滤的写入应返回 len(p)=%d，实际 %d", len("dropped"), n)
	}
}

// TestParseLevelName 验证级别解析。
func TestParseLevelName(t *testing.T) {
	cases := map[string]struct {
		value int
		valid bool
	}{
		"debug":   {LevelDebug, true},
		"info":    {LevelInfo, true},
		"warn":    {LevelWarn, true},
		"warning": {LevelWarn, true},
		"error":   {LevelError, true},
		"ERROR":   {LevelError, true},
		"3":       {LevelError, true},
		"0":       {LevelDebug, true},
		"":        {LevelInfo, false},
		"trace":   {LevelInfo, false},
		"bogus":   {LevelInfo, false},
	}
	for in, want := range cases {
		got, ok := ParseLevelName(in)
		if got != want.value || ok != want.valid {
			t.Errorf("ParseLevelName(%q) = (%d,%v)，期望 (%d,%v)", in, got, ok, want.value, want.valid)
		}
	}
}

// TestRotatorNoErrorWhenDirRemoved 验证日志目录被删除后不会 panic，且下次写入自我恢复。
func TestRotatorNoErrorWhenDirRemoved(t *testing.T) {
	r := newTestRotator(t, 1024, 2)
	if _, err := r.Write([]byte("first\n")); err != nil {
		t.Fatalf("写入失败: %v", err)
	}
	if err := r.Close(); err != nil {
		t.Fatalf("关闭失败: %v", err)
	}
	if err := os.RemoveAll(r.Dir()); err != nil {
		t.Fatalf("删除目录失败: %v", err)
	}
	if _, err := r.Write([]byte("second\n")); err != nil {
		t.Fatalf("目录被删除后写入应自动重建目录，实际失败: %v", err)
	}
	if r.LastError() != nil {
		t.Fatalf("自愈后不应残留错误: %v", r.LastError())
	}
}

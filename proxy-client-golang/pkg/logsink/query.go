package logsink

import (
	"bytes"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"
	"unicode/utf8"
)

// 查询相关的硬上限。历史日志可能很大，接口必须“从尾部读”，绝不能整文件读入内存。
const (
	// MaxTailLines 单次返回的最大行数。
	MaxTailLines = 5000
	// DefaultTailLines 未指定 lines 时的默认行数。
	DefaultTailLines = 200
	// maxTailBytes 单次反向扫描的最大字节数（约 4MB）。
	maxTailBytes = 4 << 20
	// maxTailLineBytes 单行最大字节数（防止单行超长导致内存暴涨）。
	maxTailLineBytes = 64 << 10
	// maxTailWindowBytes 反向扫描的字节窗口上限，避免为了几行日志扫完整个大文件。
	maxTailWindowBytes = 16 << 20
)

// logLineRe 从一行日志里解析级别标记。
// 落盘格式为 "[2006-01-02 15:04:05] INF 消息"，同时兼容 zerolog ConsoleWriter 的
// "15:04:05 INF msg" 与纯文本（无级别标记时按 info 处理）。
var logLineRe = regexp.MustCompile(`^(?:\[[^\]]{1,40}\]\s*)?(?:\d{2}:\d{2}:\d{2}\s+)?(DBG|INF|WRN|ERR|FTL|PNC)\b[ \t]*`)

// secretQueryRe 给 URL 查询参数里的敏感值打码（?token=xxx / &password=xxx）。
var secretQueryRe = regexp.MustCompile(`(?i)([?&](?:token|password|passwd|pwd|secret|api_?key)=)([^&\s"']{1,512})`)

// sensitiveValueRe 兜底处理裸 "token=xxx"（不带 ? 或 & 前缀）的情况。
var sensitiveValueRe = regexp.MustCompile(`(?i)\b(token|password|passwd|pwd|secret|api_?key)=([^\s&"']{1,512})`)

// Info 描述一个日志文件。
type Info struct {
	Name    string `json:"name"`
	Size    int64  `json:"size"`
	ModTime string `json:"modTime"`
	Active  bool   `json:"active"`
}

// TailOptions 是 ReadTail 的查询条件。
type TailOptions struct {
	// Lines 期望返回的最大行数（会被夹到 [1, MaxTailLines]）。
	Lines int
	// Level 级别过滤（debug/info/warn/error/all，空或 all 表示不过滤）。
	Level string
	// Keyword 关键词过滤（大小写不敏感，空表示不过滤）。
	Keyword string
}

// TailResult 是 ReadTail 的返回结果。
type TailResult struct {
	File      string   `json:"file"`
	Lines     []string `json:"lines"`
	Total     int      `json:"total"`
	Truncated bool     `json:"truncated"`
}

// timeNow 抽出时间源，便于测试注入（当前实现直接使用系统时间）。
func timeNow() time.Time { return time.Now() }

// timeFromUnixNano 把纳秒时间戳还原成时间。
func timeFromUnixNano(nano int64) time.Time { return time.Unix(0, nano) }

// rotatedSuffixRe 返回匹配本接收器历史文件名的正则。
func rotatedSuffixRe(base string) *regexp.Regexp {
	stem, ext := splitExt(base)
	return regexp.MustCompile(`^` + regexp.QuoteMeta(stem) + `-\d{8}-\d{6}(-\d+)?` + regexp.QuoteMeta(ext) + `$`)
}

// ValidLogFileName 校验客户端提供的日志文件名，只允许“本接收器自己写出”的文件名。
//
// 【安全·路径穿越第一道闸门】
//   - 必须满足 filepath.Base(name) == name，从根上排除 "../x"、"a/b"、"a\\b" 与绝对路径；
//   - 显式拒绝路径分隔符与 NUL 字节；
//   - 只接受当前活动文件名，或 <stem>-<yyyyMMdd-HHmmss>[-n]<ext> 形式的历史文件名。
//
// 调用方仍必须再用 Resolve（filepath.Rel + 前缀复核）做纵深防御。
func (r *Rotator) ValidLogFileName(name string) bool {
	if name == "" || len(name) > 255 {
		return false
	}
	if strings.ContainsAny(name, "/\\\x00") {
		return false
	}
	if name == "." || name == ".." || filepath.Base(name) != name || filepath.Clean(name) != name {
		return false
	}
	if name == r.active {
		return true
	}
	return rotatedSuffixRe(r.active).MatchString(name)
}

// Resolve 把文件名解析为日志目录内的绝对路径。
//
// 【安全·路径穿越】三重校验，缺一不可：
//  1. ValidLogFileName 只允许已知的文件名形态；
//  2. 拼接后 Clean，再用 filepath.Rel 计算相对路径；
//  3. 相对路径不得以 ".." 开头、不得是绝对路径，且最终绝对路径必须以日志目录为前缀。
//
// 即便第 1 步将来被放宽，第 2、3 步也保证读不到日志目录之外的文件。
func (r *Rotator) Resolve(name string) (string, error) {
	if !r.ValidLogFileName(name) {
		return "", errors.New("日志文件名不合法")
	}
	base, err := filepath.Abs(filepath.Clean(r.dir))
	if err != nil {
		return "", errors.New("日志目录解析失败")
	}
	full, err := filepath.Abs(filepath.Clean(filepath.Join(base, name)))
	if err != nil {
		return "", errors.New("日志路径解析失败")
	}

	rel, err := filepath.Rel(base, full)
	if err != nil {
		return "", errors.New("日志路径解析失败")
	}
	if rel == ".." || filepath.IsAbs(rel) || strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return "", errors.New("日志路径越界")
	}

	prefix := base + string(filepath.Separator)
	n := len(prefix)
	if len(full) < n {
		return "", errors.New("日志路径越界")
	}
	// Windows 路径大小写不敏感，用 EqualFold 比较前缀。
	if !strings.EqualFold(full[:n], prefix) {
		return "", errors.New("日志路径越界")
	}
	return full, nil
}

// collectEntries 读取目录并筛出活动文件 / 历史文件。
// 必须在持有 r.mu（或保证目录不被并发轮转）时调用。
func (r *Rotator) collectEntries() (active *Info, rotated []fileEntry) {
	if info, err := os.Stat(r.ActivePath()); err == nil && !info.IsDir() {
		active = &Info{
			Name:    r.active,
			Size:    info.Size(),
			ModTime: info.ModTime().Format("2006-01-02 15:04:05"),
			Active:  true,
		}
	}

	entries, err := os.ReadDir(r.dir)
	if err != nil {
		return active, nil
	}
	re := rotatedSuffixRe(r.active)
	rotated = make([]fileEntry, 0, len(entries))
	for _, de := range entries {
		if de.IsDir() {
			continue
		}
		name := de.Name()
		if !re.MatchString(name) {
			continue
		}
		info, err := de.Info()
		if err != nil {
			continue
		}
		rotated = append(rotated, fileEntry{
			name:    name,
			size:    info.Size(),
			modTime: info.ModTime().UnixNano(),
		})
	}
	sort.Slice(rotated, func(i, j int) bool { return rotated[i].modTime > rotated[j].modTime })
	return active, rotated
}

// fileEntry 内部使用：历史文件名与元数据。
type fileEntry struct {
	name    string
	size    int64
	modTime int64
}

// listRotatedLocked 列出历史文件（按修改时间从新到旧）。必须在持有 r.mu 时调用。
func (r *Rotator) listRotatedLocked() ([]fileEntry, error) {
	_, rotated := r.collectEntries()
	return rotated, nil
}

// ListFiles 返回日志目录内的日志文件：活动文件在前，其余按修改时间从新到旧。
func (r *Rotator) ListFiles() ([]Info, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	active, rotated := r.collectEntries()
	files := make([]Info, 0, len(rotated)+1)
	if active != nil {
		files = append(files, *active)
	}
	for _, f := range rotated {
		files = append(files, Info{
			Name:    f.name,
			Size:    f.size,
			ModTime: timeFromUnixNano(f.modTime).Format("2006-01-02 15:04:05"),
			Active:  false,
		})
	}
	if active == nil && len(rotated) == 0 {
		// 目录尚未创建 / 尚无日志：不算错误，返回空列表即可（第一次启动就是这个状态）。
		if _, err := os.Stat(r.dir); err != nil {
			return files, nil
		}
	}
	return files, nil
}

// sanitizeForFile 清洗即将落盘的文本。
//
// 【为什么】远端穿透服务端可以往隧道消息里塞 CR/LF：直接写文件就能“伪造”出额外的
// 日志行（日志注入），误导运维排查。这里与 tcp 包的 sanitizeRemote 思路一致 ——
// 去掉控制字符、折叠空白、再截断。
//
// 【绝不记录密钥】控制台令牌 / 账号口令一旦进入日志文件，就会随日志被打包、上传或
// 分享而泄漏（doc/SECURITY.md 的 G8）。因此这里对所有 token= / password= 参数打码。
func sanitizeForFile(s string) string {
	cleaned := strings.Map(func(r rune) rune {
		if r == '\t' {
			return ' '
		}
		if r < 0x20 || r == 0x7f {
			return -1
		}
		return r
	}, s)
	cleaned = secretQueryRe.ReplaceAllString(cleaned, "${1}****")
	cleaned = sensitiveValueRe.ReplaceAllString(cleaned, "${1}=****")
	cleaned = strings.TrimSpace(cleaned)
	if runes := []rune(cleaned); len(runes) > maxLogLineRunes {
		cleaned = string(runes[:maxLogLineRunes]) + "…"
	}
	return cleaned
}

// ReadTail 从文件末尾反向读取日志。
//
// 实现要点：以固定大小的块从尾部向前扫描，按 '\n' 切分，凑够行数或达到字节上限即停止，
// 因此读取 100MB 日志的内存占用与耗时由 lines / 上限决定，而不是由文件大小决定。
func (r *Rotator) ReadTail(name string, opts TailOptions) (TailResult, error) {
	result := TailResult{File: name, Lines: []string{}}

	path, err := r.Resolve(name)
	if err != nil {
		return result, err
	}
	f, err := os.Open(path)
	if err != nil {
		return result, fmt.Errorf("打开日志文件失败: %w", err)
	}
	defer func() { _ = f.Close() }()

	size := int64(0)
	if info, statErr := f.Stat(); statErr == nil {
		size = info.Size()
	}

	lines := clampLines(opts.Lines)
	level := strings.ToLower(strings.TrimSpace(opts.Level))
	keyword := strings.ToLower(strings.TrimSpace(opts.Keyword))

	collected := make([]string, 0, lines)
	buf := make([]byte, tailChunkSize)
	pos := size
	// pending 保存「尚未遇到换行、因而不完整」的一小段文本。
	// 【内存陷阱】所有切片都必须复制：pending / raw 若直接引用 buf，下一轮 ReadAt
	// 覆盖 buf 时这段数据会被改写，表现为日志行互相拼接、内容错乱。
	var pending []byte
	var bytesScanned int64
	totalCandidates := 0
	truncated := false

	for pos > 0 {
		// 收满即停：这里用条件检查而不是 break 跳转，确保最后一定会执行“新 → 旧
		// 翻转成旧 → 新”的收尾逻辑（这正是本函数早期版本的 bug 来源）。
		if len(collected) >= lines {
			break
		}
		chunk := int64(len(buf))
		if pos < chunk {
			chunk = pos
		}
		pos -= chunk
		if _, err := f.ReadAt(buf[:chunk], pos); err != nil && !errors.Is(err, io.EOF) {
			return result, fmt.Errorf("读取日志文件失败: %w", err)
		}
		bytesScanned += chunk

		// 块内**从右向左**扫描：每遇到一个换行符，就得到一条完整日志行
		// （换行符右侧的字节 + pending）。这样 collected 天然是「新 → 旧」。
		// 反过来正向扫描会让收集顺序变成「旧 → 新」，再整体反转就彻底打乱顺序。
		segment := buf[:chunk]
		end := len(segment)
		for {
			idx := bytes.LastIndexByte(segment[:end], '\n')
			if idx < 0 {
				// 本块已无换行符：segment[0:end] 属于跨块的同一行，并入 pending。
				if end > 0 {
					head := segment[:end]
					combined := make([]byte, 0, len(head)+len(pending))
					combined = append(combined, head...)
					combined = append(combined, pending...)
					pending = combined
					if len(pending) > maxTailLineBytes {
						pending = pending[len(pending)-maxTailLineBytes:]
						truncated = true
					}
				}
				break
			}
			// 当前行 = 换行符右侧字节 + pending。
			// 【内存陷阱】必须复制：若引用 segment/pending 底层的 buf，下一轮 ReadAt
			// 覆盖 buf 时已收集的内容会被改写（表现为日志行互相拼接、内容错乱）。
			right := segment[idx+1 : end]
			raw := make([]byte, 0, len(right)+len(pending))
			raw = append(raw, right...)
			raw = append(raw, pending...)
			pending = pending[:0]
			end = idx
			totalCandidates++
			if line, ok := acceptLine(raw, level, keyword); ok {
				collected = append(collected, line)
				if len(collected) >= lines {
					// 已经凑够请求行数；前面若还有内容即视为被截断。
					truncated = pos > 0 || idx > 0
					break
				}
			}
			if end == 0 {
				break
			}
		}
		if bytesScanned >= maxTailBytes {
			truncated = true
			break
		}
		if bytesScanned >= maxTailWindowBytes && len(collected) > 0 {
			truncated = true
			break
		}
	}

	// 文件首行（或整个文件没有换行符）也要作为一行处理；它比已收集的都要旧，直接追加。
	if len(pending) > 0 {
		totalCandidates++
		if len(collected) < lines {
			if line, ok := acceptLine(pending, level, keyword); ok {
				collected = append(collected, line)
			}
		}
	}

	// 反向扫描得到的是「新 → 旧」，翻转成「旧 → 新」。
	for i, j := 0, len(collected)-1; i < j; i, j = i+1, j-1 {
		collected[i], collected[j] = collected[j], collected[i]
	}

	result.Lines = collected
	result.Total = totalCandidates
	result.Truncated = truncated
	return result, nil
}

// Serve 把指定日志文件以附件形式写回 HTTP 响应。
//
// 【安全】路径穿越防护全部收敛在 Resolve 里（文件名形态校验 + filepath.Rel +
// 绝对路径前缀复核），这里只负责输出；同时必须：
//   - 用服务端生成的 Content-Disposition 文件名（绝不回显客户端输入，避免响应头注入）；
//   - 设置 X-Content-Type-Options: nosniff，避免日志内容被浏览器当成可执行内容；
//   - 显式指定 text/plain，且不设置任何 HTML 相关类型。
func (r *Rotator) Serve(w io.Writer, name string) error {
	path, err := r.Resolve(name)
	if err != nil {
		return err
	}
	f, err := os.Open(path)
	if err != nil {
		return fmt.Errorf("打开日志文件失败: %w", err)
	}
	defer func() { _ = f.Close() }()
	_, err = io.Copy(w, f)
	return err
}

// SafeDownloadName 生成用于 Content-Disposition 的安全文件名：
// 只保留字母、数字、点、下划线与短横线，其余一律替换为下划线。
// 这样即使 future 放宽了命名规则，响应头也不可能被注入。
func SafeDownloadName(name string) string {
	var b strings.Builder
	for _, r := range name {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9',
			r == '.', r == '_', r == '-':
			b.WriteRune(r)
		default:
			b.WriteRune('_')
		}
	}
	out := b.String()
	if out == "" || out == "." || out == ".." {
		out = "proxy-client.log"
	}
	return out
}

// clampLines 把请求行数夹到合法范围。
func clampLines(n int) int {
	if n <= 0 {
		return DefaultTailLines
	}
	if n > MaxTailLines {
		return MaxTailLines
	}
	return n
}

// acceptLine 过滤并规范化一行日志；不满足条件时返回 ok=false。
// 返回的字符串统一形如 "[warn] 正文"，前端据此着色。
func acceptLine(raw []byte, level, keyword string) (string, bool) {
	if len(raw) == 0 {
		return "", false
	}
	if !utf8.Valid(raw) {
		// 历史文件可能被截断在字符中间：只丢弃非法字节，不放弃整行。
		raw = bytes.ToValidUTF8(raw, []byte(""))
	}
	line := strings.TrimRight(string(raw), "\r")
	if strings.TrimSpace(line) == "" {
		return "", false
	}
	lineLevel, text := parseLineLevel(line)
	if level != "" && level != "all" && lineLevel != level {
		return "", false
	}
	if keyword != "" && !strings.Contains(strings.ToLower(text), keyword) {
		return "", false
	}
	return "[" + lineLevel + "] " + text, true
}

// parseLineLevel 解析一行日志的级别，返回小写级别与去掉时间戳/级别标记后的正文。
func parseLineLevel(line string) (string, string) {
	m := logLineRe.FindStringSubmatch(line)
	if m == nil {
		return "info", line
	}
	token := m[1]
	rest := strings.TrimSpace(line[len(m[0]):])
	if rest == "" {
		// 只有时间戳与级别、没有正文时，原样返回整行，避免展示成空白。
		rest = line
	}
	return levelFromToken(token), rest
}

// levelFromToken 把 zerolog 的级别缩写转成控制台使用的小写级别。
func levelFromToken(token string) string {
	switch strings.ToUpper(token) {
	case "DBG":
		return "debug"
	case "WRN":
		return "warn"
	case "ERR", "FTL", "PNC":
		return "error"
	default:
		return "info"
	}
}

// FormatLine 生成落盘用的日志行："[2006-01-02 15:04:05] INF 消息"。
// 级别沿用 zerolog 的缩写词，与 ConsoleWriter 风格一致，便于人工阅读与解析。
func FormatLine(level string, message string) string {
	token := levelToken(level)
	return "[" + timeNow().Format("2006-01-02 15:04:05") + "] " + token + " " + sanitizeForFile(message)
}

// levelToken 把控制台级别名转成落盘用的三字母缩写。
func levelToken(level string) string {
	switch strings.ToLower(strings.TrimSpace(level)) {
	case "debug":
		return "DBG"
	case "warn", "warning":
		return "WRN"
	case "error", "err", "fatal", "panic", "failed", "fail":
		return "ERR"
	default:
		return "INF"
	}
}

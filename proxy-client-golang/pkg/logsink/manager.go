package logsink

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"

	"github.com/rs/zerolog"
)

// 与控制台（web 包）一致的级别编号：0=Debug 1=Info 2=Warn 3=Error。
// 这里重复定义而不是反向依赖 web 包，避免 pkg → web 的循环依赖。
const (
	LevelDebug = 0
	LevelInfo  = 1
	LevelWarn  = 2
	LevelError = 3
)

// errNilManager 在管理器为 nil 时返回，避免调用方解引用空指针。
var errNilManager = errors.New("日志接收器未初始化")

// DefaultLevelFunc 在未注入级别源时使用：默认 Info。
func DefaultLevelFunc() int { return LevelInfo }

// Manager 把「大小轮转文件写入」与「运行时日志级别」组合起来，作为 zerolog 的
// MultiLevelWriter 之一使用。
//
// 关键设计：
//   - 只需要 zerolog.LevelWriter，不需要 zerolog.Logger：WriteLevel 直接用传入的
//     level 过滤，因此运行时改级别对文件立即生效，无需重启、无需重建 logger；
//   - 级别来自一个注入的函数（由 web 包读取它自己的原子变量），因此控制台改级别时
//     文件与控制台看到的是同一个值，不会出现两边不一致；
//   - 普通 Write（无级别信息）按 Info 处理，并同样过一遍级别过滤。
type Manager struct {
	rot   *Rotator
	level func() int

	mu     sync.Mutex
	layerr error
}

// NewManager 创建日志接收器管理器。levelFunc 为 nil 时使用 DefaultLevelFunc。
func NewManager(cfg Config, levelFunc func() int) *Manager {
	if levelFunc == nil {
		levelFunc = DefaultLevelFunc
	}
	return &Manager{
		rot:   NewRotator(cfg),
		level: levelFunc,
	}
}

// Rotator 返回底层轮转器（只读用途：目录、文件名、状态）。
func (m *Manager) Rotator() *Rotator { return m.rot }

// Dir 返回日志目录绝对路径。
func (m *Manager) Dir() string { return m.rot.Dir() }

// FileName 返回当前日志文件名。
func (m *Manager) FileName() string { return m.rot.FileName() }

// Keep 返回历史文件保留数量。
func (m *Manager) Keep() int { return m.rot.Keep() }

// MaxBytes 返回单文件大小上限。
func (m *Manager) MaxBytes() int64 { return m.rot.MaxBytes() }

// levelValue 读取当前级别编号；越界时回落到 Info。
func (m *Manager) levelValue() int {
	v := m.level()
	if v < LevelDebug || v > LevelError {
		return LevelInfo
	}
	return v
}

// LevelValue 返回当前生效的日志级别编号。
func (m *Manager) LevelValue() int { return m.levelValue() }

// Write 实现 io.Writer：无级别信息的写入按 Info 级别处理。
func (m *Manager) Write(p []byte) (int, error) {
	return m.WriteLevel(zerolog.InfoLevel, p)
}

// WriteLevel 实现 zerolog.LevelWriter：低于当前级别的内容直接丢弃（不落盘）。
func (m *Manager) WriteLevel(level zerolog.Level, p []byte) (int, error) {
	if zerologLevelValue(level) < m.levelValue() {
		// 返回 len(p) 而不是 0：io.Writer 的约定要求“未出错即报告全部消费”，
		// 否则 MultiLevelWriter 会把它当成短写并报错，反而让上层日志系统报警。
		return len(p), nil
	}
	n, err := m.rot.Write(p)
	if err != nil {
		m.recordError(err)
	}
	return n, err
}

// WriteMessage 以指定级别写一条完整日志行。
// 控制台事件（隧道消息 / 失败事件）走这里落盘，保证文件里有与界面相同的记录。
// 任何错误都被吞掉 —— 日志落盘失败绝不能影响隧道数据路径。
func (m *Manager) WriteMessage(level, message string) {
	if m == nil {
		return
	}
	if levelValueOfName(level) < m.levelValue() {
		return
	}
	line := FormatLine(level, message) + "\n"
	if _, err := m.rot.Write([]byte(line)); err != nil {
		m.recordError(err)
	}
}

func (m *Manager) recordError(err error) {
	if err == nil {
		return
	}
	m.mu.Lock()
	m.layerr = err
	m.mu.Unlock()
}

// LastError 返回最近一次写入/轮转错误。
func (m *Manager) LastError() error {
	if m == nil {
		return nil
	}
	m.mu.Lock()
	layerr := m.layerr
	m.mu.Unlock()
	if layerr != nil {
		return layerr
	}
	if m.rot != nil {
		return m.rot.LastError()
	}
	return nil
}

// Status 汇总日志子系统的运行状态，供 /console/diagnose 使用。
type Status struct {
	Dir         string `json:"dir"`
	File        string `json:"file"`
	ActiveSize  int64  `json:"activeSize"`
	MaxBytes    int64  `json:"maxBytes"`
	Keep        int    `json:"keep"`
	Level       string `json:"level"`
	Writable    bool   `json:"writable"`
	RotateError string `json:"rotateError,omitempty"`
}

// Status 返回日志子系统状态。
func (m *Manager) Status() Status {
	if m == nil {
		return Status{}
	}
	writable, writeErr := m.DirWritable()
	st := Status{
		Dir:        m.rot.Dir(),
		File:       m.rot.FileName(),
		ActiveSize: m.rot.Written(),
		MaxBytes:   m.rot.MaxBytes(),
		Keep:       m.rot.Keep(),
		Level:      LevelName(m.levelValue()),
		Writable:   writable,
	}
	if err := m.LastError(); err != nil {
		st.RotateError = err.Error()
	} else if writeErr != nil {
		st.RotateError = writeErr.Error()
	}
	return st
}

// DirWritable 检查日志目录是否可写：确保目录存在，并写入一个临时文件后删除。
// 只用于 /console/diagnose 的只读探测，不会留下垃圾文件。
func (m *Manager) DirWritable() (bool, error) {
	if m == nil || m.rot == nil {
		return false, errNilManager
	}
	dir := m.rot.Dir()
	if err := os.MkdirAll(dir, dirPerm); err != nil {
		return false, err
	}
	probe := filepath.Join(dir, ".write-probe")
	f, err := os.OpenFile(probe, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, filePerm)
	if err != nil {
		return false, err
	}
	closeErr := f.Close()
	removeErr := os.Remove(probe)
	if closeErr != nil {
		return false, closeErr
	}
	if removeErr != nil {
		return false, removeErr
	}
	return true, nil
}

// LevelValueOf 把控制台级别名转成级别编号（供 web 层复用，避免两处各写一套映射）。
func LevelValueOf(level string) int { return levelValueOfName(level) }

// levelValueOfName 把级别名转成编号；未知名称按 Info 处理。
func levelValueOfName(level string) int {
	switch normalizeLevelName(level) {
	case "debug":
		return LevelDebug
	case "warn":
		return LevelWarn
	case "error":
		return LevelError
	default:
		return LevelInfo
	}
}

// normalizeLevelName 归一化级别名（兼容 zerolog 缩写、大小写、warning 与数字写法）。
// 无法识别时返回空串：调用方据此区分「合法的 info」与「非法输入」。
func normalizeLevelName(level string) string {
	switch strings.ToLower(strings.TrimSpace(level)) {
	case "debug", "0":
		return "debug"
	case "warn", "warning", "2":
		return "warn"
	case "error", "err", "fatal", "panic", "3":
		return "error"
	case "info", "1":
		return "info"
	default:
		return ""
	}
}

// zerologLevelValue 把 zerolog 级别映射到 0..3 编号（Fatal/Panic 归入 Error）。
func zerologLevelValue(level zerolog.Level) int {
	switch level {
	case zerolog.TraceLevel, zerolog.DebugLevel:
		return LevelDebug
	case zerolog.InfoLevel, zerolog.NoLevel:
		return LevelInfo
	case zerolog.WarnLevel:
		return LevelWarn
	default:
		// ErrorLevel / FatalLevel / PanicLevel / Disabled 一律按 Error 处理。
		return LevelError
	}
}

// zerologLevel 把 0..3 编号映射回 zerolog 级别。
func zerologLevel(value int) zerolog.Level {
	switch value {
	case LevelDebug:
		return zerolog.DebugLevel
	case LevelWarn:
		return zerolog.WarnLevel
	case LevelError:
		return zerolog.ErrorLevel
	default:
		return zerolog.InfoLevel
	}
}

// globalLevel 是本进程最近一次设置的全局级别编号。
//
// 【为什么自己维护】zerolog 没有读取全局级别的公开 API（只有 SetGlobalLevel），
// 因此这里用一个原子变量镜像它，供控制台回显当前级别。两者只在 SetGlobalLevel
// 里同时更新，不会出现不一致。
var globalLevel atomic.Int32

func init() {
	globalLevel.Store(LevelInfo)
}

// SetGlobalLevel 设置 zerolog 的全局级别，并返回实际生效的级别编号。
// zerolog 内部用原子变量维护全局级别，因此可在运行时被控制台安全调用。
func SetGlobalLevel(value int) int {
	if value < LevelDebug || value > LevelError {
		value = LevelInfo
	}
	zerolog.SetGlobalLevel(zerologLevel(value))
	globalLevel.Store(int32(value))
	return value
}

// GlobalLevel 返回当前 zerolog 全局级别对应的编号。
func GlobalLevel() int { return int(globalLevel.Load()) }

// LevelName 把级别编号转成名称。
func LevelName(value int) string {
	switch value {
	case LevelDebug:
		return "debug"
	case LevelWarn:
		return "warn"
	case LevelError:
		return "error"
	default:
		return "info"
	}
}

// ParseLevelName 解析管理员提交的级别名，返回级别编号与是否合法。
// 同时接受数字（"0".."3"）与 zerolog 缩写，便于脚本调用；其余输入一律视为非法
// （调用方必须拒绝，而不是静默回落到 info，否则运维会以为设置生效了）。
func ParseLevelName(raw string) (int, bool) {
	switch normalizeLevelName(raw) {
	case "debug":
		return LevelDebug, true
	case "info":
		return LevelInfo, true
	case "warn":
		return LevelWarn, true
	case "error":
		return LevelError, true
	}
	return LevelInfo, false
}

// RotateNow 立即执行一次轮转（供诊断接口做“日志可写 & 轮转可用”验证）。
// 失败只记录错误，不会 panic。
func (m *Manager) RotateNow() {
	if m == nil || m.rot == nil {
		return
	}
	m.rot.mu.Lock()
	err := m.rot.rotateFileLocked()
	m.rot.mu.Unlock()
	if err != nil {
		m.recordError(err)
	}
}

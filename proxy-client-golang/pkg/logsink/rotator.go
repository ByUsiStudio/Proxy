// Package logsink 实现「日志落盘 + 按大小轮转 + 历史查询」。
//
// 配置方式（由 main.go 解析命令行参数后写入同名环境变量，也可直接用环境变量启动）：
//
//	-logDir     / LOG_DIR      日志目录，默认 logs（相对进程工作目录）
//	-logFile    / LOG_FILE     当前日志文件名，默认 proxy-client.log
//	-logMaxMB   / LOG_MAX_MB   单个日志文件大小上限（MB），默认 10
//	-logKeep    / LOG_KEEP     轮转后保留的历史文件数量，默认 5
//
// 设计要点（为什么这样写）：
//  1. 只用标准库自写轮转器，不引入任何新依赖（离线环境无法解析新的模块）；
//  2. 轮转与写入共用一把互斥锁，保证「关闭 → 改名 → 重新打开」三步之间不会出现
//     半写状态（否则改名后的历史文件尾部会缺一段，或新文件与旧文件同时被写）；
//  3. 任何文件操作失败都只记录错误，绝不 panic、绝不阻塞调用方 —— 日志系统故障
//     不允许影响隧道数据通道（写失败时下次写入会重新尝试打开文件，即自我恢复）；
//  4. 【安全】日志目录用 0700、日志文件用 0600 创建（Windows 之外生效），
//     避免同机其它用户读到隧道事件；写入前统一清洗控制字符并给敏感查询参数打码，
//     绝不让控制台令牌 / 账号口令 / ?token= 值落盘（G8/G15 同类问题不得回归）。
package logsink

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// 默认配置值，与 main.go 的命令行默认值保持一致。
const (
	DefaultDirName    = "logs"
	DefaultFileName   = "proxy-client.log"
	DefaultMaxMB      = 10
	DefaultKeepFiles  = 5
	defaultMaxBytes   = int64(DefaultMaxMB) << 20
	rotatedTimeLayout = "20060102-150405"
	// dirPerm / filePerm 日志目录与日志文件的权限（POSIX 系统生效，Windows 忽略）。
	dirPerm  os.FileMode = 0o700
	filePerm os.FileMode = 0o600
	// maxLogLineRunes 单条日志消息落盘前的最大字符数，避免一条超长消息撑爆文件。
	maxLogLineRunes = 4096
	// tailChunkSize 反向读取日志时的块大小。
	tailChunkSize = 64 << 10
)

// Config 是日志文件接收器的配置。
type Config struct {
	// Dir 日志目录（相对路径按进程工作目录解析）。
	Dir string
	// BaseName 当前日志文件名（不含目录）。
	BaseName string
	// MaxBytes 单文件大小上限（字节）。
	MaxBytes int64
	// Keep 轮转文件保留数量（0 表示不保留历史文件）。
	Keep int
}

// Rotator 是支持按大小轮转的日志文件写入器。
// 零值不可用，必须通过 NewRotator 创建。
type Rotator struct {
	mu       sync.Mutex
	dir      string
	active   string
	keep     int
	maxBytes int64

	file    *os.File
	current int64
	lastErr error
}

// NewRotator 创建轮转写入器。
// 这里**不**预创建目录：构造函数不返回 error，目录创建失败（例如权限不足）
// 会在首次写入时被记录到 lastErr，并由 /console/diagnose 报告，进程照常启动。
func NewRotator(cfg Config) *Rotator {
	dir := strings.TrimSpace(cfg.Dir)
	if dir == "" {
		dir = DefaultDirName
	}
	name := strings.TrimSpace(cfg.BaseName)
	if name == "" {
		name = DefaultFileName
	}

	r := &Rotator{
		dir:      dir,
		active:   name,
		keep:     cfg.Keep,
		maxBytes: cfg.MaxBytes,
	}
	if r.maxBytes <= 0 {
		r.maxBytes = defaultMaxBytes
	}
	if r.keep < 0 {
		r.keep = 0
	}

	if abs, err := filepath.Abs(dir); err == nil {
		r.dir = abs
	}
	if err := os.MkdirAll(r.dir, dirPerm); err != nil {
		r.lastErr = fmt.Errorf("创建日志目录失败: %w", err)
	} else {
		// MkdirAll 对已存在的目录不会修正权限，这里显式收紧一次（失败不致命）。
		_ = os.Chmod(r.dir, dirPerm)
	}
	return r
}

// Dir 返回日志目录的绝对路径。
func (r *Rotator) Dir() string { return r.dir }

// FileName 返回当前日志文件名。
func (r *Rotator) FileName() string { return r.active }

// ActivePath 返回当前日志文件的绝对路径。
func (r *Rotator) ActivePath() string { return filepath.Join(r.dir, r.active) }

// MaxBytes 返回单文件大小上限。
func (r *Rotator) MaxBytes() int64 { return r.maxBytes }

// Keep 返回历史文件保留数量。
func (r *Rotator) Keep() int { return r.keep }

// LastError 返回最近一次文件操作错误（nil 表示正常）。
func (r *Rotator) LastError() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.lastErr
}

// Written 返回当前活动文件已写入的字节数（0 表示尚未打开文件）。
func (r *Rotator) Written() int64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.current
}

// Write 实现 io.Writer。写入前按需轮转；任何错误只记录不返回给调用方之外的处理，
// 由上层（zerolog MultiLevelWriter / Manager）决定是否上报。
func (r *Rotator) Write(p []byte) (int, error) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if err := r.rotateLocked(int64(len(p))); err != nil {
		r.lastErr = err
		return 0, err
	}
	if r.file == nil {
		err := r.openLocked()
		if err != nil {
			r.lastErr = err
			return 0, err
		}
	}

	n, err := r.file.Write(p)
	if n > 0 {
		r.current += int64(n)
	}
	if err != nil {
		// 写失败（磁盘满 / 句柄失效）：关闭并丢弃句柄，下次写入会重新打开，
		// 避免「一次失败之后所有日志都静默丢失」。
		_ = r.file.Close()
		r.file = nil
		r.current = 0
		r.lastErr = fmt.Errorf("写日志文件失败: %w", err)
	} else {
		r.lastErr = nil
	}
	return n, err
}

// rotateLocked 在「本次写入会超过上限」时执行轮转。必须在持有 r.mu 时调用。
func (r *Rotator) rotateLocked(incoming int64) error {
	if r.file == nil {
		// 尚未打开文件：先看看活动文件已经有多大（进程重启后继续追加）。
		if info, err := os.Stat(r.ActivePath()); err == nil && info.Size() > 0 {
			if info.Size()+incoming > r.maxBytes {
				if err := r.rotateFileLocked(); err != nil {
					return err
				}
			}
		}
		return nil
	}
	if r.current+incoming <= r.maxBytes {
		return nil
	}
	return r.rotateFileLocked()
}

// rotateFileLocked 关闭当前文件并改成带时间戳的历史文件，然后清理超量历史文件。
// 必须在持有 r.mu 时调用。
func (r *Rotator) rotateFileLocked() error {
	if r.file != nil {
		if err := r.file.Sync(); err != nil {
			// 同步失败不阻止轮转：数据可能已经落盘，继续轮转总比把文件卡住好。
			r.lastErr = fmt.Errorf("同步日志文件失败: %w", err)
		}
		if err := r.file.Close(); err != nil {
			r.lastErr = fmt.Errorf("关闭日志文件失败: %w", err)
		}
		r.file = nil
		r.current = 0
	}

	active := r.ActivePath()
	if info, err := os.Stat(active); err != nil || info.Size() == 0 {
		// 没有内容就没有必要轮转，避免产生一堆 0 字节历史文件。
		return nil
	}

	target := r.rotatedNameLocked(time.Now())
	if err := os.Rename(active, target); err != nil {
		// 改名失败（例如历史文件被其它进程占用）时保留原文件继续追加，
		// 宁可超出大小上限，也不能丢日志。
		r.lastErr = fmt.Errorf("轮转日志文件失败: %w", err)
		return nil
	}
	if err := r.pruneLocked(); err != nil {
		r.lastErr = err
	}
	return nil
}

// rotatedNameLocked 生成不与既有文件冲突的历史文件名。必须在持有 r.mu 时调用。
func (r *Rotator) rotatedNameLocked(now time.Time) string {
	stem, ext := splitExt(r.active)
	for i := 0; ; i++ {
		name := stem + "-" + now.Format(rotatedTimeLayout)
		if i > 0 {
			name += fmt.Sprintf("-%d", i)
		}
		name += ext
		if _, err := os.Stat(filepath.Join(r.dir, name)); errors.Is(err, os.ErrNotExist) {
			return filepath.Join(r.dir, name)
		}
		if i > 1000 {
			// 极端情况下的兜底：加纳秒保证唯一，绝不会死循环。
			return filepath.Join(r.dir, fmt.Sprintf("%s-%d%s", stem, now.UnixNano(), ext))
		}
	}
}

// pruneLocked 删除超出 keep 数量的历史文件（按修改时间从旧到新）。必须在持有 r.mu 时调用。
func (r *Rotator) pruneLocked() error {
	if r.keep <= 0 {
		return nil
	}
	files, err := r.listRotatedLocked()
	if err != nil {
		return err
	}
	// listRotatedLocked 已按修改时间从新到旧排序，超出 keep 的部分即最旧的。
	for i := r.keep; i < len(files); i++ {
		if err := os.Remove(filepath.Join(r.dir, files[i].name)); err != nil && !errors.Is(err, os.ErrNotExist) {
			return fmt.Errorf("删除历史日志失败: %w", err)
		}
	}
	return nil
}

// openLocked 打开（必要时创建）活动日志文件并统计当前大小。必须在持有 r.mu 时调用。
func (r *Rotator) openLocked() error {
	if err := os.MkdirAll(r.dir, dirPerm); err != nil {
		return fmt.Errorf("创建日志目录失败: %w", err)
	}
	f, err := os.OpenFile(r.ActivePath(), os.O_CREATE|os.O_WRONLY|os.O_APPEND, filePerm)
	if err != nil {
		return fmt.Errorf("打开日志文件失败: %w", err)
	}
	// OpenFile 的 perm 只对新建文件生效，已存在的文件可能权限过宽，这里收紧一次。
	_ = f.Chmod(filePerm)
	r.file = f
	if info, err := f.Stat(); err == nil {
		r.current = info.Size()
	} else {
		r.current = 0
	}
	r.lastErr = nil
	return nil
}

// Close 关闭当前文件（用于进程退出或测试收尾）。
func (r *Rotator) Close() error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.file == nil {
		return nil
	}
	err := r.file.Close()
	r.file = nil
	r.current = 0
	return err
}

// splitExt 拆分文件名与扩展名（保留点号）。
func splitExt(name string) (string, string) {
	ext := filepath.Ext(name)
	return strings.TrimSuffix(name, ext), ext
}

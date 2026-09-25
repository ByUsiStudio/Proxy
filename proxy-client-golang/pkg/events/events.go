// Package events 提供「失败事件环形缓冲」。
//
// 设计要点（为什么这样写）：
//  1. 只记录**失败/异常**事件，不复制整条日志流。日志是高频的，若把每条日志都塞进
//     内存缓冲，既浪费内存又淹没有用信号；控制台需要的是「最近出了哪些问题」。
//  2. 缓冲有界（默认 200 条），写满后覆盖最旧的一条。诊断/告警接口永远不会因为
//     事件过多而撑爆内存，也不需要额外的清理协程。
//  3. message 可能来自远端穿透服务端（完全不可信），因此这里与日志落盘一样做
//     CR/LF 与控制字符清洗，避免伪造/拆分展示行；前端也只以文本节点渲染。
//  4. 单一互斥锁：写入是「一次 append + 一次覆盖」，临界区极短，不会阻塞隧道数据路径。
package events

import (
	"strings"
	"sync"
	"time"
)

const (
	// DefaultCapacity 默认环形缓冲容量。
	DefaultCapacity = 200
	// maxMessageRunes 单条事件消息的最大字符数（与远端消息上限保持一致量级）。
	maxMessageRunes = 512
	// maxKindRunes 事件类型的最大字符数（本地常量，纯防御）。
	maxKindRunes = 32
	// maxDomainRunes 域名的最大字符数（域名本身有长度上限，纯防御）。
	maxDomainRunes = 253
)

// Event 是一条失败/异常事件。
//
// 【安全】Message 与 Domain 可能来自远端，必须视为不可信文本：服务端已做 CR/LF 清洗，
// 前端只允许用 textContent 渲染。
type Event struct {
	// Time 事件发生时间（RFC3339，便于前端与排序直接使用）。
	Time string `json:"time"`
	// Kind 事件类型，例如 tunnel-create / reconnect / cloud-api / config-import /
	// ws-channel / tls-dial。
	Kind string `json:"kind"`
	// Domain 关联的隧道域名（可为空，例如云端不可达这类全局事件）。
	Domain string `json:"domain"`
	// Message 事件描述（已清洗，绝不含控制字符）。
	Message string `json:"message"`

	// at 仅用于内存内的 24 小时统计，不参与序列化。
	at time.Time
}

// Buffer 是并发安全的失败事件环形缓冲。
type Buffer struct {
	mu   sync.Mutex
	buf  []Event
	next int
	size int
	// now 允许测试注入固定时间源。
	now func() time.Time
}

// New 创建容量为 capacity 的环形缓冲；capacity <= 0 时使用 DefaultCapacity。
func New(capacity int) *Buffer {
	if capacity <= 0 {
		capacity = DefaultCapacity
	}
	return &Buffer{
		buf: make([]Event, capacity),
		now: time.Now,
	}
}

// Cap 返回缓冲容量。
func (b *Buffer) Cap() int {
	if b == nil {
		return 0
	}
	return len(b.buf)
}

// Len 返回当前已记录的事件数量（不超过容量）。
func (b *Buffer) Len() int {
	if b == nil {
		return 0
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.size
}

// sanitize 清洗不可信文本：去掉控制字符，折叠空白，截断超长内容。
func sanitize(s string, maxRunes int) string {
	cleaned := strings.Map(func(r rune) rune {
		if r == '\t' {
			return ' '
		}
		if r < 0x20 || r == 0x7f {
			return -1
		}
		return r
	}, s)
	cleaned = strings.TrimSpace(cleaned)
	if maxRunes > 0 {
		if runes := []rune(cleaned); len(runes) > maxRunes {
			cleaned = string(runes[:maxRunes]) + "…"
		}
	}
	return cleaned
}

// Record 记录一条失败事件。kind 为空时记为 "unknown"。
// 该函数绝不会 panic，也绝不会因为缓冲满而阻塞调用方。
func (b *Buffer) Record(kind, domain, message string) {
	if b == nil {
		return
	}
	now := b.now()
	ev := Event{
		Time:    now.Format(time.RFC3339),
		Kind:    sanitize(kind, maxKindRunes),
		Domain:  sanitize(domain, maxDomainRunes),
		Message: sanitize(message, maxMessageRunes),
		at:      now,
	}
	if ev.Kind == "" {
		ev.Kind = "unknown"
	}

	b.mu.Lock()
	b.buf[b.next] = ev
	b.next = (b.next + 1) % len(b.buf)
	if b.size < len(b.buf) {
		b.size++
	}
	b.mu.Unlock()
}

// Latest 返回最新的 limit 条事件（时间倒序：最新在前）。limit <= 0 视为全部。
func (b *Buffer) Latest(limit int) []Event {
	if b == nil {
		return []Event{}
	}
	b.mu.Lock()
	defer b.mu.Unlock()

	if limit <= 0 || limit > b.size {
		limit = b.size
	}
	out := make([]Event, 0, limit)
	for i := 0; i < limit; i++ {
		// next-1 是最后写入的位置，逐条向前回溯。
		idx := (b.next - 1 - i + len(b.buf)*2) % len(b.buf)
		out = append(out, b.buf[idx])
	}
	return out
}

// CountSince 统计 since 之后记录的事件条数（用于接口里的 count24h）。
func (b *Buffer) CountSince(since time.Time) int {
	if b == nil {
		return 0
	}
	b.mu.Lock()
	defer b.mu.Unlock()

	n := 0
	for i := 0; i < b.size; i++ {
		idx := (b.next - 1 - i + len(b.buf)*2) % len(b.buf)
		if b.buf[idx].at.After(since) {
			n++
		}
	}
	return n
}

// Clear 清空缓冲（保留容量）。
func (b *Buffer) Clear() {
	if b == nil {
		return
	}
	b.mu.Lock()
	for i := range b.buf {
		b.buf[i] = Event{}
	}
	b.next = 0
	b.size = 0
	b.mu.Unlock()
}

package events

import (
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"
)

// TestBufferBound 验证环形缓冲严格有界，且始终保留最新的记录。
func TestBufferBound(t *testing.T) {
	const capacity = 8
	b := New(capacity)
	if b.Cap() != capacity {
		t.Fatalf("容量 = %d，期望 %d", b.Cap(), capacity)
	}
	if b.Len() != 0 {
		t.Fatalf("初始长度 = %d，期望 0", b.Len())
	}

	for i := 0; i < capacity*5; i++ {
		b.Record("tunnel-create", fmt.Sprintf("d%d.example.com", i), fmt.Sprintf("失败 #%d", i))
	}
	if b.Len() != capacity {
		t.Fatalf("长度 = %d，期望被限制在 %d", b.Len(), capacity)
	}

	latest := b.Latest(0)
	if len(latest) != capacity {
		t.Fatalf("Latest 返回 %d 条，期望 %d", len(latest), capacity)
	}
	// 最新在前：第一条应当是最后写入的。
	if !strings.Contains(latest[0].Message, fmt.Sprintf("#%d", capacity*5-1)) {
		t.Fatalf("Latest 首条不是最新记录: %+v", latest[0])
	}
	if !strings.Contains(latest[capacity-1].Message, fmt.Sprintf("#%d", capacity*5-capacity)) {
		t.Fatalf("Latest 末条不是最旧保留记录: %+v", latest[capacity-1])
	}

	// limit 生效。
	if got := len(b.Latest(3)); got != 3 {
		t.Fatalf("Latest(3) 返回 %d 条", got)
	}
}

// TestBufferEmptyReturnsArray 验证空缓冲返回空切片而不是 nil（JSON 里应为 [] 而非 null）。
func TestBufferEmptyReturnsArray(t *testing.T) {
	b := New(0)
	if got := b.Latest(10); got == nil || len(got) != 0 {
		t.Fatalf("空缓冲 Latest 应返回非 nil 空切片，实际 %#v", got)
	}
	var nilBuf *Buffer
	if got := nilBuf.Latest(1); got == nil {
		t.Fatal("nil 缓冲也应返回非 nil 空切片")
	}
}

// TestBufferSanitizesRemoteText 验证不可信文本被清洗（CR/LF 注入、超长截断）。
func TestBufferSanitizesRemoteText(t *testing.T) {
	b := New(4)
	b.Record("ws-channel", "evil\r\ndomain", "伪造行\r\n[ERR] 假的错误")
	got := b.Latest(1)
	if len(got) != 1 {
		t.Fatalf("期望 1 条记录，实际 %d", len(got))
	}
	if strings.ContainsAny(got[0].Domain, "\r\n") || strings.ContainsAny(got[0].Message, "\r\n") {
		t.Fatalf("控制字符未被清洗: %+v", got[0])
	}
	if !strings.HasPrefix(got[0].Message, "伪造行") {
		t.Fatalf("清洗后内容异常: %q", got[0].Message)
	}

	b.Record("", "", strings.Repeat("x", maxMessageRunes*3))
	got = b.Latest(1)
	if len([]rune(got[0].Message)) > maxMessageRunes+1 {
		t.Fatalf("超长消息未被截断: %d 字符", len([]rune(got[0].Message)))
	}
	if got[0].Kind != "unknown" {
		t.Fatalf("空 kind 应归一化为 unknown，实际 %q", got[0].Kind)
	}
}

// TestBufferCountSince 验证 24 小时统计口径。
func TestBufferCountSince(t *testing.T) {
	b := New(16)
	base := time.Now()
	b.now = func() time.Time { return base.Add(-30 * time.Hour) }
	b.Record("cloud-api", "", "很久以前的失败")

	b.now = func() time.Time { return base }
	b.Record("cloud-api", "", "刚刚的失败")
	b.Record("reconnect", "a.example.com", "刚刚的重连失败")

	if got := b.CountSince(time.Now().Add(-24 * time.Hour)); got != 2 {
		t.Fatalf("count24h = %d，期望 2", got)
	}
	if got := b.CountSince(time.Now().Add(-48 * time.Hour)); got != 3 {
		t.Fatalf("count48h = %d，期望 3", got)
	}
}

// TestBufferClear 验证清空。
func TestBufferClear(t *testing.T) {
	b := New(4)
	b.Record("tls-dial", "a", "x")
	b.Clear()
	if b.Len() != 0 {
		t.Fatalf("Clear 后长度 = %d", b.Len())
	}
	if b.Cap() != 4 {
		t.Fatalf("Clear 不应改变容量: %d", b.Cap())
	}
}

// TestBufferConcurrent 用 -race 运行时验证并发写入的安全性。
func TestBufferConcurrent(t *testing.T) {
	b := New(32)
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			for j := 0; j < 200; j++ {
				b.Record("ws-channel", fmt.Sprintf("d%d", id), fmt.Sprintf("失败 %d", j))
				_ = b.Latest(5)
				_ = b.CountSince(time.Now().Add(-time.Hour))
			}
		}(i)
	}
	wg.Wait()
	if b.Len() != 32 {
		t.Fatalf("并发写入后长度 = %d，期望 32", b.Len())
	}
}

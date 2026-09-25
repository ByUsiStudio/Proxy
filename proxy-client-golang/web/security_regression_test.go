package web

import (
	"fmt"
	"testing"
	"time"
)

func TestHostAllowlist(t *testing.T) {
	t.Setenv("WEB_HOST", "")
	t.Setenv("WEB_ALLOWED_HOSTS", "")
	cases := []struct {
		host string
		want bool
	}{
		{"", false},
		{"127.0.0.1:10240", true},
		{"127.0.0.1", true},
		{"127.0.0.5:10240", true},
		{"localhost:10240", true},
		{"LOCALHOST", true},
		{"[::1]:10240", true},
		{"::1", true},
		{"evil.tld:10240", false},
		{"evil.tld", false},
		{"10.0.0.1:10240", false},
		{"192.168.1.10", false},
	}
	for _, c := range cases {
		if got := hostInAllowlist(c.host); got != c.want {
			t.Errorf("hostInAllowlist(%q) = %v, want %v", c.host, got, c.want)
		}
	}

	t.Setenv("WEB_ALLOWED_HOSTS", "console.example.com, 10.1.2.3")
	if !hostInAllowlist("console.example.com:10240") {
		t.Error("WEB_ALLOWED_HOSTS 中的主机名应被放行")
	}
	if !hostInAllowlist("10.1.2.3") {
		t.Error("WEB_ALLOWED_HOSTS 中的 IP 应被放行")
	}
	if hostInAllowlist("other.example.com") {
		t.Error("未列入白名单的主机名不应被放行")
	}

	t.Setenv("WEB_HOST", "192.168.1.10")
	if !hostInAllowlist("192.168.1.10:10240") {
		t.Error("WEB_HOST 指定的绑定主机名应被放行")
	}

	t.Setenv("WEB_HOST", "0.0.0.0")
	t.Setenv("WEB_ALLOWED_HOSTS", "")
	if hostInAllowlist("192.168.1.10") {
		t.Error("监听 0.0.0.0 不代表任意主机名都可信")
	}
}

func TestRateLimiterBounded(t *testing.T) {
	r := newRateLimiter(3, time.Minute)
	for i := 0; i < maxRateLimiterKeys+200; i++ {
		r.allow(fmt.Sprintf("10.0.%d.%d|/server/proxy", (i/256)%256, i%256))
	}
	r.mu.Lock()
	n := len(r.hits)
	r.mu.Unlock()
	if n > maxRateLimiterKeys {
		t.Fatalf("限流器 map 未受限: %d 个键", n)
	}

	// 认证失败限流语义
	a := newRateLimiter(2, time.Minute)
	if a.exceeded("ip") {
		t.Fatal("初始不应超限")
	}
	a.fail("ip")
	a.fail("ip")
	if !a.exceeded("ip") {
		t.Fatal("失败次数达到上限后应判定超限")
	}
}

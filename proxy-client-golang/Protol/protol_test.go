package Protol

import (
	"bufio"
	"bytes"
	"encoding/binary"
	"testing"

	"proxy-client-golang/hpMessage"
)

// frame 构造一帧原始数据（魔数 + 长度 + 负载）。
func frame(header, length int32, payload []byte) []byte {
	buf := new(bytes.Buffer)
	_ = binary.Write(buf, binary.BigEndian, header)
	_ = binary.Write(buf, binary.BigEndian, length)
	_ = binary.Write(buf, binary.BigEndian, payload)
	return buf.Bytes()
}

// 未知协议头必须返回错误：返回 (nil, nil) 会让调用方无限 Peek 同一个字节（CPU 忙循环）。
func TestDecodeRejectsBadHeader(t *testing.T) {
	raw := frame(1234, 3, []byte{1, 2, 3})
	if _, err := Decode(bufio.NewReader(bytes.NewReader(raw))); err == nil {
		t.Fatal("未知协议头必须返回错误，否则调用方会死循环")
	}
}

// 长度字段来自网络：负数会 panic，超大值会 OOM，都必须被拒绝。
func TestDecodeRejectsBadLength(t *testing.T) {
	for _, length := range []int32{-1, 0, maxFrameBytes + 1, 0x7FFFFFFF} {
		raw := frame(magicHeader, length, []byte{1, 2, 3})
		if _, err := Decode(bufio.NewReader(bytes.NewReader(raw))); err == nil {
			t.Fatalf("长度 %d 必须被拒绝", length)
		}
	}
}

func TestDecodeRoundTrip(t *testing.T) {
	message := &hpMessage.HpMessage{Type: hpMessage.HpMessage_KEEPALIVE}
	encoded := Encode(message)
	if len(encoded) == 0 {
		t.Fatal("Encode 不应返回空帧")
	}
	got, err := Decode(bufio.NewReader(bytes.NewReader(encoded)))
	if err != nil {
		t.Fatalf("合法帧解码失败: %v", err)
	}
	if got.Type != hpMessage.HpMessage_KEEPALIVE {
		t.Fatalf("解码结果不匹配: %v", got.Type)
	}
}

package Protol

import (
	"bufio"
	"bytes"
	"encoding/binary"
	"errors"
	"fmt"
	"github.com/golang/protobuf/proto"
	"io"
	"proxy-client-golang/hpMessage"
)

// magicHeader 协议魔数，用于识别一帧数据的开始。
const magicHeader = 9999

// frameHeaderBytes 协议头长度：4 字节魔数 + 4 字节长度。
const frameHeaderBytes = 8

// maxFrameBytes 单帧负载的最大长度（1MB）。
// 【安全修复】长度字段来自网络，必须在使用前校验：
//   - 负数会导致 make/切片 panic；
//   - 0x7FFFFFFF 这类超大值会让 make 一次性申请 2GB 内存（OOM）。
//
// 解码运行在 goroutine 中且没有 recover，一旦 panic 会直接拖垮整个进程，
// 所以这里宁可返回错误让调用方关闭连接。
const maxFrameBytes = 1 << 20

func Encode(message *hpMessage.HpMessage) []byte {
	d, err := proto.Marshal(message)
	if err != nil {
		// 不能忽略序列化错误：忽略后会把空内容当成合法帧发出去。
		return nil
	}
	i, err := encode(d)
	if err != nil {
		return nil
	}
	return i
}

func Decode(reader *bufio.Reader) (*hpMessage.HpMessage, error) {
	d, err := decode(reader)
	if err != nil {
		return nil, err
	}
	if len(d) == 0 {
		return nil, errors.New("协议帧内容为空")
	}
	message := &hpMessage.HpMessage{}
	// 【安全修复】原来这里会用 println(hex.Dump(d)) 打印整帧原始字节，
	// 里面可能包含账号密码等业务数据，属于调试残留，已移除。
	if err := proto.Unmarshal(d, message); err != nil {
		return nil, fmt.Errorf("协议帧解析失败: %w", err)
	}
	return message, nil
}

// 将数据包编码（即加上包头再转为二进制）
func encode(mes []byte) ([]byte, error) {
	//创建数据包
	dataPackage := new(bytes.Buffer) //使用字节缓冲区，一步步写入性能更高
	//写消息头
	err := binary.Write(dataPackage, binary.BigEndian, int32(magicHeader))
	if err != nil {
		return nil, err
	}
	//写长度
	err = binary.Write(dataPackage, binary.BigEndian, int32(len(mes)))
	if err != nil {
		return nil, err
	}
	//写入消息
	err = binary.Write(dataPackage, binary.BigEndian, mes)
	if err != nil {
		return nil, err
	}
	return dataPackage.Bytes(), nil
}

// 解码数据包
func decode(reader *bufio.Reader) ([]byte, error) {
	// 头部固定 8 字节：4 字节魔数 + 4 字节长度
	headerAndLength, err := reader.Peek(frameHeaderBytes)
	if err != nil {
		return nil, err
	}
	header := bytesToInt(headerAndLength[0:4])
	length := bytesToInt(headerAndLength[4:frameHeaderBytes])
	if header != magicHeader {
		// 【安全修复】这里绝不能返回 (nil, nil)：
		// 调用方会把“没有数据”当成正常情况继续 Peek 同一个字节，
		// 由于没有任何字节被消费，就会形成 100% CPU 的死循环（拒绝服务）。
		// 返回明确错误，让调用方关闭连接。
		return nil, fmt.Errorf("协议头不合法: %d", header)
	}
	if length <= 0 || length > maxFrameBytes {
		return nil, fmt.Errorf("协议帧长度不合法: %d（允许范围 1-%d）", length, maxFrameBytes)
	}
	// 读取 header + 长度 + 数据，不够则等待
	data := make([]byte, frameHeaderBytes+length)
	if _, err := io.ReadFull(reader, data); err != nil {
		return nil, err
	}
	return data[frameHeaderBytes:], nil
}

func bytesToInt(bys []byte) int {
	bytebuffer := bytes.NewBuffer(bys)
	var data int32
	_ = binary.Read(bytebuffer, binary.BigEndian, &data)
	return int(data)
}

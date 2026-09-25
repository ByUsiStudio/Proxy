package tcp

import (
	"bufio"
	"crypto/tls"
	"io"
	"net"
	"proxy-client-golang/Protol"
	"strconv"
	"time"
)

type TcpConnection struct {
}

func NewTcpConnection() *TcpConnection {
	return &TcpConnection{}
}

func (connection *TcpConnection) Connect(host string, port int, redType bool, handler Handler, call func(mgs string)) net.Conn {
	return connection.ConnectWithTLS(host, port, redType, handler, call, nil)
}

func (connection *TcpConnection) ConnectWithTLS(host string, port int, redType bool, handler Handler, call func(mgs string), tlsConfig *tls.Config) net.Conn {
	var conn net.Conn
	var err error

	address := net.JoinHostPort(host, strconv.Itoa(port))
	// 【安全修复】统一使用带超时的拨号器：上游被黑洞（丢包/不响应）时，
	// 原来的 net.Dial 会挂起几分钟，控制台 HTTP 请求也会被一起拖死。
	dialer := &net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}

	if tlsConfig != nil {
		// TLS连接（DialWithDialer 内部使用同一个超时完成 TCP 连接与握手）
		conn, err = tls.DialWithDialer(dialer, "tcp", address, tlsConfig)
	} else {
		// 普通TCP连接
		conn, err = dialer.Dial("tcp", address)
	}

	if err != nil {
		// 回调可能为 nil（例如某些内部调用不关心日志），必须先判空再调用。
		if call != nil {
			if redType {
				call("不能能连到穿透服务器：" + address + " 原因：" + err.Error())
			} else {
				call("不能能连到内网服务器：" + address + " 原因：" + err.Error())
			}
		}
		return nil
	}

	if tlsConfig != nil {
		if tlsConn, ok := conn.(*tls.Conn); ok {
			if err := tlsConn.Handshake(); err != nil {
				if call != nil {
					call("TLS握手失败：" + err.Error())
				}
				_ = tlsConn.Close()
				return nil
			}
		}
	}

	handler.ChannelActive(conn)
	//设置读
	go func() {
		reader := bufio.NewReader(conn)
		for {
			//尝试读检查连接激活
			_, err := reader.Peek(1)
			if err != nil {
				handler.ChannelInactive(conn)
				return
			}
			if redType {
				decode, e := Protol.Decode(reader)
				if e != nil {
					// 解码失败（含协议头非法）必须关闭连接，
					// 否则读循环会一直 Peek 同一个字节空转。
					if call != nil {
						call(e.Error())
					}
					handler.ChannelInactive(conn)
					return
				}
				if decode != nil {
					handler.ChannelRead(conn, decode)
				}

			} else {
				if reader.Buffered() > 0 {
					data := make([]byte, reader.Buffered())
					if _, err := io.ReadFull(reader, data); err != nil {
						handler.ChannelInactive(conn)
						return
					}
					handler.ChannelRead(conn, data)
				}
			}
		}
	}()
	return conn
}

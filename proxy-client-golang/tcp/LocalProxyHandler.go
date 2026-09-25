package tcp

import (
	"proxy-client-golang/hpMessage"
	"net"
)

type LocalProxyHandler struct {
	HpClientHandler *HpClientHandler
	RemoteChannelId string
	Active          bool
}

// ChannelActive 连接激活时，发送注册信息给云端
func (l *LocalProxyHandler) ChannelActive(conn net.Conn) {
	l.Active = true
	l.HpClientHandler.Add(l.RemoteChannelId, conn)
}

func (l *LocalProxyHandler) ChannelRead(conn net.Conn, data interface{}) {
	bytes, ok := data.([]byte)
	if !ok {
		return
	}
	if err := l.HpClientHandler.WriteToRemote(hpMessage.HpMessage_TCP, l.RemoteChannelId, bytes); err != nil {
		l.HpClientHandler.callMsg("内网发送远端错误：" + err.Error())
	}
}

func (l *LocalProxyHandler) ChannelInactive(conn net.Conn) {
	l.Active = false
	l.HpClientHandler.Close(l.RemoteChannelId)
}

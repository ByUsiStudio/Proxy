package tcp

import (
	"crypto/tls"
	"net"
	"strconv"
	"sync"
	"sync/atomic"

	"proxy-client-golang/hpMessage"
)

type HpClient struct {
	CallMsg func(message string)

	// mu 保护 conn/handler/serverAddress/serverPort/tlsConfig，
	// 这些字段会被控制台 HTTP 协程与重连协程同时访问。
	mu            sync.RWMutex
	conn          net.Conn
	serverAddress string
	serverPort    int
	handler       *HpClientHandler
	tlsConfig     *tls.Config

	isKill atomic.Bool
}

func NewHpClient(callMsg func(message string)) *HpClient {
	return &HpClient{
		CallMsg: callMsg,
	}
}

func (hpClient *HpClient) Connect(messageType hpMessage.HpMessage_MessageType, serverAddress string, serverPort int, username string, password string, domain string, remotePort int, proxyAddress string, proxyPort int) {
	hpClient.connectWithTLS(messageType, serverAddress, serverPort, username, password, domain, remotePort, proxyAddress, proxyPort, nil)
}

func (hpClient *HpClient) ConnectWithTLS(messageType hpMessage.HpMessage_MessageType, serverAddress string, serverPort int, username string, password string, domain string, remotePort int, proxyAddress string, proxyPort int, tlsConfig *tls.Config) {
	hpClient.connectWithTLS(messageType, serverAddress, serverPort, username, password, domain, remotePort, proxyAddress, proxyPort, tlsConfig)
}

func (hpClient *HpClient) connectWithTLS(messageType hpMessage.HpMessage_MessageType, serverAddress string, serverPort int, username string, password string, domain string, remotePort int, proxyAddress string, proxyPort int, tlsConfig *tls.Config) {
	hpClient.mu.Lock()
	oldConn := hpClient.conn
	hpClient.mu.Unlock()
	if oldConn != nil {
		_ = oldConn.Close()
	}

	connection := NewTcpConnection()
	handler := &HpClientHandler{
		Port:         remotePort,
		Password:     password,
		Username:     username,
		Domain:       domain,
		MessageType:  messageType,
		ProxyAddress: proxyAddress,
		ProxyPort:    proxyPort,
		CallMsg:      hpClient.CallMsg,
	}

	conn := connection.ConnectWithTLS(serverAddress, serverPort, true, handler, hpClient.CallMsg, tlsConfig)

	hpClient.mu.Lock()
	hpClient.serverAddress = serverAddress
	hpClient.serverPort = serverPort
	hpClient.handler = handler
	hpClient.tlsConfig = tlsConfig
	hpClient.conn = conn
	hpClient.mu.Unlock()
}

// GetConn 返回当前云端连接，可能为 nil。
func (hpClient *HpClient) GetConn() net.Conn {
	hpClient.mu.RLock()
	defer hpClient.mu.RUnlock()
	return hpClient.conn
}

// GetHandler 返回当前隧道处理器，可能为 nil。
func (hpClient *HpClient) GetHandler() *HpClientHandler {
	hpClient.mu.RLock()
	defer hpClient.mu.RUnlock()
	return hpClient.handler
}

func (hpClient *HpClient) GetStatus() bool {
	handler := hpClient.GetHandler()
	if handler == nil {
		return false
	}
	return handler.IsActive()
}

// Stats 返回该隧道的实时流量与连接统计。
func (hpClient *HpClient) Stats() HandlerStats {
	handler := hpClient.GetHandler()
	if handler == nil {
		return HandlerStats{}
	}
	return handler.Stats()
}

func (hpClient *HpClient) IsKill() bool {
	return hpClient.isKill.Load()
}

func (hpClient *HpClient) GetProxyServer() string {
	handler := hpClient.GetHandler()
	if handler == nil {
		return ""
	}
	return handler.ProxyAddress + ":" + strconv.Itoa(handler.ProxyPort)
}

func (hpClient *HpClient) GetServer() string {
	hpClient.mu.RLock()
	defer hpClient.mu.RUnlock()
	return hpClient.serverAddress + ":" + strconv.Itoa(hpClient.serverPort)
}

func (hpClient *HpClient) Kill() {
	hpClient.isKill.Store(true)
	hpClient.Close()
}

func (hpClient *HpClient) Close() {
	hpClient.mu.Lock()
	conn := hpClient.conn
	handler := hpClient.handler
	hpClient.mu.Unlock()

	if conn != nil {
		_ = conn.Close()
	}
	if handler != nil {
		handler.CloseAll()
	}
}

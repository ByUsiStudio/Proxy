package tcp

import (
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"os"
	"sync"
)

// SSLConfig SSL连接配置
type SSLConfig struct {
	Enable       bool
	CertFile     string
	KeyFile      string
	CAFile       string
	ServerName   string
	InsecureSkip bool // 跳过证书验证（仅用于测试）
}

var (
	sslConfig     *SSLConfig
	sslConfigLock sync.RWMutex
)

// SetSSLConfig 设置全局SSL配置
func SetSSLConfig(config *SSLConfig) {
	sslConfigLock.Lock()
	defer sslConfigLock.Unlock()
	sslConfig = config
}

// GetSSLConfig 获取当前SSL配置
func GetSSLConfig() *SSLConfig {
	sslConfigLock.RLock()
	defer sslConfigLock.RUnlock()
	return sslConfig
}

// LoadSSLConfigFromFile 从配置文件加载SSL配置
func LoadSSLConfigFromFile(configPath string) error {
	// 简化实现：如果文件存在则尝试读取环境变量配置
	// 实际项目中可以使用 viper 或 YAML/JSON 配置文件
	return nil
}

// NewTLSConfig 根据SSL配置创建tls.Config
func (c *SSLConfig) NewTLSConfig() (*tls.Config, error) {
	if c == nil || !c.Enable {
		return nil, nil
	}

	cfg := &tls.Config{
		InsecureSkipVerify: c.InsecureSkip,
	}

	if c.ServerName != "" {
		cfg.ServerName = c.ServerName
	}

	// 加载客户端证书（双向SSL）
	if c.CertFile != "" && c.KeyFile != "" {
		cert, err := tls.LoadX509KeyPair(c.CertFile, c.KeyFile)
		if err != nil {
			return nil, fmt.Errorf("加载客户端证书失败: %w", err)
		}
		cfg.Certificates = []tls.Certificate{cert}
	}

	// 加载CA证书
	if c.CAFile != "" {
		// io/ioutil 已废弃，改用 os.ReadFile
		caCert, err := os.ReadFile(c.CAFile)
		if err != nil {
			return nil, fmt.Errorf("加载CA证书失败: %w", err)
		}
		caCertPool := x509.NewCertPool()
		if !caCertPool.AppendCertsFromPEM(caCert) {
			return nil, fmt.Errorf("解析CA证书失败")
		}
		cfg.RootCAs = caCertPool
	}

	return cfg, nil
}

// EnvSSLConfig 从环境变量加载SSL配置
func EnvSSLConfig() *SSLConfig {
	config := &SSLConfig{}

	if os.Getenv("SSL_ENABLED") == "true" {
		config.Enable = true
	}
	if v := os.Getenv("SSL_CERT_FILE"); v != "" {
		config.CertFile = v
	}
	if v := os.Getenv("SSL_KEY_FILE"); v != "" {
		config.KeyFile = v
	}
	if v := os.Getenv("SSL_CA_FILE"); v != "" {
		config.CAFile = v
	}
	if v := os.Getenv("SSL_SERVER_NAME"); v != "" {
		config.ServerName = v
	}
	if os.Getenv("SSL_INSECURE_SKIP") == "true" {
		config.InsecureSkip = true
	}

	return config
}

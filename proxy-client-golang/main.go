package main

import (
	"flag"
	"fmt"
	"os"
	"proxy-client-golang/pkg/logger"
	"proxy-client-golang/pkg/logsink"
	"proxy-client-golang/tcp"
	"proxy-client-golang/web"
	"strconv"
	"time"

	"github.com/rs/zerolog"
)

const (
	LogLevelDebug = iota
	LogLevelInfo
	LogLevelWarn
	LogLevelError
)

func initLogger(level int) logger.Logger {
	return initLoggerWithSink(level, os.Getenv("LOG_DIR"), os.Getenv("LOG_FILE"),
		envInt("LOG_MAX_MB", logsink.DefaultMaxMB), envInt("LOG_KEEP", logsink.DefaultKeepFiles))
}

// initLoggerWithSink 构建日志器。
//
// 【为什么用 MultiLevelWriter】STDOUT 输出（ConsoleWriter，带颜色、便于人工观察）与
// 文件落盘（按大小轮转、带级别过滤）必须是两个独立 sink：控制台/容器环境没有终端时
// 依然要能落盘排查，反过来开发机上也不该因为磁盘不可写就没有控制台输出。
//
// 文件 sink 的级别源直接读取 web 包的原子变量，因此控制台运行时改级别会同时作用于
// 两者，不会出现「界面显示 debug，文件里只有 info」的不一致。
func initLoggerWithSink(level int, logDir string, logFile string, logMaxMB int, logKeep int) logger.Logger {
	output := zerolog.ConsoleWriter{
		Out:        os.Stdout,
		TimeFormat: time.DateTime,
		FormatLevel: func(i interface{}) string {
			var levelColor string
			switch i.(string) {
			case "debug":
				levelColor = "\x1b[36m" // Cyan
			case "info":
				levelColor = "\x1b[32m" // Green
			case "warn":
				levelColor = "\x1b[33m" // Yellow
			case "error":
				levelColor = "\x1b[31m" // Red
			default:
				levelColor = "\x1b[37m" // White
			}
			return fmt.Sprintf("%s%-5s\x1b[0m", levelColor, i)
		},
	}

	// 初始化日志落盘 sink（大小轮转 + 运行时级别过滤）。
	sink := logsink.NewManager(logsink.Config{
		Dir:      logDir,
		BaseName: logFile,
		MaxBytes: int64(logMaxMB) << 20,
		Keep:     logKeep,
	}, web.LogLevelValue)

	zl := zerolog.New(zerolog.MultiLevelWriter(output, sink)).With().Timestamp().Logger()
	web.SetLogSink(sink)

	logsink.SetGlobalLevel(level)

	return &logger.ZeroLogger{Logger: zl}
}

// envInt 读取整型环境变量，缺失或非法时返回默认值。
func envInt(name string, fallback int) int {
	raw := os.Getenv(name)
	if raw == "" {
		return fallback
	}
	v, err := strconv.Atoi(raw)
	if err != nil {
		return fallback
	}
	return v
}

func main() {
	var (
		deviceId string
		logLevel int
		// 日志落盘配置
		logDir   string
		logFile  string
		logMaxMB int
		logKeep  int
		// Web 控制台配置
		webHost  string
		webPort  int
		webToken string
		// 云端账号凭据：仅用于启动时拉取自动穿透配置
		apiUser string
		apiPass string
		// SSL配置参数
		sslEnabled      bool
		sslCertFile     string
		sslKeyFile      string
		sslCAFile       string
		sslServerName   string
		sslInsecureSkip bool
	)

	flag.StringVar(&deviceId, "deviceId", "NO_ID", "设备ID")
	flag.IntVar(&logLevel, "logLevel", web.LogLevelInfo, "日志级别(0=Debug,1=Info,2=Warn,3=Error)")

	// 日志落盘配置：默认在当前工作目录的 logs/ 下按大小轮转。
	// 与其它参数一致：命令行优先，其次同名环境变量（LOG_DIR / LOG_FILE / LOG_MAX_MB / LOG_KEEP）。
	flag.StringVar(&logDir, "logDir", logsink.DefaultDirName, "日志目录(默认 logs)")
	flag.StringVar(&logFile, "logFile", logsink.DefaultFileName, "日志文件名(默认 proxy-client.log)")
	flag.IntVar(&logMaxMB, "logMaxMB", logsink.DefaultMaxMB, "单个日志文件大小上限(MB，默认 10)")
	flag.IntVar(&logKeep, "logKeep", logsink.DefaultKeepFiles, "轮转后保留的历史日志文件数量(默认 5)")

	// Web 控制台配置：默认只监听本机，远程访问需要显式指定令牌。
	flag.StringVar(&webHost, "webHost", "", "Web控制台监听地址(默认 127.0.0.1，0.0.0.0 表示允许局域网访问)")
	flag.IntVar(&webPort, "webPort", 0, "Web控制台端口(默认 10240)")
	flag.StringVar(&webToken, "webToken", "", "Web控制台访问令牌(远程访问时必须设置)")

	// 云端账号凭据：自动穿透配置接口要求鉴权，首次启动时只能由这里提供。
	flag.StringVar(&apiUser, "apiUser", "", "云端账号(用于启动时拉取自动穿透配置)")
	flag.StringVar(&apiPass, "apiPass", "", "云端口令(用于启动时拉取自动穿透配置)")

	// SSL/TLS配置参数
	flag.BoolVar(&sslEnabled, "ssl", false, "启用SSL/TLS加密连接")
	flag.StringVar(&sslCertFile, "sslCert", "", "SSL客户端证书文件路径（PEM格式）")
	flag.StringVar(&sslKeyFile, "sslKey", "", "SSL客户端私钥文件路径（PEM格式）")
	flag.StringVar(&sslCAFile, "sslCA", "", "SSL CA证书文件路径（用于验证服务器证书）")
	flag.StringVar(&sslServerName, "sslServerName", "", "SSL服务器名称（SNI）")
	flag.BoolVar(&sslInsecureSkip, "sslInsecureSkip", false, "跳过SSL证书验证（仅用于测试）")

	flag.Parse()

	// 环境变量处理
	if deviceId == "NO_ID" {
		if envId := os.Getenv("deviceId"); envId != "" {
			deviceId = envId
		}
	}

	// 日志落盘配置：命令行优先，其次同名环境变量（LOG_DIR / LOG_FILE / LOG_MAX_MB / LOG_KEEP）。
	// 与其它参数的处理方式保持一致：显式传参才覆盖环境变量，否则保留环境变量的值。
	if logDir != logsink.DefaultDirName {
		_ = os.Setenv("LOG_DIR", logDir)
	}
	if logFile != logsink.DefaultFileName {
		_ = os.Setenv("LOG_FILE", logFile)
	}
	if logMaxMB != logsink.DefaultMaxMB {
		_ = os.Setenv("LOG_MAX_MB", strconv.Itoa(logMaxMB))
	}
	if logKeep != logsink.DefaultKeepFiles {
		_ = os.Setenv("LOG_KEEP", strconv.Itoa(logKeep))
	}

	// Web 控制台配置：命令行参数优先，其次环境变量（由 StartWeb 读取）。
	if webHost != "" {
		_ = os.Setenv("WEB_HOST", webHost)
	}
	if webToken != "" {
		_ = os.Setenv("WEB_TOKEN", webToken)
	}
	if webPort > 0 {
		_ = os.Setenv("WEB_PORT", strconv.Itoa(webPort))
	}

	// 云端账号凭据：命令行参数优先，其次环境变量（由 InitCloudDevice 读取）。
	// 只在参数非空时覆盖环境变量，避免把已有配置清掉。
	if apiUser != "" {
		_ = os.Setenv("API_USER", apiUser)
	}
	if apiPass != "" {
		_ = os.Setenv("API_PASS", apiPass)
	}

	// 初始化SSL配置
	if sslEnabled || os.Getenv("SSL_ENABLED") == "true" {
		sslConfig := &tcp.SSLConfig{
			Enable:       sslEnabled || os.Getenv("SSL_ENABLED") == "true",
			CertFile:     getEnvOrFlag("SSL_CERT_FILE", sslCertFile),
			KeyFile:      getEnvOrFlag("SSL_KEY_FILE", sslKeyFile),
			CAFile:       getEnvOrFlag("SSL_CA_FILE", sslCAFile),
			ServerName:   getEnvOrFlag("SSL_SERVER_NAME", sslServerName),
			InsecureSkip: sslInsecureSkip || os.Getenv("SSL_INSECURE_SKIP") == "true",
		}
		tcp.SetSSLConfig(sslConfig)
	}

	// 初始化日志系统：必须在 flag.Parse 之后，才能拿到日志落盘配置。
	log := initLogger(logLevel)

	log.Infof("启动参数 deviceId=%s logLevel=%s(%d)", deviceId, logsink.LevelName(logLevel), logLevel)
	log.Infof("日志落盘已启用: 目录=%s 文件=%s 单文件上限=%dMB 保留=%d 个历史文件",
		logsinkValueOr(os.Getenv("LOG_DIR"), logsink.DefaultDirName),
		logsinkValueOr(os.Getenv("LOG_FILE"), logsink.DefaultFileName),
		envInt("LOG_MAX_MB", logsink.DefaultMaxMB), envInt("LOG_KEEP", logsink.DefaultKeepFiles))

	// 打印SSL配置状态
	if sslConfig := tcp.GetSSLConfig(); sslConfig != nil && sslConfig.Enable {
		log.Infof("SSL配置: 启用=%t, 证书=%s, CA=%s, 服务器名称=%s, 跳过验证=%t",
			sslConfig.Enable, sslConfig.CertFile, sslConfig.CAFile, sslConfig.ServerName, sslConfig.InsecureSkip)
	}

	web.InitCloudDevice("https://proxy.properos.cn", deviceId, logLevel, log)

	web.StartWeb(webPort, "16.0", log)
}

// logsinkValueOr 返回环境变量值或默认值（仅用于启动日志展示）。
func logsinkValueOr(value, fallback string) string {
	if value == "" {
		return fallback
	}
	return value
}

// getEnvOrFlag 获取环境变量值，如果环境变量存在则优先使用
func getEnvOrFlag(envName, flagValue string) string {
	if envValue := os.Getenv(envName); envValue != "" {
		return envValue
	}
	return flagValue
}

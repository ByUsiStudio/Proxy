package android

import (
	_ "proxy-client-golang/pkg/logger"
	"proxy-client-golang/web"
)

// 自定义一个简单的 logger 实现
type simpleLogger struct{}

func (s simpleLogger) Debugf(format string, args ...interface{}) {
	// Android 环境下可以通过 log.Println 输出
	// android.Log.Printf(format, args...)
}
func (s simpleLogger) Infof(format string, args ...interface{})  {}
func (s simpleLogger) Warnf(format string, args ...interface{})  {}
func (s simpleLogger) Errorf(format string, args ...interface{}) {}
func (s simpleLogger) Fatalf(format string, args ...interface{}) {}

func Start(apiAddress string, port int, coreVersion string, deviceId string) {
	// 创建 logger 实例
	var log simpleLogger

	// 设置日志级别，可以根据需要调整，比如 web.LogLevelInfo
	const logLevel = web.LogLevelInfo

	// 正确调用：传递所有必需的参数
	web.InitCloudDevice(apiAddress, deviceId, logLevel, log)
	web.StartWeb(port, coreVersion, log)
}

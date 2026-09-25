# Proxy内网穿透编译教程  

> **适用说明**  
> 本教程基于手机Termux环境演示，适配**Debian系Linux发行版**。  
> 如遇环境差异，请灵活调整或寻求AI帮助解决。  

---

## 一、基础环境准备  

### 1. 系统更新  
```bash  
apt update && apt upgrade -y  
```  
> 出现提示时输入 `y` 并回车  

### 2. 安装依赖  
```bash  
apt install git openjdk-25-jdk maven golang -y  
```  
> **报错处理**：复制完整日志求助AI工具，保持耐心  

> **注意**：Java版本需25+，如系统无openjdk-25，可安装openjdk-21或更高版本  

---

## 二、代码获取  
### ▶ 国内用户（Gitee源）  
```bash  
git clone https://gitee.com/byusi/proxy.git  
cd proxy  
```  

### ▶ 国际用户（Github源）  
```bash  
git clone https://github.com/byusiteam/proxy.git  
cd proxy  
```  
> 任一源失败可切换另一个  

---

## 三、配置修改  
### 1. 服务端配置  
```bash  
nano proxy-server/src/main/resources/app.properties  
```  

### 2. 节点端配置  
```bash  
nano proxy-proxy/src/main/resources/app.properties  
```  

### 3. SSL证书配置（可选）
节点端支持自定义SSL证书，用于HTTPS加密连接：
```bash  
nano proxy-proxy/src/main/resources/app.properties  
```  
**SSL配置项**：
```properties
# 是否启用自定义SSL证书
ssl.enabled=false
# 密钥库路径（支持JKS、P12格式）
ssl.keyStore=/path/to/keystore.jks
# 密钥库密码
ssl.keyStorePassword=your_password
# 密钥管理器密码（默认与密钥库密码相同）
ssl.keyManagerPassword=your_password
# 密钥库类型（JKS、P12等）
ssl.keyStoreType=JKS
# SSL协议（TLS、TLSv1.2、TLSv1.3）
ssl.protocol=TLS
```

### 4. Golang客户端SSL配置（可选）
客户端支持SSL/TLS加密连接，可通过命令行参数或环境变量配置：
```bash  
# 命令行参数方式
./proxy-client -ssl -sslCert /path/to/cert.pem -sslKey /path/to/key.pem -sslCA /path/to/ca.pem

# 环境变量方式
export SSL_ENABLED=true
export SSL_CERT_FILE=/path/to/cert.pem
export SSL_KEY_FILE=/path/to/key.pem
export SSL_CA_FILE=/path/to/ca.pem
export SSL_SERVER_NAME=your.server.com
``  

### 5. 客户端API地址  
```bash  
nano proxy-client-golang/main.go  
```  
> **关键修改**：  
> 将 `web.InitCloudDevice("https://proxy.properos.cn", deviceId, logLevel, log)`  
> 中的 URL 替换为你的服务端地址（**请使用 https，避免账号密码明文传输**）
>
> **Web 控制台**默认只监听 `127.0.0.1:10240`。如需局域网访问，用  
> `./proxy-client -webHost 0.0.0.0 -webToken <随机令牌>` 启动，再用  
> `http://主机地址:10240/?token=<随机令牌>` 打开。

---

## 四、编译流程  
### ▶ Java程序编译  
```bash  
mvn clean package  
```  
**输出位置**：  
- 服务端 → `proxy-server/target/*.jar`  
- 节点端 → `proxy-proxy/target/*.jar`  

**运行命令**：  
```bash  
java -jar /路径/文件名.jar  
```  

### ▶ Golang客户端编译  
```bash  
cd proxy-client-golang  
go mod tidy           # 安装依赖  
chmod +x build.sh     # 添加执行权限  
./build.sh            # 开始编译  
```  
**输出位置**：  
- 二进制文件 → `build/` 目录  
- **Linux x86_64用户** → 选择 `proxy-client-amd64`  

---

## 重要声明  
1. **环境差异**：教程可能不完全适配你的设备，但核心流程通用  
2. **故障处理**：  
   - 编译报错 → 复制完整日志求助AI工具  
   - 联系作者 → 可能无法及时响应  
3. **心态建议**：  
   - 保持心平气和  
   - 技术问题可通过社区/论坛解决  

> **最后提示**：内网穿透涉及网络安全，请遵守当地法律法规使用

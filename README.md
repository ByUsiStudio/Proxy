# Proxy穿透
- [编译教程](Build.md)
- 加入官方群聊 [Telegram](https://t.me/+1_rc1TbWydVkN2I1) [QQ群](mqqapi://card/show_pslcard?src_type=internal&version=1&uin=822726278&card_type=group&source=qrcode)
- 访客记录
  > ![访客记录](https://count.kjchmc.cn/get/@ByUsi-Proxy-master?theme=rule34)

#### 版本信息
- **当前版本**: 16.0
- **Java版本**: 25+
- **Golang版本**: 1.21+

#### 新特性 (v16.0)
- [x] Java版本升级至25
- [x] 自定义SSL证书支持（Java端和Golang端）
- [x] 日志系统优化（slf4j统一日志输出）
- [x] 代码质量改进

#### 新特性 (v16.1 · 前端重构与安全加固)
- [x] 三端界面统一重构：Golang 客户端控制台、Java 云后台管理端、云端与节点落地页共用一套设计语言
- [x] 全面改为相对布局（rem / % / clamp / Grid / Flex），移除固定像素宽度与按百分比放大字号的写法
- [x] 深色 / 浅色 / 跟随系统三种主题，可记忆在本机
- [x] 控制台新增：全局 Toast 提示、确认弹窗、实时日志中心（关键词 / 级别 / 域名过滤 + 暂停 + 导出）、
  隧道列表自动刷新与排序、批量停止、流量与连接数图表、配置导出 / 导入、二维码分享
- [x] 安全加固：控制台访问令牌与会话 Cookie（SameSite=Strict）、跨站请求拦截、输入校验、
  连接数上限、流量与连接统计、安全响应头（CSP 等）
- [x] 修复多处 XSS（前端不再拼接 HTML 注入数据）、WebSocket 跨站劫持、未鉴权的隧道控制接口、
  任意文件读取 / 删除（节点端）、默认账号口令、CORS 通配、后台越权等问题
- [ ] 详见 [doc/SECURITY.md](doc/SECURITY.md)

#### 本地 Web 控制台
启动客户端后，在浏览器打开 `http://127.0.0.1:10240/` 即可进入本地控制台（首次进入会自动建立会话）。

| 功能 | 说明 |
| --- | --- |
| 穿透服务 | 隧道列表、状态、实时流量、排序、批量停止、配置导出 |
| 端口管理 / 域名管理 | 云端端口与域名（含自定义域名）的增删 |
| 自动穿透 | 把配置保存到云端，客户端带 `-deviceId` 启动时自动创建隧道 |
| 运行日志 | WebSocket 实时日志，支持级别 / 域名 / 关键词过滤与导出 |
| 数据统计 | 本机实时流量、连接数趋势、云端流量记录 |
| 设置与分享 | 主题、令牌状态、配置导出 / 导入与二维码分享 |

启动参数：

```bash
proxy-client                                     # 默认只监听 127.0.0.1:10240
proxy-client -webPort 10240                      # 自定义端口
proxy-client -webHost 0.0.0.0 -webToken 你的令牌  # 允许局域网访问（必须设置令牌）
```

局域网访问还需要显式声明允许的主机名/地址，防止 **DNS 重绑定**绕过本机信任：

```bash
# WEB_ALLOWED_HOSTS 为逗号分隔的允许主机（可带端口），不配置时只信任回环地址
WEB_HOST=0.0.0.0 WEB_TOKEN=<至少16位随机串> \
WEB_ALLOWED_HOSTS=192.168.1.5,console.example.com \
proxy-client
```

> **安全说明**：控制台默认只监听回环地址，并且拒绝跨站请求。若确需从其它设备访问，
> 请通过 `-webHost 0.0.0.0 -webToken <至少 16 位的随机令牌>` 暴露，并在
> `WEB_ALLOWED_HOSTS` 中列出实际使用的主机名/地址，然后用
> `http://主机地址:端口/?token=<令牌>` 打开。
> 未设置令牌、令牌过短或主机名不在允许列表内时，访问都会被拒绝。
> 安卓客户端已改为通过回环地址访问控制台，不会把控制台暴露到同一 Wi-Fi。

#### 介绍
我们采用的是数据转发实现 稳定性可靠性是有保证的即便是极端的环境只要能上网就能实现穿透。
我们支持TCP和UDP协议，针对 http/https ws/wss 协议做了大量的优化工作可以更加灵活的控制。让用户使用更佳舒服简单。

#### 源项目
[![HServer/hp-内网穿透](https://gitee.com/HServer/hp/widgets/widget_card.svg?colors=4183c4,ffffff,ffffff,e3e9ed,666666,9b9b9b)](https://gitee.com/HServer/hp)

#### 多仓库
- Gitee：
  ```url
  https://gitee.com/byusi/proxy
  ```
- GitHub：
  ```url
  https://github.com/ByUsiTeam/Proxy
  ```

### **Termux**和**Linux**快速部署脚本
  - Gitee
  ```bash
  bash -c "$(curl -sSL https://gitee.com/byusi/proxy/raw/master/shell/install2.sh)"
  ```
  - GitHub
  ```bash
  bash -c "$(curl -sSL https://raw.githubusercontent.com/ByUsiTeam/Proxy/master/shell/install2.sh)"
  ```

### **Termux**和**Linux**快速解除部署脚本
  - Gitee
  ```bash
  bash -c "$(curl -sSL https://gitee.com/byusi/proxy/raw/master/shell/uninstall.sh)"
  ```

  - GitHub
  ```bash
  bash -c "(curl -sSL https://raw.githubusercontent.com/ByUsiTeam/Proxy/master/shell/uninstall.sh)"
  ```

### 原理图
<img src="https://gitee.com/byusi/proxy/raw/master/doc/img_1.png" width="500" />


## 云后台管理web
<img src="https://gitee.com/byusi/proxy/raw/master/doc/img_3.png" width="500" />

### 安卓客服端
[![ByUsi/Proxy-Client](https://gitee.com/byusi/proxy-client/widgets/widget_card.svg?colors=4183c4,ffffff,ffffff,e3e9ed,666666,9b9b9b)](https://gitee.com/byusi/proxy-client)
<img src="https://gitee.com/byusi/proxy/raw/master/doc/d.jpg" width="500" />
<img src="https://gitee.com/byusi/proxy/raw/master/doc/e.jpg" width="500" />
<img src="https://gitee.com/byusi/proxy/raw/master/doc/f.jpg" width="500" />

### Golang客服端
为了跨平台我们提供golang的实现
<img src="https://gitee.com/byusi/proxy/raw/master/doc/Screenshot_20250422032415.png" width="500" />
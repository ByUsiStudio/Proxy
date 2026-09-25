# 安全审计与修复记录（v16.1）

本文件记录本轮针对 Proxy 项目的前端重构、安全审计与修复内容，包含**已修复项**、
**验证方式**、**仍需处理的架构级问题**和**升级注意事项**。

- 审计范围：Golang 客户端（`proxy-client-golang`）、安卓客户端（`proxy-android-client`）、
  Java 云后台（`proxy-server`）、节点端（`proxy-proxy`）、公共模块（`proxy-common`）
- 方法：逐文件静态审计 + 运行期回归验证（真实启动控制台并用 HTTP 请求验证）
- 说明：Java 端实际使用 **HServer 3.3.M2 + BeetlSQL + FreeMarker**（非 Spring Boot）。
  已核实该框架的 FreeMarker 未开启自动转义（`FreemarkerUtil` 未调用 `setOutputFormat`），
  因此模板中的 `${...}` 全部为原样输出，凡涉及数据的插值都必须显式 `?html` / `?js_string`。

---

## 一、Golang 客户端 Web 控制台（`proxy-client-golang/web`）

| # | 漏洞 / 问题 | 位置 | 修复 |
| --- | --- | --- | --- |
| 1 | 控制接口完全无鉴权，且绑定 `0.0.0.0`；跨站表单即可远程创建隧道（内网穿透跳板 + 盲 SSRF） | `web.go` 旧 `StartWeb` | 默认只监听 `127.0.0.1`；本机请求建立 `SameSite=Strict` 会话 Cookie；非本机访问必须携带 `WEB_TOKEN`/`-webToken`；所有控制接口要求会话令牌 |
| 2 | 无 CSRF 防护 | `web.go` | 统一校验 `Sec-Fetch-Site`（仅 `same-origin`/`none`）与 `Origin`（必须与 `Host` 同源），不满足直接 403 |
| 3 | WebSocket `CheckOrigin` 恒真 + 无鉴权 → 任意页面可窃听隧道日志 | `web.go` | 握手前同源/令牌校验；`CheckOrigin` 改为同源判断；`HandshakeTimeout`；`SetReadLimit(8KB)`；读超时 + ping/pong；写入加互斥锁（单写者）；写失败立即剔除连接 |
| 4 | 前端 22 处 `$.html()/.prepend()` 字符串拼接 → 存储型 / DOM 型 XSS（域名、日志、捐赠者、服务器名等均为接口或远端数据） | 旧 `*.html` | 6+2 个页面全部重写为 DOM API；运行时 `PX.el()` **不提供** html 选项（唯一 innerHTML 路径是内置图标常量表）；新增 CSP `script-src 'self'`，页面无内联脚本与内联事件 |
| 5 | 账号密码明文存 `localStorage`，每次刷新重复上报 | 旧 `*.html` | 资料（用户名/域名/端口）存 `localStorage`，**密码只存 `sessionStorage`**；旧 `userInfo` 自动迁移并清除；密码缺失时弹窗二次验证 |
| 6 | `ws://` 硬编码，HTTPS 下不可用且明文 | 旧 `center.html` | 按页面协议选择 `ws://` / `wss://` |
| 7 | `/core/version` 与 `InitCloudDevice` 在 `http.Get` 失败后 `resp.Body` 空指针 | `web.go` | 失败提前返回；统一带 15s 超时的 `http.Client` |
| 8 | `strings.Split(server_info, ":")[1]` 越界 panic | `web.go` | 改用 `net.SplitHostPort` 并校验主机与端口范围 |
| 9 | `//go:embed *` 把 `web.go` 源码嵌入静态目录，且 `StaticFS` 允许目录列举 | `web.go` | 嵌入模式收敛为 `*.html common img`；静态资源改为显式读文件 + 路径规范化，`/static/web.go` 返回 404 |
| 10 | 缺少安全响应头 | `web.go` | 增加 CSP、`X-Content-Type-Options`、`X-Frame-Options: DENY`、`Referrer-Policy`、COOP/CORP、`Permissions-Policy` |
| 11 | 未做输入校验（域名/IP/端口/类型任意字符串），日志注入 | `web.go` | 全字段白名单校验（主机名正则、端口 1-65535、类型枚举、凭据长度上限）；日志只记录路径不记录查询串 |
| 12 | 无资源上限：隧道/goroutine/连接数可被无限创建 | `web.go`、`tcp` | 隧道数上限 512；`/server/proxy` 限流 30 次/分钟；单隧道并发连接上限 4096；表单体 64KB 上限 |
| 13 | 并发数据竞争（`isKill`、`Active`、`handler`、`ConnGroup`） | `tcp/HpClient*.go` | 改为 `atomic.Bool` / `atomic.Int64` / `RWMutex`；通道表由包级全局改为**每隧道独立**，消除跨隧道串扰 |
| 14 | `http.Server` 无超时（Slowloris）；信任任意代理头 | `web.go` | 显式 `http.Server`（ReadHeader/Read/Idle 超时，WebSocket 不设写超时）；`SetTrustedProxies(nil)` |
| 15 | `/hp/*` 反代把本机 Cookie 与令牌转发给云端 | `web.go` | 转发前剔除 `Cookie` 与 `X-Proxy-Token`；独立 Transport 超时；`ErrorHandler` 返回 502 而非 panic |
| 16 | 依赖 Google Fonts 图标字体（离线不可用、泄漏访问信息、削弱 CSP） | 旧 `*.html` | 改为内联 SVG 图标表，完全离线；同时移除 `via.placeholder.com` 外链 |
| 17 | 内置 jQuery 3.5.1（历史 XSS CVE 面）+ 旧样式表 | `web/common` | 删除 `jquery.min.js` 与 `proxy-beauty.css`，改用原生 JS 与新的设计系统 |

### 新增能力（前端重构）
- 统一设计系统 `common/css/app.css`：全部使用 `rem/%/clamp()/fr` 的相对流式布局，
  Grid/Flex 文档流骨架（无 `position:fixed` + `margin-left` 硬撑），明/暗/跟随系统三态主题。
- 页面：登录、穿透服务、端口管理、域名管理、自动穿透、运行日志、数据统计、设置与分享。
- 全局 Toast 与确认弹窗（替换 `alert()`/`confirm()`）；日志中心（级别/域名/关键词过滤、暂停、导出、复制）；
  隧道列表自动刷新与排序、批量停止；流量与连接数图表（Canvas 自绘）；配置导出/导入与二维码分享
  （服务端 `skip2/go-qrcode` 生成，`/console/share/qr`，>2000 字节时明确报错）。

---

## 二、安卓客户端（`proxy-android-client`）

| # | 问题 | 修复 |
| --- | --- | --- |
| 1 | WebView 加载 `http://<局域网IP>:10240`，把含账号密码的控制台暴露在同一 Wi-Fi | 改为 `http://127.0.0.1:10240`（服务与控制台同设备），并保留局域网地址仅用于日志排查 |
| 2 | `MIXED_CONTENT_ALWAYS_ALLOW` | 改为 `MIXED_CONTENT_NEVER_ALLOW` |
| 3 | `onReceivedSslError` 直接 `handler.proceed()`（无条件信任任意证书） | 改为 `handler.cancel()` 并提示用户 |
| 4 | 开放地理位置、本地文件访问、JS 弹窗 | 全部关闭（`setGeolocationEnabled(false)`、`setAllowFileAccess(false)`、`setAllowContentAccess(false)`、`setJavaScriptCanOpenWindowsAutomatically(false)`），并移除已废弃的 AppCache |
| 5 | 把任意局域网 IP 都当作“本地控制台”在 WebView 内加载 | 仅信任 `localhost/127.0.0.1/::1`，其它地址交给系统浏览器 |
| 6 | `/statistics/getMyInfo` 只传 username（越权读他人流量） | 增加 password 参数并做 URL 编码 |

> ⚠️ 安卓端为破坏性变更，必须重新编译 APK 才能与新的控制台安全模型配合。

---

## 三、Java 云后台（`proxy-server`）

| # | 漏洞 | 修复 |
| --- | --- | --- |
| 1 | `db/init.sql` 内置 `admin/123456` 等默认口令 | 移除默认口令种子数据；新增 `SafeInputUtil` 账号白名单 |
| 2 | `POST /user/reg` 会覆盖已存在用户的密码（验证码仅 4 位、`java.util.Random`、无尝试上限）→ 任意账号接管 | 已存在用户不再被注册覆盖；验证码改用 `SecureRandom`、提高位数、限制尝试次数、一次性消费 |
| 3 | `POST /notice/push` 未鉴权即可把任意账号 `type` 置为 `-1`（封号 DoS + 邮件滥用） | 增加鉴权，封禁逻辑与通知队列解耦 |
| 4 | `POST /proxy/reg` 向匿名调用方返回 `REG_TOKEN`（节点端所有 `@CheckApi` 接口的唯一凭据） | 不再在响应中返回令牌，注册需要共享密钥/鉴权 |
| 5 | 后台“会话”= 管理员密码写入非 HttpOnly Cookie，且校验用 `String.contains`、口令为空时**失效放行** | 新增 `AdminSessionStore`：`SecureRandom` 会话 ID、服务端内存表、30 分钟空闲过期、精确匹配、`HttpOnly`+`SameSite=Strict`（https 下加 `Secure`）；未配置口令时**拒绝**访问后台；新增 `/admin/login`、`/admin/logout` |
| 6 | CORS 同时返回 `Access-Control-Allow-Origin: *` 与 `Allow-Credentials: true` | 改为按配置的允许来源列表返回 |
| 7 | 后台无 CSRF 防护，且删除类操作走 GET（`<img>` 即可触发） | 状态变更接口要求 POST + `Origin`/`Referer` 同源校验，Cookie 带 `SameSite` |
| 8 | 大量接口仅凭 `userId`/`username` 参数执行读写（端口、域名、配置、统计）→ 越权 | 新增 `UserAuthUtil.checkOwnership`：必须携带并与目标账号一致的 username+password；配置读取不再回显密码 |
| 9 | FreeMarker 未转义 + 未鉴权的 `/statistics/add`、`/config/save` → 存储型 XSS 窃取后台口令 | 服务端对入库字段做白名单校验（`SafeInputUtil`）；模板侧全部改为 `?html`/`?js_string` 转义 |
| 10 | 明文存储/返回/记录口令 | 停止在日志中输出登录结果与口令；配置接口不再回显口令（彻底改造为哈希+令牌见第六节） |
| 11 | SQL 字符串拼接（`removeExp` 等） | 改为参数绑定 |
| 12 | 主机头 `startsWith` 前缀匹配（`host.attacker.tld` 命中） | 改为去端口后的精确匹配 |
| 13 | APK 上传未校验、写入 CWD；异常信息回显客户端 | 增加文件校验与固定目录；异常仅记服务端日志并返回通用提示 |
| 14 | 节点端任意文件读取 / 删除（`/photoDetail/{path}`、`/photoRemove*`） | 规范化路径并做根目录包含校验，补充 API 校验，拒绝 `..`、绝对路径与空名 |
| 15 | 节点端 `/clearAll` 未鉴权（可清空登录失败黑名单）；`notReg` 配置项会静默关闭所有鉴权 | `/clearAll` 增加 `@CheckApi`；鉴权不再受 `notReg` 影响 |
| 16 | `SerializationUtil` 未使用的 Java 反序列化工具（潜在 RCE 入口） | 删除该类 |
| 17 | 图片审核队列使用非线程安全 `ArrayList` 并发读写，工作线程异常即终止 | 改为线程安全队列 + `poll()` 消费，异常不再终止工作线程 |

> 编译验证：`mvn -o -DskipTests compile` 全模块通过（Java 25 + HServer 3.3.M2）。

---

## 四、验证记录

### 1. Golang 控制台运行期回归（真实启动 + HTTP 请求）
| 用例 | 期望 | 实测 |
| --- | --- | --- |
| `GET /` | 302 → `/static/login.html`，种下会话 Cookie | [x] 302 + `HttpOnly; SameSite=Strict` |
| `GET /server/info` 无令牌 | 401 | [x] 401 |
| `GET /server/info` 错误令牌 | 401 | [x] 401 |
| `GET /server/info` 正确令牌 | 200 `[]` | [x] 200 |
| `POST /server/proxy`（`Origin: https://evil.example`） | 403 | [x] 403 跨站请求已被拒绝 |
| `POST /server/proxy`（`Sec-Fetch-Site: cross-site`） | 403 | [x] 403 |
| `POST /server/proxy` 非法服务器地址 | 业务错误 | [x] `穿透服务器地址不合法` |
| `POST /server/proxy` 正常 | 添加成功 | [x] 200 添加成功 |
| `POST /server/stop` / `/server/batchStop` | 停止成功 | [x] 200 已停止 1 条隧道 |
| `POST /console/config/import`（JSON 对象 / 裸数组 / 表单） | 全部支持 | [x] 三种格式均导入成功，非法条目逐条报错 |
| `GET /console/share/qr` | 200 PNG | [x] 200 `image/png` |
| 二维码内容 > 2000 字节 | 明确报错 | [x] 413 `内容过长（2500 字节）…` |
| `GET /static/web.go` | 404（不再泄漏源码） | [x] 404 |
| `GET /static/../web.go` | 404 | [x] 404 |
| WebSocket 跨站 / 无令牌 / 同源+令牌 | 403 / 401 / 101 | [x] 403 / 401 / **101 并收到实时日志** |
| `GET /api.js` | `application/javascript` | [x]（配合 `nosniff` 可被正常执行） |
| `HEAD /` | 200（兼容客户端存活探测） | [x] 200 |
| 安全响应头 | CSP 等存在 | [x] CSP/COOP/CORP/nosniff/Referrer-Policy |

### 2. 静态检查
- `go vet ./...` 通过；`go build ./...` 通过（Go 1.26）。
- `mvn -o -DskipTests compile` 全模块通过。
- 全部页面脚本 `node --check` 通过；独立交叉校验「JS 引用的元素 id ↔ HTML 定义」无缺失。
- 危险 sink 扫描（`.html(`、`innerHTML`、`insertAdjacentHTML`、`document.write`、内联 `onclick`、`eval`、`new Function`）：
  页面代码 0 命中，唯一 `innerHTML` 用法为内置图标常量表。
- 危险外链扫描：控制台与落地页均已无 CDN / 字体 / 占位图外链。

---

## 五、仍需处理的架构级问题（建议单独版本窗口）

1. **口令明文与协议级令牌（最高优先）**
   当前 `/user/login` 仍会把 `password` 返回给客户端（历史客户端依赖），且隧道注册协议
   `REGISTER` 使用 username+password，导致云端必须保存可还原的口令。
   建议迁移方案：新增 `/user/token` 下发长期令牌 → 客户端只保存令牌 → `REGISTER` 携带令牌 →
   `UserVo` 去掉 password → 数据库改存 bcrypt/argon2。需要 **Go / 安卓 / Java 云后台 / 节点端同步改造**。
2. **云端地址与证书**：`main.go` 中的 `https://proxy.properos.cn` 实测返回 HTTP 526
   （Cloudflare 源站证书无效），属于部署侧问题；节点端静态首页仍跳转 `http://proxy.byusi.cn:9090`，
   建议统一为 HTTPS 并复核域名。
3. **GET 携带口令**：Java 侧 `/statistics/getMyInfo` 等为 GET + password 参数，口令会进入各级访问日志，
   建议改为 POST 或令牌化。
4. **邮箱验证码**：已改 `SecureRandom` + 限制尝试次数，建议后续改为短 TTL 的一次性链接；
   同时按调用方 IP（而非收件人）限流，避免邮件轰炸。
5. **CORS 允许列表**：需按实际部署域名在配置中声明，切勿回退为 `*`。
6. **历史数据**：默认口令账号需强制重置；非邮箱格式的历史账号可能无法通过新的账号白名单校验，需要迁移。
7. **后台模板转义**：本轮已对全部模板补齐 `?html`/`?js_string`，但该框架默认不转义，
   后续新增模板务必遵循同一约定（详见各模板头部注释）。

---

## 六、升级注意事项（破坏性变更清单）

1. 控制台默认只监听 `127.0.0.1`；局域网访问需 `-webHost 0.0.0.0 -webToken <令牌>`，
   并使用 `http://主机:端口/?token=<令牌>` 打开。
2. 云端端口/域名/配置/统计接口现在要求携带 `username`+`password`（新版控制台已自动注入；
   旧版客户端与自定义脚本需要同步修改）。
3. 安卓客户端必须重新编译（WebView 回环地址、混合内容策略、`getLog` 新增 password 参数）。
4. 后台登录改为表单 POST `/admin/login`，会话 Cookie 名 `admin_session`（30 分钟空闲过期），
   并提供 `/admin/logout`；旧 `auth=<密码>` Cookie 不再有效。
5. 节点端对 `/photo*` 接口做了路径包含校验，任何越出图片目录的路径都会被拒绝。

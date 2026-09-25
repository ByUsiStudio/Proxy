# 安全审计与修复记录（v16.1）

本文件记录本轮针对 Proxy 项目的前端重构、安全审计与修复内容，包含**已修复项**、
**验证方式**、**仍需处理的架构级问题**和**升级注意事项**。

- 审计范围：Golang 客户端（`proxy-client-golang`）、安卓客户端（`proxy-android-client`）、
  Java 云后台（`proxy-server`）、节点端（`proxy-proxy`）、公共模块（`proxy-common`）
- 方法：逐文件静态审计 + 运行期回归验证（真实启动控制台并用 HTTP 请求验证）
- 说明：Java 端实际使用 **HServer 3.3.M2 + BeetlSQL + FreeMarker**（非 Spring Boot）。
  已核实该框架的 FreeMarker 未开启自动转义（`FreemarkerUtil` 未调用 `setOutputFormat`），
  因此模板中的 `${...}` 全部为原样输出，凡涉及数据的插值都必须显式 `?html` / `?js_string`。
- 轮次：第一轮（§一 ~ §六）覆盖三端的鉴权、XSS、越权、注入与默认口令；
  **第二轮（§八）** 在此基础上做交叉复核，新增 30 项「上轮遗漏或新引入」的问题，
  并补充了可重复执行的静态检查工具（§七）。前端重构与新增能力的说明见
  [`FRONTEND.md`](./FRONTEND.md)。

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
| `GET /` | 302 → `/static/login.html`，种下会话 Cookie | ✅ 302 + `HttpOnly; SameSite=Strict` |
| `GET /server/info` 无令牌 | 401 | ✅ 401 |
| `GET /server/info` 错误令牌 | 401 | ✅ 401 |
| `GET /server/info` 正确令牌 | 200 `[]` | ✅ 200 |
| `POST /server/proxy`（`Origin: https://evil.example`） | 403 | ✅ 403 跨站请求已被拒绝 |
| `POST /server/proxy`（`Sec-Fetch-Site: cross-site`） | 403 | ✅ 403 |
| `POST /server/proxy` 非法服务器地址 | 业务错误 | ✅ `穿透服务器地址不合法` |
| `POST /server/proxy` 正常 | 添加成功 | ✅ 200 添加成功 |
| `POST /server/stop` / `/server/batchStop` | 停止成功 | ✅ 200 已停止 1 条隧道 |
| `POST /console/config/import`（JSON 对象 / 裸数组 / 表单） | 全部支持 | ✅ 三种格式均导入成功，非法条目逐条报错 |
| `GET /console/share/qr` | 200 PNG | ✅ 200 `image/png` |
| 二维码内容 > 2000 字节 | 明确报错 | ✅ 413 `内容过长（2500 字节）…` |
| `GET /static/web.go` | 404（不再泄漏源码） | ✅ 404 |
| `GET /static/../web.go` | 404 | ✅ 404 |
| WebSocket 跨站 / 无令牌 / 同源+令牌 | 403 / 401 / 101 | ✅ 403 / 401 / **101 并收到实时日志** |
| `GET /api.js` | `application/javascript` | ✅（配合 `nosniff` 可被正常执行） |
| `HEAD /` | 200（兼容客户端存活探测） | ✅ 200 |
| 安全响应头 | CSP 等存在 | ✅ CSP/COOP/CORP/nosniff/Referrer-Policy |

### 2. 静态检查
- `go vet ./...` 通过；`go build ./...` 通过（Go 1.26）。
- `mvn -o -DskipTests compile` 全模块通过。
- 全部页面脚本 `node --check` 通过；独立交叉校验「JS 引用的元素 id ↔ HTML 定义」无缺失。
- 危险 sink 扫描（`.html(`、`innerHTML`、`insertAdjacentHTML`、`document.write`、内联 `onclick`、`eval`、`new Function`）：
  页面代码 0 命中，唯一 `innerHTML` 用法为内置图标常量表。
- 危险外链扫描：控制台与落地页均已无 CDN / 字体 / 占位图外链。

---

## 五、第一轮遗留的架构级问题（第二轮复核后状态）

> 本节保留第一轮的结论。第二轮对其中部分项做了补偿性加固，
> 逐项的最新状态见 **§九**。

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

1. 控制台默认只监听 `127.0.0.1`；局域网访问需 `-webHost 0.0.0.0 -webToken <至少16位令牌>`，
   并在 `WEB_ALLOWED_HOSTS` 中声明实际使用的主机名/地址，然后使用
   `http://主机:端口/?token=<令牌>` 打开。**未配置 `WEB_ALLOWED_HOSTS` 时局域网主机名访问会被拒绝**
   （仅回环名称可用），这是防止 DNS 重绑定的必要代价，详见第八节「G1」。
2. 云端端口/域名/配置/统计接口现在要求携带 `username`+`password`（新版控制台已自动注入；
   旧版客户端与自定义脚本需要同步修改）。
3. 安卓客户端必须重新编译（WebView 回环地址、混合内容策略、`getLog` 新增 password 参数）。
4. 后台登录改为表单 POST `/admin/login`，会话 Cookie 名 `admin_session`（30 分钟空闲过期），
   并提供 `/admin/logout`；旧 `auth=<密码>` Cookie 不再有效。
5. 节点端对 `/photo*` 接口做了路径包含校验，任何越出图片目录的路径都会被拒绝。
6. **部署在反向代理（Nginx / Cloudflare 等）之后时必须设置 `trusted.proxy=true`**：
   否则（a）来源 IP 取真实 TCP 对端而非 `X-Forwarded-For`（限流与节点鉴权更严格但仍可工作）、
   （b）CSRF 同源校验按「本机看到的协议+Host」比对，TLS 终止在代理上时 `Origin` 与期望来源不一致，
   会导致后台的**表单 POST 被 403 拒绝**。该配置项在两个模块的 `app.properties` 中都有说明。
7. 后台新增三个只读 GET 接口 `/admin/log/export`、`/admin/log/stats`、`/admin/config/export`；
   新增三个 POST 接口 `/admin/log/batchRemove`、`/admin/config/batchRemove`、`/admin/config/import`。
   反向代理若按路径做访问控制，需要同步放行。
8. **自动穿透（`-deviceId`）现在必须同时提供账号凭据**：
   `proxy-client -deviceId <设备ID> -apiUser <账号> -apiPass <口令>`
   （或环境变量 `API_USER` / `API_PASS`）。
   原因：云端 `GET /config/listDevice` 此前**完全匿名**，任何人只要提交一个设备 ID
   （用户自选、形如 IMEI，规则 `^[0-9a-zA-Z]{10,36}$`，且无限流）就能拿到该账号的
   **明文口令、用户名、内网地址与配置 id**，属严重凭据泄露。现在该接口要求校验账号归属。
   未提供凭据时客户端只打印一条操作提示并跳过自动穿透，不会静默失败。
   彻底方案（令牌化）见 §九.1。
9. **节点/云端接口鉴权收紧**：
   - `POST /statistics/add`（云端）现在要求集群令牌（`X-Cluster-Token` / `X-Cluster-Secret`
     请求头，或 `token`/`secret` 查询参数），节点端已同步携带；
     旧版节点若不升级会写入失败。
   - `POST /user/logout`（云端，新增）：站点页面的「退出」改为调用它，
     服务端会真正作废 `user_session`；同时 `user_session` 改由服务端下发
     （`HttpOnly; SameSite=Lax`，HTTPS 下加 `Secure`），前端不再用 JS 写 Cookie。
   - `/user/email` 同时接受 GET 与 POST，并按**真实 TCP 对端 IP** 限流。
   - 后台 `/admin/logout` 从「纯只读页面」移入「历史 GET 变更别名」集合：
     仍可用 GET 访问（页面上的退出链接不变），但带来源信息时必须严格同源，
     因此跨站 `<img src="/admin/logout">` 强制登出会被 403 拒绝。
10. **图片审核缓冲上限变化**：NSFW 审核缓冲由「装箱 `List<Byte>`，约 10MB 才清空」
    改为「`ByteArrayOutputStream`，硬上限 4MB」。**超过 4MB 的图片不再被送审**
    （这是内存可控的必要取舍），上限常量见 `PhotoMessageHandler.MAX_PHOTO_BYTES`。
11. **同源比较变严格**：CSRF 的 Origin/Referer 校验现在比较
    **scheme + host + port**（缺省端口按协议补齐）。`cors.allowedOrigins`
    中的条目必须带 `http://` 或 `https://` 前缀且端口一致，否则不会匹配。
12. **数据库需要迁移**：第三轮新增三张表（`sys_audit_log`、`sys_quota`、`sys_template`）。
    全新部署由 `db/init.sql` 自动创建；**已有部署必须执行** `db/migrate-v16.1.sql`
    （幂等，`CREATE TABLE IF NOT EXISTS`，不删数据）。
13. **新增配置项**（`proxy-server/src/main/resources/app.properties`）：
    `logbackName=logback-proxy.xml`（启用自定义日志轮转）、`admin.syslog.dir=log`、
    `admin.syslog.max.lines=2000`、`admin.report.dir=report`、`admin.report.interval.hours=24`。
    节点端 `proxy-proxy` 同样新增 `logbackName=logback-proxy.xml`（日志名 `proxy-node`）。
14. **控制台新增参数**（`proxy-client`）：`-logDir`(默认 `logs`)、`-logFile`(默认 `proxy-client.log`)、
    `-logMaxMB`(默认 `10`)、`-logKeep`(默认 `5`)，对应环境变量 `LOG_DIR`/`LOG_FILE`/`LOG_MAX_MB`/`LOG_KEEP`。
15. **后台新增只读 GET 路由**：`/admin/dashboard`、`/admin/dashboard/data`、`/admin/audit`、
    `/admin/audit/export`、`/admin/audit/stats`、`/admin/syslog`、`/admin/syslog/list`、
    `/admin/syslog/view`、`/admin/syslog/download`、`/admin/quota`、`/admin/quota/export`、
    `/admin/template`、`/admin/template/export`、`/admin/report`、`/admin/report/download`、
    `/admin/report/data`、`/admin/search`；状态变更全部为 POST。
    反向代理若按路径做访问控制，需要同步放行。`/admin` 现在渲染仪表盘（不再是 302 到 `/admin/proxy`）。

---

## 七、静态检查工具（可重复执行）

| 工具 | 作用 |
| --- | --- |
| `tools/check-frontend.mjs` | 三端前端一致性：DOM id 引用 ↔ HTML 定义、图标名 ↔ 常量表、模板引用的 `Admin.*` ↔ 实际导出、后台内联脚本 id ↔ 模板定义、危险 sink（`innerHTML`/`eval`/内联事件…）、外部 CDN 引用、FreeMarker 未转义插值 |
| `tools/check-java.mjs` | **Java 注释平衡**：捕获「丢失块注释结束符导致整段代码（含路由）被吞进注释」——编译期与其它检查都看不出来（第三轮据此拦截了 N1） |
| `check-tags.ps1` | 后台模板 HTML 标签平衡 |
| `tools/template-check/TemplateCheck.java` | 用 FreeMarker 官方解析器**解析**全部模板（发现语法错误） |
| `tools/template-check/RenderSmoke.java` | 用宽松桩模型**渲染**全部模板（发现 `?c` 用在字符串等求值期错误） |
| `tools/qr-verify/` | 自实现的纯前端二维码编码器与 `skip2/go-qrcode` 逐模块比对 |

用法见 [`tools/README.md`](../tools/README.md) 与 [`FRONTEND.md`](./FRONTEND.md)。

---

## 八、第二轮审计与修复（v16.1）

第二轮审计对 Go 客户端与 Java 云后台/节点端分别做了**逐文件静态审计 + 可执行验证**，
共确认 **30 项问题**（Go 16 项、Java/节点 14 项），全部已修复或有明确的补偿措施。
下表只列关键项；完整清单与利用路径见各模块审计报告与代码注释。

### 8.1 Golang 客户端（`proxy-client-golang`）

| # | 严重度 | 问题 | 修复 |
| --- | --- | --- | --- |
| G1 | **严重** | **DNS 重绑定鉴权绕过**：`isSameSite` 只把 `Origin` 与可被攻击者控制的 `Host` 比较，`establishSession` 又仅凭「来源 IP 是回环」就下发会话并直接把控制台令牌返回给调用方。攻击者页面用域名解析到 `127.0.0.1` 即可通过全部校验，随后可创建隧道、导出含明文口令的配置 | 新增 `hostAllowed` / `hostInAllowlist`：`Host` 必须命中回环名称、`WEB_HOST`（非 `0.0.0.0`/`::`）或 `WEB_ALLOWED_HOSTS` 列表；在 `isSameSite`、`establishSession`、`requireSession`、`/console/session`、`GET /`、WebSocket `CheckOrigin` 全部强制校验。**无 `Origin` 的请求同样必须过 Host 校验** |
| G2 | 高 | 协议帧长度直接取自信道且无上限 → 负值 panic 杀进程、`0x7FFFFFFF` 触发 2GB 分配 | `maxFrameBytes = 1<<20`，分配前校验 `0 < length <= max` |
| G3 | 高 | 未知协议头返回 `(nil,nil)` 且不消费字节 → 读循环 100% CPU 忙等（每连接占满一核） | 返回显式错误，由调用方关闭连接 |
| G4 | 高 | `tlsConfig, _ = sslCfg.NewTLSConfig()` 吞掉错误后回落明文 → 口令与隧道流量明文外发而日志仍显示「SSL 已启用」 | TLS 配置在 `ConnGroup.Store` **之前**校验，失败即中止创建并回滚，绝不回落明文 |
| G5 | 中 | WebSocket 无上限且广播同步阻塞 → 不读数据的客户端可拖住隧道数据面 | `maxWSClients=64`；每客户端 128 槽缓冲队列 + 单写协程；`wsSend` 非阻塞丢弃；单次关停、`WaitGroup` 收尾 |
| G6 | 中 | `tunnel.client` 无锁写入 + `stopTunnel` TOCTOU → 竞态，且「已停止」的隧道仍在转发且无法再停 | 客户端先构造并绑定再 `Store`，由 `tunnel.mu` 保护；`stopTunnel` 加锁重读后 `Kill` |
| G7 | 中 | `HpClientHandler.WriteToRemote` 多协程无锁写同一连接 → 大帧交错导致隧道数据损坏 | 新增 `connMu` + `writeFrame` 串行化所有写 |
| G8 | 中 | 控制台令牌写入日志；无登出接口 → UI 退出后 12 小时 Cookie 仍有效 | 日志只打印掩码前缀；新增鉴权 `POST /console/logout`（`Max-Age=0`），前端退出时调用 |
| G9 | 低 | 无拨号超时且在 HTTP 处理函数内同步拨号 → 黑洞地址可长时间占用隧道槽位 | `net.Dialer{10s}` / `tls.DialWithDialer` |
| G10 | 低 | 限流表无界增长；新增与导入共用同一 IP 桶 | 有界淘汰；限流键改为 `IP + 路由` |
| G11 | 低 | 过短 `WEB_TOKEN` 仍被采用；无失败限流；`crypto/rand` 失败时回落到可预测令牌 | 短令牌被拒绝并改为随机令牌；`crypto/rand` 重试后仍失败则拒绝启动；10 次/5 分钟按 IP 锁定（只统计「提供了错误令牌」的请求） |
| G12 | 低 | `println(hex.Dump(d))` 调试残留泄露解密后的帧内容 | 移除 |
| G13 | 低 | 服务端可控字符串直接进入操作员日志视图（可伪造日志行） | 去除 CR/LF 与控制字符、截断、加 `[远端]` 前缀 |
| G14 | 低 | 通道登记 check-then-act + 同 id 覆盖 → fd 泄漏、计数漂移 | `LoadOrStore` + 关闭败者；上限用 CAS |
| G15 | 低 | 前端把账号口令拼进 GET 查询串；二维码用 `?token=` 作为 `<img src>` | `/hp/config/remove` 改走 POST；GET-only 接口保留查询参数并在代码中标注残余风险；二维码改为带 `X-Proxy-Token` 头取回后用 `blob:` URL 渲染（CSP `img-src` 增加 `blob:`） |
| G16 | 低 | 可预测的令牌回落 | 由 G11 覆盖 |

新增自动化测试：`Protol/protol_test.go`（错误头、非法/超大长度、往返编解码）、
`web/security_regression_test.go`（Host 允许列表含 `WEB_HOST`/`WEB_ALLOWED_HOSTS` 各分支、限流上界与失败语义）。

### 8.2 Java 云后台与节点端（`proxy-server` / `proxy-proxy` / `proxy-common`）

| # | 严重度 | 问题 | 修复 |
| --- | --- | --- | --- |
| J1 | 高 | **`X-Forwarded-For` 可伪造**：HServer 的 `getIpAddress()` 原样返回该头，`localOnly` 的「回环」判断与邮件限流因此可被任意绕过（`@CheckApi` 全部失效 + 邮件轰炸） | 新增 `NetUtil` 取**真实 TCP 对端**；仅当 `trusted.proxy=true` 时才采纳转发头并取「从右往左第一个不可信跳」；`Ex.java` 不再回显该头 |
| J2 | 高 | **`GET /config/listDevice` 未鉴权且序列化 `password`** → 凭 `deviceId` 即可拿到账号明文口令、内网地址与配置 id | 增加账号凭据与设备归属校验、按 IP 限流，并明确「不回显口令」的输出契约。**破坏性变更**：客户端必须用 `-apiUser/-apiPass`（或 `API_USER`/`API_PASS`）提供凭据，否则跳过自动穿透并打印明确提示（见 §六.8） |
| J3 | 中高 | `/config/remove` 只校验调用方身份、不校验目标 `id` 归属 → IDOR 删除他人配置 | 删除前校验记录 `user_id` 与登录账号一致 |
| J4 | 中 | `/user/login`、`/user/domainLogin`、`/admin/login` 无失败次数限制 | 新增 `LoginFailureLimiter`：按账号+IP 计数，达到阈值后锁定窗口期，成功即清零，表有界 |
| J5 | 中 | 节点端 FreeMarker 模板**完全未转义**（潜在存储型 XSS，一旦取到即拿到全节点令牌） | 全部插值补 `?html` / `?js_string` / `?url`，属性保持双引号 |
| J6 | 中 | 节点端 `/photoList`、`/photo/{time}`、`/photoDetail/{path}` 缺少 `@CheckApi` | 补鉴权；对必须能被 `<img>` 直接引用的只读图片路径给出兼容方案 |
| J7 | 中 | `HpMessageDecoder` 帧长度无上界 → OOM / 负长度异常 | 上限校验 + 越界即关闭连接 |
| J8 | 中 | 图片流水线用 `List<Byte>` 累积（装箱约 150–200 MB/处理器实例） | 改为带硬上限（4MB）的字节缓冲，超限丢弃并只记一次日志 —— 行为变化见 §六.10 |
| J9 | 中 | 站点用户登出只清 JS Cookie、服务端会话仍有效；`user_session` 由 JS 写入因此无法 `HttpOnly`；会话表满时 `SESSIONS.clear()` 等于全站登出 | 新增服务端 `POST /user/logout` 作废会话；Cookie 改由服务端下发 `HttpOnly; SameSite=Lax`（HTTPS 加 `Secure`）；改为按空闲淘汰 |
| J10 | 中低 | 集群级 `REG_TOKEN` / `REG_CODE` 渲染进 HTML 与 URL（静态、不轮换，进入历史/日志/Referer） | 后台页加入显著风险提示、外链加 `no-referrer`；节点端仅以 `?url` 编码并统一 `Referrer-Policy: no-referrer`；轮换/改头属架构级改造，见第五节 |
| J11 | 低中 | `/statistics/add` 匿名可写、`/load/data` 匿名可读节点清单、`/user/email` 为 GET 且可发信 | `/statistics/add` 改为要求集群令牌（节点端已同步携带）；`/user/email` 同时接受 GET/POST 并按真实对端 IP 限流；`/load/data` **仍为匿名**（Go 控制台无法附加凭据），但响应已收缩为最小字段投影并按 IP 限流 —— 残余风险见 §九.10 |
| J12 | 低 | `sameOrigin()` 在 Origin+Referer 均缺失时返回 `true`，且只比较主机名（忽略协议与端口） | 改为严格 scheme+host+port 比对；缺失来源信息时仅接受带 `X-Requested-With: XMLHttpRequest` 的请求；历史 GET 删除别名在有来源信息时必须严格同源 |
| J13 | 低 | `AdminController.isHttps()` 信任可伪造的 `x-forwarded-proto`，且把「客户端源端口」当作判断依据 | 仅在 `trusted.proxy=true` 时采纳转发头，否则按服务端实际协议判断 |
| J14 | 低 | `Location` 头由解码后的路径拼接（`%0d%0a` 可达） | 白名单校验重定向目标，不再拼接用户输入 |

### 8.3 本轮验证结果

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| Go 编译 | `go build ./...` | 通过（exit 0） |
| Go 静态检查 | `go vet ./...` | 通过（exit 0） |
| Go 格式 | `gofmt -l .` | 无输出 |
| Go 测试 | `go test ./... -count=1` | 通过（`Protol`、`web` 两个包有测试） |
| Java 编译 | `mvn -o -DskipTests compile` | 全模块 BUILD SUCCESS |
| 前端一致性 | `node tools/check-frontend.mjs` | 全部通过（含 31 个模板的转义扫描、144 个后台内联脚本 id 引用） |
| Java 注释平衡 | `node tools/check-java.mjs` | 154 个文件通过（捕获了 N1：路由被注释吞掉） |
| 模板标签平衡 | `check-tags.ps1` | 19 个后台/站点模板 OK |
| 模板语法 / 渲染 | `tools/template-check/*.java` | 31 个模板解析通过；31 个模板在空数据桩模型下渲染通过 |
| 二维码编码器 | `tools/qr-verify/compare.mjs` | 35/35 与参考实现一致 |

---

## 九、仍未闭环的架构级问题（需跨端联动，建议单独版本窗口）

1. **口令明文可还原（最高优先）**：`sys_user.password` / `sys_config.password` 仍是明文，
   `/user/login`、`/user/domainLogin` 仍需要把口令返回给客户端，隧道注册协议 `REGISTER` 使用
   username+password。因此本轮只能做到「不额外泄露」（不写入日志、不在列表回显、导出不含口令、
   `listDevice` 加鉴权），**无法改为哈希**。彻底方案：新增 `/user/token` 下发长期令牌 →
   客户端只存令牌 → `REGISTER` 携带令牌 → 数据库改存 bcrypt/argon2，
   需要 **Go / 安卓 / Java 云后台 / 节点端同步改造**。
2. **GET 携带口令**：`/statistics/getMyInfo`、`/server/portList`、`/server/domainList`、
   `/config/list` 在服务端仍是 GET-only，前端只能把凭据放进查询串（代码中已标注残余风险）。
   服务端补齐等价 POST 后即可彻底消除。
3. **集群令牌轮换与传输**：`REG_TOKEN` 为静态值、经 URL 传递。建议支持轮换 +
   请求头传递，并把节点管理接口改为带鉴权的 JSON API。
4. **云端地址与证书**：`main.go` 中的 `https://proxy.properos.cn` 实测 HTTP 526
   （Cloudflare 源站证书无效），属部署侧问题；节点端静态首页仍跳转 `http://proxy.byusi.cn:9090`，
   建议统一 HTTPS 并复核域名。
5. **邮箱验证码**：已使用 `SecureRandom` + 尝试次数上限，建议进一步改为短 TTL 一次性链接，
   并按**调用方 IP**（而非收件人）限流。
6. **CORS 允许列表**：需按实际部署域名在配置中声明，切勿回退为 `*`。
7. **历史数据**：默认口令账号需强制重置；非邮箱格式的历史账号可能无法通过账号白名单校验，需要迁移。
8. **后台模板转义**：该框架默认不转义，新增模板必须显式转义；
   `tools/check-frontend.mjs` 的 `ftl-escape` 分组已对此做回归检查。
9. **门户自助申请缺少审批流**：目前「申请域名/端口」等于校验后直接写入归属记录
   （页面标注为「已提交，等待解析生效」）。真正的审批需要新增审批表
   （id/user_id/type/value/status/approver/time），本轮未做。
10. **配额仅部分强制**：只有「月度流量」与「最大隧道数」在配置下发时真正拦截；
    `max_conns`（云端看不到实时连接数）与 `max_ports`（`sys_port` 是管理员授予的固定端口，
    与隧道实际占用端口不是同一概念）仅作记录，已在代码与页面明确标注。
11. **审计日志未纳入轮转清理的运维约束**：已提供 `removeExpired(keepDays)`，
    但仍需由运维决定保留期并通过定时任务调用（当前未默认开启删除，避免误删留痕）。

---

## 十、第三轮：新增能力的针对性安全审查（v16.1）

新增功能（日志落盘、审计日志、系统日志查看、配额、模板、报表、全局搜索、自助门户）
一并做了针对性安全审查。以下为**设计层面的安全决定**与**本轮发现并修复的问题**。

### 10.1 新增能力的安全边界

| 能力 | 安全决定 |
| --- | --- |
| 控制台日志落盘 | 写盘前剥离控制字符（防止远端字符串伪造日志行）、对 `token=/password=/secret=` 正则打码、**绝不落盘控制台令牌**；目录 `0700`、文件 `0600` |
| 日志文件查询/下载 | 三重路径穿越防线：`filepath.Base(name)==name`、拒绝 `/ \ NUL`、只接受「活动文件名」或「`<主名>-<时间戳>[-n].ext`」格式；再用 `filepath.Rel` + 绝对路径前缀复核（大小写不敏感）；下载文件名由服务端重新生成，不回显客户端输入；`X-Content-Type-Options: nosniff` |
| 审计日志 | 异步有界队列 + 守护写线程（写不动就丢弃并计数，不影响业务）；`detail` 只允许业务标识，并有 `password=/token=/secret=` 正则脱敏兜底；IP 取**真实 TCP 对端**；只审计状态变更与失败事件，**不审计读页面**（避免刷爆表） |
| 后台系统日志查看 | 只读且限定在配置的日志目录内，目录不存在时返回空列表而不是报错；读取有行数与字节上限 |
| 全局搜索 | 五个维度全部**参数化查询 + 结果条数上限**；审计维度**不返回 `detail`/`user_agent`**；用户维度不返回口令；表不存在时降级而不是 500 |
| 用户自助门户 | 身份**只从 `AuthFilter` 写入的会话用户名获取**，从不信任任何客户端传入的 username/userId；域名/端口申请均经 `SafeInputUtil` 白名单校验 + 每账号 3 个上限 + 1 次/分钟限流 + 唯一性/占用检查；ID 由服务端生成 UUID；导出只导出本人数据且**不含口令** |
| 用量配额 | 在**配置下发时**强制（超限账号不再拿到隧道配置、也不再允许新增），避免云端去猜实时连接数；未强制执行的字段（`max_conns`/`max_ports`）在代码与页面中**明确标注为仅记录**，不假装已生效 |
| 隧道模板 | 模板内容**永不包含口令**；导入/下发逐条白名单校验并回报失败明细；下发受 `PROXY_SIZE` 与配额双重约束 |

### 10.2 本轮发现并修复的问题

| # | 严重度 | 问题 | 修复 |
| --- | --- | --- | --- |
| N1 | **高** | `ConfigController.listDevice` 的 Javadoc **丢失了块注释结束符**，导致紧随其后的下一个结束符（约 70 行之后）之间的全部内容——包括 `@GET("listDevice")` 整个处理器、限流、凭据校验、配额拒绝与归属过滤——**全部变成注释**。后果：该路由根本未注册，客户端自动穿透引导接口 404；且 `removeByGet` 的 Javadoc 被一并吞掉。**javac 编译通过、全部静态检查通过** | 补回丢失的结束符；新增 `tools/check-java.mjs` 用注释状态机捕获同类问题（Javadoc 内出现 `@GET/@POST` 即判失败），并在文档中记录为「发布前拦截」的缺陷 |
| N2 | 中 | 门户自助申请端口放行 `1..65535`，允许申请 80/443 或节点自身监听端口，可能与节点进程冲突 | 收紧为 `10000..60000`（与既有 `/server/portAdd` 一致），边界定义为单一常量并在代码与页面注明原因 |
| N3 | 中 | 控制台日志级别 `logLevel` 是裸 `int`，被 HTTP 协程与隧道协程并发读写（数据竞争），且新增了运行时切换接口后竞争面扩大 | 改为 `atomic.Int32`；`-race` 验证通过 |
| N4 | 中 | `web.log` 可能为 nil（宿主忘记调用 `SetLogger` 时），任何日志调用都会 panic——新增的落盘/事件/诊断路径大幅增加了日志调用点，风险随之放大 | 默认使用丢弃式实现，`SetLogger(nil)` 也回落丢弃式 |
| N5 | 低 | 审计/日志相关文本可能被远端字符串注入 CR/LF 从而伪造日志行 | 落盘与审计入库前统一剥离控制字符 |
| N6 | 低 | 门户「穿透日志」原先按 `username` **模糊匹配**（`andLike '%name%'`），同后缀账号（`a@qq.com` 与 `xa@qq.com`）可能互相读到记录 | 门户侧改用精确匹配（`andEq`）；已同步修正导出路径 |

> 说明：N1 是**本轮引入、本轮拦截**的缺陷，说明「编译通过 + 静态检查通过」并不足以保证路由存在；
> `tools/check-java.mjs` 即为补上这一盲区而新增。

### 10.3 与既有架构债的关系

新增能力**没有**触碰第五节第 1 条（口令明文可还原）这一最高优先项：审计与日志都显式避免记录凭据，
导出与模板都不含口令。彻底解决仍需跨端令牌化改造。

10. **`/load/data` 仍为匿名接口（已降级但未闭环）**：Go 控制台调用 `/hp/load/data`
    时不会附加账号凭据（控制台只对固定的一批 GET 路径注入凭据），因此本轮无法直接改为强制鉴权。
    已做的缓解：响应收缩为最小字段投影（仅 name/ip/port/level/num，去掉连接明细与统计），
    并按真实对端 IP 限流（60 次/分钟）。
    彻底方案：把 `/hp/load/data` 加入前端 `CLOUD_GET_ONLY_CREDENTIAL_PATHS` 清单，
    服务端再改为要求 `UserAuthUtil` 校验——需要 Go 控制台与 Java 端同步改动。
11. **TLS 卸载部署下的 `Secure` 判断**：服务端优先用 Netty pipeline 中的 `SslHandler`
    判断本机是否终止 TLS；若 TLS 终止在反向代理上且未设置 `trusted.proxy=true`，
    服务端无法感知外部协议，Cookie 不会带 `Secure`（属已知限制，已在 `NetUtil` 与
    `app.properties` 中标注）。


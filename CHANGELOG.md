# 更新日志

Proxy 内网穿透项目的所有重要变更都记录在此文件。

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

分类含义：**新增** 新能力 · **变更** 行为或配置的调整 · **修复** 缺陷修复 ·
**安全** 安全漏洞的修复与加固 · **移除** 删除的能力 · **文档** 文档 · **构建** 构建与工程化。

---

## [16.1] - 2026-09-25

本轮包含三部分工作：**三端前端重构与统一设计**、**三轮安全审计与修复**、
**日志能力与七项新功能**。详细记录分别见
[`doc/SECURITY.md`](doc/SECURITY.md) 与 [`doc/FRONTEND.md`](doc/FRONTEND.md)。

### 新增 — 日志与可观测性

- **Go 控制台日志落盘与轮转**：新增 `pkg/logsink/`（自实现轮转器，零新依赖）。
  新增启动参数 `-logDir`(默认 `logs`)、`-logFile`(默认 `proxy-client.log`)、
  `-logMaxMB`(默认 `10`)、`-logKeep`(默认 `5`)，并提供 `LOG_DIR`/`LOG_FILE`/
  `LOG_MAX_MB`/`LOG_KEEP` 环境变量回退。目录权限 `0700`、文件 `0600`。
- **Go 控制台历史日志查询/下载**：新增「日志文件」页面与
  `GET /console/logs/files｜tail｜download`；尾部倒序读取（有界），
  下载走 `fetch` + `X-Proxy-Token` + `blob:`，令牌不出现在 URL 中。
- **控制台运行时切换日志级别**：`GET/POST /console/log-level` + 设置页分段控件；
  级别不写盘，重启回到启动参数。
- **后台管理员操作审计日志**：新增 `sys_audit_log` 表、`AuditService`（异步有界队列 +
  守护写线程）与「审计日志」页面（筛选、CSV/JSON 导出、批量删除、自动刷新、详情弹窗）。
  覆盖后台全部增删改、登录成功/失败、被拦截请求、站点用户登录失败、
  配额拒绝、模板下发与门户自助申请。
- **后台应用日志轮转与在线查看/下载**：通过 `app.properties` 的
  `logbackName=logback-proxy.xml` 启用自定义 logback 配置
  （`log/proxy-server.log`，单文件 20MB、按天+序号滚动、保留 14 份、总量上限 2GB，
  另有独立 ERROR 文件）；新增「系统日志」页面，可列出文件、按级别/关键词查看尾部并下载。
  节点端 `proxy-proxy` 同样启用（日志名 `proxy-node`）。
- **三端失败事件汇总与告警**：控制台新增有界失败事件环形缓冲（200 条 + 30 秒同键冷却）、
  页头告警徽标与统计页面板；后台顶栏徽标显示近 24 小时失败事件数并可跳转失败筛选。

### 新增 — 后台管理能力

- **首页仪表盘**：`/admin` 直接渲染仪表盘（不再 302 到 `/admin/proxy`）。
  展示用户/域名/自动穿透配置/统计行数、在线节点、近期流量，含流量趋势与审计趋势图表
  （可导出 PNG）、「最近事件」事件流与快捷入口。
- **用量配额与限流管理**：新增 `sys_quota` 表与 `/admin/quota` 页面。
  可限制最大隧道数、端口数、并发连接与月度流量，并提供「刷新超限状态」。
  **实际强制**的是月度流量与最大隧道数（在配置下发时拒绝）；
  `max_conns`/`max_ports` 目前仅作记录，已在代码与页面明确标注。
- **批量隧道模板与一键部署**：新增 `sys_template` 表与 `/admin/template` 页面。
  一组隧道配置可存为模板（**不含口令**）、导入导出，并对指定账号批量下发，逐条回报失败明细。
- **全局搜索**：顶栏搜索框 + `/admin/search`，覆盖用户、域名、自动穿透配置、
  流量统计与审计日志五个维度，结果分组展示且各自有条数上限。
- **统计报表**：新增 `/admin/report` 页面与流量/连接/按端口三张图表，
  支持导出 PNG（客户端合成白底，数据不上传）、CSV/JSON 下载与「立即生成报表」；
  定时任务按 `admin.report.interval.hours` 周期性写出 CSV。
- **隧道健康检查与一键诊断**（Go 控制台）：`GET /console/diagnose` 一次返回
  本机服务、云端可达性、逐条隧道（含对目标地址的 TCP 拨号探测）与运行环境四段报告，
  10 秒总预算、最多 50 条隧道、8 路并发；穿透服务页可一键查看并复制纯文本报告。

### 新增 — 用户自助门户

- 新增「我的用量」页面（`/index/usage`）：本月与累计用量、按端口与按天图表
  （页面内自绘 Canvas，不加载后台脚本）、配额展示、我的域名与端口列表。
- 自助导出：`/index/export` 可导出**本人**的统计数据与隧道配置（**不含口令**）。
- 自助申请：`POST /index/apply/domain` 与 `/index/apply/port`，
  经白名单校验 + 每账号 3 个上限 + 1 次/分钟限流 + 唯一性/占用检查，
  端口范围限制为 `10000~60000`（避开系统与节点保留端口）。
- 门户顶栏新增「我的用量」入口，并补齐主题引导与切换。

### 新增 — 前端能力（第一轮）

- 三端界面统一重构，共用一套设计令牌（品牌色、语义色、圆角、间距、字号取值一致）。
- 全面改为相对布局（`rem`/`%`/`clamp()`/`Grid`/`Flex`），移除固定像素宽度与按百分比放大字号。
- 深浅色与跟随系统三态主题（可记忆在本机，首屏由内联引导脚本消除闪烁）。
- 全局 Toast 与 Promise 化确认框，替换全部 `alert()`/`confirm()`。
- 控制台：实时日志中心（级别/域名/关键词过滤、暂停、导出 TXT/JSON）、
  隧道列表自动刷新与排序、批量停止、流量与连接数图表、配置导出/导入与二维码分享。
- 后台：日志筛选与全量导出、流量图表、自动刷新、批量删除、配置导入导出与二维码分享
  （另见下方 16.1 的仪表盘/审计/系统日志/配额/模板/报表/搜索页面）。

### 安全

三轮审计累计发现并修复 **30 项**问题（Go 16 项 + Java/节点 14 项），
另在新增能力审查中发现并修复 **6 项**。重点项：

- **Golang 客户端**
  - **DNS 重绑定鉴权绕过（严重）**：原先仅凭「来源 IP 是回环」即下发控制台会话与令牌，
    且用可被攻击者控制的 `Host` 做同源判断。现要求 `Host` 必须命中回环名称、
    `WEB_HOST` 或 `WEB_ALLOWED_HOSTS`。
  - 协议帧长度无上限（负值 panic、超大值 OOM）、未知协议头导致 100% CPU 忙等、
    TLS 初始化失败后**静默回落明文**。
  - WebSocket 客户端无上限且广播阻塞隧道数据面；隧道客户端指针存在数据竞争与
    「已停止却仍在转发」的 TOCTOU；隧道连接多协程无锁写导致数据交错。
  - 控制台令牌被写入日志、缺少登出接口；请求限流表无界增长；调试用 `hex.Dump` 泄露载荷。
- **Java 云后台与节点端**
  - **`X-Forwarded-For` 可伪造（高）**：导致节点 `@CheckApi` 全部失效与邮件限流被绕过。
    现取真实 TCP 对端，转发头仅在 `trusted.proxy=true` 时按「从右往左第一个不可信跳」采纳。
  - **`GET /config/listDevice` 匿名即可获取账号明文口令（高）**：现要求账号凭据与设备归属，并限流。
  - `/config/remove` 的 IDOR 删除、登录爆破（新增失败限流）、节点端 FreeMarker 模板
    **完全未转义**、协议帧长度无上限、图片流水线装箱字节累积（约 150–200MB/实例）、
    站点登出后服务端会话仍有效、集群令牌渲染进 HTML/URL、CSRF 同源校验忽略协议与端口。
  - 新增能力的安全边界：日志查询的路径穿越三重防线、审计日志凭据脱敏兜底与
    「不审计读路径」、模板永不含口令、门户身份仅取会话。
- **发布前拦截的缺陷**：`ConfigController.listDevice` 因**丢失块注释结束符**导致整段处理器
  被吞进注释 —— javac 与全部静态检查均通过，但该路由未注册、客户端引导接口 404。
  已修复，并新增 `tools/check-java.mjs` 用注释状态机捕获同类问题。
- **新增校验工具**：`tools/check-frontend.mjs`（前端一致性、危险 sink、模板转义、DOM id）、
  `tools/check-java.mjs`（Java 注释平衡）、`tools/template-check/`（模板解析 + 渲染冒烟）、
  `tools/qr-verify/`（自实现二维码编码器与 `skip2/go-qrcode` 逐模块比对）。

### 变更（含破坏性变更）

- **控制台默认只监听 `127.0.0.1`**；局域网访问需 `-webHost 0.0.0.0 -webToken <至少16位令牌>`
  并在 `WEB_ALLOWED_HOSTS` 中声明实际使用的主机名/地址。
- **自动穿透需账号凭据**：`-deviceId` 必须搭配 `-apiUser`/`-apiPass`
  （或 `API_USER`/`API_PASS`），因为云端 `listDevice` 不再匿名。
- **反向代理后需设 `trusted.proxy=true`**，否则严格同源校验会拒绝后台表单 POST，
  且 Cookie 不会带 `Secure`。
- 云端端口/域名/配置/统计接口现在要求携带 `username`+`password`；
  后台登录改为表单 POST `/admin/login`，会话 Cookie 名 `admin_session`。
- 新增 `POST /user/logout`，`user_session` 改由服务端下发 `HttpOnly`；
  `/statistics/add` 需集群令牌；图片审核缓冲上限由约 10MB 降为 4MB。
- 数据库新增三张表（`sys_audit_log`、`sys_quota`、`sys_template`）。
  全新部署由 `proxy-server/src/main/resources/db/init.sql` 自动创建；
  **已有部署必须手动执行** `proxy-server/src/main/resources/db/migrate-v16.1.sql`
  （幂等，全部使用 `CREATE TABLE IF NOT EXISTS`，不删除任何数据）：
  ```bash
  sqlite3 db.db < proxy-server/src/main/resources/db/migrate-v16.1.sql
  ```
- 新增配置项：`logbackName`、`admin.syslog.dir`、`admin.syslog.max.lines`、
  `admin.report.dir`、`admin.report.interval.hours`、`trusted.proxy`。
- 后台新增一批只读 GET 路由（仪表盘/审计/系统日志/配额/模板/报表/搜索），
  状态变更全部为 POST；`/admin` 改为渲染仪表盘。

### 修复

- 修复门户「穿透日志」按用户名**模糊匹配**导致同后缀账号可能互相读到记录（改为精确匹配）。
- 修复门户自助申请端口范围过宽（`1~65535` → `10000~60000`）。
- 修复控制台日志级别变量的数据竞争（裸 `int` → `atomic.Int32`）。
- 修复 `web.log` 可能为 nil 时任何日志调用都会 panic。
- 修复后端日志查询、事件、诊断等新增接口的若干边界问题（均在静态检查与单元测试覆盖下）。

### 文档

- 新增 [`doc/FRONTEND.md`](doc/FRONTEND.md)：三端统一设计约定、日志能力与七项功能的落点、
  验证方式与手工回归清单。
- 扩充 [`doc/SECURITY.md`](doc/SECURITY.md)：三轮审计的完整记录、升级注意事项与残余风险。
- 新增 [`tools/README.md`](tools/README.md)：全部校验工具的用途与推荐执行顺序。
- 更新 `README.md`（启动参数、控制台与自动穿透说明）与 `Build.md`（JDK 前置条件）。

### 构建

- 根 `pom.xml` 新增 `build.suffix` 属性：允许把构建输出放到 `target<suffix>`，
  便于同一工作树并行构建；默认仍为 `target`（行为不变）。
- 根 `pom.xml` 新增 `maven-enforcer-plugin` JDK 前置检查：
  构建要求 JDK 25+，版本不足时给出可操作的修复指引
  （Maven 只读取 `JAVA_HOME`，不使用 `PATH` 中的 `java`）。
- 全部模块在 JDK 25 下 `mvn clean package` 通过，产出
  `proxy-common-16.0.jar`、`proxy-server-16.0.jar`、`proxy-proxy-16.0.jar`。

---

## [16.0] - 2026-06-19

### 新增

- 自定义 SSL 证书支持（Java 端与 Golang 端）。

### 变更

- Java 版本升级至 25，`pom.xml` 编译目标同步调整为 25。
- 日志系统优化：统一使用 slf4j 输出。

### 修复

- 代码质量改进与若干细节修正。

---

## [15.8] 及更早

早期版本的详细变更请查阅 Git 提交历史与标签：

```bash
git log --oneline            # 全部提交
git tag -l                   # 已有标签：15.8 / 15.7 / 15.6 / 15.5 / 15.4.2 / 15.4 / 15.3 / 15.2 / 15.0 / 1.2.3
git show <tag>               # 查看某个版本的改动范围
```

> 说明：早期提交信息量差异较大（部分为 `update` 等简短描述），
> 因此本文件不再逐条追溯，以避免记录不准确的信息。

---

## 如何验证本轮变更

```bash
# 前端一致性、危险 sink、模板转义、DOM id
node tools/check-frontend.mjs

# Java 注释平衡（防路由被注释吞掉）
node tools/check-java.mjs

# 模板标签平衡 / 语法 / 渲染
powershell -NoProfile -ExecutionPolicy Bypass -File check-tags.ps1
FM="$USERPROFILE/.m2/repository/org/freemarker/freemarker/2.3.31/freemarker-2.3.31.jar"
java -cp "$FM" tools/template-check/TemplateCheck.java proxy-server/src/main/resources/template proxy-proxy/src/main/resources/template
java -cp "$FM" tools/template-check/RenderSmoke.java   proxy-server/src/main/resources/template proxy-proxy/src/main/resources/template

# 二维码编码器回归
node tools/qr-verify/compare.mjs

# 编译与测试
cd proxy-client-golang && go vet ./... && go build ./... && go test ./... -count=1
cd .. && JAVA_HOME=/path/to/jdk-25 mvn clean package
```

详细的手工回归清单见 [`doc/FRONTEND.md`](doc/FRONTEND.md) 的「手工回归要点」。

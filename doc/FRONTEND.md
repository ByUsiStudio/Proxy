# 三端前端重构与功能说明（v16.1）

本文档说明本轮对 Proxy 项目**三端前端**的重构结果、统一设计约定，以及新增的
六项能力在三端的落点与验证方式。安全审计与修复记录见 [`SECURITY.md`](./SECURITY.md)。

- **Golang 客户端 Web 控制台** —— `proxy-client-golang/web`
- **Java 云后台管理端** —— `proxy-server/src/main/resources/template/admin` + `static/common`
- **中转端落地页** —— `proxy-proxy/src/main/resources/static/index`

---

## 一、统一设计约定

三端共用同一套设计语言，避免「同一产品三种观感」。
设计令牌（颜色 / 圆角 / 间距 / 字号）在三个样式表中**取值完全一致**，
并已通过脚本核对：

| 令牌 | `app.css`（控制台） | `admin.css`（后台） | `index/css/style.css`（落地页） |
| --- | --- | --- | --- |
| `--brand-500` | `#2f6bff` | `#2f6bff` | `#2f6bff` |
| `--success-500` | `#10b981` | `#10b981` | `#10b981` |
| `--warning-500` | `#f59e0b` | `#f59e0b` | `#f59e0b` |
| `--danger-500` | `#ef4444` | `#ef4444` | `#ef4444` |
| `--r-lg` / `--s-4` / `--fs-md` | `1rem` / `1rem` / `0.9375rem` | 同左 | `1rem` / `1rem`（落地页字号用 `rem` 直接声明） |

> 深色主题下 `--brand-500` 统一提升为 `#5b88ff` 以保证暗底对比度（控制台与后台）。

| 维度 | 约定 |
| --- | --- |
| 色彩 | 品牌蓝 `#2f6bff`（`--brand-500`）为唯一主色；语义色统一为 success `#10b981` / warning `#f59e0b` / danger `#ef4444` |
| 主题 | `<html data-theme="light\|dark">`；控制台与后台为 **三态**（跟随系统 / 浅色 / 深色），落地页为两态切换 + 系统回退 |
| 主题键 | 控制台 `px_theme`、后台 `px_admin_theme`、站点 `px_site_theme`、落地页 `px_landing_theme`（互不干扰，且都在样式生效前由内联引导脚本写入，消除首屏闪烁） |
| 布局 | 全部 `Grid / Flex` 文档流 + `rem / % / clamp() / fr` 相对单位；无固定像素宽度、无 `position:fixed` + `margin-left` 硬撑骨架 |
| 断点 | 48rem（窄屏堆叠）/ 56rem / 62rem（后台抽屉切 off-canvas） |
| 图标 | 内置描边风格内联 SVG 常量表，**不依赖图标字体、不依赖 CDN**，可离线运行且兼容严格 CSP |
| 组件 | 按钮 / 输入 / 下拉 / 开关 / 分段控件 / 卡片 / KPI / 徽章 / 表格 / 空状态 / 模态框 / Toast 在三端同名同形 |
| 反馈 | 统一 Toast（成功/失败/警告/信息）+ Promise 化确认框，**不使用 `alert()` / `confirm()`** |
| 可访问性 | 图标按钮带 `aria-label`；排序表头带 `aria-sort`；开关/分段控件带 `aria-pressed`；Toast 容器 `aria-live="polite"`；模态框 `role="dialog" aria-modal="true"` |

### 安全基线（三端共同遵守）

1. **动态数据一律用 `textContent` / DOM API 写入**，不拼接 HTML。
   唯一的 `innerHTML` 路径是内置图标常量表，且图标名必须命中常量表、
   额外 class 经过字符白名单校验（见 `app.js` 的 `PX.icon` / `Admin.svg`）。
2. **数值/标记属性**（`data-*`、`aria-*`）由 `setAttribute` 写入。
3. 管理后台 FreeMarker 模板**全部显式转义**（该框架不自动转义）：
   HTML 文本与双引号属性用 `?html`，URL 参数用 `?url`，数值用 `?c`。
4. 所有下载/导出都经过 **CSV 公式注入**防护，文件名由服务端生成。
5. 用户输入参与请求前先做白名单校验与长度上限；链接参数一律 `encodeURIComponent`。

---

## 二、六项新增能力的落点

### 1. 主题切换 + Toast

| 端 | 主题 | Toast |
| --- | --- | --- |
| Go 控制台 | `PX.theme`：跟随系统 / 浅色 / 深色，`matchMedia` 监听系统变化，分段控件在页头与设置页各有一处 | `PX.toast / toastOk / toastErr / toastWarn`，替换全部原生 `alert` |
| Java 后台 | `Admin.theme`：同上三态，渲染到 `#themeSlot`；切换时派发 `admin:themechange` 事件，自绘图表跟随重绘 | `Admin.toast / toastOk / toastErr / toastWarn` |
| 落地页 | `script.js`：两态切换 + `prefers-color-scheme` 回退，图标用 DOM API 重建 | `window.pxToast`：代码复制、主题切换给出轻提示 |

### 2. 日志搜索过滤导出

- **Go 控制台（运行日志页）**：`PX.logConsole` 组件 —— 级别分段过滤、域名下拉过滤、
  关键词实时过滤（防抖 200ms）、暂停/继续（暂停期间明确提示丢弃行数）、
  清空、复制、**导出 TXT / JSON**（导出的是当前过滤结果）、心跳探测。
- **Java 后台（用户日志页）**：`/admin/log` 支持**用户名模糊 + 端口精确**的服务端筛选
  （条件随分页链接保留，`data-pager-params`）；`/admin/log/export` 导出
  **当前筛选条件下的全量数据**（CSV / JSON，上限 2 万行）；
  页面内另有针对当前页的即时过滤框。

### 3. 列表自动刷新 · 排序 · 批量停止

- **Go 控制台（穿透服务页）**：
  - 自动刷新：开关 + 间隔选择（2/5/10/30 秒），偏好写入 `localStorage`（`px_center_prefs`）；
  - 排序：域名 / 类型 / 内网服务 / 穿透服务器 / 状态 / 入站 / 出站 / 连接 / 时长，
    表头点击切换升降序并同步 `aria-sort`；
  - **批量停止**：全选 / 单选 + 批量停止按钮（显示已选数量）+ 二次确认，
    调用后端 `/server/batchStop`（单次上限 200 条）。
- **Java 后台**：日志页与自动穿透页均有自动刷新开关（30 秒，倒计时提示）与
  表头排序；两页均支持**勾选后批量删除**（`/admin/log/batchRemove`、
  `/admin/config/batchRemove`，单次上限 500 条）。

### 4. 流量统计图表

- **Go 控制台（数据统计页）**：Canvas 自绘（`PX.chart`，无 ECharts / 无 CDN）
  - 按隧道的入站/出站**柱状图**；
  - 活跃连接数**折线趋势**（浏览器端采样最近 30 个点）；
  - 云端流量记录表格（可排序）+ 本机 KPI。
- **Java 后台（用户日志页）**：Canvas 自绘（`Admin.chart`）
  - **按天流量趋势**折线图（接收/发送双序列）；
  - **按端口流量分布**柱状图（取入站流量前 12 个端口）；
  - 图表数据来自 `/admin/log/stats`（服务端内存聚合，取样上限 2 万行，
    避免把整表读进内存），与当前筛选条件联动。
- 两者都会在主题切换与容器尺寸变化时自动重绘。

### 5. 配置导入导出 + 二维码分享

- **Go 控制台（设置与分享页）**：
  - 导出：`/console/config/export`，可选是否包含凭据（`?secrets=1`，默认不含）；
  - 导入：支持导出文件格式、裸数组、表单三种形态，逐条校验并回报失败明细；
  - **二维码分享**：服务端 `skip2/go-qrcode` 生成 PNG（`/console/share/qr`），
    前端先压缩字段名以适配容量，超过 2000 字节时给出明确提示并建议改用复制/下载。
- **Java 后台（自动穿透页）**：
  - 导出：`/admin/config/export`（JSON / CSV），**永不包含 password**；
  - 导入：`/admin/config/import` 接受与导出一致的 JSON，逐条经 `SafeInputUtil`
    白名单校验（用户必须真实存在、host:port、域名、设备ID、类型、端口），
    不合法的条目逐条回报原因且不写库；
  - **二维码分享**：`static/common/js/qrcode.js` 为自实现的纯前端编码器
    （字节模式 / 纠错级别 M / 版本 1-10，含 Reed-Solomon、掩码评估、格式信息 BCH），
    内容取当前页可见行、逐条加入直到逼近容量上限，超出部分明确提示省略条数；
    数据不出浏览器，且同样**不含 password**。

### 6. 本地控制台令牌鉴权

- **Go 控制台**：
  - 默认只监听 `127.0.0.1`；远程访问需 `-webHost 0.0.0.0 -webToken <令牌>`；
  - 启动时由 `crypto/rand` 生成 32 字节令牌（`WEB_TOKEN` 可覆盖）；
  - 本机访问建立 `SameSite=Strict` + `HttpOnly` 会话 Cookie；
    非本机访问必须携带令牌（`X-Proxy-Token` 头 / `?token=` / Cookie）；
  - 登录页支持 `?token=` 传入，读取后立即用 `history.replaceState` 从地址栏移除，
    避免令牌残留于历史记录与 Referer；
  - 所有控制类接口（含 WebSocket）统一走 `requireSession`，并校验
    `Sec-Fetch-Site` 与 `Origin` 同源。
- **Java 后台**：`AdminSessionStore` 随机会话 ID + `HttpOnly` + `SameSite=Strict`，
  30 分钟空闲过期，登出即作废；后台入口密码为空时**拒绝所有** `/admin` 请求（fail-closed）。

---

## 三、第二轮新增：日志能力与七项功能（v16.1）

### 3.1 日志能力

| 能力 | 落点 | 说明 |
| --- | --- | --- |
| **控制台日志落盘 + 轮转** | `proxy-client-golang/pkg/logsink/` | 自实现轮转器（无新依赖）：单文件超限即改名为 `<name>-<时间戳>.log`，保留 `-logKeep` 份；目录 `0700`、文件 `0600`；写入前剥离控制字符并对 `token=/password=/secret=` 打码，**绝不落盘凭据** |
| **历史日志查询/下载** | `web/console_logs.go` + `web/logs.html` | `GET /console/logs/files｜tail｜download`（均在 `requireSession` 之后并限流）；尾部倒序读取（1MB 有界），路径穿越三重防线；下载用 `fetch` + `X-Proxy-Token` + `blob:`，**令牌不进 URL** |
| **运行时切换日志级别** | `web/console_logs.go` + 设置页 | `GET/POST /console/log-level`；`logLevel` 由裸 `int` 改为 `atomic.Int32`（修复数据竞争）；级别**不写盘**，重启回到启动参数 |
| **后台操作审计日志** | `sys_audit_log` 表 + `AuditService` + `admin/audit.ftl` | 记录「谁/何时/来源IP/做了什么/结果」；异步有界队列写入（不阻塞业务、写不动就丢弃并计数）；`detail` 禁止凭据，并有正则脱敏兜底；覆盖后台全部增删改、登录成功/失败、被拦请求、用户登录失败、配额拒绝、模板下发、门户自助申请 |
| **后台应用日志查看/下载** | `logback-proxy.xml` + `admin/syslog.ftl` | 通过 `app.properties` 的 `logbackName=logback-proxy.xml` 启用自定义 logback：`log/proxy-server.log`（20MB×14 份、总量 2GB）+ 仅 ERROR 的独立文件；后台可列目录、按级别/关键词查看尾部、下载（严格目录包含校验） |
| **失败事件汇总与告警** | `pkg/events/`、`web/console_logs.go`、后台审计 | 控制台：200 条有界环形缓冲 + 30 秒同键冷却，页头徽标 + 统计页面板；后台：审计 `recentFail`（近 24h）驱动顶栏告警徽标，一键跳转「失败事件」筛选 |

### 3.2 七项功能

| 功能 | 落点 | 要点 |
| --- | --- | --- |
| **后台首页仪表盘** | `/admin/dashboard`（`/admin` 也渲染它） | 用户/域名/配置/统计行数、在线节点、近期流量；流量趋势与审计趋势双图表 + PNG 导出；「最近事件」事件流；快捷入口；30 秒自动刷新；顶栏失败事件徽标 |
| **隧道健康检查与一键诊断** | `/console/diagnose` + 穿透服务页 | 四段报告：本机服务 / 云端可达性 / 每条隧道（含对目标做 2 秒 TCP 拨号探测）/ 配置与环境；总预算 10 秒、最多 50 条、8 并发；结果可在弹窗查看并复制为纯文本 |
| **用量配额与限流管理** | `sys_quota` 表 + `/admin/quota` + `QuotaService` | 限制隧道数、端口数、并发连接、月度流量；**实际强制**的是「月度流量」与「最大隧道数」（在配置下发时拒绝）；`max_conns`/`max_ports` 仅作记录并已在页面与代码中如实标注；定时任务刷新「超限」状态 |
| **全局搜索** | `/admin/search` + 顶栏搜索框 | 五个维度（用户/域名/自动穿透配置/流量统计/审计日志）各限 20 条，全部参数化查询、有界；审计结果不返回 `detail`；用户结果不含口令 |
| **批量隧道模板与一键部署** | `sys_template` 表 + `/admin/template` | 一组隧道配置存为模板（**不含口令**），可导入/导出，对指定账号批量下发；逐条白名单校验并回报失败明细 |
| **图表与报表导出** | `/admin/report` + `Admin.chart.toPng` | 流量/连接/按端口三张图，均可导出 PNG（客户端合成白底，不上传数据）；CSV/JSON 报表下载；可手动「立即生成」并由定时任务按 `admin.report.interval.hours` 周期性落盘 |
| **用户自助门户增强** | `/index/usage` + `/index/usage/data` + `/index/export` | 本月/累计用量、按端口与按天图表（页面内自绘 Canvas，不加载 admin.js）、配额展示、我的域名与端口；可自助导出自己的统计数据与隧道配置（**不含口令**）；可提交域名/端口申请（服务端校验 + 每人 3 个上限 + 1 次/分钟限流 + 归属校验，端口范围收紧为 10000~60000 以避开系统与节点保留端口） |

---

## 四、验证方式

### 自动化检查（可重复执行）

```bash
# 三端前端一致性：DOM id 引用、图标名、Admin.* API、危险 sink、外链、模板转义、后台内联脚本 id
node tools/check-frontend.mjs

# Java 注释平衡：防「丢失的块注释结束符把整段代码（含路由）吞进注释」
node tools/check-java.mjs

# 管理后台模板标签平衡
powershell -NoProfile -ExecutionPolicy Bypass -File check-tags.ps1

# FreeMarker 模板语法（只解析）
FM="$USERPROFILE/.m2/repository/org/freemarker/freemarker/2.3.31/freemarker-2.3.31.jar"
java -cp "$FM" tools/template-check/TemplateCheck.java \
     proxy-server/src/main/resources/template proxy-proxy/src/main/resources/template

# FreeMarker 模板渲染（空数据桩模型，捕获求值期错误）
java -cp "$FM" tools/template-check/RenderSmoke.java \
     proxy-server/src/main/resources/template proxy-proxy/src/main/resources/template

# 纯前端二维码编码器：与 skip2/go-qrcode 逐模块比对（见 tools/README.md）
node tools/qr-verify/compare.mjs --gen
( cd tools/qr-verify && go build -o qrverify.exe . && ./qrverify.exe texts.txt > ref.txt )
node tools/qr-verify/compare.mjs

# 编译
cd proxy-client-golang && go vet ./... && go build ./... && go test ./... -count=1
mvn -o -DskipTests compile
```

### 实测结果

| 项目 | 结果 |
| --- | --- |
| `tools/check-frontend.mjs` | 全部通过：9 个 Go 页面脚本共 196 个 DOM id 引用存在；后台 17 个模板共 144 个内联脚本 id 引用存在；Go 50 个 / 后台 51 个图标名有效；后台引用的 32 个 `Admin.*` API 存在；危险 sink 仅剩「内置图标常量」；无外部 CDN；**31 个 FreeMarker 模板**无未转义插值 |
| `tools/check-java.mjs` | 154 个 Java 文件注释平衡；1 处**有意**注释掉的路由（`/app/download`）作为提示列出 |
| `check-tags.ps1` | 19 个后台/站点模板全部 OK |
| `tools/template-check` 解析 | 31 个模板全部解析通过 |
| `tools/template-check` 渲染 | **31 个模板在空数据桩模型下全部渲染通过**（无求值期 500） |
| `tools/qr-verify` | 35/35 一致（4 项仅掩码选择不同、矩阵等价） |
| `go build` / `go vet` / `gofmt -l` | 通过 / 通过 / 无输出 |
| `go test ./... -count=1` | 通过（`Protol`、`pkg/events`、`pkg/logsink`、`web` 四组，含日志轮转、尾部读取、路径校验、级别原子性、路由端到端） |
| `mvn -o -DskipTests compile`（清空 target 后全量重编） | 14 + 107 + 33 个源文件编译，BUILD SUCCESS |

### 手工回归要点

1. 控制台：切换三态主题 → 刷新后保持；断开网络 → Toast 报错而非静默失败。
2. 穿透服务页：开启自动刷新并改间隔 → 选中若干隧道批量停止 → 列表与 KPI 同步更新。
3. 运行日志页：制造多域名日志 → 按级别/域名/关键词过滤 → 导出 TXT/JSON 内容与屏幕一致。
4. 数据统计页：添加隧道产生流量 → 柱状图与连接数折线随自动刷新变化。
5. 设置与分享页：导出配置 → 生成二维码（内容超限时给出明确提示）→ 清空隧道后再导入 → 检查失败明细。
6. 后台用户日志页：按用户名/端口筛选 → 图表与列表口径一致 → 导出 CSV 用 Excel 打开中文不乱码、
   以 `=`/`+`/`-`/`@` 开头的单元格不被当作公式执行。
7. 后台自动穿透页：导入 JSON（含非法条目）→ 检查逐条失败原因 → 生成二维码 → 手机扫码校验内容。
8. 远程访问控制台：仅带令牌可进入；去掉令牌应被拦截；`?token=` 使用后地址栏应已清除令牌。

#### 第二轮新增功能的回归要点

9. **日志文件**：启动客户端 > 产生若干隧道事件 > 打开「日志文件」页 → 文件列表有当前文件且
   大小在增长 → 按级别/关键词查看尾部 → 下载的文件内容与页面一致；把 `-logMaxMB` 调到 1
   制造轮转 → 列表出现 `<name>-<时间戳>.log` 且数量不超过 `-logKeep`。
10. **运行时日志级别**：在设置页切到 `debug` → 日志立刻变详细（页面上与日志文件里都生效）；
    重启客户端后回到启动参数指定的级别。
11. **失败事件**：故意填错云端地址或停止一条隧道 → 页头出现失败事件徽标 → 打开列表能看到
    时间/类型/原因，文字均为纯文本（含 `<` 等字符不会被当作 HTML）。
12. **一键诊断**：在穿透服务页点「一键诊断」→ 报告包含本机/云端/每条隧道/配置四段，
    目标不可达时结论为 `fail` 并给出原因；10 秒内返回；「复制诊断报告」得到纯文本。
13. **后台仪表盘**：登录后进入 `/admin` 应渲染仪表盘且左侧「仪表盘」高亮；图表随主题切换重绘；
    「导出 PNG」得到白底图片；顶栏失败事件徽标可点击跳转到审计页的失败筛选。
14. **审计日志**：做一次「删除用户 / 导入配置 / 批量删除统计」→ 审计页出现对应记录
    （操作者/动作/目标/结果/来源IP），失败的尝试也有记录；导出 CSV 与页面口径一致；
    详情弹窗中的内容不被当作 HTML 解析。
15. **系统日志**：系统日志页能列出 `log/proxy-server*.log` → 选择文件查看尾部 → 下载可用；
    手工构造 `?file=../../db.db` 应被拒绝（不返回文件内容）。
16. **用量配额**：给某账号设 1GB 月度流量并把统计改成超限 → 该账号的自动穿透配置不再下发
    （客户端日志提示或静默跳过）；后台「刷新超限状态」后配额页显示超限徽标与原因。
17. **隧道模板**：新建模板（含非法条目应被逐条拒绝）→ 导出 JSON → 重新导入 → 对 1~2 个账号
    一键下发 → 自动穿透页能看到新增配置，且模板**不含口令**。
18. **报表**：选择 7/14/30 天 → 三张图与表格数字一致 → 导出 CSV/JSON 打开正常 →
    「立即生成报表」在 `report/` 目录产生文件。
19. **全局搜索**：顶栏搜索框输入用户名/域名/端口 → 结果按五组分类显示，计数正确；
    无结果时显示空状态；结果中的 `<script>` 之类输入以纯文本呈现（不执行）。
20. **自助门户**：登录站点后打开「我的用量」→ 本月/累计数字与图表显示正常（深色模式同样正确）；
    导出自己的统计数据与配置（配置**不含口令**）→ 提交域名申请与端口申请各一次，
    第二次立即提交应被限流提示；端口填 `80` 应被范围校验拒绝。


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

## 三、验证方式

### 自动化检查（可重复执行）

```bash
# 三端前端一致性：DOM id 引用、图标名、Admin.* API、危险 sink、外链、模板转义
node tools/check-frontend.mjs

# 管理后台模板标签平衡
powershell -NoProfile -ExecutionPolicy Bypass -File check-tags.ps1

# FreeMarker 模板语法（只解析不渲染；编译期发现不了模板语法错误）
FM="$USERPROFILE/.m2/repository/org/freemarker/freemarker/2.3.31/freemarker-2.3.31.jar"
java -cp "$FM" tools/template-check/TemplateCheck.java \
     proxy-server/src/main/resources/template proxy-proxy/src/main/resources/template

# 纯前端二维码编码器：与 skip2/go-qrcode 逐模块比对（见 tools/README.md）
node tools/qr-verify/compare.mjs --gen
( cd tools/qr-verify && go build -o qrverify.exe . && ./qrverify.exe texts.txt > ref.txt )
node tools/qr-verify/compare.mjs

# 编译
cd proxy-client-golang && go vet ./... && go build ./...
mvn -o -DskipTests compile
```

### 实测结果

| 项目 | 结果 |
| --- | --- |
| `tools/check-frontend.mjs` | 全部通过：8 个页面脚本共 164 个 DOM id 引用全部存在；Go 控制台 47 个 / 后台 51 个图标名全部有效；后台模板引用的 26 个 `Admin.*` API 全部存在；危险 sink 仅剩「内置图标常量」用法；无外部 CDN 引用；**23 个 FreeMarker 模板**（后台/站点 17 + 节点端 6）均无未转义插值 |
| `check-tags.ps1` | 12 个后台模板全部 OK |
| `tools/template-check` | 23 个 FreeMarker 模板全部解析通过（含本轮重写的 `admin/log.ftl`、`admin/config.ftl`） |
| `tools/qr-verify` | 35/35 一致（其中 4 项仅掩码选择不同、矩阵等价），覆盖版本 1-10 容量边界与 UTF-8 多字节内容 |
| `go vet ./...` / `go build ./...` | 通过 |
| `mvn -o -DskipTests compile` | 通过 |

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

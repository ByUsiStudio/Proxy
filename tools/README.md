# 前端静态检查与二维码回归测试

本目录放的是三端前端的**可重复验证工具**，用于在没有浏览器/运行环境的
情况下捕获集成缺陷。所有脚本都只读源码，不会修改项目文件。

## 1. `check-frontend.mjs` —— 三端前端一致性检查

```bash
node tools/check-frontend.mjs
```

退出码：`0` 全部通过；`1` 存在 FAIL。

检查项：

| 分组 | 说明 |
| --- | --- |
| `go-ids` | Go 控制台每个页面脚本引用的 DOM id 都能在对应 HTML 中找到 |
| `icons` / `icons-admin` | HTML/JS 中 `data-icon` 使用的图标名都存在于内置图标常量表 |
| `admin-api` | 后台模板调用的 `Admin.*` API 都由 `static/common/js` 下的自研脚本导出 |
| `sink` | 扫描 `innerHTML` / `insertAdjacentHTML` / `document.write` / `eval` / `new Function` / 内联事件属性；只有「右侧确为内置图标常量」的用法才被放行，其余一律报错 |
| `extern` | 扫描外部 CDN / 字体 / 图片引用（离线可用性与 CSP 要求） |
| `ftl-escape` | `proxy-server/template`（后台/站点，17 个）与 `proxy-proxy/template`（节点端，6 个）中未转义的 `${...}` 插值（该框架**不**自动转义，漏转义即 XSS） |

> 注意：`ftl-escape` 只覆盖 `proxy-server` 与 `proxy-proxy` 两个模块的
> `src/main/resources/template` 目录；`proxy-server/src/main/resources/templates/email`
> 下的邮件正文（含正常的外部图片引用）不参与扫描。

## 2. `qr-verify/` —— 纯前端二维码编码器回归测试

管理后台的「配置分享二维码」使用自实现的编码器
（`proxy-server/src/main/resources/static/common/js/qrcode.js`，
字节模式 / 纠错级别 M / 版本 1-10，含 Reed-Solomon、掩码评估与格式信息 BCH）。
这类逻辑一旦偏差，生成的二维码会直接扫不出来，因此用项目已依赖的
`skip2/go-qrcode` 作为参考实现逐模块比对。

```bash
# 1) 生成测试文本
node tools/qr-verify/compare.mjs --gen

# 2) 生成参考矩阵（在 tools/qr-verify 目录内执行）
cd tools/qr-verify
go build -o qrverify.exe .
./qrverify.exe texts.txt > ref.txt      # Windows PowerShell 亦可: .\qrverify.exe texts.txt > ref.txt

# 3) 比对
cd ../..
node tools/qr-verify/compare.mjs
```

通过标准：所有测试文本的**版本选择一致**且**矩阵逐模块相同**。
若两个实现各自选出的最优掩码不同（8 种掩码都合法可扫），脚本会单独列出，
不计为失败。

覆盖范围：版本 1-10 的字节容量边界（14/26/42/62/84/106/122/152/180/213 字节）、
超出单版本容量后的版本递增、以及 UTF-8 多字节（中文、变音符号）内容。

> 首轮实测结果：35/35 一致，其中 4 项仅掩码选择不同、矩阵等价。

## 3. `template-check/` —— FreeMarker 模板语法校验

HServer 只在**真正渲染页面时**才解析 FreeMarker 模板，因此指令拼写错误、
`<#if>` 未闭合、`<#include>` 路径不对等问题在编译期发现不了，只有用户打开页面才 500。
本工具用 FreeMarker 官方解析器把模板逐个解析一遍（只解析、不渲染，无需数据模型）。

```bash
FM="$USERPROFILE/.m2/repository/org/freemarker/freemarker/2.3.31/freemarker-2.3.31.jar"
java -cp "$FM" tools/template-check/TemplateCheck.java \
     proxy-server/src/main/resources/template \
     proxy-proxy/src/main/resources/template
```

退出码：`0` 全部解析通过；`1` 存在语法错误（会打印文件名与错误摘要）。

> 首轮实测结果：23 个模板全部 OK（含本轮重写的 `admin/log.ftl`、`admin/config.ftl`）。

## 4. `../check-tags.ps1` —— 管理后台模板标签平衡

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File check-tags.ps1
```

剥离 FreeMarker 指令后用栈匹配所有非空元素的开闭标签，用于发现
重构模板时常见的漏闭合标签。

## 5. 建议的执行顺序

```bash
node tools/check-frontend.mjs                 # 静态一致性与危险 sink
powershell -File check-tags.ps1               # 标签平衡
java -cp "$FM" tools/template-check/TemplateCheck.java <模板目录...>   # 模板语法
node tools/qr-verify/compare.mjs              # 二维码编码器回归
```


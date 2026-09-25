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
| `admin-ids` | 后台模板**内联脚本**引用的 DOM id 都能在该模板或 `header.ftl` 中找到 |
| `sink` | 扫描 `innerHTML` / `insertAdjacentHTML` / `document.write` / `eval` / `new Function` / 内联事件属性；只有「右侧确为内置图标常量」的用法才被放行，其余一律报错 |
| `extern` | 扫描外部 CDN / 字体 / 图片引用（离线可用性与 CSP 要求） |
| `ftl-escape` | `proxy-server/template`（后台/站点）与 `proxy-proxy/template`（节点端）中未转义的 `${...}` 插值（该框架**不**自动转义，漏转义即 XSS） |

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

## 3. `check-java.mjs` —— Java 注释平衡检查

```bash
node tools/check-java.mjs
```

捕获「一个丢失的块注释结束符，把整段代码吞进注释」这类**编译期完全看不出来**的缺陷。
本仓库真实发生过一次：某次编辑漏掉了 Javadoc 结尾的结束符，导致
`@GET("listDevice")` 整个处理器变成注释——javac 编译通过、所有静态检查通过，
但该路由直接消失、客户端引导接口 404。

判定规则：

| 情况 | 结论 |
| --- | --- |
| 文件结束时仍处于块注释中 | **FAIL**（注释未闭合） |
| Javadoc（`/**` 开头）内出现 `@GET/@POST/@PUT/@DELETE` | **FAIL**（Javadoc 不该包含注解，几乎必然是误吞代码） |
| 普通块注释（`/*` 开头）内出现路由注解 | INFO（很可能是**有意**注释掉的路由） |

## 4. `template-check/` —— FreeMarker 模板语法与渲染校验

HServer 只在**真正渲染页面时**才解析 FreeMarker 模板，因此模板问题在编译期发现不了。
两个互补的工具：

### 4.1 `TemplateCheck.java` —— 解析（语法）

```bash
FM="$USERPROFILE/.m2/repository/org/freemarker/freemarker/2.3.31/freemarker-2.3.31.jar"
java -cp "$FM" tools/template-check/TemplateCheck.java \
     proxy-server/src/main/resources/template \
     proxy-proxy/src/main/resources/template
```

立即发现指令拼写错误、`<#if>` 未闭合、`<#include>` 路径不对等语法问题。

### 4.2 `RenderSmoke.java` —— 渲染（求值）

```bash
java -cp "$FM" tools/template-check/RenderSmoke.java \
     proxy-server/src/main/resources/template \
     proxy-proxy/src/main/resources/template
```

用「宽松但类型合理」的桩模型把每个模板**真正渲染一遍**，捕获解析阶段看不见的
求值期错误：`?c` 用在了字符串上、`?size` 用在了非序列上、变量缺默认值、
拼错的 built-in——这些表现为「打开页面 500」。

桩模型会从 FreeMarker 的报错里**自动学习**每个变量该是什么类型
（`seq`/`num`/`bool`/`hash`/`str`）并重试，因此不需要人工维护变量表。
局限：它证明的是「**空数据时不会 500**」，不能替代有数据时的真实回归。

退出码均为 `0` 通过、`1` 存在错误。

## 5. `../check-tags.ps1` —— 管理后台模板标签平衡

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File check-tags.ps1
```

剥离 FreeMarker 指令后用栈匹配所有非空元素的开闭标签，用于发现
重构模板时常见的漏闭合标签。

## 6. 建议的执行顺序

```bash
node tools/check-frontend.mjs                 # 前端一致性与危险 sink / 模板转义 / DOM id
node tools/check-java.mjs                     # Java 注释平衡（防路由被注释吞掉）
powershell -File check-tags.ps1               # 模板标签平衡
java -cp "$FM" tools/template-check/TemplateCheck.java <模板目录...>   # 模板语法
java -cp "$FM" tools/template-check/RenderSmoke.java <模板目录...>     # 模板渲染（空数据）
node tools/qr-verify/compare.mjs              # 二维码编码器回归
```


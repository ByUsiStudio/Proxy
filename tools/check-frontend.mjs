#!/usr/bin/env node
/* ==========================================================================
   check-frontend.mjs —— 三端前端静态一致性检查
   用途：在无可运行环境的情况下，静态捕获「前端引用了不存在的 DOM id / 图标名」
         «HTML 中存在但脚本从未使用的关键容器» 这类集成缺陷。

   检查项：
     A. Go 控制台：各页面 JS 的 getElementById / byId 引用 ↔ 对应 .html 的 id 定义
     B. Go 控制台：HTML 与 JS 中 data-icon 使用的图标名 ↔ app.js 的 ICON_PATHS 表
     C. Java 后台：模板 data-icon 图标名 ↔ admin.js 的 ICON_PATHS 表
     D. Java 后台：admin.js 暴露的 Admin.* API ↔ 模板/内联脚本中的调用
     E. 全端：危险 sink 扫描（innerHTML / document.write / eval / new Function /
        insertAdjacentHTML / 内联 on* 事件属性），白名单为内置图标常量表
     F. 全端：外部 CDN / 第三方资源引用扫描
     G. Java 后台：FreeMarker 模板中未转义的 ${...} 插值（框架不自动转义）

   用法: node tools/check-frontend.mjs
   退出码: 0 = 全部通过；1 = 存在 FAIL
   ========================================================================== */
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, dirname, relative, basename } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const results = [];
let failed = 0;

function pass(group, message) { results.push(['PASS', group, message]); }
function fail(group, message) { results.push(['FAIL', group, message]); failed++; }
function info(group, message) { results.push(['INFO', group, message]); }

function read(p) { return readFileSync(join(ROOT, p), 'utf8'); }
function exists(p) { return existsSync(join(ROOT, p)); }

/** 去掉 // 行注释与 /* *\/ 块注释，避免把注释里的示例代码当成真实用法。 */
function stripJsComments(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
    .replace(/(^|[^:'"\\])\/\/[^\n]*/g, (m, p1) => p1 + ' '.repeat(m.length - p1.length));
}

function walk(dir, filter, out = []) {
  const abs = join(ROOT, dir);
  if (!existsSync(abs)) return out;
  for (const name of readdirSync(abs)) {
    const rel = join(dir, name);
    const st = statSync(join(ROOT, rel));
    if (st.isDirectory()) walk(rel, filter, out);
    else if (filter(rel)) out.push(rel.replace(/\\/g, '/'));
  }
  return out;
}

/** 从形如 { a: 'path', b: "path" } 的对象字面量文本中抽取键名。 */
function extractObjectKeys(source, objectName) {
  const start = source.indexOf(objectName + ' = {');
  if (start < 0) return null;
  const body = source.slice(start);
  const end = body.indexOf('\n  };');
  const scope = end > 0 ? body.slice(0, end) : body.slice(0, 20000);
  const keys = new Set();
  for (const m of scope.matchAll(/^\s*([A-Za-z_$][\w$]*)\s*:/gm)) keys.add(m[1]);
  return keys;
}

/* ------------------------------------------------------------------ *
 * A. Go 控制台：页面 JS 引用的 id 必须存在
 * ------------------------------------------------------------------ */
const GO_WEB = 'proxy-client-golang/web';
const GO_PAGES = {
  'login.js': ['login.html'],
  'center.js': ['center.html'],
  'port.js': ['port.html'],
  'domain.js': ['domain.html'],
  'autoproxy.js': ['autoproxy.html'],
  'log.js': ['log.html'],
  'stats.js': ['stats.html'],
  'settings.js': ['settings.html']
};

function htmlIds(html) {
  const ids = new Set();
  for (const m of html.matchAll(/\bid\s*=\s*"([^"]+)"/g)) ids.add(m[1]);
  // 运行时动态追加的容器：js 里通过 appendChild 到已有容器，不算 id
  return ids;
}

for (const [jsFile, htmlFiles] of Object.entries(GO_PAGES)) {
  const jsPath = `${GO_WEB}/common/js/pages/${jsFile}`;
  if (!exists(jsPath)) { fail('go-ids', `缺少脚本 ${jsPath}`); continue; }
  const js = read(jsPath);

  const referenced = new Set();
  // byId('x') / getElementById('x') / byId("x")
  for (const m of js.matchAll(/\b(?:byId|getElementById)\(\s*['"]([^'"]+)['"]/g)) referenced.add(m[1]);
  // 形如 ['a','b'].forEach(... els[id] = byId(id)) 的批量列表
  for (const m of js.matchAll(/\[\s*((?:\s*'[^']+'\s*,?)+)\s*\]\s*\.forEach/g)) {
    for (const s of m[1].matchAll(/'([^']+)'/g)) referenced.add(s[1]);
  }

  const defined = new Set();
  let missingHtml = false;
  for (const htmlFile of htmlFiles) {
    const htmlPath = `${GO_WEB}/${htmlFile}`;
    if (!exists(htmlPath)) { fail('go-ids', `缺少页面 ${htmlPath}`); missingHtml = true; continue; }
    for (const id of htmlIds(read(htmlPath))) defined.add(id);
  }
  if (missingHtml) continue;
  // 外壳（app.js）注入的 id
  for (const id of ['appHeader', 'sideNav', 'scrim', 'menuToggle', 'shellStatus', 'shellVersion', 'login_out']) defined.add(id);

  const missing = [...referenced].filter((id) => !defined.has(id));
  if (missing.length) fail('go-ids', `${jsFile}: 引用了未定义的 id -> ${missing.join(', ')}`);
  else pass('go-ids', `${jsFile}: ${referenced.size} 个 id 引用全部存在`);
}

/* ------------------------------------------------------------------ *
 * B/C. 图标名必须存在于图标表
 * ------------------------------------------------------------------ */
const goIcons = extractObjectKeys(read(`${GO_WEB}/common/js/app.js`), 'ICON_PATHS');
if (!goIcons || !goIcons.size) fail('icons', 'app.js: 无法解析 ICON_PATHS');
else {
  const files = walk(GO_WEB, (p) => /\.(html|js)$/.test(p));
  const bad = new Map();
  for (const file of files) {
    const src = file.endsWith('.js') ? stripJsComments(read(file)) : read(file);
    for (const m of src.matchAll(/\bdata-icon\s*=\s*"([^"]+)"/g)) {
      if (!goIcons.has(m[1])) bad.set(`${file}:${m[1]}`, true);
    }
    for (const m of src.matchAll(/\bicon:\s*'([^']+)'/g)) {
      if (!goIcons.has(m[1])) bad.set(`${file} (icon:):${m[1]}`, true);
    }
    for (const m of src.matchAll(/\biconNode\(\s*'([^']+)'/g)) {
      if (!goIcons.has(m[1])) bad.set(`${file} (iconNode):${m[1]}`, true);
    }
  }
  if (bad.size) fail('icons', `Go 控制台使用了未定义的图标 -> ${[...bad.keys()].join(', ')}`);
  else pass('icons', `Go 控制台图标名全部有效（表内 ${goIcons.size} 个）`);
}

const ADMIN_TPL = 'proxy-server/src/main/resources/template';
const adminJsPath = 'proxy-server/src/main/resources/static/common/js/admin.js';
const adminIcons = exists(adminJsPath) ? extractObjectKeys(read(adminJsPath), 'ICON_PATHS') : null;
if (!adminIcons) fail('icons-admin', 'admin.js: 无法解析 ICON_PATHS');
else {
  const files = walk(ADMIN_TPL, (p) => p.endsWith('.ftl'));
  const bad = new Map();
  for (const file of files) {
    const src = read(file);
    for (const m of src.matchAll(/\bdata-icon\s*=\s*"([^"]+)"/g)) {
      if (!adminIcons.has(m[1])) bad.set(`${file}:${m[1]}`, true);
    }
  }
  if (bad.size) fail('icons-admin', `管理后台使用了未定义的图标 -> ${[...bad.keys()].join(', ')}`);
  else pass('icons-admin', `管理后台图标名全部有效（表内 ${adminIcons.size} 个）`);
}

/* ------------------------------------------------------------------ *
 * D. 模板引用的 Admin.* API 是否真实存在
 * ------------------------------------------------------------------ */
if (exists(adminJsPath)) {
  const adminJs = read(adminJsPath);
  const tplFiles = walk(ADMIN_TPL, (p) => p.endsWith('.ftl'));
  const referenced = new Set();
  for (const file of tplFiles) {
    for (const m of read(file).matchAll(/\bAdmin\.([A-Za-z_$][\w$]*)/g)) referenced.add(m[1]);
  }
  for (const m of adminJs.matchAll(/\bAdmin\.([A-Za-z_$][\w$]*)\s*=/g)) referenced.delete(m[1]);
  const defined = new Set();
  for (const m of adminJs.matchAll(/\bAdmin\.([A-Za-z_$][\w$]*)\s*=/g)) defined.add(m[1]);
  const missing = [...referenced].filter((name) => !defined.has(name));
  if (missing.length) fail('admin-api', `模板调用了未定义的 Admin.* -> ${missing.join(', ')}`);
  else pass('admin-api', `模板引用的 Admin.* API 全部存在（${[...referenced].length} 个）`);
}

/* ------------------------------------------------------------------ *
 * E. 危险 sink 扫描
 *    仅当赋值右侧确为「内置可信常量」时才放行：
 *      · PX.icon('literal') / Admin.svg('literal') —— 图标常量表
 *      · 裸标识符（如 MOON / SUN）—— 文件内定义的常量 SVG 片段
 *    其余任何含拼接、模板串或变量的用法一律 FAIL。
 * ------------------------------------------------------------------ */
const SINK_PATTERNS = [
  { name: 'innerHTML', re: /\.innerHTML\s*=/g },
  { name: 'insertAdjacentHTML', re: /insertAdjacentHTML\s*\(/g },
  { name: 'document.write', re: /document\.write(?:ln)?\s*\(/g },
  { name: 'eval', re: /\beval\s*\(/g },
  { name: 'new Function', re: /new\s+Function\s*\(/g },
  { name: 'inline on* attribute', re: /<[a-zA-Z][^>]*\son(?:click|change|input|submit|load|error|mouse\w+|key\w+)\s*=\s*"/g }
];

/** 判断 innerHTML 赋值右侧是否为可信常量表达式。 */
function trustedInnerHtmlRhs(line) {
  const m = line.match(/\.innerHTML\s*=\s*(.+?);?\s*$/);
  if (!m) return false;
  const rhs = m[1].trim().replace(/;$/, '');
  // 内置图标常量表调用，参数必须是字符串字面量
  if (/^(?:PX|Admin)\.(?:icon|svg)\(\s*'[^'\\]*'\s*(?:,\s*'[^'\\]*'\s*)?\)$/.test(rhs)) return true;
  // 裸标识符（文件内常量）
  if (/^[A-Za-z_$][\w$]*$/.test(rhs)) return true;
  return false;
}

const scanRoots = [
  { dir: GO_WEB, filter: (p) => /\.(html|js)$/.test(p) },
  // templates/email 是发信正文，外部图片属正常邮件实践，不参与前端资源扫描
  { dir: 'proxy-server/src/main/resources', filter: (p) => /\.(html|js|ftl)$/.test(p) && !/mdui(\.min)?\.js|jquery|echarts|paging\.js|templates\/email\//.test(p) },
  { dir: 'proxy-proxy/src/main/resources', filter: (p) => /\.(html|js|ftl)$/.test(p) && !/mdui(\.min)?\.js|jquery|echarts|paging\.js/.test(p) }
];
for (const { dir, filter } of scanRoots) {
  for (const file of walk(dir, filter)) {
    const raw = read(file);
    const src = file.endsWith('.js') ? stripJsComments(raw) : raw;
    for (const { name, re } of SINK_PATTERNS) {
      for (const hit of src.matchAll(re)) {
        const lineStart = src.lastIndexOf('\n', hit.index) + 1;
        const lineEnd = src.indexOf('\n', hit.index);
        const line = src.slice(lineStart, lineEnd < 0 ? undefined : lineEnd);
        if (name === 'innerHTML' && trustedInnerHtmlRhs(line)) {
          info('sink', `${file}:${src.slice(0, hit.index).split('\n').length} innerHTML ← 内置图标常量（已确认安全）`);
          continue;
        }
        fail('sink', `${file}:${src.slice(0, hit.index).split('\n').length} 发现 ${name}`);
      }
    }
  }
}

/* ------------------------------------------------------------------ *
 * F. 外部资源引用
 * ------------------------------------------------------------------ */
const EXT_RE = /(?:src|href)\s*=\s*"(https?:)?\/\/(?!localhost|127\.0\.0\.1)[^"]+"/g;
for (const { dir, filter } of scanRoots) {
  for (const file of walk(dir, filter)) {
    if (file.includes('/vendor/')) continue;
    const src = read(file);
    for (const m of src.matchAll(EXT_RE)) {
      // 用户文档/仓库链接允许出现在 <a href>，脚本/样式/图片外链不允许
      const isAsset = /\.(js|css|woff2?|ttf|png|jpe?g|gif|svg)(\?|"|$)/i.test(m[0]);
      const isScriptOrStyle = /<(?:script|link)\b/i.test(src.slice(Math.max(0, m.index - 120), m.index));
      if (isAsset || isScriptOrStyle) fail('extern', `${file}: 外部资源引用 ${m[0].slice(0, 90)}`);
    }
  }
}
pass('extern', '外部资源扫描完成（用户可见的文档链接不计入）');

/* ------------------------------------------------------------------ *
 * G. FreeMarker 未转义插值
 * ------------------------------------------------------------------ */
const TPL_ALLOW_NO_ESCAPE = /^\s*(?:\$\{)?(?:totalRow\?c|page\?c|totalPage\?c|\d+)(?:\})?/;
for (const file of walk(ADMIN_TPL, (p) => p.endsWith('.ftl'))) {
  const src = read(file);
  const lines = src.split(/\r?\n/);
  const bad = [];
  lines.forEach((line, i) => {
    // 跳过 FreeMarker 注释行与指令行
    if (/^\s*<#--/.test(line) || /^\s*<#/.test(line)) return;
    for (const m of line.matchAll(/\$\{([^}]*)\}/g)) {
      const expr = m[1].trim();
      if (!expr) continue;
      // 已转义
      if (/\?(html|js_string|url|json_string)\b/.test(expr)) continue;
      // 纯数字字面量 / 布尔
      if (/^[-\d.]+$/.test(expr) || expr === 'true' || expr === 'false') continue;
      // 显式转数字（?c）、集合大小（?size）等不可能承载 HTML 的表达式
      if (/\?(c|size)\b/.test(expr)) continue;
      bad.push(`行 ${i + 1}: \${${expr}}`);
    }
  });
  if (bad.length) fail('ftl-escape', `${basename(file)}: 存在未转义插值 -> ${bad.slice(0, 6).join(' | ')}${bad.length > 6 ? ` …(共 ${bad.length} 处)` : ''}`);
}
pass('ftl-escape', 'FreeMarker 转义扫描完成');

/* ------------------------------------------------------------------ *
 * 输出
 * ------------------------------------------------------------------ */
const ORDER = { FAIL: 0, INFO: 1, PASS: 2 };
results.sort((a, b) => ORDER[a[0]] - ORDER[b[0]]);
let lastGroup = null;
for (const [level, group, message] of results) {
  if (group !== lastGroup) {
    console.log(`\n[${group}]`);
    lastGroup = group;
  }
  console.log(`  ${level === 'FAIL' ? '✗' : level === 'INFO' ? '·' : '✓'} ${message}`);
}
console.log(`\n${failed === 0 ? '全部检查通过' : `存在 ${failed} 项需要处理`}`);
process.exit(failed === 0 ? 0 : 1);

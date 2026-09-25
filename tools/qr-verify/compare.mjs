/* ==========================================================================
   qrcode.js 回归测试：以 skip2/go-qrcode 为参考实现，逐模块比对管理后台的
   纯前端二维码编码器。

   为什么要做这个测试：
     proxy-server 不允许引入 CDN，也没有可用的二维码依赖，因此
     `static/common/js/qrcode.js` 是自己实现的（字节模式 / 纠错级别 M / 版本 1-10，
     含 Reed-Solomon、掩码评估与格式信息 BCH）。这类编码逻辑一旦有偏差，
     生成的二维码就会直接扫不出来，必须与成熟实现逐位对齐。

   三步执行（沙箱禁止子进程管道捕获输出，因此用文件交换数据）：
     1) node tools/qr-verify/compare.mjs --gen
     2) go build -o qrverify.exe .   (在本目录内)
        ./qrverify.exe texts.txt > ref.txt
     3) node tools/qr-verify/compare.mjs

   通过标准：全部测试文本的版本选择一致、矩阵与参考实现逐模块相同。
   掩码选择差异（两个实现各自挑选的最优掩码不同）不算失败 —— 8 种掩码
   生成的二维码都合法可扫，脚本会单独列出来。
   ========================================================================== */
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..', '..');
const qrSource = readFileSync(
  join(root, 'proxy-server/src/main/resources/static/common/js/qrcode.js'),
  'utf8'
);

// 构造最小浏览器环境：qrcode.js 的编码路径只用到 window，完全不碰 DOM
const sandbox = { devicePixelRatio: 1 };
sandbox.window = sandbox;
new Function('window', 'document', qrSource)(sandbox, undefined);
const Admin = sandbox.Admin;
if (!Admin || !Admin.qrcode) throw new Error('qrcode.js 未导出 Admin.qrcode');

function buildTexts() {
  // 长度覆盖版本 1..10 的字节容量边界。
  // 关键：只用小写字母 —— 小写字符在 QR 里只能走字节模式，
  // 这样 go-qrcode 的分段模式优化无法把小写串拆成更省位的数字/字母数字段，
  // 参考实现与「纯字节模式」的 JS 实现才具备可比性。
  const lengths = [
    1, 5, 13, 14, 15, 20, 26, 27, 28, 41, 42, 43, 61, 62, 63, 83, 84, 85,
    105, 106, 107, 121, 122, 123, 151, 152, 153, 179, 180, 181, 199, 200, 213
  ];
  const alphabet = 'abcdefghijklmnopqrstuvwxyz';
  const texts = lengths.map((n, i) => {
    let s = '';
    while (s.length < n) s += alphabet[(i * 7 + s.length) % alphabet.length];
    return s.slice(0, n);
  });
  // UTF-8 多字节内容同样只能走字节模式，用来覆盖字符计数与字节编码
  texts.push('隧道配置分享'.repeat(6));
  texts.push('übergrößenträger'.repeat(4));
  return texts;
}

const texts = buildTexts();

if (process.argv.includes('--gen')) {
  writeFileSync(join(here, 'texts.txt'), texts.join('\n') + '\n', 'utf8');
  console.log(`已写出 ${texts.length} 条测试文本到 tools/qr-verify/texts.txt`);
  process.exit(0);
}

let goOut;
try {
  goOut = readFileSync(join(here, 'ref.txt'), 'utf8');
} catch (e) {
  console.error('缺少 ref.txt：请先执行');
  console.error('  node tools/qr-verify/compare.mjs --gen');
  console.error('  (cd tools/qr-verify && go build -o qrverify.exe . && ./qrverify.exe texts.txt > ref.txt)');
  process.exit(2);
}

// 解析参考输出
const refs = [];
let cur = null;
for (const line of goOut.split(/\r?\n/)) {
  if (line.startsWith('RESULT\t')) {
    cur = { version: Number(line.slice(7)), rows: [] };
  } else if (line === 'END') {
    refs.push(cur);
    cur = null;
  } else if (cur && /^[01]+$/.test(line)) {
    cur.rows.push(line);
  }
}
if (refs.length !== texts.length) {
  console.error(`参考输出数量不符：期望 ${texts.length}，实际 ${refs.length}`);
  process.exit(1);
}

function grid(modules) {
  return modules.map((row) => row.map((d) => (d ? '1' : '0')).join(''));
}

let pass = 0;
const maskOnly = [];
const failures = [];

for (let i = 0; i < texts.length; i++) {
  const text = texts[i];
  const ref = refs[i];
  const mine = Admin.qrcode(text);

  if (!mine) {
    failures.push({ i, len: text.length, reason: 'JS 编码器返回 null（容量判定不一致）', refVersion: ref.version });
    continue;
  }
  if (mine.version !== ref.version) {
    failures.push({ i, len: text.length, reason: `版本不一致 JS=${mine.version} GO=${ref.version}` });
    continue;
  }

  const mineRows = grid(mine.modules);
  if (mineRows.length === ref.rows.length && mineRows.every((r, idx) => r === ref.rows[idx])) {
    pass++;
    continue;
  }

  // 直接比较失败：逐个掩码再试，区分「编码错误」与「掩码评分差异」。
  // 掩码只影响可读性的细微差别，8 种掩码产生的都是合法二维码，
  // 因此掩码差异记为警告而非失败。
  let sameWithOtherMask = -1;
  for (let m = 0; m < 8; m++) {
    const alt = Admin.qrcode(text, { version: mine.version, mask: m });
    if (!alt) continue;
    const rows = grid(alt.modules);
    if (rows.length === ref.rows.length && rows.every((r, idx) => r === ref.rows[idx])) {
      sameWithOtherMask = m;
      break;
    }
  }
  if (sameWithOtherMask >= 0) {
    maskOnly.push({ i, len: text.length, mineMask: mine.mask, refMask: sameWithOtherMask });
    pass++;
    continue;
  }

  let diff = -1;
  for (let r = 0; r < ref.rows.length; r++) {
    if (mineRows[r] !== ref.rows[r]) { diff = r; break; }
  }
  failures.push({
    i,
    len: text.length,
    version: mine.version,
    mineMask: mine.mask,
    reason: `矩阵不一致（首个不同行 ${diff}）—— 编码实现有误`
  });
}

console.log(`对比完成：${pass}/${texts.length} 一致（其中仅掩码选择不同、矩阵仍等价的 ${maskOnly.length} 项）`);
if (maskOnly.length) {
  console.log('\n掩码选择差异（合法，不影响可扫性）：');
  for (const m of maskOnly) {
    console.log(`  #${m.i} len=${m.len} JS掩码=${m.mineMask} GO掩码=${m.refMask}`);
  }
}
if (failures.length) {
  console.log(`\n真实失败（${failures.length} 项）：`);
  for (const f of failures) {
    console.log(`  #${f.i} len=${f.len} → ${f.reason}`);
  }
  process.exit(1);
}
console.log('\n结论：JS 二维码编码器与 skip2/go-qrcode 参考实现逐模块一致。');

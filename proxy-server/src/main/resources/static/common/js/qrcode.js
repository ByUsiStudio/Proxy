/* ==========================================================================
   qrcode.js —— 纯前端二维码编码器（无任何外部依赖 / 无 CDN / 兼容严格 CSP）
   用途：管理后台「配置分享」把精简后的隧道配置渲染成二维码，便于手机扫码导入。

   实现范围：
     · 字节模式（UTF-8），纠错级别 M，版本 1-10（版本 10 约可容纳 213 字节）
     · 完整的 Reed-Solomon 纠错、掩码评估（8 种掩码 + 4 条惩罚规则）、格式信息 BCH(15,5)
     · 版本 7 起写入版本信息（BCH(18,6)）
   为什么自己实现：
     项目不允许引入 CDN，也没有可用的二维码依赖；服务端渲染成图片则会把
     「配置内容」落到服务端日志/缓存里，这里改为纯浏览器内绘制，数据不出本机。
   ========================================================================== */
(function (global) {
  'use strict';

  var Admin = global.Admin || (global.Admin = {});

  /* ------------------------------------------------------------------ *
   * 1. GF(256) 与 Reed-Solomon
   * ------------------------------------------------------------------ */
  var EXP = new Uint8Array(512);
  var LOG = new Uint8Array(256);

  (function initGf() {
    var x = 1;
    for (var i = 0; i < 255; i++) {
      EXP[i] = x;
      LOG[x] = i;
      x <<= 1;
      if (x & 0x100) { x ^= 0x11d; } // 本原多项式 x^8+x^4+x^3+x^2+1
    }
    for (var j = 255; j < 512; j++) { EXP[j] = EXP[j - 255]; }
  })();

  function gfMul(a, b) {
    if (a === 0 || b === 0) { return 0; }
    return EXP[LOG[a] + LOG[b]];
  }

  /** 生成多项式 ∏(x - α^i)，i = 0..ecLen-1；下标 0 为最高次项。 */
  function rsGenerator(ecLen) {
    var poly = [1];
    for (var i = 0; i < ecLen; i++) {
      var next = new Array(poly.length + 1);
      for (var k = 0; k < next.length; k++) { next[k] = 0; }
      for (var j = 0; j < poly.length; j++) {
        next[j] ^= poly[j];
        next[j + 1] ^= gfMul(poly[j], EXP[i]);
      }
      poly = next;
    }
    return poly;
  }

  /** 计算 ecLen 个纠错码字。 */
  function rsEncode(data, ecLen) {
    var gen = rsGenerator(ecLen);
    var res = new Array(data.length + ecLen);
    for (var i = 0; i < data.length; i++) { res[i] = data[i]; }
    for (var k = data.length; k < res.length; k++) { res[k] = 0; }
    for (var d = 0; d < data.length; d++) {
      var coef = res[d];
      if (coef === 0) { continue; }
      for (var g = 0; g < gen.length; g++) {
        res[d + g] ^= gfMul(gen[g], coef);
      }
    }
    return res.slice(data.length);
  }

  /* ------------------------------------------------------------------ *
   * 2. 版本参数表（纠错级别 M）
   *    ec       —— 每块的纠错码字数
   *    groups   —— [[块数, 每块数据码字数], ...]
   * ------------------------------------------------------------------ */
  var VERSIONS = {
    1: { ec: 10, groups: [[1, 16]] },
    2: { ec: 16, groups: [[1, 28]] },
    3: { ec: 26, groups: [[1, 44]] },
    4: { ec: 18, groups: [[2, 32]] },
    5: { ec: 24, groups: [[2, 43]] },
    6: { ec: 16, groups: [[4, 27]] },
    7: { ec: 18, groups: [[4, 31]] },
    8: { ec: 22, groups: [[2, 38], [2, 39]] },
    9: { ec: 22, groups: [[3, 36], [2, 37]] },
    10: { ec: 26, groups: [[4, 43], [1, 44]] }
  };

  var ALIGN = {
    1: [], 2: [6, 18], 3: [6, 22], 4: [6, 26], 5: [6, 30],
    6: [6, 34], 7: [6, 22, 38], 8: [6, 24, 42], 9: [6, 26, 46], 10: [6, 28, 50]
  };

  /** 版本 7-10 的版本信息位串（ISO/IEC 18004 表 D.1） */
  var VERSION_INFO = { 7: 0x07C94, 8: 0x085BC, 9: 0x09A99, 10: 0x0A4D3 };

  /** 纠错级别 M 的编码为 0b00，格式信息里占 2 bit。 */
  var EC_LEVEL_BITS = 0;

  function dataCodewords(version) {
    var spec = VERSIONS[version];
    var total = 0;
    for (var i = 0; i < spec.groups.length; i++) {
      total += spec.groups[i][0] * spec.groups[i][1];
    }
    return total;
  }

  /* ------------------------------------------------------------------ *
   * 3. UTF-8 编码
   * ------------------------------------------------------------------ */
  function utf8Bytes(text) {
    var s = String(text === null || text === undefined ? '' : text);
    if (typeof TextEncoder !== 'undefined') {
      return Array.prototype.slice.call(new TextEncoder().encode(s));
    }
    var out = [];
    for (var i = 0; i < s.length; i++) {
      var code = s.charCodeAt(i);
      if (code < 0x80) {
        out.push(code);
      } else if (code < 0x800) {
        out.push(0xc0 | (code >> 6), 0x80 | (code & 0x3f));
      } else if (code >= 0xd800 && code <= 0xdbff && i + 1 < s.length) {
        var next = s.charCodeAt(i + 1);
        if (next >= 0xdc00 && next <= 0xdfff) {
          var cp = 0x10000 + ((code - 0xd800) << 10) + (next - 0xdc00);
          out.push(0xf0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3f), 0x80 | ((cp >> 6) & 0x3f), 0x80 | (cp & 0x3f));
          i++;
          continue;
        }
        out.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
      } else {
        out.push(0xe0 | (code >> 12), 0x80 | ((code >> 6) & 0x3f), 0x80 | (code & 0x3f));
      }
    }
    return out;
  }

  /* ------------------------------------------------------------------ *
   * 4. 位缓冲
   * ------------------------------------------------------------------ */
  function BitBuffer() {
    this.bits = [];
  }
  BitBuffer.prototype.put = function (value, length) {
    for (var i = length - 1; i >= 0; i--) {
      this.bits.push((value >>> i) & 1);
    }
  };
  BitBuffer.prototype.length = function () { return this.bits.length; };

  /* ------------------------------------------------------------------ *
   * 5. 编码为码字序列
   * ------------------------------------------------------------------ */
  /** 字符计数指示符长度：版本 1-9 为 8 bit，10-40 为 16 bit。 */
  function countBits(version) {
    return version <= 9 ? 8 : 16;
  }

  /** 选择能容纳 len 字节的最小版本；超出能力返回 null。 */
  function pickVersion(byteLength) {
    for (var v = 1; v <= 10; v++) {
      var capacity = dataCodewords(v) * 8;
      var need = 4 + countBits(v) + byteLength * 8;
      if (need <= capacity) { return v; }
    }
    return null;
  }

  function buildCodewords(bytes, version) {
    var capacityBits = dataCodewords(version) * 8;
    var buffer = new BitBuffer();
    buffer.put(0x4, 4);                       // 字节模式
    buffer.put(bytes.length, countBits(version));
    for (var i = 0; i < bytes.length; i++) {
      buffer.put(bytes[i], 8);
    }
    // 终止符：最多 4 个 0
    var terminator = Math.min(4, capacityBits - buffer.length());
    if (terminator > 0) { buffer.put(0, terminator); }
    // 补齐到字节边界
    while (buffer.length() % 8 !== 0) { buffer.bits.push(0); }
    // 补齐码字 0xEC / 0x11 交替
    var padToggle = true;
    while (buffer.length() < capacityBits) {
      buffer.put(padToggle ? 0xec : 0x11, 8);
      padToggle = !padToggle;
    }

    var dataBytes = [];
    for (var b = 0; b < buffer.length(); b += 8) {
      var value = 0;
      for (var k = 0; k < 8; k++) { value = (value << 1) | buffer.bits[b + k]; }
      dataBytes.push(value);
    }

    // 分块 + 计算纠错
    var spec = VERSIONS[version];
    var dataBlocks = [];
    var ecBlocks = [];
    var offset = 0;
    for (var g = 0; g < spec.groups.length; g++) {
      var blockCount = spec.groups[g][0];
      var blockSize = spec.groups[g][1];
      for (var n = 0; n < blockCount; n++) {
        var block = dataBytes.slice(offset, offset + blockSize);
        offset += blockSize;
        dataBlocks.push(block);
        ecBlocks.push(rsEncode(block, spec.ec));
      }
    }

    // 交织：先按列取数据码字，再按列取纠错码字
    var result = [];
    var maxData = 0;
    for (var d = 0; d < dataBlocks.length; d++) {
      maxData = Math.max(maxData, dataBlocks[d].length);
    }
    for (var col = 0; col < maxData; col++) {
      for (var di = 0; di < dataBlocks.length; di++) {
        if (col < dataBlocks[di].length) { result.push(dataBlocks[di][col]); }
      }
    }
    for (var ec = 0; ec < spec.ec; ec++) {
      for (var ei = 0; ei < ecBlocks.length; ei++) {
        result.push(ecBlocks[ei][ec]);
      }
    }
    return result;
  }

  /* ------------------------------------------------------------------ *
   * 6. 矩阵构造
   * ------------------------------------------------------------------ */
  function makeGrid(size) {
    var grid = [];
    for (var r = 0; r < size; r++) {
      var row = [];
      for (var c = 0; c < size; c++) { row.push(null); }
      grid.push(row);
    }
    return grid;
  }

  function setModule(grid, reserved, row, col, dark) {
    if (row < 0 || col < 0 || row >= grid.length || col >= grid.length) { return; }
    grid[row][col] = !!dark;
    reserved[row][col] = true;
  }

  function placeFinder(grid, reserved, row, col) {
    for (var r = -1; r <= 7; r++) {
      for (var c = -1; c <= 7; c++) {
        var rr = row + r;
        var cc = col + c;
        if (rr < 0 || cc < 0 || rr >= grid.length || cc >= grid.length) { continue; }
        var dark = (r >= 0 && r <= 6 && (c === 0 || c === 6))
          || (c >= 0 && c <= 6 && (r === 0 || r === 6))
          || (r >= 2 && r <= 4 && c >= 2 && c <= 4);
        setModule(grid, reserved, rr, cc, dark);
      }
    }
  }

  function placeAlignment(grid, reserved, version) {
    var centers = ALIGN[version];
    for (var i = 0; i < centers.length; i++) {
      for (var j = 0; j < centers.length; j++) {
        var row = centers[i];
        var col = centers[j];
        // 跳过与定位图形重叠的位置
        if ((row === 6 && col === 6)
          || (row === 6 && col === grid.length - 7)
          || (row === grid.length - 7 && col === 6)) {
          continue;
        }
        for (var r = -2; r <= 2; r++) {
          for (var c = -2; c <= 2; c++) {
            var dark = Math.max(Math.abs(r), Math.abs(c)) !== 1;
            setModule(grid, reserved, row + r, col + c, dark);
          }
        }
      }
    }
  }

  function placeTiming(grid, reserved) {
    for (var i = 8; i < grid.length - 8; i++) {
      var dark = i % 2 === 0;
      setModule(grid, reserved, 6, i, dark);
      setModule(grid, reserved, i, 6, dark);
    }
  }

  /** 预留格式信息区域（占位为浅色），真正的位由 drawFormat 写入。 */
  function reserveFormatAreas(grid, reserved) {
    var size = grid.length;
    // 环绕左上角定位图形的两条边（row 8 / col 8）；
    // (8,6) 与 (6,8) 属于时序图形，已被 placeTiming 预留，这里跳过。
    for (var i = 0; i <= 8; i++) {
      if (!reserved[8][i]) { setModule(grid, reserved, 8, i, false); }
      if (!reserved[i][8]) { setModule(grid, reserved, i, 8, false); }
    }
    // 右上（row 8 右端 8 个）与左下（col 8 下端 8 个，含固定暗模块）
    for (var j = 0; j < 8; j++) {
      setModule(grid, reserved, 8, size - 1 - j, false);
      setModule(grid, reserved, size - 1 - j, 8, false);
    }
    // 固定的暗模块
    setModule(grid, reserved, size - 8, 8, true);
  }

  function placeVersionInfo(grid, reserved, version) {
    var info = VERSION_INFO[version];
    if (info === undefined) { return; }
    var size = grid.length;
    for (var i = 0; i < 18; i++) {
      var bit = ((info >> i) & 1) === 1;
      var row = Math.floor(i / 3);
      var col = size - 11 + (i % 3);
      setModule(grid, reserved, row, col, bit);
      setModule(grid, reserved, col, row, bit);
    }
  }

  function placeData(grid, reserved, codewords) {
    var size = grid.length;
    var bitIndex = 0;
    var totalBits = codewords.length * 8;
    var upward = true;
    for (var col = size - 1; col > 0; col -= 2) {
      if (col === 6) { col--; } // 跳过竖向时序线所在列
      for (var i = 0; i < size; i++) {
        var row = upward ? size - 1 - i : i;
        for (var k = 0; k < 2; k++) {
          var cc = col - k;
          if (reserved[row][cc]) { continue; }
          var dark = false;
          if (bitIndex < totalBits) {
            dark = ((codewords[bitIndex >> 3] >> (7 - (bitIndex & 7))) & 1) === 1;
          }
          grid[row][cc] = dark;
          bitIndex++;
        }
      }
      upward = !upward;
    }
  }

  /* ------------------------------------------------------------------ *
   * 7. 掩码与格式信息
   * ------------------------------------------------------------------ */
  function maskFn(mask, row, col) {
    switch (mask) {
      case 0: return (row + col) % 2 === 0;
      case 1: return row % 2 === 0;
      case 2: return col % 3 === 0;
      case 3: return (row + col) % 3 === 0;
      case 4: return (Math.floor(row / 2) + Math.floor(col / 3)) % 2 === 0;
      case 5: return ((row * col) % 2) + ((row * col) % 3) === 0;
      case 6: return (((row * col) % 2) + ((row * col) % 3)) % 2 === 0;
      case 7: return (((row + col) % 2) + ((row * col) % 3)) % 2 === 0;
      default: return false;
    }
  }

  function applyMask(grid, reserved, mask) {
    var size = grid.length;
    var out = [];
    for (var r = 0; r < size; r++) {
      var row = [];
      for (var c = 0; c < size; c++) {
        var dark = grid[r][c];
        if (!reserved[r][c] && maskFn(mask, r, c)) { dark = !dark; }
        row.push(dark);
      }
      out.push(row);
    }
    return out;
  }

  /** 格式信息：2 bit 纠错级别 + 3 bit 掩码，BCH(15,5) 后异或 0x5412。 */
  function formatBits(mask) {
    var data = (EC_LEVEL_BITS << 3) | mask;
    var value = data << 10;
    for (var i = 4; i >= 0; i--) {
      if ((value >> (10 + i)) & 1) {
        value ^= 0x537 << i;
      }
    }
    return ((data << 10) | value) ^ 0x5412;
  }

  function drawFormat(grid, reserved, mask) {
    var size = grid.length;
    var bits = formatBits(mask);
    // bit(i) 取第 i 位（bit 0 为最低有效位），与 ISO/IEC 18004 的排布一致
    function bit(i) { return ((bits >> i) & 1) === 1; }

    // 第一份：环绕左上角定位图形
    for (var i = 0; i <= 5; i++) {
      setModule(grid, reserved, i, 8, bit(i));
    }
    setModule(grid, reserved, 7, 8, bit(6));
    setModule(grid, reserved, 8, 8, bit(7));
    setModule(grid, reserved, 8, 7, bit(8));
    for (var j = 9; j <= 14; j++) {
      setModule(grid, reserved, 8, 14 - j, bit(j));
    }

    // 第二份：右上角与左下角
    for (var k = 0; k <= 7; k++) {
      setModule(grid, reserved, 8, size - 1 - k, bit(k));
    }
    for (var m = 8; m <= 14; m++) {
      setModule(grid, reserved, size - 15 + m, 8, bit(m));
    }

    // 固定暗模块
    setModule(grid, reserved, size - 8, 8, true);
  }

  /* ------------------------------------------------------------------ *
   * 8. 掩码评分（ISO/IEC 18004 表 11）
   * ------------------------------------------------------------------ */
  function penalty(grid) {
    var size = grid.length;
    var score = 0;
    var r;
    var c;

    // 规则 1：同色连续 >= 5
    for (r = 0; r < size; r++) {
      var runRow = 1;
      for (c = 1; c < size; c++) {
        if (grid[r][c] === grid[r][c - 1]) {
          runRow++;
        } else {
          if (runRow >= 5) { score += 3 + (runRow - 5); }
          runRow = 1;
        }
      }
      if (runRow >= 5) { score += 3 + (runRow - 5); }
    }
    for (c = 0; c < size; c++) {
      var runCol = 1;
      for (r = 1; r < size; r++) {
        if (grid[r][c] === grid[r - 1][c]) {
          runCol++;
        } else {
          if (runCol >= 5) { score += 3 + (runCol - 5); }
          runCol = 1;
        }
      }
      if (runCol >= 5) { score += 3 + (runCol - 5); }
    }

    // 规则 2：2x2 同色块
    for (r = 0; r < size - 1; r++) {
      for (c = 0; c < size - 1; c++) {
        var v = grid[r][c];
        if (v === grid[r][c + 1] && v === grid[r + 1][c] && v === grid[r + 1][c + 1]) {
          score += 3;
        }
      }
    }

    // 规则 3：形如 1:1:3:1:1 且一侧有 4 个空白
    var pattern = [true, false, true, true, true, false, true];
    function matchesAt(get, start, limit) {
      if (start + 7 > limit) { return false; }
      for (var i = 0; i < 7; i++) {
        if (get(start + i) !== pattern[i]) { return false; }
      }
      return true;
    }
    function quietBefore(get, start) {
      for (var i = start - 4; i < start; i++) {
        if (i < 0) { continue; }
        if (get(i)) { return false; }
      }
      return true;
    }
    function quietAfter(get, start, limit) {
      for (var i = start + 7; i < start + 11; i++) {
        if (i >= limit) { continue; }
        if (get(i)) { return false; }
      }
      return true;
    }
    for (r = 0; r < size; r++) {
      (function (row) {
        var get = function (i) { return grid[row][i]; };
        for (var s = 0; s < size; s++) {
          if (matchesAt(get, s, size) && (quietBefore(get, s) || quietAfter(get, s, size))) {
            score += 40;
          }
        }
      })(r);
    }
    for (c = 0; c < size; c++) {
      (function (col) {
        var get = function (i) { return grid[i][col]; };
        for (var s = 0; s < size; s++) {
          if (matchesAt(get, s, size) && (quietBefore(get, s) || quietAfter(get, s, size))) {
            score += 40;
          }
        }
      })(c);
    }

    // 规则 4：暗模块比例偏离 50%
    var dark = 0;
    for (r = 0; r < size; r++) {
      for (c = 0; c < size; c++) {
        if (grid[r][c]) { dark++; }
      }
    }
    var ratio = (dark * 100) / (size * size);
    score += Math.floor(Math.abs(ratio - 50) / 5) * 10;
    return score;
  }

  /* ------------------------------------------------------------------ *
   * 9. 对外接口
   * ------------------------------------------------------------------ */
  /**
   * 生成二维码模块矩阵。
   *
   * @param {string} text   要编码的文本（按 UTF-8 处理）
   * @param {object} [opts] { version: 强制版本 1-10, mask: 强制掩码 0-7 }
   * @returns {{size:number, modules:boolean[][], version:number, mask:number}|null}
   *          内容超出容量时返回 null
   */
  Admin.qrcode = function (text, opts) {
    var options = opts || {};
    var bytes = utf8Bytes(text);
    var version = options.version;
    if (version === undefined || version === null) {
      version = pickVersion(bytes.length);
      if (version === null) { return null; }
    } else {
      if (!VERSIONS[version]) { return null; }
      var need = 4 + countBits(version) + bytes.length * 8;
      if (need > dataCodewords(version) * 8) { return null; }
    }

    var codewords = buildCodewords(bytes, version);
    var size = version * 4 + 17;

    // 功能图形（与掩码无关，构造一次）
    var base = makeGrid(size);
    var reserved = [];
    for (var i = 0; i < size; i++) {
      var row = [];
      for (var j = 0; j < size; j++) { row.push(false); }
      reserved.push(row);
    }
    placeFinder(base, reserved, 0, 0);
    placeFinder(base, reserved, 0, size - 7);
    placeFinder(base, reserved, size - 7, 0);
    placeAlignment(base, reserved, version);
    placeTiming(base, reserved);
    reserveFormatAreas(base, reserved);
    placeVersionInfo(base, reserved, version);
    placeData(base, reserved, codewords);

    var forcedMask = options.mask;
    var best = null;
    var bestMask = -1;
    var bestScore = Infinity;
    var masks = (forcedMask === undefined || forcedMask === null)
      ? [0, 1, 2, 3, 4, 5, 6, 7]
      : [forcedMask];

    for (var m = 0; m < masks.length; m++) {
      var maskId = masks[m];
      var candidate = applyMask(base, reserved, maskId);
      drawFormat(candidate, reserved, maskId);
      var score = penalty(candidate);
      if (score < bestScore) {
        bestScore = score;
        best = candidate;
        bestMask = maskId;
      }
    }

    return { size: size, modules: best, version: version, mask: bestMask };
  };

  /** 判断文本能否放进二维码（默认上限与后端 2KB 约定一致）。 */
  Admin.qrcodeFits = function (text, maxBytes) {
    var bytes = utf8Bytes(text).length;
    if (maxBytes && bytes > maxBytes) { return false; }
    return pickVersion(bytes) !== null;
  };

  /** 渲染到 canvas 元素。 */
  Admin.qrcodeCanvas = function (canvas, text, options) {
    var opts = options || {};
    var result = Admin.qrcode(text, opts);
    if (!result) { return null; }
    var quiet = opts.quiet === undefined ? 4 : opts.quiet;
    var scale = opts.scale || 4;
    var totalModules = result.size + quiet * 2;
    var cssSize = totalModules * scale;
    var dpr = global.devicePixelRatio || 1;

    canvas.width = Math.round(cssSize * dpr);
    canvas.height = Math.round(cssSize * dpr);
    canvas.style.width = cssSize + 'px';
    canvas.style.height = cssSize + 'px';

    var ctx = canvas.getContext('2d');
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.fillStyle = opts.background || '#ffffff';
    ctx.fillRect(0, 0, cssSize, cssSize);
    ctx.fillStyle = opts.foreground || '#000000';
    for (var r = 0; r < result.size; r++) {
      for (var c = 0; c < result.size; c++) {
        if (!result.modules[r][c]) { continue; }
        ctx.fillRect((c + quiet) * scale, (r + quiet) * scale, scale, scale);
      }
    }
    return result;
  };

  /** 返回 data URL（PNG），便于下载或 <img> 展示。 */
  Admin.qrcodeDataUrl = function (text, options) {
    var canvas = document.createElement('canvas');
    var result = Admin.qrcodeCanvas(canvas, text, options);
    if (!result) { return null; }
    return canvas.toDataURL('image/png');
  };

  /** 暴露底层函数，便于离线测试与回归对比。 */
  Admin.qrcodeInternal = {
    utf8Bytes: utf8Bytes,
    pickVersion: pickVersion,
    dataCodewords: dataCodewords,
    buildCodewords: buildCodewords,
    formatBits: formatBits,
    rsEncode: rsEncode,
    versions: VERSIONS
  };
})(window);

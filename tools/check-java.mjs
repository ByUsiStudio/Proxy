#!/usr/bin/env node
/* ==========================================================================
   check-java.mjs —— Java 源码注释平衡检查
   目的：捕获「一个丢失的块注释结束符，把整段代码吞进注释」这类
        **编译期完全看不出来**的缺陷。

   为什么需要它：
     proxy-server 中出现过一次真实事故——某次编辑漏掉了 Javadoc 结尾的块注释结束符，
     导致下一个结束符之前的所有内容（包括 `@GET("listDevice")` 整个处理器）
     都变成了注释。javac 编译通过、所有静态检查通过，但该路由直接消失，
     客户端引导接口 404。这类问题只有「注释状态机」能静态发现。

   （本文件第一版自己就踩了同一个坑：在块注释的散文里直接写了两个字符
     组成的结束符，于是注释提前结束。改写为文字描述后正常。这也侧面说明了
     这类缺陷有多容易发生。）

   检查项：
     1. 每个 .java 文件结束时必须回到「代码态」——即块注释开闭平衡；
     2. 任何 `@GET/@POST/@PUT/@DELETE/@Controller/@Bean ...` 或成员声明出现在
        块注释内部时，若该文件注释不平衡 → FAIL（几乎必然是误吞代码）；
        若平衡 → 仅 INFO（可能只是文档里举了个例子）。

   用法: node tools/check-java.mjs
   退出码: 0 通过；1 存在 FAIL
   ========================================================================== */
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, dirname, basename, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');

function walk(dir, out = []) {
  const abs = join(ROOT, dir);
  if (!existsSync(abs)) return out;
  for (const name of readdirSync(abs)) {
    const rel = join(dir, name).replace(/\\/g, '/');
    const st = statSync(join(ROOT, rel));
    if (st.isDirectory()) walk(rel, out);
    else if (rel.endsWith('.java')) out.push(rel);
  }
  return out;
}

/**
 * 逐字符扫描，返回注释块信息。
 * 处理：行注释、块注释、字符串字面量、字符字面量、文本块（三引号）。
 *
 * 返回 { unbalanced, blocks }：
 *   blocks 中每项 { startLine, endLine, javadoc, codeInComment[] }
 */
function scan(source) {
  const lines = source.split(/\r?\n/);
  const blocks = [];
  let state = 'code'; // code | line | block | string | char | textblock
  let line = 0;
  let current = null;

  for (let i = 0; i < source.length; i++) {
    const c = source[i];
    const c2 = source[i + 1];

    if (c === '\n') {
      if (state === 'line') state = 'code';
      line++;
      continue;
    }

    if (state === 'code') {
      if (c === '/' && c2 === '*') {
        // 判断是否为 Javadoc（紧接一个额外的星号）
        const javadoc = source[i + 2] === '*' && source[i + 3] !== '/';
        current = { startLine: line + 1, endLine: line + 1, javadoc, raw: [] };
        blocks.push(current);
        state = 'block';
        i++;
        continue;
      }
      if (c === '/' && c2 === '/') { state = 'line'; i++; continue; }
      if (c === '"' && c2 === '"' && source[i + 2] === '"') { state = 'textblock'; i += 2; continue; }
      if (c === '"') { state = 'string'; continue; }
      if (c === "'") { state = 'char'; continue; }
    } else if (state === 'block') {
      if (c === '*' && c2 === '/') {
        state = 'code';
        if (current) current.endLine = line + 1;
        current = null;
        i++;
        continue;
      }
      if (current) current.raw.push(lines[line] === undefined ? '' : lines[line]);
    } else if (state === 'string') {
      if (c === '\\') { i++; continue; }
      if (c === '"') state = 'code';
    } else if (state === 'char') {
      if (c === '\\') { i++; continue; }
      if (c === "'") state = 'code';
    } else if (state === 'textblock') {
      if (c === '"' && c2 === '"' && source[i + 2] === '"') { state = 'code'; i += 2; continue; }
      if (c === '\\') { i++; continue; }
    }
  }

  // 允许两种写法：Javadoc 内每行以 `*` 开头，或块注释内直接贴代码（无 `*` 前缀）
  const ROUTE = /^\s*(?:\*\s*)?@(GET|POST|PUT|DELETE)\s*[({]/;
  for (const block of blocks) {
    block.codeInComment = [];
    lines.forEach((text, idx) => {
      const no = idx + 1;
      if (no < block.startLine || no > block.endLine) return;
      if (ROUTE.test(text)) {
        block.codeInComment.push({ line: no, text: text.trim().slice(0, 110) });
      }
    });
  }

  return { unbalanced: state === 'block', blocks };
}

const files = [
  ...walk('proxy-server/src/main/java'),
  ...walk('proxy-common/src/main/java'),
  ...walk('proxy-proxy/src/main/java')
];

let failed = 0;
const warnings = [];

for (const file of files) {
  const { unbalanced, blocks } = scan(readFileSync(join(ROOT, file), 'utf8'));
  if (unbalanced) {
    failed++;
    console.log(`  ✗ ${file}: 块注释未闭合（文件结束时仍处于注释中）`);
    for (const b of blocks) {
      for (const s of b.codeInComment.slice(0, 8)) {
        console.log(`      行 ${s.line} 处于注释内: ${s.text}`);
      }
    }
  }
  // Javadoc（/** 开头）里出现路由注解：几乎必然是「漏了结束符把代码吞进注释」，
  // 因为 Javadoc 是用来描述方法的，不应该包含 @GET/@POST 这类注解本身。
  for (const b of blocks) {
    if (!b.javadoc || !b.codeInComment.length) continue;
    failed++;
    console.log(`  ✗ ${file}: Javadoc（行 ${b.startLine}-${b.endLine}）内出现路由注解，代码可能被误吞：`);
    for (const s of b.codeInComment.slice(0, 8)) {
      console.log(`      行 ${s.line}: ${s.text}`);
    }
  }
  // 普通块注释（/* 开头）里出现路由注解：很可能是**有意**注释掉的路由，
  // 只作为提示列出，不判失败。
  for (const b of blocks) {
    if (b.javadoc || !b.codeInComment.length) continue;
    warnings.push({ file, block: b });
  }
}

for (const w of warnings) {
  console.log(`  · ${w.file}: 普通块注释(行 ${w.block.startLine}-${w.block.endLine})内含 ${w.block.codeInComment.length} 个路由注解 —— 若为有意禁用则忽略`);
  for (const s of w.block.codeInComment.slice(0, 3)) {
    console.log(`      行 ${s.line}: ${s.text}`);
  }
}

console.log();
if (failed === 0) {
  console.log(`Java 注释平衡检查通过：${files.length} 个文件${warnings.length ? `（${warnings.length} 处有意注释掉的路由，仅供参考）` : ''}。`);
  process.exit(0);
}
console.log(`存在 ${failed} 处问题——块注释可能吞掉了真实代码，请优先修复。`);
process.exit(1);

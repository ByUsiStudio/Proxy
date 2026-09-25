/* ==========================================================================
   日志文件页：落盘日志的历史查询与下载
   ---------------------------------------------------------------------------
   相关服务端接口（全部要求控制台会话，见 web/console_logs.go）：
     GET /console/logs/files
          → { dir, file, maxBytes, keep, activeSize, files:[{name,size,modTime,active}] }
     GET /console/logs/tail?file=&lines=&level=&keyword=
          → { file, lines:["[warn] 正文", …], total, truncated }
     GET /console/logs/download?file=
          → 原始文件（附件）
   安全约束：
     1. 绝不把控制台令牌放进 URL —— 一律走 PX.api（自动注入 X-Proxy-Token）；
        下载用 fetch + blob: 对象 URL，与 pages/settings.js 的二维码做法一致。
     2. 文件名只由服务端返回，前端不拼路径；行内容一律 textContent 渲染。
   ========================================================================== */
(function () {
  'use strict';
  var PX = window.PX;

  var LINE_OPTIONS = [200, 500, 2000];
  var LEVELS = ['all', 'debug', 'info', 'warn', 'error'];
  // 与服务端 /console/logs/tail 的 level 参数一致；颜色沿用 .log-line--* 既有样式。
  var LEVEL_CLASS = { debug: 'log-line--debug', info: 'log-line--info', warn: 'log-line--warn', error: 'log-line--error' };

  var state = {
    files: [],
    selected: '',
    level: 'all',
    lines: [],
    loading: false
  };

  var els = {};

  function byId(id) { return document.getElementById(id); }

  function cacheElements() {
    [
      'logDirText', 'refreshFiles', 'logFileRows', 'fileFoot', 'fileSelect',
      'levelSeg', 'keyword', 'lineLimit', 'loadBtn', 'refreshBtn', 'downloadBtn',
      'copyBtn', 'lineCount', 'truncHint', 'logView', 'logFoot'
    ].forEach(function (id) { els[id] = byId(id); });
  }

  /* ---------------------------------------------------------------- *
   * 文件列表
   * ---------------------------------------------------------------- */
  function fileEmptyRow(title, detail) {
    PX.clear(els.logFileRows);
    els.logFileRows.appendChild(PX.el('tr', {}, [
      PX.el('td', { colspan: '5' }, [PX.empty(title, detail, 'archive')])
    ]));
  }

  function renderFiles() {
    PX.clear(els.logFileRows);
    if (!state.files.length) {
      fileEmptyRow('暂无日志文件', '进程还没有写出日志，或日志落盘未启用');
      return;
    }

    var frag = document.createDocumentFragment();
    state.files.forEach(function (file) {
      var name = String(file.name || '');
      var isActive = !!file.active;

      var selectBtn = PX.el('button', {
        type: 'button', class: 'btn btn--subtle btn--sm',
        onclick: function () { selectFile(name, true); }
      }, [PX.iconNode('eye', 'icon--sm'), PX.el('span', { text: '查看' })]);

      var downloadBtn = PX.el('button', {
        type: 'button', class: 'btn btn--ghost btn--sm',
        onclick: function () { downloadFile(name); }
      }, [PX.iconNode('download', 'icon--sm'), PX.el('span', { text: '下载' })]);

      frag.appendChild(PX.el('tr', {}, [
        PX.el('td', { 'data-label': '文件名' }, [
          PX.el('span', { class: 'mono truncate', title: name, text: name })
        ]),
        PX.el('td', { class: 'table__num', 'data-label': '大小', text: PX.fmtBytes(file.size) }),
        PX.el('td', { 'data-label': '修改时间', text: String(file.modTime || '—') }),
        PX.el('td', { class: 'table__num', 'data-label': '状态' }, [
          PX.el('span', {
            class: 'badge ' + (isActive ? 'badge--ok' : 'badge--muted'),
            text: isActive ? '当前' : '历史'
          })
        ]),
        PX.el('td', { class: 'table__actions', 'data-label': '操作' }, [selectBtn, downloadBtn])
      ]));
    });
    els.logFileRows.appendChild(frag);
  }

  function loadFiles() {
    els.fileFoot.textContent = '正在读取日志文件列表…';
    return PX.api.get('/console/logs/files').then(function (payload) {
      var data = payload && typeof payload === 'object' ? payload : {};
      state.files = Array.isArray(data.files) ? data.files : [];

      els.logDirText.textContent = '目录 ' + String(data.dir || '—') +
        ' · 单文件上限 ' + PX.fmtBytes(data.maxBytes) +
        ' · 保留 ' + (Number(data.keep) || 0) + ' 个历史文件';

      var active = state.files.filter(function (f) { return f && f.active; })[0];
      var fallback = active ? active.name : (state.files[0] ? state.files[0].name : '');
      // 保持用户已选文件；若已不存在则回落到当前活动文件。
      var stillThere = state.files.some(function (f) { return f && f.name === state.selected; });
      selectFile(stillThere ? state.selected : fallback, false);

      renderFiles();
      els.fileFoot.textContent = state.files.length
        ? '共 ' + state.files.length + ' 个日志文件 · 更新于 ' + PX.fmtTime()
        : '暂无日志文件';
      return state.files;
    }).catch(function (err) {
      state.files = [];
      fileEmptyRow('日志文件读取失败', (err && err.message) || '请稍后重试');
      els.fileFoot.textContent = '读取失败：' + ((err && err.message) || '未知错误');
      els.logDirText.textContent = '目录信息不可用';
      PX.toastErr((err && err.message) || '日志文件读取失败');
      return [];
    });
  }

  /** 切换当前选中的日志文件；autoQuery 为 true 时立即查询（用户点击行为）。 */
  function selectFile(name, autoQuery) {
    state.selected = String(name || '');
    PX.clear(els.fileSelect);
    if (!state.files.length) {
      els.fileSelect.appendChild(PX.el('option', { value: '', text: '暂无文件' }));
      return;
    }
    state.files.forEach(function (file) {
      var value = String(file.name || '');
      els.fileSelect.appendChild(PX.el('option', {
        value: value,
        text: value + (file.active ? '（当前）' : '')
      }));
    });
    els.fileSelect.value = state.selected;
    if (autoQuery) query();
  }

  /* ---------------------------------------------------------------- *
   * 查询与渲染
   * ---------------------------------------------------------------- */
  /** 从 "[warn] 正文" 里拆出级别与正文；格式由服务端保证，异常时按 info 处理。 */
  function splitLine(line) {
    var text = String(line === null || line === undefined ? '' : line);
    var m = /^\[(debug|info|warn|error)\]\s?([\s\S]*)$/.exec(text);
    if (!m) return { level: 'info', text: text };
    return { level: m[1], text: m[2] };
  }

  function renderLines() {
    PX.clear(els.logView);
    if (!state.lines.length) {
      els.logView.appendChild(PX.empty('没有匹配的日志', '尝试放宽级别或关键词过滤条件', 'terminal'));
      return;
    }
    var frag = document.createDocumentFragment();
    state.lines.forEach(function (raw) {
      var item = splitLine(raw);
      frag.appendChild(PX.el('div', {
        class: 'log-line ' + (LEVEL_CLASS[item.level] || '')
      }, [
        PX.el('span', { class: 'log-line__time', text: '[' + item.level + ']' }),
        PX.el('span', { class: 'log-line__msg', text: item.text })
      ]));
    });
    els.logView.appendChild(frag);
    // 查询历史日志时默认滚到底部，最新的一行在最后。
    els.logView.scrollTop = els.logView.scrollHeight;
  }

  function query() {
    if (!state.selected) {
      PX.toastWarn('请先选择要查询的日志文件');
      return Promise.resolve();
    }
    if (state.loading) return Promise.resolve();
    state.loading = true;
    els.loadBtn.disabled = true;
    els.logFoot.textContent = '正在读取 ' + state.selected + ' 的尾部内容…';

    var params = {
      file: state.selected,
      lines: Number(els.lineLimit.value) || 500,
      level: state.level
    };
    var keyword = String(els.keyword.value || '').trim();
    if (keyword) params.keyword = keyword;

    return PX.api.get('/console/logs/tail', params).then(function (payload) {
      var data = payload && typeof payload === 'object' ? payload : {};
      state.lines = Array.isArray(data.lines) ? data.lines : [];
      renderLines();

      var truncated = !!data.truncated;
      els.lineCount.textContent = '显示 ' + state.lines.length + ' / 扫描 ' + (Number(data.total) || 0) + ' 行';
      els.truncHint.textContent = truncated ? '内容已截断：只返回了文件尾部的一部分' : '';
      els.logFoot.textContent = '文件 ' + state.selected + ' · 共显示 ' + state.lines.length +
        ' 行 · 更新于 ' + PX.fmtTime();
      if (truncated) PX.toastWarn('日志较长，已只返回尾部 ' + state.lines.length + ' 行');
      return data;
    }).catch(function (err) {
      state.lines = [];
      renderLines();
      els.lineCount.textContent = '查询失败';
      els.logFoot.textContent = '查询失败：' + ((err && err.message) || '未知错误');
      PX.toastErr((err && err.message) || '日志查询失败');
      return null;
    }).then(function (data) {
      state.loading = false;
      els.loadBtn.disabled = false;
      return data;
    });
  }

  /* ---------------------------------------------------------------- *
   * 下载 / 复制
   * ---------------------------------------------------------------- */
  /* 下载必须走 fetch + blob:：把令牌放在 X-Proxy-Token 头里，
     绝不能拼成 /console/logs/download?file=x&token=xxx 这种会进历史记录的 URL。 */
  function downloadFile(name) {
    var target = String(name || state.selected || '');
    if (!target) {
      PX.toastWarn('请先选择要下载的日志文件');
      return;
    }
    getToken().then(function (token) {
      var headers = token ? { 'X-Proxy-Token': token } : {};
      return fetch('/console/logs/download?file=' + encodeURIComponent(target), {
        method: 'GET', headers: headers, credentials: 'same-origin', cache: 'no-store'
      });
    }).then(function (res) {
      if (!res.ok) {
        return res.text().then(function (body) {
          var msg = '';
          try { msg = (JSON.parse(body) || {}).Msg || ''; } catch (e) { msg = ''; }
          throw new PX.ApiError(msg || ('下载失败（HTTP ' + res.status + '）'), res.status, null);
        });
      }
      // 优先使用服务端给出的文件名（Last-Modified 等不解析，保持简单）。
      return res.blob().then(function (blob) {
        if (typeof URL === 'undefined' || !URL.createObjectURL) {
          throw new PX.ApiError('当前浏览器不支持直接下载，请改用「复制」', 0, null);
        }
        var url = URL.createObjectURL(blob);
        var link = PX.el('a', { href: url, download: target });
        document.body.appendChild(link);
        link.click();
        setTimeout(function () {
          URL.revokeObjectURL(url);
          link.remove();
        }, 200);
        PX.toastOk('已开始下载 ' + target);
      });
    }).catch(function (err) {
      PX.toastErr((err && err.message) || '日志下载失败');
    });
  }

  function getToken() {
    try { return Promise.resolve(sessionStorage.getItem('px_console_token') || ''); }
    catch (e) { return Promise.resolve(''); }
  }

  function copyLines() {
    if (!state.lines.length) {
      PX.toastWarn('当前没有可复制的日志');
      return;
    }
    PX.copy(state.lines.join('\n'));
  }

  /* ---------------------------------------------------------------- *
   * 装配
   * ---------------------------------------------------------------- */
  function wireLevels() {
    PX.$$('.seg__item', els.levelSeg).forEach(function (btn) {
      btn.addEventListener('click', function () {
        var level = btn.getAttribute('data-level') || 'all';
        if (LEVELS.indexOf(level) === -1) level = 'all';
        state.level = level;
        PX.$$('.seg__item', els.levelSeg).forEach(function (node) {
          var active = node === btn;
          node.classList.toggle('is-active', active);
          node.setAttribute('aria-pressed', active ? 'true' : 'false');
        });
        query();
      });
    });
  }

  function wire() {
    els.refreshFiles.addEventListener('click', function () {
      loadFiles().then(function () {
        PX.toast('已刷新日志文件列表', { type: 'info', timeout: 1600 });
      });
    });
    els.loadBtn.addEventListener('click', function () { query(); });
    els.refreshBtn.addEventListener('click', function () { query(); });
    els.downloadBtn.addEventListener('click', function () { downloadFile(state.selected); });
    els.copyBtn.addEventListener('click', copyLines);

    els.fileSelect.addEventListener('change', function (ev) {
      selectFile(ev.target.value, true);
    });
    els.lineLimit.addEventListener('change', function () { query(); });
    els.keyword.addEventListener('keydown', function (ev) {
      if (ev.key === 'Enter') query();
    });
    els.keyword.addEventListener('input', PX.debounce(function () { query(); }, 400));

    wireLevels();
  }

  function init() {
    var profile = PX.shell.mount({ active: 'logs' });
    if (!profile) return;
    cacheElements();
    PX.applyIcons();
    // 默认按 200 行渲染，避免首屏在弱设备上卡顿；用户可切换到 2000。
    els.lineLimit.value = '500';
    wire();
    loadFiles().then(function (files) {
      if (files && files.length) query();
      else PX.toastWarn('暂无可查询的日志文件');
    });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();

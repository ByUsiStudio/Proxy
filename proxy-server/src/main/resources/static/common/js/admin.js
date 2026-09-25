/* ==========================================================================
   admin.js —— Proxy 内网穿透 管理后台共享运行时（原生 JS，无 jQuery、无 CDN）
   内容：
     1. ICON    —— 内置描边风格 SVG 图标表（新增图标用内联 SVG，不依赖字体/CDN）
     2. theme   —— localStorage 键 px_admin_theme（auto|light|dark）+ <html data-theme>
                    渲染分段控件到 #themeSlot，auto 模式跟随系统
     3. toast   —— 固定 .toast-host 通知（不使用 alert()）
     4. confirm —— Promise<boolean> 确认框（替代 onclick="return confirm(...)"）
     5. dialog  —— 打开/关闭页面内 data-dialog 弹窗
     6. table   —— filterTable / sortTable / 列内搜索
     7. pager   —— 初始化 paging.js（保留原分页参数与路由；仅清理其固定像素内联样式）
     8. nav     —— 依据 location.pathname 标记当前导航项
     9. 事件委托 —— [data-dialog-open] / [data-dialog-close] / [data-confirm] / [data-nav-toggle]
   安全约定：任何服务端数据都只通过 textContent / dataset / 属性赋值写入 DOM，
             本文件不使用 innerHTML 处理服务端数据（仅用于内置图标常量）。
   ========================================================================== */
(function (global) {
  'use strict';

  var Admin = {};

  /* ------------------------------------------------------------------ *
   * 0. 小工具
   * ------------------------------------------------------------------ */
  function $(sel, root) { return (root || document).querySelector(sel); }
  function $$(sel, root) {
    return Array.prototype.slice.call((root || document).querySelectorAll(sel));
  }

  function append(parent, children) {
    if (children === null || children === undefined || children === false) return;
    if (Array.isArray(children)) {
      children.forEach(function (c) { append(parent, c); });
      return;
    }
    if (children instanceof Node) {
      parent.appendChild(children);
      return;
    }
    parent.appendChild(document.createTextNode(String(children)));
  }

  /** el(tag, props, children)：字符串子节点一律以文本插入，不解析 HTML。 */
  Admin.el = function (tag, props, children) {
    var node = document.createElement(tag);
    if (props) {
      Object.keys(props).forEach(function (key) {
        var value = props[key];
        if (value === null || value === undefined || value === false) return;
        if (key === 'class') node.className = value;
        else if (key === 'text') node.textContent = value;
        else if (key === 'icon') node.appendChild(Admin.svgNode(value));
        else if (key === 'dataset') Object.assign(node.dataset, value);
        else if (key === 'style') Object.assign(node.style, value);
        else if (key.indexOf('on') === 0 && typeof value === 'function') {
          node.addEventListener(key.slice(2).toLowerCase(), value);
        } else if (value === true) node.setAttribute(key, '');
        else node.setAttribute(key, String(value));
      });
    }
    append(node, children);
    return node;
  };

  /* ------------------------------------------------------------------ *
   * 1. ICON / AdminIcons 兼容层
   * ------------------------------------------------------------------ */
  var ICON_PATHS = {
    menu: 'M3 6h18M3 12h18M3 18h18',
    close: 'M18 6 6 18M6 6l12 12',
    plus: 'M12 5v14M5 12h14',
    minus: 'M5 12h14',
    refresh: 'M21 12a9 9 0 0 1-9 9 9 9 0 0 1-8.5-6M3 12a9 9 0 0 1 9-9 9 9 0 0 1 8.5 6M21 3v6h-6M3 21v-6h6',
    info: 'M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0M12 16v-4M12 8h.01',
    stop: 'M7 7h10v10H7z',
    trash: 'M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14',
    rocket: 'M12 2c3 3 5 7 5 11l-2 3H9l-2-3c0-4 2-8 5-11zM9 16l-4 5M15 16l4 5',
    server: 'M4 4h16v6H4zM4 14h16v6H4zM8 7h.01M8 17h.01',
    globe: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18M3 12h18M12 3c2.5 2.5 2.5 15 0 18M12 3c-2.5 2.5-2.5 15 0 18',
    sync: 'M21 12a9 9 0 0 1-9 9 9 9 0 0 1-8.5-6M3 12a9 9 0 0 1 9-9 9 9 0 0 1 8.5 6M21 3v6h-6M3 21v-6h6',
    list: 'M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01',
    chart: 'M18 20V10M12 20V4M6 20v-6',
    gear: 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-2.9 1.2 2 2 0 1 1-4 0 1.7 1.7 0 0 0-2.9-1.2l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1A1.7 1.7 0 0 0 4.6 15a2 2 0 1 1 0-4 1.7 1.7 0 0 0 1.2-2.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1A1.7 1.7 0 0 0 11 4.6a2 2 0 1 1 4 0 1.7 1.7 0 0 0 2.9 1.2l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1A1.7 1.7 0 0 0 19.4 11a2 2 0 1 1 0 4z',
    logout: 'M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9',
    search: 'M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16M21 21l-4.3-4.3',
    filter: 'M4 5h16l-6 7v6l-4 2v-8z',
    pause: 'M9 5v14M15 5v14',
    play: 'M7 4l12 8-12 8z',
    download: 'M12 3v12M7 10l5 5 5-5M4 21h16',
    upload: 'M12 21V9M7 14l5-5 5 5M4 3h16',
    copy: 'M9 9h10v10H9zM5 15H4V4h11v1',
    check: 'M20 6 9 17l-5-5',
    warn: 'M12 3 2 20h20L12 3zM12 9v5M12 17h.01',
    danger: 'M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0M15 9l-6 6M9 9l6 6',
    sun: 'M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10M12 1v3M12 20v3M4.2 4.2l2.1 2.1M17.7 17.7l2.1 2.1M1 12h3M20 12h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1',
    moon: 'M21 13A9 9 0 1 1 11 3a7 7 0 0 0 10 10z',
    monitor: 'M3 4h18v12H3zM8 20h8M12 16v4',
    qr: 'M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h2v2h-2zM18 14h2v2h-2zM14 18h2v2h-2zM18 18h2v2h-2z',
    share: 'M4 12v7a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-7M12 3v12M8 7l4-4 4 4',
    key: 'M15 8a4 4 0 1 0-3.6 4L10 13.4V16H7.4L5 18.4V21h3l7-7a4 4 0 0 0 0-6z',
    shield: 'M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z',
    shieldCheck: 'M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10zM9 12l2 2 4-4',
    activity: 'M22 12h-4l-3 8-4-16-3 8H2',
    cpu: 'M4 4h16v16H4zM9 9h6v6H9zM9 1v3M15 1v3M9 20v3M15 20v3M1 9h3M1 15h3M20 9h3M20 15h3',
    link: 'M10 13a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1 1M14 11a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1-1',
    clock: 'M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20M12 6v6l4 2',
    users: 'M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8M23 21v-2a4 4 0 0 0-3-3.9',
    terminal: 'm4 17 6-6-6-6M12 19h8',
    inbox: 'M22 12h-6l-2 3h-4l-2-3H2M5.5 5h13l3.5 7v7H2v-7z',
    zap: 'M13 2 3 14h9l-1 8 10-12h-9z',
    eye: 'M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
    chevron: 'm6 9 6 6 6-6',
    external: 'M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6M15 3h6v6M10 14 21 3',
    arrowDown: 'M12 5v14M19 12l-7 7-7-7',
    arrowUp: 'M12 19V5M5 12l7-7 7 7',
    layers: 'm12 2 9 5-9 5-9-5zM3 12l9 5 9-5M3 17l9 5 9-5',
    box: 'M21 8 12 3 3 8v8l9 5 9-5zM3 8l9 5 9-5M12 13v8',
    uploadCloud: 'M6 18a4 4 0 0 1 .5-8 6 6 0 0 1 11.5 1.5A3.5 3.5 0 0 1 18 18H6M12 21V11M8.5 14.5 12 11l3.5 3.5',
    coins: 'M9 14a5 5 0 1 0 0-10 5 5 0 0 0 0 10M15 20a5 5 0 1 0 0-10 5 5 0 0 0 0 10M4.5 9h9M10.5 15h9'
  };

  /** 返回内置图标 SVG 字符串（只接受内部常量，无注入面）。 */
  Admin.svg = function (name, extraClass) {
    var d = ICON_PATHS[name];
    if (!d) return '';
    return '<svg class="icon' + (extraClass ? ' ' + extraClass : '') +
      '" viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="' + d + '"/></svg>';
  };

  /** 返回 SVG 节点（用于 DOM 构建，避免 innerHTML 与任何外部数据接触）。 */
  Admin.svgNode = function (name, extraClass) {
    var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('class', 'icon' + (extraClass ? ' ' + extraClass : ''));
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('aria-hidden', 'true');
    svg.setAttribute('focusable', 'false');
    var d = ICON_PATHS[name];
    if (d) {
      var path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      path.setAttribute('d', d);
      svg.appendChild(path);
    }
    return svg;
  };

  /**
   * 把页面中 <span data-icon="name"> 占位替换为内联 SVG（仅内置常量）。
   * 旧的 Material Icons 字体写法（<i class="mdui-icon material-icons">menu</i>）
   * 继续由本地 vendored 字体渲染，本函数不做任何处理。
   */
  Admin.applyIcons = function (root) {
    $$('[data-icon]', root || document).forEach(function (node) {
      var name = node.getAttribute('data-icon');
      if (!name || node.getAttribute('data-icon-applied') === '1') return;
      node.textContent = '';
      node.appendChild(Admin.svgNode(name, node.getAttribute('data-icon-class') || ''));
      node.setAttribute('data-icon-applied', '1');
    });
  };

  /* 与旧代码里的 AdminIcons 命名保持兼容。 */
  Admin.AdminIcons = {
    get: function (name, extraClass) { return Admin.svg(name, extraClass); },
    node: function (name, extraClass) { return Admin.svgNode(name, extraClass); },
    apply: function (root) { Admin.applyIcons(root); }
  };

  /* ------------------------------------------------------------------ *
   * 2. 主题：localStorage 键 px_admin_theme（auto|light|dark）
   * ------------------------------------------------------------------ */
  var THEME_KEY = 'px_admin_theme';
  var mediaQuery = global.matchMedia ? global.matchMedia('(prefers-color-scheme: dark)') : null;

  Admin.theme = {
    mode: 'auto',
    modes: [
      { id: 'auto', label: '跟随系统', icon: 'monitor' },
      { id: 'light', label: '浅色', icon: 'sun' },
      { id: 'dark', label: '深色', icon: 'moon' }
    ],
    read: function () {
      var saved = null;
      try { saved = localStorage.getItem(THEME_KEY); } catch (e) { saved = null; }
      return saved === 'light' || saved === 'dark' ? saved : 'auto';
    },
    resolved: function () {
      if (Admin.theme.mode === 'auto') return mediaQuery && mediaQuery.matches ? 'dark' : 'light';
      return Admin.theme.mode;
    },
    apply: function () {
      var resolved = Admin.theme.resolved();
      document.documentElement.setAttribute('data-theme', resolved);
      document.documentElement.style.colorScheme = resolved;
      Admin.theme.syncControl();
    },
    set: function (mode) {
      Admin.theme.mode = (mode === 'light' || mode === 'dark') ? mode : 'auto';
      try { localStorage.setItem(THEME_KEY, Admin.theme.mode); } catch (e) { /* 隐私模式写入失败可忽略 */ }
      Admin.theme.apply();
    },
    init: function () {
      Admin.theme.mode = Admin.theme.read();
      Admin.theme.apply();
      if (mediaQuery) {
        var onChange = function () { if (Admin.theme.mode === 'auto') Admin.theme.apply(); };
        if (mediaQuery.addEventListener) mediaQuery.addEventListener('change', onChange);
        else if (mediaQuery.addListener) mediaQuery.addListener(onChange);
      }
      Admin.theme.mount('#themeSlot');
      return Admin.theme.mode;
    },
    control: function () {
      var seg = Admin.el('div', { class: 'seg', role: 'group', 'aria-label': '主题切换' });
      Admin.theme.modes.forEach(function (m) {
        var active = Admin.theme.mode === m.id;
        var btn = Admin.el('button', {
          type: 'button',
          class: 'seg__item' + (active ? ' is-active' : ''),
          title: m.label,
          'aria-pressed': active ? 'true' : 'false',
          dataset: { themeMode: m.id },
          onclick: function () { Admin.theme.set(m.id); }
        });
        btn.appendChild(Admin.svgNode(m.icon, 'icon--sm'));
        btn.appendChild(Admin.el('span', { class: 'sr-only', text: m.label }));
        seg.appendChild(btn);
      });
      return seg;
    },
    syncControl: function () {
      $$('[data-theme-mode]').forEach(function (node) {
        var active = node.getAttribute('data-theme-mode') === Admin.theme.mode;
        node.classList.toggle('is-active', active);
        node.setAttribute('aria-pressed', active ? 'true' : 'false');
      });
    },
    /** 渲染主题分段控件到指定容器。 */
    mount: function (target) {
      var slot = typeof target === 'string' ? $(target) : target;
      if (!slot || slot.getAttribute('data-theme-mounted') === '1') return;
      slot.textContent = '';
      slot.appendChild(Admin.theme.control());
      slot.setAttribute('data-theme-mounted', '1');
    }
  };

  /* ------------------------------------------------------------------ *
   * 3. Toast
   * ------------------------------------------------------------------ */
  var toastHost = null;

  function ensureToastHost() {
    if (!toastHost || !document.body.contains(toastHost)) {
      toastHost = Admin.el('div', { class: 'toast-host', 'aria-live': 'polite', 'aria-atomic': 'false' });
      document.body.appendChild(toastHost);
    }
    return toastHost;
  }

  Admin.toast = function (message, options) {
    var opts = options || {};
    var type = opts.type || 'info';
    var iconName = { success: 'check', error: 'danger', warning: 'warn', info: 'info' }[type] || 'info';

    var toast = Admin.el('div', { class: 'toast toast--' + type, role: 'status' }, [
      Admin.el('span', { class: 'toast__icon' }, [Admin.svgNode(iconName)]),
      Admin.el('div', { class: 'toast__body' }, [
        opts.title ? Admin.el('div', { class: 'toast__title', text: opts.title }) : null,
        Admin.el('div', { class: 'toast__msg', text: message })
      ]),
      Admin.el('button', {
        type: 'button', class: 'toast__close', 'aria-label': '关闭',
        onclick: function () { remove(); }
      }, [Admin.svgNode('close', 'icon--sm')])
    ]);

    var timer = null;
    function remove() {
      if (timer) clearTimeout(timer);
      toast.classList.add('toast--out');
      setTimeout(function () { if (toast.parentNode) toast.parentNode.removeChild(toast); }, 260);
    }

    ensureToastHost().appendChild(toast);
    var timeout = opts.timeout === undefined ? 3600 : opts.timeout;
    if (timeout > 0) timer = setTimeout(remove, timeout);
    return remove;
  };

  Admin.toastOk = function (m, t) { return Admin.toast(m, { type: 'success', title: t }); };
  Admin.toastErr = function (m, t) { return Admin.toast(m, { type: 'error', title: t, timeout: 6000 }); };
  Admin.toastWarn = function (m, t) { return Admin.toast(m, { type: 'warning', title: t, timeout: 5000 }); };

  /* ------------------------------------------------------------------ *
   * 4. 确认框（Promise<boolean>）
   * ------------------------------------------------------------------ */
  var openModals = [];

  Admin.modal = {
    open: function (modal) {
      if (!modal) return;
      modal.classList.add('is-open');
      modal.removeAttribute('aria-hidden');
      if (openModals.indexOf(modal) === -1) openModals.push(modal);
      var focusable = modal.querySelector('[data-autofocus]') || modal.querySelector('input,select,textarea,button');
      if (focusable) setTimeout(function () { try { focusable.focus(); } catch (e) { /* 忽略 */ } }, 60);
    },
    close: function (modal) {
      if (!modal) return;
      modal.classList.remove('is-open');
      modal.setAttribute('aria-hidden', 'true');
      var idx = openModals.indexOf(modal);
      if (idx >= 0) openModals.splice(idx, 1);
    },
    closeTop: function () {
      if (openModals.length) Admin.modal.close(openModals[openModals.length - 1]);
    }
  };

  document.addEventListener('keydown', function (ev) {
    if (ev.key === 'Escape' && openModals.length) Admin.modal.closeTop();
  });

  Admin.confirm = function (options) {
    var opts = options || {};
    return new Promise(function (resolve) {
      var modal = Admin.el('div', { class: 'modal', role: 'dialog', 'aria-modal': 'true' });
      var body = Admin.el('div', { class: 'modal__body' }, [
        Admin.el('p', { text: opts.message || '确认执行该操作？' }),
        opts.detail ? Admin.el('p', { class: 'muted text-xs mt-2 mono', text: opts.detail }) : null
      ]);
      var okBtn = Admin.el('button', {
        type: 'button',
        class: 'btn ' + (opts.danger ? 'btn--danger' : 'btn--primary'),
        text: opts.okText || '确认'
      });
      var cancelBtn = Admin.el('button', {
        type: 'button', class: 'btn btn--ghost', text: opts.cancelText || '取消'
      });
      var panel = Admin.el('div', { class: 'modal__panel' }, [
        Admin.el('div', { class: 'modal__head' }, [
          Admin.el('h3', { class: 'modal__title', text: opts.title || '请确认' })
        ]),
        body,
        Admin.el('div', { class: 'modal__foot' }, [cancelBtn, okBtn])
      ]);
      modal.appendChild(panel);

      function done(value) {
        Admin.modal.close(modal);
        setTimeout(function () { if (modal.parentNode) modal.parentNode.removeChild(modal); }, 260);
        resolve(value);
      }
      okBtn.addEventListener('click', function () { done(true); });
      cancelBtn.addEventListener('click', function () { done(false); });
      modal.addEventListener('click', function (ev) { if (ev.target === modal) done(false); });

      document.body.appendChild(modal);
      Admin.modal.open(modal);
      setTimeout(function () { try { okBtn.focus(); } catch (e) { /* 忽略 */ } }, 60);
    });
  };

  /* ------------------------------------------------------------------ *
   * 5. 页面内弹窗（data-dialog 打开 / data-dialog-close 关闭）
   * ------------------------------------------------------------------ */
  Admin.dialog = {
    open: function (id) {
      var modal = document.getElementById(String(id));
      if (!modal) return;
      Admin.modal.open(modal);
      var focusable = modal.querySelector('[data-autofocus]') || modal.querySelector('input:not([type=hidden]),select,textarea');
      if (focusable) setTimeout(function () { try { focusable.focus(); } catch (e) { /* 忽略 */ } }, 60);
    },
    close: function (modal) { Admin.modal.close(modal); },
    closeAll: function () { openModals.slice().forEach(Admin.modal.close); }
  };

  /* ------------------------------------------------------------------ *
   * 6. 表格：过滤与排序
   * ------------------------------------------------------------------ */
  /** 按输入框内容过滤表格数据行（textContent 匹配，不解析 HTML）。 */
  Admin.filterTable = function (inputEl, tableEl) {
    var input = typeof inputEl === 'string' ? $(inputEl) : inputEl;
    var table = typeof tableEl === 'string' ? $(tableEl) : tableEl;
    if (!input || !table) return;
    var rows = $$('tbody tr', table);
    var keyword = String(input.value || '').trim().toLowerCase();
    var shown = 0;
    rows.forEach(function (row) {
      if (row.hasAttribute('data-filter-skip')) { row.hidden = false; return; }
      var hit = !keyword || String(row.textContent || '').toLowerCase().indexOf(keyword) !== -1;
      row.hidden = !hit;
      if (hit) shown++;
    });
    var hint = table.getAttribute('data-filter-hint');
    if (hint) {
      var node = document.getElementById(hint);
      if (node) node.textContent = keyword ? ('匹配 ' + shown + ' 行') : '';
    }
    table.setAttribute('data-filtered', keyword ? '1' : '0');
  };

  function cellValue(row, index) {
    var cell = row.children[index];
    if (!cell) return '';
    var input = cell.querySelector('input,select,textarea');
    var raw = input ? String(input.value || '') : String(cell.textContent || '');
    return raw.trim();
  }

  /** 点击表头排序；th 需带 data-sort，data-sort-type=number|text 控制比较方式。 */
  Admin.sortTable = function (th, type) {
    if (!th) return;
    var table = th.closest('table');
    if (!table) return;
    var headers = $$('thead th', table);
    var index = headers.indexOf(th);
    if (index < 0) return;
    var body = table.tBodies[0];
    if (!body) return;

    var current = th.getAttribute('aria-sort');
    var dir = current === 'ascending' ? 'descending' : 'ascending';
    var kind = type || th.getAttribute('data-sort-type') || 'text';

    headers.forEach(function (h) { h.removeAttribute('aria-sort'); });
    th.setAttribute('aria-sort', dir);

    var rows = $$('tbody tr', table).filter(function (r) { return !r.hasAttribute('data-filter-skip'); });
    var factor = dir === 'ascending' ? 1 : -1;

    rows.sort(function (a, b) {
      var av = cellValue(a, index);
      var bv = cellValue(b, index);
      if (kind === 'number') {
        var an = parseFloat(av.replace(/[^\d.\-]/g, ''));
        var bn = parseFloat(bv.replace(/[^\d.\-]/g, ''));
        if (isNaN(an)) an = -Infinity;
        if (isNaN(bn)) bn = -Infinity;
        return (an - bn) * factor;
      }
      return av.localeCompare(bv, 'zh-CN', { numeric: true }) * factor;
    });

    rows.forEach(function (row) { body.appendChild(row); });
  };

  /** 为 [data-sortable="true"] 表格的可排序表头绑定点击排序。 */
  Admin.bindSortableTables = function (root) {
    $$('table[data-sortable="true"]', root || document).forEach(function (table) {
      $$('thead th', table).forEach(function (th) {
        if (th.hasAttribute('data-sort-ignore') || th.querySelector('input,select,button')) return;
        var label = String(th.textContent || '').trim();
        if (!label) return;
        th.classList.add('is-sortable');
        th.setAttribute('role', 'columnheader');
        th.setAttribute('tabindex', '0');
        th.addEventListener('click', function () { Admin.sortTable(th); });
        th.addEventListener('keydown', function (ev) {
          if (ev.key === 'Enter' || ev.key === ' ') {
            ev.preventDefault();
            Admin.sortTable(th);
          }
        });
        var ind = Admin.el('span', { class: 'sort-ind', 'aria-hidden': 'true', text: '↕' });
        th.appendChild(ind);
      });
    });
  };

  /** 为 [data-table-filter] 指定的列内搜索框绑定过滤。 */
  Admin.bindTableFilters = function (root) {
    $$('[data-table-filter]', root || document).forEach(function (input) {
      var table = document.getElementById(input.getAttribute('data-table-filter'));
      if (!table || input.getAttribute('data-filter-bound') === '1') return;
      input.setAttribute('data-filter-bound', '1');
      var handler = function () { Admin.filterTable(input, table); };
      input.addEventListener('input', handler);
      input.addEventListener('search', handler);
      handler();
    });
  };

  /* ------------------------------------------------------------------ *
   * 7. 分页：初始化 paging.js（保留原分页参数与跳转路由）
   * ------------------------------------------------------------------ */
  function cleanPagerInlineStyles(el) {
    // paging.js 会写入固定像素的行内宽度/位移，这里改为交由 CSS 的流式布局处理。
    $$('[style]', el).forEach(function (node) {
      node.style.removeProperty('width');
      node.style.removeProperty('transform');
    });
    el.style.removeProperty('width');
    el.style.removeProperty('height');
  }

  Admin.initPagers = function (root) {
    if (!global.jQuery || typeof global.jQuery.fn.paging !== 'function') return;
    $$('[data-pager-url]', root || document).forEach(function (el) {
      if (el.getAttribute('data-pager-ready') === '1') return;
      var url = el.getAttribute('data-pager-url');
      if (!url) return;
      var page = parseInt(el.getAttribute('data-pager-page') || '1', 10) || 1;
      var total = parseInt(el.getAttribute('data-pager-total') || '1', 10) || 1;
      var param = el.getAttribute('data-pager-param') || '';
      var inputId = el.getAttribute('data-pager-input') || '';
      var extra = '';
      if (param && inputId) {
        var inputEl = document.getElementById(inputId);
        if (inputEl) extra = '&' + param + '=' + encodeURIComponent(String(inputEl.value || ''));
      }
      el.setAttribute('data-pager-ready', '1');
      global.jQuery(el).paging({
        initPageNo: page,
        totalPages: total,
        slideSpeed: 0,
        jump: true,
        callback: function (target) {
          if (target !== page) {
            global.location.href = url + '?page=' + target + extra;
          }
        }
      });
      cleanPagerInlineStyles(el);
    });
  };

  /* ------------------------------------------------------------------ *
   * 8. 导航：按 location.pathname 标记当前项
   * ------------------------------------------------------------------ */
  Admin.initNav = function () {
    var path = (global.location.pathname || '').replace(/\/+$/, '') || '/';
    $$('.nav__link[href], .mdui-list-item[href]').forEach(function (link) {
      var raw = link.getAttribute('href') || '';
      if (!raw || raw.charAt(0) !== '/') return;
      var href = raw.split('?')[0].replace(/\/+$/, '') || '/';
      var active = href === path ||
        (href !== '/admin' && path.indexOf(href + '/') === 0);
      if (active) {
        link.classList.add('is-active');
        link.setAttribute('aria-current', 'page');
      } else {
        link.classList.remove('is-active');
        link.removeAttribute('aria-current');
      }
    });
  };

  Admin.setTitle = function (title) {
    if (!title) return;
    document.title = title + ' · Proxy内网穿透';
    $$('[data-page-title]').forEach(function (node) { node.textContent = title; });
  };

  /* ------------------------------------------------------------------ *
   * 9. 事件委托（抽屉开关 / 弹窗 / 确认）
   * ------------------------------------------------------------------ */
  var drawer = null;
  var scrim = null;

  function closeDrawer() {
    if (drawer) drawer.classList.remove('is-open');
    if (scrim) scrim.classList.remove('is-open');
  }

  function toggleDrawer() {
    if (!drawer) return;
    var open = !drawer.classList.contains('is-open');
    drawer.classList.toggle('is-open', open);
    if (scrim) scrim.classList.toggle('is-open', open);
  }

  document.addEventListener('click', function (ev) {
    var t;

    t = ev.target.closest && ev.target.closest('[data-nav-toggle]');
    if (t) { ev.preventDefault(); toggleDrawer(); return; }

    t = ev.target.closest && ev.target.closest('[data-nav-close]');
    if (t) { ev.preventDefault(); closeDrawer(); return; }

    t = ev.target.closest && ev.target.closest('[data-dialog-open]');
    if (t) {
      ev.preventDefault();
      Admin.dialog.open(t.getAttribute('data-dialog-open'));
      return;
    }

    t = ev.target.closest && ev.target.closest('[data-dialog-close]');
    if (t) {
      ev.preventDefault();
      var owner = t.closest('.modal');
      if (owner) Admin.modal.close(owner);
      return;
    }

    // 链接型危险操作：data-confirm="提示语"，确认后再跳转（href 保持原样）
    t = ev.target.closest && ev.target.closest('a[data-confirm]');
    if (t) {
      ev.preventDefault();
      var href = t.getAttribute('href');
      if (!href) return;
      Admin.confirm({
        title: t.getAttribute('data-confirm-title') || '请确认',
        message: t.getAttribute('data-confirm'),
        detail: t.getAttribute('data-confirm-detail') || '',
        danger: t.getAttribute('data-confirm-danger') !== 'false'
      }).then(function (ok) { if (ok) global.location.href = href; });
      return;
    }

    // 点击模态框背景关闭
    if (ev.target.classList && ev.target.classList.contains('modal')) {
      Admin.modal.close(ev.target);
    }
  });

  // 表单型危险操作：data-confirm="提示语"
  document.addEventListener('submit', function (ev) {
    var form = ev.target;
    if (!form || !form.getAttribute) return;
    var message = form.getAttribute('data-confirm');
    if (!message || form.getAttribute('data-confirmed') === '1') {
      form.removeAttribute('data-confirmed');
      return;
    }
    ev.preventDefault();
    Admin.confirm({
      title: form.getAttribute('data-confirm-title') || '请确认',
      message: message,
      danger: form.getAttribute('data-confirm-danger') !== 'false'
    }).then(function (ok) {
      if (!ok) return;
      form.setAttribute('data-confirmed', '1');
      if (typeof form.requestSubmit === 'function') form.requestSubmit();
      else form.submit();
    });
  }, true);

  /* ------------------------------------------------------------------ *
   * 10. 启动
   * ------------------------------------------------------------------ */
  function boot() {
    Admin.theme.init();
    Admin.applyIcons();
    Admin.initNav();
    Admin.bindTableFilters();
    Admin.bindSortableTables();
    Admin.initPagers();

    drawer = $('#main-drawer') || $('.admin-drawer');
    scrim = $('#drawer-scrim') || $('.scrim');

    var version = $('[data-app-version]');
    if (version) version.textContent = '内网穿透管理后台';
  }

  Admin.boot = boot;

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }

  global.Admin = Admin;
  /* 兼容旧代码里可能引用到的全局图标助手 */
  global.AdminIcons = Admin.AdminIcons;
})(window);

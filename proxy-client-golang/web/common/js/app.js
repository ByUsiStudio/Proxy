/* ==========================================================================
   Proxy 控制台共享运行时 app.js
   设计约束：
   1. 所有动态数据一律通过 textContent / 属性赋值写入 DOM，绝不拼接 HTML，
      从根上消除 XSS。唯一的 innerHTML 用法是常量图标表（内置可信字符串）。
   2. 不使用任何外部 CDN，图标为内联 SVG，可离线运行并兼容严格 CSP。
   3. 所有请求携带 X-Proxy-Token，配合服务端同源校验实现 CSRF 防护。
   ========================================================================== */
(function (global) {
  'use strict';

  var PX = {};

  /* ------------------------------------------------------------------ *
   * 1. 图标表（stroke 风格内联 SVG，避免依赖 Material Icons 字体）
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
    arrowUp: 'M12 19V5M5 12l7-7 7 7'
  };

  /** 返回内置图标 SVG 字符串（仅接受内部常量，不存在注入面）。 */
  PX.icon = function (name, extraClass) {
    var d = ICON_PATHS[name];
    if (!d) return '';
    return (
      '<svg class="icon' + (extraClass ? ' ' + extraClass : '') +
      '" viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="' + d + '"/></svg>'
    );
  };

  PX.iconNode = function (name, extraClass) {
    var span = document.createElement('span');
    span.className = 'icon-slot';
    span.innerHTML = PX.icon(name, extraClass);
    return span.firstChild || span;
  };

  /** 把页面中 <span data-icon="name"> 占位替换为内联 SVG 图标。 */
  PX.applyIcons = function (root) {
    PX.$$('[data-icon]', root || document).forEach(function (node) {
      var name = node.getAttribute('data-icon');
      if (!name || node.getAttribute('data-icon-applied') === '1') return;
      node.innerHTML = PX.icon(name, node.getAttribute('data-icon-class') || '');
      node.setAttribute('data-icon-applied', '1');
    });
  };

  /* ------------------------------------------------------------------ *
   * 2. DOM 工具
   * ------------------------------------------------------------------ */
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

  /**
   * el(tag, props, children)
   * props: class / text / icon / html(仅可信图标) / dataset / style / on* / 其它属性
   * 字符串子节点始终以文本节点插入，不会解析为 HTML。
   */
  PX.el = function (tag, props, children) {
    var node = document.createElement(tag);
    if (props) {
      Object.keys(props).forEach(function (key) {
        var value = props[key];
        if (value === null || value === undefined || value === false) return;
        if (key === 'class') node.className = value;
        else if (key === 'text') node.textContent = value;
        else if (key === 'icon') node.innerHTML = PX.icon(value);
        else if (key === 'html') node.innerHTML = value;
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

  PX.clear = function (node) {
    while (node && node.firstChild) node.removeChild(node.firstChild);
    return node;
  };

  PX.$ = function (sel, root) { return (root || document).querySelector(sel); };
  PX.$$ = function (sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); };

  /* ------------------------------------------------------------------ *
   * 3. 格式化
   * ------------------------------------------------------------------ */
  PX.fmtBytes = function (bytes) {
    var n = Number(bytes) || 0;
    if (n <= 0) return '0 B';
    var units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
    var i = Math.min(Math.floor(Math.log(n) / Math.log(1024)), units.length - 1);
    var v = n / Math.pow(1024, i);
    return (i === 0 ? v.toFixed(0) : v.toFixed(v >= 100 ? 1 : 2)) + ' ' + units[i];
  };

  PX.fmtDuration = function (seconds) {
    var s = Math.max(0, Math.floor(Number(seconds) || 0));
    if (s < 60) return s + ' 秒';
    var m = Math.floor(s / 60);
    if (m < 60) return m + ' 分 ' + (s % 60) + ' 秒';
    var h = Math.floor(m / 60);
    if (h < 24) return h + ' 时 ' + (m % 60) + ' 分';
    return Math.floor(h / 24) + ' 天 ' + (h % 24) + ' 时';
  };

  PX.fmtNum = function (n) {
    return (Number(n) || 0).toLocaleString('zh-CN');
  };

  PX.fmtTime = function (date) {
    var d = date instanceof Date ? date : new Date(date || Date.now());
    function p(v) { return (v < 10 ? '0' : '') + v; }
    return p(d.getHours()) + ':' + p(d.getMinutes()) + ':' + p(d.getSeconds());
  };

  PX.escapeText = function (s) { return String(s === null || s === undefined ? '' : s); };

  /* ------------------------------------------------------------------ *
   * 4. 主题
   * ------------------------------------------------------------------ */
  var THEME_KEY = 'px_theme';
  var mediaQuery = global.matchMedia ? global.matchMedia('(prefers-color-scheme: dark)') : null;

  PX.theme = {
    mode: 'auto',
    modes: [
      { id: 'auto', label: '跟随系统', icon: 'monitor' },
      { id: 'light', label: '浅色', icon: 'sun' },
      { id: 'dark', label: '深色', icon: 'moon' }
    ],
    init: function () {
      var saved = null;
      try { saved = localStorage.getItem(THEME_KEY); } catch (e) { saved = null; }
      PX.theme.mode = saved === 'light' || saved === 'dark' ? saved : 'auto';
      PX.theme.apply();
      if (mediaQuery) {
        var onChange = function () { if (PX.theme.mode === 'auto') PX.theme.apply(); };
        if (mediaQuery.addEventListener) mediaQuery.addEventListener('change', onChange);
        else if (mediaQuery.addListener) mediaQuery.addListener(onChange);
      }
      return PX.theme.mode;
    },
    resolved: function () {
      if (PX.theme.mode === 'auto') return mediaQuery && mediaQuery.matches ? 'dark' : 'light';
      return PX.theme.mode;
    },
    apply: function () {
      var resolved = PX.theme.resolved();
      document.documentElement.setAttribute('data-theme', resolved);
      document.documentElement.style.colorScheme = resolved;
      PX.theme.onChange && PX.theme.onChange(resolved);
    },
    set: function (mode) {
      PX.theme.mode = mode;
      try { localStorage.setItem(THEME_KEY, mode); } catch (e) { /* 忽略隐私模式写入失败 */ }
      PX.theme.apply();
    },
    /** 渲染一个分段控件，用于页面头部。 */
    control: function () {
      var seg = PX.el('div', { class: 'seg', role: 'group', 'aria-label': '主题切换' });
      PX.theme.modes.forEach(function (m) {
        var btn = PX.el('button', {
          type: 'button',
          class: 'seg__item' + (PX.theme.mode === m.id ? ' is-active' : ''),
          title: m.label,
          'aria-pressed': PX.theme.mode === m.id ? 'true' : 'false',
          dataset: { themeMode: m.id },
          onclick: function () {
            PX.theme.set(m.id);
            PX.$$('.seg__item', seg).forEach(function (node) {
              var active = node.dataset.themeMode === m.id;
              node.classList.toggle('is-active', active);
              node.setAttribute('aria-pressed', active ? 'true' : 'false');
            });
          }
        }, [PX.iconNode(m.icon, 'icon--sm'), PX.el('span', { class: 'sr-only', text: m.label })]);
        seg.appendChild(btn);
      });
      return seg;
    }
  };

  /* ------------------------------------------------------------------ *
   * 5. Toast / 确认框
   * ------------------------------------------------------------------ */
  var toastHost = null;

  PX.toast = function (message, options) {
    var opts = options || {};
    var type = opts.type || 'info';
    if (!toastHost) {
      toastHost = PX.el('div', { class: 'toast-host', 'aria-live': 'polite', 'aria-atomic': 'false' });
      document.body.appendChild(toastHost);
    }
    var iconName = { success: 'check', error: 'danger', warning: 'warn', info: 'info' }[type] || 'info';

    var toast = PX.el('div', { class: 'toast toast--' + type, role: 'status' }, [
      PX.el('span', { class: 'toast__icon', icon: iconName }),
      PX.el('div', { class: 'toast__body' }, [
        opts.title ? PX.el('div', { class: 'toast__title', text: opts.title }) : null,
        PX.el('div', { class: 'toast__msg', text: message })
      ]),
      PX.el('button', {
        type: 'button', class: 'toast__close', 'aria-label': '关闭',
        onclick: function () { remove(); }
      }, [PX.iconNode('close', 'icon--sm')])
    ]);

    var timer = null;
    function remove() {
      if (timer) clearTimeout(timer);
      toast.classList.add('toast--out');
      setTimeout(function () { toast.remove(); }, 240);
    }
    toastHost.appendChild(toast);
    var timeout = opts.timeout === undefined ? 3600 : opts.timeout;
    if (timeout > 0) timer = setTimeout(remove, timeout);
    return remove;
  };

  PX.toastOk = function (m, t) { return PX.toast(m, { type: 'success', title: t }); };
  PX.toastErr = function (m, t) { return PX.toast(m, { type: 'error', title: t, timeout: 6000 }); };
  PX.toastWarn = function (m, t) { return PX.toast(m, { type: 'warning', title: t, timeout: 5000 }); };

  /* 模态框基础能力 */
  var openModals = [];

  PX.modal = {
    open: function (modal) {
      modal.classList.add('is-open');
      document.body.style.overflow = 'hidden';
      if (openModals.indexOf(modal) === -1) openModals.push(modal);
      var focusable = modal.querySelector('[data-autofocus]') || modal.querySelector('input,button,select,textarea');
      if (focusable) setTimeout(function () { focusable.focus(); }, 60);
    },
    close: function (modal) {
      modal.classList.remove('is-open');
      var idx = openModals.indexOf(modal);
      if (idx >= 0) openModals.splice(idx, 1);
      if (!openModals.length) document.body.style.overflow = '';
    },
    closeTop: function () {
      if (openModals.length) PX.modal.close(openModals[openModals.length - 1]);
    }
  };

  document.addEventListener('keydown', function (ev) {
    if (ev.key === 'Escape' && openModals.length) {
      var top = openModals[openModals.length - 1];
      if (top.dataset.static !== 'true') PX.modal.close(top);
    }
  });

  /** 统一的确认对话框，替代原生 confirm()。 */
  PX.confirm = function (options) {
    var opts = options || {};
    return new Promise(function (resolve) {
      var modal = PX.el('div', { class: 'modal', role: 'dialog', 'aria-modal': 'true' });
      var body = PX.el('div', { class: 'modal__body' }, [
        PX.el('p', { text: opts.message || '确认执行该操作？' }),
        opts.detail ? PX.el('p', { class: 'muted text-xs mt-2 mono', text: opts.detail }) : null
      ]);
      var okBtn = PX.el('button', {
        type: 'button',
        class: 'btn ' + (opts.danger ? 'btn--danger' : 'btn--primary'),
        text: opts.okText || '确认'
      });
      var cancelBtn = PX.el('button', {
        type: 'button', class: 'btn btn--ghost', text: opts.cancelText || '取消'
      });
      var panel = PX.el('div', { class: 'modal__panel' }, [
        PX.el('div', { class: 'modal__head' }, [PX.el('h3', { class: 'modal__title', text: opts.title || '请确认' })]),
        body,
        PX.el('div', { class: 'modal__foot' }, [cancelBtn, okBtn])
      ]);
      modal.appendChild(panel);

      function done(value) {
        PX.modal.close(modal);
        setTimeout(function () { modal.remove(); }, 240);
        resolve(value);
      }
      okBtn.addEventListener('click', function () { done(true); });
      cancelBtn.addEventListener('click', function () { done(false); });
      modal.addEventListener('click', function (ev) { if (ev.target === modal) done(false); });

      document.body.appendChild(modal);
      PX.modal.open(modal);
      setTimeout(function () { okBtn.focus(); }, 60);
    });
  };

  /* ------------------------------------------------------------------ *
   * 6. 控制台会话与 API
   * ------------------------------------------------------------------ */
  var TOKEN_KEY = 'px_console_token';
  var bootPromise = null;
  var sessionInfo = null;

  function readToken() {
    try { return sessionStorage.getItem(TOKEN_KEY) || ''; } catch (e) { return ''; }
  }

  /**
   * 支持通过 ?token=xxx 访问控制台（远程访问场景）。
   * 读取后立即从地址栏移除，避免令牌残留在历史记录或 Referer 中。
   */
  function captureUrlToken() {
    var search = global.location.search;
    if (!search || search.indexOf('token=') === -1) return;
    var params = new URLSearchParams(search);
    var token = params.get('token');
    if (token) {
      writeToken(token);
      params.delete('token');
      var rest = params.toString();
      try {
        global.history.replaceState(null, '', global.location.pathname + (rest ? '?' + rest : '') + global.location.hash);
      } catch (e) { /* 忽略 */ }
    }
  }

  function writeToken(token) {
    try { sessionStorage.setItem(TOKEN_KEY, token); } catch (e) { /* 忽略 */ }
  }

  PX.ApiError = function (message, status, payload) {
    this.name = 'ApiError';
    this.message = message;
    this.status = status;
    this.payload = payload;
  };
  PX.ApiError.prototype = Object.create(Error.prototype);

  function request(path, options) {
    var opts = options || {};
    var headers = {};
    var token = readToken();
    if (token) headers['X-Proxy-Token'] = token;

    var body;
    if (opts.json !== undefined) {
      headers['Content-Type'] = 'application/json;charset=UTF-8';
      body = JSON.stringify(opts.json);
    } else if (opts.form !== undefined) {
      headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
      var params = new URLSearchParams();
      Object.keys(opts.form).forEach(function (k) {
        var v = opts.form[k];
        if (v !== null && v !== undefined) params.append(k, v);
      });
      body = params.toString();
    }

    var controller = typeof AbortController !== 'undefined' ? new AbortController() : null;
    var timer = null;
    if (controller) timer = setTimeout(function () { controller.abort(); }, opts.timeout || 20000);

    return fetch(path, {
      method: opts.method || 'GET',
      headers: headers,
      body: body,
      credentials: 'same-origin',
      cache: 'no-store',
      signal: controller ? controller.signal : undefined
    }).then(function (res) {
      if (timer) clearTimeout(timer);
      return res.text().then(function (text) {
        var data = null;
        if (text) {
          try { data = JSON.parse(text); } catch (e) { data = text; }
        }
        return { ok: res.ok, status: res.status, data: data };
      });
    }).catch(function (err) {
      if (timer) clearTimeout(timer);
      if (err && err.name === 'AbortError') {
        throw new PX.ApiError('请求超时，请检查网络或云端服务', 0, null);
      }
      throw new PX.ApiError('网络请求失败：' + (err && err.message ? err.message : '未知错误'), 0, null);
    });
  }

  PX.api = {
    /** 原始请求，返回 {ok, status, data}。 */
    raw: request,
    /** 建立/刷新控制台会话。 */
    bootstrap: function (force) {
      if (bootPromise && !force) return bootPromise;
      var token = readToken();
      if (token && !force) {
        sessionInfo = sessionInfo || {};
        bootPromise = Promise.resolve(sessionInfo);
        return bootPromise;
      }
      bootPromise = request('/console/session').then(function (res) {
        if (res.status === 200 && res.data && res.data.Data) {
          writeToken(res.data.Data.token);
          sessionInfo = res.data.Data;
          return sessionInfo;
        }
        throw new PX.ApiError(
          (res.data && (res.data.Msg || res.data.msg)) || '无法建立控制台会话，请从本机打开控制台地址。',
          res.status, res.data
        );
      });
      return bootPromise;
    },
    session: function () { return sessionInfo; },
    /** 带令牌的请求，遇 401 自动重试一次。 */
    send: function (path, options) {
      return PX.api.bootstrap().then(function () {
        return request(path, options);
      }).then(function (res) {
        if (res.status === 401) {
          return PX.api.bootstrap(true).then(function () { return request(path, options); });
        }
        return res;
      });
    },
    /** 期望 JSON 业务响应的 GET，失败时抛出 ApiError。 */
    get: function (path, params) {
      var url = path;
      if (params) {
        var qs = new URLSearchParams();
        Object.keys(params).forEach(function (k) {
          if (params[k] !== null && params[k] !== undefined && params[k] !== '') qs.append(k, params[k]);
        });
        var s = qs.toString();
        if (s) url += (url.indexOf('?') === -1 ? '?' : '&') + s;
      }
      return PX.api.send(url, { method: 'GET' }).then(unwrap);
    },
    post: function (path, form) {
      return PX.api.send(path, { method: 'POST', form: form }).then(unwrap);
    },
    postJson: function (path, obj) {
      return PX.api.send(path, { method: 'POST', json: obj }).then(unwrap);
    }
  };

  function unwrap(res) {
    if (!res.ok) {
      var msg = (res.data && (res.data.Msg || res.data.msg || res.data.message)) || ('请求失败（HTTP ' + res.status + '）');
      throw new PX.ApiError(msg, res.status, res.data);
    }
    return res.data;
  }

  /** 兼容本地接口的大写 Code/Msg 与云端接口的小写 code/msg。 */
  PX.isOk = function (payload) {
    if (!payload || typeof payload !== 'object') return false;
    var code = payload.Code !== undefined ? payload.Code : payload.code;
    return code === 200 || code === 0;
  };

  PX.msgOf = function (payload, fallback) {
    if (payload && typeof payload === 'object') {
      return payload.Msg || payload.msg || payload.message || fallback || '';
    }
    return fallback || '';
  };

  /* ------------------------------------------------------------------ *
   * 7. 账号会话（凭据仅存于会话存储，不落盘）
   * ------------------------------------------------------------------ */
  var PROFILE_KEY = 'px_profile';
  var PASSWORD_KEY = 'px_password';
  var LEGACY_KEY = 'userInfo';

  function safeSessionGet(key) {
    try { return sessionStorage.getItem(key) || ''; } catch (e) { return ''; }
  }
  function safeSessionSet(key, value) {
    try { sessionStorage.setItem(key, value); } catch (e) { /* 忽略 */ }
  }
  function safeSessionRemove(key) {
    try { sessionStorage.removeItem(key); } catch (e) { /* 忽略 */ }
  }
  function safeLocalGet(key) {
    try { return localStorage.getItem(key); } catch (e) { return null; }
  }
  function safeLocalSet(key, value) {
    try { localStorage.setItem(key, value); } catch (e) { /* 忽略 */ }
  }
  function safeLocalRemove(key) {
    try { localStorage.removeItem(key); } catch (e) { /* 忽略 */ }
  }

  PX.user = {
    /** 迁移旧版 localStorage.userInfo：只保留资料，密码移入会话存储。 */
    migrate: function () {
      var legacy = safeLocalGet(LEGACY_KEY);
      if (!legacy) return null;
      try {
        var parsed = JSON.parse(legacy);
        safeLocalSet(PROFILE_KEY, JSON.stringify(parsed));
        if (parsed && parsed.password) safeSessionSet(PASSWORD_KEY, parsed.password);
        safeLocalRemove(LEGACY_KEY);
        return parsed;
      } catch (e) {
        safeLocalRemove(LEGACY_KEY);
        return null;
      }
    },
    profile: function () {
      var raw = safeLocalGet(PROFILE_KEY);
      if (!raw) return null;
      try { return JSON.parse(raw); } catch (e) { return null; }
    },
    password: function () { return safeSessionGet(PASSWORD_KEY); },
    credentials: function () {
      var profile = PX.user.profile();
      if (!profile) return null;
      return { username: profile.username, password: PX.user.password() };
    },
    save: function (data) {
      if (!data) return null;
      var profile = {
        id: data.id,
        username: data.username,
        level: data.level,
        tips: data.tips,
        domains: data.domains,
        ports: data.ports
      };
      safeLocalSet(PROFILE_KEY, JSON.stringify(profile));
      if (data.password) safeSessionSet(PASSWORD_KEY, data.password);
      return profile;
    },
    clear: function () {
      safeLocalRemove(PROFILE_KEY);
      safeSessionRemove(PASSWORD_KEY);
      safeLocalRemove(LEGACY_KEY);
    },
    login: function (username, password) {
      return PX.api.post('/hp/user/login', { username: username, password: password }).then(function (data) {
        if (!PX.isOk(data)) throw new PX.ApiError(PX.msgOf(data, '登录失败'), 200, data);
        PX.user.save(data.data);
        return data.data;
      });
    },
    /** 重新登录以刷新域名/端口配置。 */
    refresh: function () {
      var cred = PX.user.credentials();
      if (!cred || !cred.password) {
        return PX.user.askPassword().then(function () { return PX.user.refresh(); });
      }
      return PX.user.login(cred.username, cred.password);
    },
    /** 会话中缺少密码时向用户索取（用于添加穿透 / 刷新配置）。 */
    askPassword: function () {
      var profile = PX.user.profile();
      if (!profile) return Promise.reject(new PX.ApiError('登录状态已失效，请重新登录', 401, null));
      return new Promise(function (resolve, reject) {
        var input = PX.el('input', {
          class: 'input', type: 'password', placeholder: '请输入账号密码',
          autocomplete: 'current-password', 'data-autofocus': 'true'
        });
        var errText = PX.el('p', { class: 'field__error hidden' });
        var modal = PX.el('div', { class: 'modal', role: 'dialog', 'aria-modal': 'true' });
        var ok = PX.el('button', { type: 'button', class: 'btn btn--primary', text: '验证' });
        var cancel = PX.el('button', { type: 'button', class: 'btn btn--ghost', text: '取消' });
        var panel = PX.el('div', { class: 'modal__panel' }, [
          PX.el('div', { class: 'modal__head' }, [PX.el('h3', { class: 'modal__title', text: '重新验证' })]),
          PX.el('div', { class: 'modal__body' }, [
            PX.el('p', { class: 'muted text-sm', text: '出于安全考虑，密码只保存在当前标签页。请输入 ' + profile.username + ' 的密码继续。' }),
            PX.el('div', { class: 'field mt-4' }, [
              PX.el('label', { class: 'field__label', text: '密码' }),
              input, errText
            ])
          ]),
          PX.el('div', { class: 'modal__foot' }, [cancel, ok])
        ]);
        modal.appendChild(panel);

        function fail(message) {
          errText.textContent = message;
          errText.classList.remove('hidden');
          ok.disabled = false;
          ok.textContent = '验证';
        }
        function finish() {
          PX.modal.close(modal);
          setTimeout(function () { modal.remove(); }, 240);
        }
        function submit() {
          var value = input.value;
          if (!value) { fail('请输入密码'); return; }
          ok.disabled = true;
          ok.textContent = '验证中…';
          PX.user.login(profile.username, value).then(function () {
            finish();
            resolve(true);
          }).catch(function (err) {
            fail(err && err.message ? err.message : '验证失败');
          });
        }
        ok.addEventListener('click', submit);
        cancel.addEventListener('click', function () { finish(); reject(new PX.ApiError('已取消', 0, null)); });
        input.addEventListener('keydown', function (ev) { if (ev.key === 'Enter') submit(); });
        modal.addEventListener('click', function (ev) { if (ev.target === modal) { finish(); reject(new PX.ApiError('已取消', 0, null)); } });

        document.body.appendChild(modal);
        PX.modal.open(modal);
      });
    },
    /** 确保已登录，否则跳转登录页。 */
    require: function () {
      PX.user.migrate();
      var profile = PX.user.profile();
      if (!profile) {
        location.replace('login.html');
        return null;
      }
      return profile;
    },
    logout: function () {
      PX.user.clear();
      location.replace('login.html');
    }
  };

  /* ------------------------------------------------------------------ *
   * 8. 应用外壳（页头 + 侧边导航）
   * ------------------------------------------------------------------ */
  var NAV_GROUPS = [
    {
      label: '穿透控制',
      items: [
        { id: 'center', href: 'center.html', label: '穿透服务', icon: 'rocket' },
        { id: 'port', href: 'port.html', label: '端口管理', icon: 'server' },
        { id: 'domain', href: 'domain.html', label: '域名管理', icon: 'globe' },
        { id: 'autoproxy', href: 'autoproxy.html', label: '自动穿透', icon: 'sync' }
      ]
    },
    {
      label: '运行观测',
      items: [
        { id: 'log', href: 'log.html', label: '运行日志', icon: 'list' },
        { id: 'stats', href: 'stats.html', label: '数据统计', icon: 'chart' }
      ]
    },
    {
      label: '系统',
      items: [
        { id: 'settings', href: 'settings.html', label: '设置与分享', icon: 'gear' }
      ]
    }
  ];

  PX.shell = {
    navGroups: NAV_GROUPS,
    mount: function (options) {
      var opts = options || {};
      var profile = null;
      if (opts.requireUser !== false) {
        profile = PX.user.require();
        if (!profile) return null;
      } else {
        PX.user.migrate();
        profile = PX.user.profile();
      }

      var header = document.getElementById('appHeader');
      var nav = document.getElementById('sideNav');
      var scrim = document.getElementById('scrim');

      if (header) {
        PX.clear(header);
        var menuBtn = PX.el('button', {
          type: 'button', class: 'btn btn--ghost btn--icon', id: 'menuToggle',
          'aria-label': '打开菜单', 'aria-expanded': 'false',
          onclick: function () { toggleNav(); }
        }, [PX.iconNode('menu')]);

        var statusChip = PX.el('span', { class: 'chip chip--dot', id: 'shellStatus', text: '连接中' });

        header.appendChild(menuBtn);
        header.appendChild(PX.el('a', { class: 'brand', href: 'center.html' }, [
          PX.el('span', { class: 'brand__mark', icon: 'shieldCheck' }),
          PX.el('span', {}, [
            PX.el('span', { text: 'Proxy' }),
            PX.el('span', { class: 'brand__sub', id: 'shellVersion', text: ' 控制台' })
          ])
        ]));
        header.appendChild(PX.el('div', { class: 'header__spacer' }));
        header.appendChild(PX.el('div', { class: 'header__actions' }, [
          PX.el('span', { class: 'hide-sm', id: 'shellStatusWrap' }, [statusChip]),
          PX.theme.control(),
          profile ? PX.el('span', { class: 'chip', title: profile.username || '' }, [
            PX.iconNode('users', 'icon--sm'),
            PX.el('span', { class: 'truncate', text: profile.username || '未登录' })
          ]) : null,
          PX.el('button', {
            type: 'button', class: 'btn btn--ghost btn--icon', id: 'login_out',
            'aria-label': '退出登录', title: '退出登录',
            onclick: function () { PX.user.logout(); }
          }, [PX.iconNode('logout')])
        ]));
      }

      if (nav) {
        PX.clear(nav);
        NAV_GROUPS.forEach(function (group) {
          nav.appendChild(PX.el('div', { class: 'side-nav__group', text: group.label }));
          group.items.forEach(function (item) {
            nav.appendChild(PX.el('a', {
              class: 'side-nav__link' + (item.id === opts.active ? ' is-active' : ''),
              href: item.href,
              'aria-current': item.id === opts.active ? 'page' : null
            }, [PX.iconNode(item.icon), PX.el('span', { text: item.label })]));
          });
        });
        nav.appendChild(PX.el('div', { class: 'side-nav__footer' }, [
          PX.el('div', { text: '控制台版本 ' + (opts.version || '16.0') }),
          profile ? PX.el('div', { class: 'mono truncate', text: profile.username || '' }) : null
        ]));
      }

      function setNav(open) {
        if (!nav) return;
        nav.classList.toggle('is-open', open);
        if (scrim) scrim.classList.toggle('is-open', open);
        var btn = document.getElementById('menuToggle');
        if (btn) btn.setAttribute('aria-expanded', open ? 'true' : 'false');
      }
      function toggleNav() { setNav(!(nav && nav.classList.contains('is-open'))); }

      if (scrim) scrim.addEventListener('click', function () { setNav(false); });
      document.addEventListener('keydown', function (ev) { if (ev.key === 'Escape') setNav(false); });
      if (nav) nav.addEventListener('click', function (ev) { if (ev.target.closest('a')) setNav(false); });
      global.addEventListener('resize', function () { if (global.innerWidth >= 992) setNav(false); });

      PX.shell.setStatus = function (text, kind) {
        var chip = document.getElementById('shellStatus');
        if (!chip) return;
        chip.textContent = text;
        chip.className = 'chip chip--dot' + (kind ? ' chip--' + kind : '');
      };

      PX.api.bootstrap().then(function (info) {
        sessionInfo = info || {};
        var versionNode = document.getElementById('shellVersion');
        if (versionNode) versionNode.textContent = ' v' + ((info && info.coreVersion) || '16.0');
        PX.shell.setStatus('控制台已就绪', 'ok');
      }).catch(function (err) {
        PX.shell.setStatus('未授权', 'warn');
        PX.toastErr(err.message || '控制台会话建立失败', '安全提示');
      });

      return profile;
    }
  };

  /* ------------------------------------------------------------------ *
   * 9. WebSocket 日志通道
   * ------------------------------------------------------------------ */
  PX.ws = function (handlers) {
    var onMessage = handlers.onMessage || function () {};
    var onStatus = handlers.onStatus || function () {};
    var socket = null;
    var closedByUser = false;
    var attempt = 0;
    var reconnectTimer = null;

    function url() {
      var proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
      var token = readToken();
      return proto + location.host + '/ws' + (token ? '?token=' + encodeURIComponent(token) : '');
    }

    function connect() {
      if (closedByUser) return;
      try {
        socket = new WebSocket(url());
      } catch (e) {
        schedule();
        return;
      }
      socket.addEventListener('open', function () {
        attempt = 0;
        onStatus('open');
      });
      socket.addEventListener('message', function (ev) {
        var payload = null;
        try { payload = JSON.parse(ev.data); } catch (e) { payload = { msg: ev.data, domain: 'system', level: 'info' }; }
        onMessage(payload);
      });
      socket.addEventListener('close', function () {
        onStatus('closed');
        schedule();
      });
      socket.addEventListener('error', function () {
        onStatus('error');
      });
    }

    function schedule() {
      if (closedByUser || reconnectTimer) return;
      attempt += 1;
      var delay = Math.min(1000 * Math.pow(1.6, Math.min(attempt, 6)), 15000);
      reconnectTimer = setTimeout(function () {
        reconnectTimer = null;
        connect();
      }, delay);
    }

    connect();

    return {
      close: function () {
        closedByUser = true;
        if (reconnectTimer) clearTimeout(reconnectTimer);
        if (socket) socket.close();
      },
      send: function (text) {
        if (socket && socket.readyState === WebSocket.OPEN) socket.send(text);
      },
      isOpen: function () { return socket && socket.readyState === WebSocket.OPEN; }
    };
  };

  /* ------------------------------------------------------------------ *
   * 10. 日志控制台组件（搜索 / 级别过滤 / 暂停 / 导出）
   * ------------------------------------------------------------------ */
  PX.logConsole = function (config) {
    var view = config.view;
    var limit = config.limit || 600;
    var entries = [];
    var domains = {};
    var paused = false;
    var pending = 0;
    var state = { level: 'all', domain: 'all', keyword: '' };
    var frame = null;
    var listeners = [];

    function matches(entry) {
      if (state.level !== 'all' && (entry.level || 'info') !== state.level) return false;
      if (state.domain !== 'all' && (entry.domain || 'system') !== state.domain) return false;
      if (state.keyword) {
        var hay = ((entry.domain || '') + ' ' + (entry.msg || '')).toLowerCase();
        if (hay.indexOf(state.keyword) === -1) return false;
      }
      return true;
    }

    function line(entry) {
      return PX.el('div', { class: 'log-line log-line--' + (entry.level || 'info') }, [
        PX.el('span', { class: 'log-line__time', text: entry.time || PX.fmtTime() }),
        PX.el('span', { class: 'log-line__domain', title: entry.domain || 'system', text: entry.domain || 'system' }),
        PX.el('span', { class: 'log-line__msg', text: entry.msg || '' })
      ]);
    }

    function render() {
      frame = null;
      var visible = entries.filter(matches);
      var atBottom = view.scrollTop + view.clientHeight >= view.scrollHeight - 24;
      PX.clear(view);
      if (!visible.length) {
        view.appendChild(PX.el('div', { class: 'empty' }, [
          PX.el('span', { icon: 'terminal' }),
          PX.el('div', { class: 'empty__title', text: entries.length ? '没有匹配的日志' : '等待日志输出…' }),
          PX.el('div', { class: 'text-xs faint', text: entries.length ? '尝试调整过滤条件' : '隧道产生的事件会实时显示在这里' })
        ]));
      } else {
        var frag = document.createDocumentFragment();
        visible.slice(-limit).forEach(function (entry) { frag.appendChild(line(entry)); });
        view.appendChild(frag);
      }
      if (atBottom) view.scrollTop = view.scrollHeight;
      listeners.forEach(function (fn) { fn({ total: entries.length, visible: visible.length, paused: paused }); });
    }

    function schedule() {
      if (frame) return;
      frame = requestAnimationFrame(render);
    }

    function push(entry) {
      var item = {
        domain: entry.domain || entry.Domain || 'system',
        msg: entry.msg || entry.Msg || '',
        level: (entry.level || entry.Level || 'info').toLowerCase(),
        time: entry.time || entry.Time || PX.fmtTime()
      };
      if (item.domain && !domains[item.domain]) {
        domains[item.domain] = true;
        listeners.forEach(function (fn) { fn({ domainAdded: item.domain }); });
      }
      if (paused) {
        pending += 1;
        return;
      }
      entries.push(item);
      if (entries.length > limit) entries.splice(0, entries.length - limit);
      schedule();
    }

    return {
      push: push,
      onChange: function (fn) { listeners.push(fn); },
      setLevel: function (level) { state.level = level; schedule(); },
      setDomain: function (domain) { state.domain = domain; schedule(); },
      setKeyword: function (keyword) { state.keyword = (keyword || '').trim().toLowerCase(); schedule(); },
      state: function () { return { level: state.level, domain: state.domain, keyword: state.keyword, paused: paused, pending: pending }; },
      togglePause: function () {
        paused = !paused;
        if (!paused) {
          entries = entries.concat([]); // 保持位置
          pending = 0;
          // 暂停期间丢弃的条目不再补播，避免刷屏
        }
        schedule();
        return paused;
      },
      clear: function () { entries = []; pending = 0; schedule(); },
      entries: function () { return entries.slice(); },
      export: function (format) {
        var list = entries.filter(matches);
        if (format === 'json') {
          return JSON.stringify(list, null, 2);
        }
        return list.map(function (e) {
          return '[' + e.time + '] [' + (e.level || 'info') + '] [' + e.domain + '] ' + e.msg;
        }).join('\n');
      }
    };
  };

  /* ------------------------------------------------------------------ *
   * 11. 轻量图表（Canvas，无外部依赖）
   * ------------------------------------------------------------------ */
  function cssVar(name, fallback) {
    var v = getComputedStyle(document.documentElement).getPropertyValue(name);
    return (v && v.trim()) || fallback;
  }

  PX.chart = {
    palette: ['#2f6bff', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#0ea5e9'],
    /**
     * render(canvas, spec)
     * spec: { type:'line'|'bar', labels:[], series:[{name,data,color,fill}], yFormat }
     */
    render: function (canvas, spec) {
      if (!canvas) return;
      var ctx = canvas.getContext('2d');
      var dpr = global.devicePixelRatio || 1;
      var rect = canvas.getBoundingClientRect();
      var width = Math.max(rect.width, 160);
      var height = Math.max(rect.height, 120);
      canvas.width = Math.round(width * dpr);
      canvas.height = Math.round(height * dpr);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.clearRect(0, 0, width, height);

      var border = cssVar('--border', '#e2e8f2');
      var textFaint = cssVar('--text-faint', '#94a3b8');
      var textMuted = cssVar('--text-muted', '#64748b');
      var surface = cssVar('--bg-elevated', '#fff');
      var fmt = spec.yFormat || function (v) { return String(v); };

      var labels = spec.labels || [];
      var series = (spec.series || []).filter(function (s) { return s && s.data; });
      if (!series.length || !labels.length) {
        ctx.fillStyle = textFaint;
        ctx.font = '12px ' + cssVar('--font', 'sans-serif');
        ctx.textAlign = 'center';
        ctx.fillText('暂无数据', width / 2, height / 2);
        return;
      }

      var padding = { top: 12, right: 12, bottom: 26, left: 48 };
      var plotW = Math.max(width - padding.left - padding.right, 10);
      var plotH = Math.max(height - padding.top - padding.bottom, 10);

      var max = 0;
      series.forEach(function (s) {
        s.data.forEach(function (v) { if (v > max) max = v; });
      });
      if (max <= 0) max = 1;
      var niceMax = max;
      var magnitude = Math.pow(10, Math.floor(Math.log(niceMax) / Math.LN10));
      niceMax = Math.ceil(niceMax / (magnitude / 2)) * (magnitude / 2);
      if (niceMax <= 0) niceMax = 1;

      // 网格 + Y 轴
      ctx.strokeStyle = border;
      ctx.fillStyle = textFaint;
      ctx.lineWidth = 1;
      ctx.font = '11px ' + cssVar('--font', 'sans-serif');
      ctx.textAlign = 'right';
      ctx.textBaseline = 'middle';
      var ticks = 4;
      for (var i = 0; i <= ticks; i++) {
        var value = (niceMax / ticks) * i;
        var y = padding.top + plotH - (plotH * (value / niceMax));
        ctx.beginPath();
        ctx.moveTo(padding.left, Math.round(y) + 0.5);
        ctx.lineTo(padding.left + plotW, Math.round(y) + 0.5);
        ctx.stroke();
        ctx.fillText(fmt(value), padding.left - 6, y);
      }

      // X 轴标签（按需抽样）
      ctx.textAlign = 'center';
      ctx.textBaseline = 'top';
      var step = Math.ceil(labels.length / Math.max(2, Math.floor(plotW / 64)));
      labels.forEach(function (label, index) {
        if (index % step !== 0 && index !== labels.length - 1) return;
        var x = labels.length === 1
          ? padding.left + plotW / 2
          : padding.left + (plotW * index) / (labels.length - 1);
        ctx.fillText(String(label), x, padding.top + plotH + 8);
      });

      function xAt(index) {
        if (labels.length === 1) return padding.left + plotW / 2;
        return padding.left + (plotW * index) / (labels.length - 1);
      }
      function yAt(value) {
        return padding.top + plotH - (plotH * (value / niceMax));
      }

      if (spec.type === 'bar') {
        var groupW = plotW / Math.max(labels.length, 1);
        var barW = Math.max(2, (groupW * 0.62) / series.length);
        series.forEach(function (s, si) {
          ctx.fillStyle = s.color || PX.chart.palette[si % PX.chart.palette.length];
          s.data.forEach(function (v, i) {
            var x = padding.left + groupW * i + groupW * 0.19 + barW * si;
            var y = yAt(v);
            var h = Math.max(padding.top + plotH - y, v > 0 ? 2 : 0);
            ctx.beginPath();
            var r = Math.min(3, barW / 2);
            ctx.moveTo(x, y + h);
            ctx.lineTo(x, y + r);
            ctx.quadraticCurveTo(x, y, x + r, y);
            ctx.lineTo(x + barW - r, y);
            ctx.quadraticCurveTo(x + barW, y, x + barW, y + r);
            ctx.lineTo(x + barW, y + h);
            ctx.closePath();
            ctx.fill();
          });
        });
      } else {
        series.forEach(function (s, si) {
          var color = s.color || PX.chart.palette[si % PX.chart.palette.length];
          ctx.beginPath();
          s.data.forEach(function (v, i) {
            var x = xAt(i);
            var y = yAt(v);
            if (i === 0) ctx.moveTo(x, y);
            else ctx.lineTo(x, y);
          });
          ctx.strokeStyle = color;
          ctx.lineWidth = 2;
          ctx.lineJoin = 'round';
          ctx.stroke();

          if (s.fill !== false) {
            ctx.lineTo(xAt(s.data.length - 1), padding.top + plotH);
            ctx.lineTo(xAt(0), padding.top + plotH);
            ctx.closePath();
            var gradient = ctx.createLinearGradient(0, padding.top, 0, padding.top + plotH);
            gradient.addColorStop(0, color + '44');
            gradient.addColorStop(1, color + '05');
            ctx.fillStyle = gradient;
            ctx.fill();
          }

          ctx.fillStyle = surface;
          ctx.strokeStyle = color;
          s.data.forEach(function (v, i) {
            if (s.data.length > 40 && i % 2 !== 0) return;
            ctx.beginPath();
            ctx.arc(xAt(i), yAt(v), 2.4, 0, Math.PI * 2);
            ctx.fill();
            ctx.stroke();
          });
        });
      }

      // 轴线
      ctx.strokeStyle = border;
      ctx.beginPath();
      ctx.moveTo(padding.left, padding.top);
      ctx.lineTo(padding.left, padding.top + plotH);
      ctx.lineTo(padding.left + plotW, padding.top + plotH);
      ctx.stroke();

      if (spec.hint) {
        ctx.fillStyle = textMuted;
        ctx.textAlign = 'left';
        ctx.textBaseline = 'top';
        ctx.font = '11px ' + cssVar('--font', 'sans-serif');
        ctx.fillText(spec.hint, padding.left + 4, padding.top + 2);
      }
    },
    /** 绑定尺寸/主题变化自动重绘。 */
    auto: function (canvas, getSpec) {
      var redraw = function () { PX.chart.render(canvas, getSpec()); };
      if (global.ResizeObserver) {
        var ro = new ResizeObserver(function () { redraw(); });
        ro.observe(canvas);
      } else {
        global.addEventListener('resize', redraw);
      }
      var previous = PX.theme.onChange;
      PX.theme.onChange = function (resolved) {
        if (previous) previous(resolved);
        redraw();
      };
      redraw();
      return redraw;
    },
    legend: function (container, series) {
      PX.clear(container);
      series.forEach(function (s, i) {
        container.appendChild(PX.el('span', { class: 'legend__item' }, [
          PX.el('span', {
            class: 'legend__swatch',
            style: { color: s.color || PX.chart.palette[i % PX.chart.palette.length] }
          }),
          PX.el('span', { text: s.name })
        ]));
      });
    }
  };

  /* ------------------------------------------------------------------ *
   * 12. 表格排序 / 下载 / 复制
   * ------------------------------------------------------------------ */
  PX.sortRows = function (rows, key, direction, type) {
    var factor = direction === 'desc' ? -1 : 1;
    return rows.slice().sort(function (a, b) {
      var av = a[key];
      var bv = b[key];
      if (type === 'number') {
        av = Number(av) || 0;
        bv = Number(bv) || 0;
        return (av - bv) * factor;
      }
      if (type === 'boolean') {
        return ((av ? 1 : 0) - (bv ? 1 : 0)) * factor;
      }
      return String(av === undefined || av === null ? '' : av)
        .localeCompare(String(bv === undefined || bv === null ? '' : bv), 'zh-CN') * factor;
    });
  };

  PX.filterRows = function (rows, keyword, fields) {
    var kw = (keyword || '').trim().toLowerCase();
    if (!kw) return rows;
    return rows.filter(function (row) {
      return fields.some(function (field) {
        var value = row[field];
        return value !== undefined && value !== null && String(value).toLowerCase().indexOf(kw) !== -1;
      });
    });
  };

  PX.download = function (filename, content, mime) {
    var blob = new Blob([content], { type: mime || 'text/plain;charset=utf-8' });
    var url = URL.createObjectURL(blob);
    var link = PX.el('a', { href: url, download: filename });
    document.body.appendChild(link);
    link.click();
    setTimeout(function () {
      URL.revokeObjectURL(url);
      link.remove();
    }, 200);
  };

  PX.copy = function (text) {
    if (navigator.clipboard && global.isSecureContext) {
      return navigator.clipboard.writeText(text).then(function () {
        PX.toastOk('已复制到剪贴板');
      }).catch(function () {
        return PX.copyFallback(text);
      });
    }
    return PX.copyFallback(text);
  };

  PX.copyFallback = function (text) {
    var area = PX.el('textarea', { class: 'sr-only' });
    area.value = text;
    document.body.appendChild(area);
    area.select();
    var ok = false;
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
    area.remove();
    if (ok) PX.toastOk('已复制到剪贴板');
    else PX.toastWarn('当前环境不支持自动复制，请手动选择文本');
    return Promise.resolve(ok);
  };

  /* ------------------------------------------------------------------ *
   * 13. 图标按钮 / 空状态
   * ------------------------------------------------------------------ */
  PX.iconButton = function (name, label, options) {
    var opts = options || {};
    return PX.el('button', {
      type: 'button',
      class: 'btn ' + (opts.variant ? 'btn--' + opts.variant : 'btn--ghost') + (opts.small ? ' btn--sm' : ' btn--icon'),
      title: label,
      'aria-label': label,
      onclick: opts.onClick
    }, [PX.iconNode(name, opts.small ? 'icon--sm' : null), opts.small ? PX.el('span', { text: label }) : null]);
  };

  PX.empty = function (title, detail, icon) {
    return PX.el('div', { class: 'empty' }, [
      PX.el('span', { icon: icon || 'inbox' }),
      PX.el('div', { class: 'empty__title', text: title }),
      detail ? PX.el('div', { class: 'text-xs', text: detail }) : null
    ]);
  };

  PX.debounce = function (fn, wait) {
    var timer = null;
    return function () {
      var args = arguments;
      var self = this;
      if (timer) clearTimeout(timer);
      timer = setTimeout(function () { fn.apply(self, args); }, wait || 200);
    };
  };

  /* ------------------------------------------------------------------ *
   * 14. 启动
   * ------------------------------------------------------------------ */
  PX.theme.init();
  captureUrlToken();

  document.addEventListener('DOMContentLoaded', function () {
    PX.user.migrate();
    PX.applyIcons();
  });

  global.PX = PX;
})(window);

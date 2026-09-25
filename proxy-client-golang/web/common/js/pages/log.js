/* 运行日志页：WebSocket 实时通道 + 级别/域名/关键词过滤 + 暂停、清空、导出、复制、心跳探测 */
(function () {
  'use strict';
  var PX = window.PX;

  var LOG_LIMIT = 800;
  var LEVELS = ['all', 'debug', 'info', 'warn', 'error'];

  var state = {
    logs: null,
    channel: null,
    paused: false,
    buffered: 0,
    seenDomains: Object.create(null),
    meta: { total: 0, visible: 0 },
    metaFrame: null
  };

  var els = {};

  function byId(id) { return document.getElementById(id); }

  function cacheElements() {
    [
      'channelChip', 'levelSeg', 'domainFilter', 'keyword', 'pauseBtn', 'pauseIcon',
      'pauseLabel', 'bufferBadge', 'exportTxt', 'exportJson', 'copyBtn', 'clearBtn',
      'heartbeat', 'heartbeatBtn', 'lineCount', 'logView', 'logFoot'
    ].forEach(function (id) { els[id] = byId(id); });
  }

  /* ---------------------------------------------------------------- *
   * 通用小工具
   * ---------------------------------------------------------------- */
  /** 切换内置图标（仅使用 PX.icon 常量表，不拼接任何运行时字符串）。 */
  function setIcon(node, name) {
    if (!node) return;
    node.setAttribute('data-icon', name);
    node.removeAttribute('data-icon-applied');
    PX.clear(node);
    PX.applyIcons(node.parentNode || node);
  }

  function setChannelChip(text, kind) {
    els.channelChip.textContent = text;
    els.channelChip.className = 'chip chip--dot' + (kind ? ' chip--' + kind : '');
  }

  /* ---------------------------------------------------------------- *
   * 计数与状态文案
   * ---------------------------------------------------------------- */
  function pendingLines() {
    var snapshot = state.logs && state.logs.state ? state.logs.state() : null;
    if (snapshot && typeof snapshot.pending === 'number' && snapshot.pending > state.buffered) {
      return snapshot.pending;
    }
    return state.buffered;
  }

  function scheduleMeta() {
    if (state.metaFrame) return;
    state.metaFrame = requestAnimationFrame(updateMeta);
  }

  function updateMeta() {
    state.metaFrame = null;
    els.lineCount.textContent = '显示 ' + state.meta.visible + ' / 共 ' + state.meta.total + ' 行';

    if (state.meta.total) {
      els.logFoot.textContent = '已接收 ' + state.meta.total + ' 行 · 当前显示 ' +
        state.meta.visible + ' 行 · 最近更新 ' + PX.fmtTime();
    } else {
      els.logFoot.textContent = state.paused ? '已暂停，暂停期间的新日志会被丢弃' : '等待日志输出…';
    }

    if (state.paused) {
      els.bufferBadge.textContent = '已丢弃 ' + pendingLines() + ' 行';
      els.bufferBadge.classList.remove('hidden');
    } else {
      els.bufferBadge.classList.add('hidden');
    }
  }

  /* ---------------------------------------------------------------- *
   * 域名下拉
   * ---------------------------------------------------------------- */
  function addDomainOption(domain) {
    var name = String(domain || '');
    if (!name || state.seenDomains[name]) return;
    state.seenDomains[name] = true;
    els.domainFilter.appendChild(PX.el('option', { value: name, text: name }));
  }

  /* ---------------------------------------------------------------- *
   * 实时通道
   * ---------------------------------------------------------------- */
  function handleMessage(entry) {
    state.logs.push(entry);
    if (state.paused) state.buffered += 1;
    scheduleMeta();
  }

  function startChannel() {
    state.channel = PX.ws({
      onMessage: handleMessage,
      onStatus: function (status) {
        if (status === 'open') {
          setChannelChip('实时通道已连接', 'ok');
          PX.shell.setStatus('实时通道已连接', 'ok');
        } else if (status === 'closed') {
          setChannelChip('实时通道重连中', 'warn');
          PX.shell.setStatus('实时通道重连中', 'warn');
        } else {
          setChannelChip('实时通道异常', 'warn');
          PX.shell.setStatus('实时通道异常', 'warn');
        }
      }
    });
  }

  /* ---------------------------------------------------------------- *
   * 操作
   * ---------------------------------------------------------------- */
  function togglePause() {
    state.paused = state.logs.togglePause();
    els.pauseLabel.textContent = state.paused ? '继续' : '暂停';
    els.pauseBtn.setAttribute('aria-pressed', state.paused ? 'true' : 'false');
    els.pauseBtn.title = state.paused ? '恢复实时日志渲染' : '暂停日志渲染，暂停期间的新日志会被丢弃';
    setIcon(els.pauseIcon, state.paused ? 'play' : 'pause');
    if (!state.paused) state.buffered = 0;
    updateMeta();
    PX.toast(state.paused ? '已暂停日志渲染，暂停期间的新日志会被丢弃' : '已恢复实时日志', {
      type: 'info', timeout: 1800
    });
  }

  function clearLogs() {
    state.logs.clear();
    state.buffered = 0;
    state.meta.total = 0;
    state.meta.visible = 0;
    scheduleMeta();
    PX.toast('已清空当前日志缓冲区', { type: 'info', timeout: 1800 });
  }

  function exportLogs(format) {
    if (!state.meta.visible) {
      PX.toastWarn('当前没有可导出的日志');
      return;
    }
    var isJson = format === 'json';
    var stamp = new Date().toISOString().replace(/[:T]/g, '-').slice(0, 19);
    PX.download(
      'proxy-logs-' + stamp + (isJson ? '.json' : '.txt'),
      state.logs.export(isJson ? 'json' : 'txt'),
      isJson ? 'application/json;charset=utf-8' : 'text/plain;charset=utf-8'
    );
    PX.toastOk('已导出 ' + state.meta.visible + ' 行日志');
  }

  function copyLogs() {
    if (!state.meta.visible) {
      PX.toastWarn('当前没有可复制的日志');
      return;
    }
    PX.copy(state.logs.export('txt'));
  }

  function sendHeartbeat() {
    var payload = String(els.heartbeat.value || '').trim() || 'ping';
    if (!state.channel || !state.channel.isOpen()) {
      PX.toastWarn('实时通道未连接，心跳发送失败');
      return;
    }
    if (payload !== 'ping') {
      payload = 'ping';
      els.heartbeat.value = 'ping';
      PX.toastWarn('服务端仅回显 ping 心跳，已按默认内容发送');
    }
    state.channel.send(payload);
    PX.toast('心跳已发送，等待服务端 pong 回应', { type: 'info', timeout: 2200 });
  }

  /* ---------------------------------------------------------------- *
   * 装配
   * ---------------------------------------------------------------- */
  function wireLevels() {
    PX.$$('.seg__item', els.levelSeg).forEach(function (btn) {
      btn.addEventListener('click', function () {
        var level = btn.getAttribute('data-level') || 'all';
        if (LEVELS.indexOf(level) === -1) level = 'all';
        PX.$$('.seg__item', els.levelSeg).forEach(function (node) {
          var active = node === btn;
          node.classList.toggle('is-active', active);
          node.setAttribute('aria-pressed', active ? 'true' : 'false');
        });
        state.logs.setLevel(level);
      });
    });
  }

  function wireToolbar() {
    els.pauseBtn.addEventListener('click', togglePause);
    els.clearBtn.addEventListener('click', clearLogs);
    els.exportTxt.addEventListener('click', function () { exportLogs('txt'); });
    els.exportJson.addEventListener('click', function () { exportLogs('json'); });
    els.copyBtn.addEventListener('click', copyLogs);
    els.heartbeatBtn.addEventListener('click', sendHeartbeat);
    els.heartbeat.addEventListener('keydown', function (ev) {
      if (ev.key === 'Enter') sendHeartbeat();
    });

    els.domainFilter.addEventListener('change', function (ev) {
      state.logs.setDomain(ev.target.value || 'all');
    });

    els.keyword.addEventListener('input', PX.debounce(function (ev) {
      state.logs.setKeyword(ev.target.value);
    }, 200));

    wireLevels();

    window.addEventListener('pagehide', function () {
      if (state.channel) state.channel.close();
    });
  }

  function init() {
    var profile = PX.shell.mount({ active: 'log' });
    if (!profile) return;
    cacheElements();
    PX.applyIcons();

    state.logs = PX.logConsole({ view: els.logView, limit: LOG_LIMIT });
    state.logs.onChange(function (info) {
      if (info.domainAdded) addDomainOption(info.domainAdded);
      if (typeof info.total === 'number') state.meta.total = info.total;
      if (typeof info.visible === 'number') state.meta.visible = info.visible;
      scheduleMeta();
    });

    wireToolbar();
    startChannel();
    updateMeta();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();

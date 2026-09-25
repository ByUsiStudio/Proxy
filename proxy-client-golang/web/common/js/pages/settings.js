/* 设置与分享页：外观偏好、控制台安全状态、配置导出 / 导入 / 二维码分享、危险操作 */
(function () {
  'use strict';
  var PX = window.PX;

  var TOKEN_KEY = 'px_console_token';
  var QR_MAX_BYTES = 2000;

  var state = {
    info: null,
    exportPayload: null,
    exportText: '',
    tunnelCount: 0
  };

  var els = {};

  function byId(id) { return document.getElementById(id); }

  function cacheElements() {
    [
      'themeSlot', 'consoleRows', 'consoleFoot', 'reverifyBtn', 'logoutBtn', 'refreshInfoBtn',
      'withSecrets', 'secretWarn', 'exportText', 'exportMeta', 'exportBtn', 'qrBtn',
      'downloadBtn', 'copyBtn', 'qrBox', 'qrImg', 'qrMeta', 'qrWarn', 'qrWarnText',
      'importText', 'importFile', 'importBtn', 'importResult',
      'tunnelCount', 'stopAllBtn', 'clearLoginBtn',
      'logLevelSeg', 'logLevelBadge', 'logLevelRefresh', 'logLevelFoot'
    ].forEach(function (id) { els[id] = byId(id); });
  }

  /* ---------------------------------------------------------------- *
   * 通用小工具
   * ---------------------------------------------------------------- */
  function text(value, fallback) {
    var s = value === null || value === undefined ? '' : String(value);
    return s || (fallback === undefined ? '—' : fallback);
  }

  /** 计算 UTF-8 字节数，用于展示二维码内容体积。 */
  function byteLength(value) {
    var s = String(value === null || value === undefined ? '' : value);
    if (typeof TextEncoder !== 'undefined') return new TextEncoder().encode(s).length;
    return encodeURIComponent(s).replace(/%[0-9A-F]{2}/g, 'x').length;
  }

  function readToken() {
    try { return sessionStorage.getItem(TOKEN_KEY) || ''; } catch (e) { return ''; }
  }

  /** 设备 ID：前 4 位 + 省略号 + 后 4 位，避免完整标识出现在屏幕上。 */
  function maskDevice(value) {
    var s = value === null || value === undefined ? '' : String(value);
    if (s.length > 8) return s.slice(0, 4) + '…' + s.slice(-4);
    return s || '—';
  }

  function infoRow(label, valueNode) {
    return PX.el('div', { class: 'row row--between' }, [
      PX.el('span', { class: 'muted text-sm', text: label }),
      valueNode
    ]);
  }

  function infoValue(value, fallback) {
    return PX.el('span', {
      class: 'text-sm bold mono',
      style: { textAlign: 'right' },
      text: text(value, fallback)
    });
  }

  /* ---------------------------------------------------------------- *
   * 卡片 1：外观
   * ---------------------------------------------------------------- */
  function mountTheme() {
    PX.clear(els.themeSlot).appendChild(PX.theme.control());
  }

  /* ---------------------------------------------------------------- *
   * 卡片 2：控制台安全
   * ---------------------------------------------------------------- */
  function loadConsoleInfo() {
    els.consoleFoot.textContent = '正在读取…';
    return PX.api.get('/console/info').then(function (payload) {
      var data = payload && payload.Data ? payload.Data : null;
      if (!PX.isOk(payload) || !data) {
        throw new PX.ApiError(PX.msgOf(payload, '控制台信息读取失败'), 200, payload);
      }
      state.info = data;
      renderConsoleInfo(data);
    }).catch(function (err) {
      var message = err && err.message ? err.message : '控制台信息读取失败';
      PX.clear(els.consoleRows).appendChild(PX.el('div', { class: 'alert alert--warn' }, [
        PX.el('span', { icon: 'warn' }),
        PX.el('span', { text: '读取失败：' + message })
      ]));
      els.consoleFoot.textContent = '状态读取失败';
      PX.toastErr(message);
    });
  }

  function renderConsoleInfo(data) {
    var address = text(data.consoleAddress, '');
    var addressNode;
    if (/^https?:\/\//i.test(address)) {
      addressNode = PX.el('a', {
        class: 'text-sm bold mono',
        href: address,
        target: '_blank',
        rel: 'noopener noreferrer',
        title: address,
        text: address
      });
    } else {
      addressNode = infoValue(address || '—');
    }

    var apiUrl = text(data.apiUrl, '');
    var apiNode = apiUrl
      ? PX.el('span', { class: 'text-sm mono', style: { textAlign: 'right' }, title: apiUrl, text: apiUrl })
      : PX.el('span', { class: 'text-sm muted', text: '未配置' });

    var tokenNode = PX.el('span', {
      class: 'badge ' + (data.tokenEnforced ? 'badge--ok' : 'badge--info'),
      text: data.tokenEnforced
        ? '已启用访问令牌（远程访问需携带）'
        : '本机免令牌（默认只监听回环地址）'
    });

    var rows = [
      infoRow('控制台地址', addressNode),
      infoRow('内核版本', infoValue(data.coreVersion)),
      infoRow('设备 ID', infoValue(maskDevice(data.deviceId))),
      infoRow('云端接口地址', apiNode),
      infoRow('访问令牌状态', tokenNode)
    ];

    PX.clear(els.consoleRows);
    rows.forEach(function (row) { els.consoleRows.appendChild(row); });

    var traffic = (Number(data.inBytes) || 0) + (Number(data.outBytes) || 0);
    els.consoleFoot.textContent = '隧道 ' + (Number(data.tunnelCount) || 0) + ' 条 · 在线 ' +
      (Number(data.onlineCount) || 0) + ' · 活跃连接 ' + (Number(data.activeConns) || 0) +
      ' · 累计流量 ' + PX.fmtBytes(traffic) + ' · 更新于 ' + PX.fmtTime();
  }

  function reverify() {
    if (!PX.user.profile()) {
      PX.toastWarn('登录状态已失效，请重新登录');
      return;
    }
    PX.user.askPassword().then(function () {
      PX.toastOk('密码验证通过，当前会话已刷新');
    }).catch(function (err) {
      if (err && err.message === '已取消') return;
      PX.toastErr(err && err.message ? err.message : '验证失败');
    });
  }

  /* ---------------------------------------------------------------- *
   * 卡片 2.5：运行时日志级别
   * ---------------------------------------------------------------- */
  var LOG_LEVELS = ['debug', 'info', 'warn', 'error'];

  /** 同步分段控件的高亮状态（只按已知级别名匹配）。 */
  function syncLevelSeg(level) {
    var current = LOG_LEVELS.indexOf(level) === -1 ? 'info' : level;
    PX.$$('.seg__item', els.logLevelSeg).forEach(function (node) {
      var active = node.getAttribute('data-level') === current;
      node.classList.toggle('is-active', active);
      node.setAttribute('aria-pressed', active ? 'true' : 'false');
    });
    els.logLevelBadge.className = 'badge ' + (current === 'debug' ? 'badge--warn' : 'badge--ok');
    els.logLevelBadge.textContent = current;
  }

  function renderLevelState(data) {
    var level = data && data.level ? String(data.level) : 'info';
    syncLevelSeg(level);
    els.logLevelFoot.textContent = '当前 ' + level +
      '（级别值 ' + (data && data.levelValue !== undefined ? data.levelValue : '—') + '）· 更新于 ' + PX.fmtTime();
  }

  function loadLogLevel() {
    els.logLevelFoot.textContent = '正在读取当前级别…';
    return PX.api.get('/console/log-level').then(function (payload) {
      if (!PX.isOk(payload)) throw new PX.ApiError(PX.msgOf(payload, '日志级别读取失败'), 200, payload);
      renderLevelState(payload.Data || {});
      return payload.Data || {};
    }).catch(function (err) {
      els.logLevelFoot.textContent = '级别读取失败';
      // 读取失败不弹 Toast 刷屏：设置页还有其它状态要读，静默降级即可。
      return null;
    });
  }

  function setLogLevel(level) {
    if (LOG_LEVELS.indexOf(level) === -1) return;
    PX.$$('.seg__item', els.logLevelSeg).forEach(function (node) { node.disabled = true; });
    PX.api.post('/console/log-level', { level: level }).then(function (payload) {
      if (!PX.isOk(payload)) throw new PX.ApiError(PX.msgOf(payload, '日志级别设置失败'), 200, payload);
      renderLevelState(payload.Data || {});
      PX.toastOk('日志级别已切换为 ' + ((payload.Data && payload.Data.level) || level));
    }).catch(function (err) {
      PX.toastErr((err && err.message) || '日志级别设置失败');
      // 失败时回读一次真实状态，避免界面停留在错误的选中项上。
      loadLogLevel();
    }).then(function () {
      PX.$$('.seg__item', els.logLevelSeg).forEach(function (node) { node.disabled = false; });
    });
  }

  function wireLogLevel() {
    PX.$$('.seg__item', els.logLevelSeg).forEach(function (btn) {
      btn.addEventListener('click', function () {
        setLogLevel(btn.getAttribute('data-level') || 'info');
      });
    });
    els.logLevelRefresh.addEventListener('click', function () {
      loadLogLevel().then(function (data) {
        if (data) PX.toast('日志级别已刷新', { type: 'info', timeout: 1600 });
      });
    });
  }

  /* ---------------------------------------------------------------- *
   * 卡片 3：配置导出
   * ---------------------------------------------------------------- */
  function exportPath() {
    return els.withSecrets.checked ? '/console/config/export?secrets=1' : '/console/config/export';
  }

  function syncSecretWarn() {
    els.secretWarn.classList.toggle('hidden', !els.withSecrets.checked);
  }

  function doExport() {
    els.exportBtn.disabled = true;
    return PX.api.get(exportPath()).then(function (payload) {
      var data = payload && typeof payload === 'object' ? payload : { tunnels: [] };
      var list = Array.isArray(data.tunnels) ? data.tunnels : [];
      state.exportPayload = data;
      state.exportText = JSON.stringify(data, null, 2);
      els.exportText.value = state.exportText;
      els.downloadBtn.disabled = false;
      els.copyBtn.disabled = false;
      els.qrBox.classList.add('hidden');
      els.qrWarn.classList.add('hidden');
      hideQrImage();
      els.qrMeta.textContent = '导出内容 ' + byteLength(state.exportText) + ' 字节，可生成二维码分享。';
      els.exportMeta.textContent = '共 ' + list.length + ' 条隧道 · ' +
        (data.withSecret ? '包含凭据' : '不含凭据') + ' · ' + byteLength(state.exportText) + ' 字节';
      PX.toastOk('已导出 ' + list.length + ' 条隧道配置');
      return data;
    }).catch(function (err) {
      state.exportPayload = null;
      state.exportText = '';
      els.exportMeta.textContent = '导出失败';
      PX.toastErr(err && err.message ? err.message : '导出失败');
      return null;
    }).then(function (data) {
      els.exportBtn.disabled = false;
      return data;
    });
  }

  function downloadExport() {
    if (!state.exportText) {
      PX.toastWarn('请先导出配置');
      return;
    }
    var stamp = new Date().toISOString().replace(/[:T]/g, '-').slice(0, 19);
    PX.download('proxy-tunnels-' + stamp + '.json', state.exportText, 'application/json');
    PX.toastOk('已开始下载配置文件');
  }

  function copyExport() {
    if (!state.exportText) {
      PX.toastWarn('请先导出配置');
      return;
    }
    PX.copy(state.exportText);
  }

  /* ---------------------------------------------------------------- *
   * 卡片 3：二维码分享
   * ---------------------------------------------------------------- */
  /** 紧凑分享文本：字段名压缩，去掉缩进，尽量装进二维码容量。 */
  function compactShareText(payload) {
    var list = payload && Array.isArray(payload.tunnels) ? payload.tunnels : [];
    return JSON.stringify({
      v: payload && payload.version ? payload.version : '',
      t: list.map(function (item) {
        var row = {
          d: text(item && item.domain, ''),
          y: text(item && item.type, 'TCP'),
          s: text(item && item.serverHost, '') + ':' + text(item && item.serverPort, ''),
          g: text(item && item.targetHost, '') + ':' + text(item && item.targetPort, ''),
          p: Number(item && item.remotePort) || 0,
          u: text(item && item.username, '')
        };
        if (item && item.password) row.w = String(item.password);
        return row;
      })
    });
  }

  function showQrWarn(message) {
    els.qrWarnText.textContent = message;
    els.qrWarn.classList.remove('hidden');
  }

  /* 二维码图片的 blob: 对象 URL。必须显式 revoke，否则每次生成都会泄漏一份内存。 */
  var qrObjectUrl = null;

  function releaseQrUrl() {
    if (qrObjectUrl && typeof URL !== 'undefined' && URL.revokeObjectURL) {
      try { URL.revokeObjectURL(qrObjectUrl); } catch (e) { /* 忽略 */ }
    }
    qrObjectUrl = null;
  }

  function hideQrImage() {
    releaseQrUrl();
    els.qrImg.removeAttribute('src');
  }

  function makeQr() {
    if (!state.exportPayload) {
      PX.toastWarn('请先导出配置，再生成二维码');
      return;
    }
    var share = compactShareText(state.exportPayload);
    var bytes = byteLength(share);
    /* 【安全修复】原来把控制台令牌拼进 <img src="/console/share/qr?...&token=...">，
       URL 会进入浏览器历史、Referer 以及各类访问日志。现在改成用 fetch 带
       X-Proxy-Token 头取回 PNG，再用 blob: 对象 URL 渲染。 */
    var url = '/console/share/qr?data=' + encodeURIComponent(share);
    var token = readToken();
    var headers = token ? { 'X-Proxy-Token': token } : {};

    els.qrMeta.textContent = '分享内容 ' + bytes + ' 字节（二维码上限 ' + QR_MAX_BYTES + ' 字节）。';
    els.qrWarn.classList.add('hidden');
    els.qrBtn.disabled = true;

    /* 先用 fetch 探测：二维码接口在内容超长时返回 413 + JSON 错误信息，
       直接给 <img> 赋 src 只能拿到一个笼统的加载失败。 */
    fetch(url, { method: 'GET', headers: headers, credentials: 'same-origin', cache: 'no-store' }).then(function (res) {
      if (!res.ok) {
        return res.text().then(function (body) {
          var msg = '';
          try {
            var data = JSON.parse(body);
            msg = data.Msg || data.msg || data.message || '';
          } catch (e) { msg = ''; }
          throw new PX.ApiError(msg || ('二维码生成失败（HTTP ' + res.status + '）'), res.status, null);
        });
      }
      return res.blob();
    }).then(function (blob) {
      if (typeof URL === 'undefined' || !URL.createObjectURL) {
        throw new PX.ApiError('当前浏览器不支持生成二维码图片', 0, null);
      }
      releaseQrUrl();
      qrObjectUrl = URL.createObjectURL(blob);
      els.qrImg.setAttribute('src', qrObjectUrl);
      els.qrBox.classList.remove('hidden');
      PX.toastOk('二维码已生成（' + bytes + ' 字节）');
    }).catch(function (err) {
      hideQrImage();
      els.qrBox.classList.add('hidden');
      var reason = err && err.message ? err.message : '二维码生成失败';
      var oversize = bytes > QR_MAX_BYTES || (err && err.status === 413);
      var hint = oversize
        ? ' 当前分享内容 ' + bytes + ' 字节，已超过二维码可承载的 ' + QR_MAX_BYTES + ' 字节，请改用「复制」或「下载 JSON」分享配置。'
        : ' 当前分享内容 ' + bytes + ' 字节，请重试，或改用「复制」/「下载 JSON」分享配置。';
      showQrWarn(reason + hint);
      PX.toastErr(reason);
    }).then(function () {
      els.qrBtn.disabled = false;
    });
  }

  /* ---------------------------------------------------------------- *
   * 卡片 3：配置导入
   * ---------------------------------------------------------------- */
  function showImportResult(message, failures, isError) {
    PX.clear(els.importResult);
    els.importResult.className = 'alert ' + (isError ? 'alert--warn' : 'alert--info');
    var body = [
      PX.el('span', { icon: isError ? 'warn' : 'check' }),
      PX.el('div', { class: 'stack' }, [
        PX.el('div', { class: 'text-sm bold', text: message })
      ])
    ];
    var list = Array.isArray(failures) ? failures : [];
    if (list.length) {
      var box = PX.el('div', { class: 'stack' });
      box.appendChild(PX.el('div', { class: 'text-xs muted', text: '失败明细（共 ' + list.length + ' 条）：' }));
      list.forEach(function (item) {
        box.appendChild(PX.el('div', { class: 'text-xs mono', text: String(item) }));
      });
      body[1].appendChild(box);
    }
    body.forEach(function (node) { els.importResult.appendChild(node); });
    els.importResult.classList.remove('hidden');
  }

  function parseImport(raw) {
    var trimmed = (raw || '').trim();
    if (!trimmed) {
      throw new PX.ApiError('请先粘贴或选择要导入的配置内容', 0, null);
    }
    var parsed = null;
    try {
      parsed = JSON.parse(trimmed);
    } catch (e) {
      throw new PX.ApiError('配置内容不是合法的 JSON：' + (e && e.message ? e.message : '解析失败'), 0, null);
    }
    if (Array.isArray(parsed)) return { tunnels: parsed };
    if (parsed && typeof parsed === 'object') {
      if (Array.isArray(parsed.tunnels)) return parsed;
      if (Array.isArray(parsed.Tunnels)) {
        return { version: parsed.Version, exportedAt: parsed.ExportedAt, deviceId: parsed.DeviceID, withSecret: !!parsed.WithSecret, tunnels: parsed.Tunnels };
      }
    }
    throw new PX.ApiError('配置内容缺少 tunnels 隧道数组，请确认是控制台导出的 JSON', 0, null);
  }

  /** 缺少密码时先向用户索取，再把当前账号凭据补进待导入的隧道。 */
  function fillCredentials(payload) {
    var list = Array.isArray(payload.tunnels) ? payload.tunnels : [];
    var missing = list.some(function (item) { return !item || !item.password; });
    if (!missing) return Promise.resolve(payload);

    function fill() {
      var cred = PX.user.credentials() || {};
      payload.tunnels = list.map(function (item) {
        var row = Object.assign({}, item);
        if (!row.username) row.username = cred.username || '';
        if (!row.password) row.password = cred.password || '';
        return row;
      });
      return payload;
    }

    if (PX.user.password()) return Promise.resolve(fill());
    if (!PX.user.profile()) {
      return Promise.reject(new PX.ApiError('登录状态已失效，请重新登录后再导入', 401, null));
    }
    return PX.user.askPassword().then(fill);
  }

  function doImport() {
    var parsed = null;
    try {
      parsed = parseImport(els.importText.value);
    } catch (err) {
      showImportResult(err && err.message ? err.message : '配置内容不合法', null, true);
      PX.toastErr(err && err.message ? err.message : '配置内容不合法');
      return;
    }
    if (!parsed.tunnels.length) {
      showImportResult('配置中没有可导入的隧道', null, true);
      PX.toastWarn('配置中没有可导入的隧道');
      return;
    }

    els.importBtn.disabled = true;
    fillCredentials(parsed).then(function (payload) {
      return PX.api.postJson('/console/config/import', payload);
    }).then(function (res) {
      var message = PX.msgOf(res, '导入完成');
      var failures = res && Array.isArray(res.Failures) ? res.Failures : [];
      showImportResult(message, failures, !PX.isOk(res));
      if (PX.isOk(res)) PX.toastOk(message);
      else PX.toastErr(message);
      els.importText.value = '';
      loadTunnelCount();
    }).catch(function (err) {
      var message = err && err.message ? err.message : '导入失败';
      if (message !== '已取消') {
        showImportResult(message, null, true);
        PX.toastErr(message);
      }
    }).then(function () {
      els.importBtn.disabled = false;
    });
  }

  function readImportFile(file) {
    if (!file) return;
    var reader = new FileReader();
    reader.onload = function () {
      els.importText.value = String(reader.result === null || reader.result === undefined ? '' : reader.result);
      PX.toast('已读取文件 ' + file.name, { type: 'info', timeout: 2000 });
    };
    reader.onerror = function () {
      PX.toastErr('文件读取失败，请重试或直接粘贴 JSON 内容');
    };
    reader.readAsText(file);
  }

  /* ---------------------------------------------------------------- *
   * 卡片 4：危险操作
   * ---------------------------------------------------------------- */
  function loadTunnelCount() {
    return PX.api.get('/server/info').then(function (rows) {
      var list = Array.isArray(rows) ? rows : [];
      state.tunnelCount = list.length;
      var online = list.filter(function (row) { return row && row.Status; }).length;
      els.tunnelCount.textContent = list.length
        ? '当前隧道：' + list.length + ' 条 · 在线 ' + online + ' 条'
        : '当前没有正在运行的隧道';
      return list;
    }).catch(function (err) {
      els.tunnelCount.textContent = '隧道数量读取失败';
      PX.toastErr(err && err.message ? err.message : '隧道数量读取失败');
      return [];
    });
  }

  function stopAllTunnels() {
    els.stopAllBtn.disabled = true;
    PX.api.get('/server/info').then(function (rows) {
      var list = Array.isArray(rows) ? rows : [];
      var domains = list.map(function (row) {
        return row && row.Domain ? String(row.Domain) : '';
      }).filter(function (domain) { return !!domain; });

      if (!domains.length) {
        PX.toast('当前没有正在运行的隧道', { type: 'info', timeout: 2200 });
        return null;
      }

      var preview = domains.slice(0, 8).join('、') + (domains.length > 8 ? ' 等' : '');
      return PX.confirm({
        title: '停止全部隧道',
        message: '确定停止当前全部 ' + domains.length + ' 条隧道吗？所有外部访问会立即中断。',
        detail: preview,
        okText: '全部停止',
        danger: true
      }).then(function (yes) {
        if (!yes) return null;
        return PX.api.postJson('/server/batchStop', { domains: domains }).then(function (payload) {
          var message = PX.msgOf(payload, '已停止全部隧道');
          if (PX.isOk(payload)) PX.toastOk(message);
          else PX.toastErr(message);
          return loadTunnelCount();
        });
      });
    }).catch(function (err) {
      PX.toastErr(err && err.message ? err.message : '停止全部隧道失败');
    }).then(function () {
      els.stopAllBtn.disabled = false;
    });
  }

  function clearLocalLogin() {
    PX.confirm({
      title: '清除本地登录状态',
      message: '将清除本设备保存的账号资料与当前标签页的会话密码，之后需要重新登录。',
      okText: '清除并退出',
      danger: true
    }).then(function (yes) {
      if (yes) PX.user.logout();
    });
  }

  /* ---------------------------------------------------------------- *
   * 事件装配
   * ---------------------------------------------------------------- */
  function wire() {
    els.reverifyBtn.addEventListener('click', reverify);
    els.logoutBtn.addEventListener('click', function () { PX.user.logout(); });
    els.refreshInfoBtn.addEventListener('click', function () {
      loadConsoleInfo().then(function () { return loadTunnelCount(); });
      PX.toast('已刷新控制台状态', { type: 'info', timeout: 1600 });
    });

    els.withSecrets.addEventListener('change', syncSecretWarn);
    els.exportBtn.addEventListener('click', doExport);
    els.downloadBtn.addEventListener('click', downloadExport);
    els.copyBtn.addEventListener('click', copyExport);
    els.qrBtn.addEventListener('click', makeQr);

    els.qrImg.addEventListener('error', function () {
      els.qrBox.classList.add('hidden');
      hideQrImage();
      showQrWarn('二维码图片加载失败，请重试，或改用「复制」/「下载 JSON」分享配置。');
    });

    els.importFile.addEventListener('change', function (ev) {
      var file = ev.target.files && ev.target.files[0];
      readImportFile(file);
      ev.target.value = '';
    });
    els.importBtn.addEventListener('click', doImport);

    els.stopAllBtn.addEventListener('click', stopAllTunnels);
    els.clearLoginBtn.addEventListener('click', clearLocalLogin);

    wireLogLevel();
  }

  function init() {
    var profile = PX.shell.mount({ active: 'settings' });
    if (!profile) return;
    PX.applyIcons();
    cacheElements();
    mountTheme();
    syncSecretWarn();
    wire();
    loadConsoleInfo();
    loadTunnelCount();
    loadLogLevel();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();

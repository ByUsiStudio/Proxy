/* 穿透服务页：隧道列表、批量操作、自动刷新、配置导出、实时日志预览 */
(function () {
  'use strict';
  var PX = window.PX;
  var PREFS_KEY = 'px_center_prefs';

  var state = {
    rows: [],
    sort: { key: 'Domain', dir: 'asc' },
    keyword: '',
    selected: Object.create(null),
    auto: false,
    interval: 5000,
    timer: null,
    channel: null,
    logs: null,
    loading: false
  };

  var els = {};

  var SORT_TYPES = {
    Domain: 'string',
    Type: 'string',
    Target: 'string',
    Server: 'string',
    Status: 'boolean',
    InBytes: 'number',
    OutBytes: 'number',
    ConnCount: 'number',
    Uptime: 'number'
  };

  var TYPE_OPTIONS = [
    { value: 'TCP', label: 'TCP', hint: '仅转发 TCP 流量' },
    { value: 'UDP', label: 'UDP', hint: '仅转发 UDP 流量' },
    { value: 'TCP_UDP', label: 'TCP + UDP', hint: '同时转发两种协议' }
  ];

  function byId(id) { return document.getElementById(id); }

  function cacheElements() {
    [
      'kpiTunnels', 'kpiOnline', 'kpiConns', 'kpiTraffic', 'tabData', 'listFoot',
      'search', 'selectAll', 'batchStop', 'exportBtn', 'autoRefresh', 'interval',
      'addBtn', 'addModal', 'addClose', 'addCancel', 'addSubmit', 'handle-form',
      'ip', 'port', 'typeRadios', 'serverRadios', 'domainRadios', 'portRadios',
      'serverField', 'customServerField', 'customServer', 'domainField',
      'infoModal', 'infoTitle', 'infoBody', 'infoClose', 'infoOk', 'checkCore',
      'refreshBtn', 'logPreview',
      'diagnoseBtn', 'diagnoseModal', 'diagnoseClose', 'diagnoseOk', 'diagnoseBody',
      'diagnoseCopy', 'diagnoseFoot'
    ].forEach(function (id) { els[id] = byId(id); });
  }

  /* ---------------------------------------------------------------- *
   * 偏好设置
   * ---------------------------------------------------------------- */
  function restorePrefs() {
    var raw = null;
    try { raw = localStorage.getItem(PREFS_KEY); } catch (e) { raw = null; }
    if (raw) {
      try {
        var prefs = JSON.parse(raw);
        state.auto = !!prefs.auto;
        if (prefs.interval) state.interval = Number(prefs.interval) || 5000;
      } catch (e) { /* 忽略损坏的偏好 */ }
    }
    els.autoRefresh.checked = state.auto;
    els.interval.value = String(state.interval);
  }

  function savePrefs() {
    try {
      localStorage.setItem(PREFS_KEY, JSON.stringify({ auto: state.auto, interval: state.interval }));
    } catch (e) { /* 忽略 */ }
  }

  /* ---------------------------------------------------------------- *
   * 数据加载与渲染
   * ---------------------------------------------------------------- */
  function load() {
    if (state.loading) return Promise.resolve();
    state.loading = true;
    return PX.api.get('/server/info').then(function (rows) {
      state.rows = Array.isArray(rows) ? rows : [];
      render();
    }).catch(function (err) {
      els.listFoot.textContent = '加载失败：' + (err && err.message ? err.message : '未知错误');
      PX.toastErr((err && err.message) || '隧道列表加载失败');
    }).then(function () {
      state.loading = false;
    });
  }

  function render() {
    var rows = PX.filterRows(state.rows, state.keyword, ['Domain', 'Target', 'Server', 'Type']);
    rows = PX.sortRows(rows, state.sort.key, state.sort.dir, SORT_TYPES[state.sort.key] || 'string');

    renderKpis();
    renderSortIndicators();
    renderRows(rows);

    var now = PX.fmtTime();
    els.listFoot.textContent = '共 ' + state.rows.length + ' 条隧道' +
      (state.keyword ? '（筛选出 ' + rows.length + ' 条）' : '') +
      ' · 最近更新 ' + now;

    // 清理已不存在的选中项
    var alive = Object.create(null);
    state.rows.forEach(function (row) { alive[row.Domain] = true; });
    Object.keys(state.selected).forEach(function (domain) {
      if (!alive[domain]) delete state.selected[domain];
    });
    syncSelectionUi();
  }

  function renderKpis() {
    var online = 0;
    var conns = 0;
    var traffic = 0;
    state.rows.forEach(function (row) {
      if (row.Status) online += 1;
      conns += Number(row.ConnCount) || 0;
      traffic += (Number(row.InBytes) || 0) + (Number(row.OutBytes) || 0);
    });
    els.kpiTunnels.textContent = String(state.rows.length);
    els.kpiOnline.textContent = online + ' / ' + state.rows.length;
    els.kpiConns.textContent = PX.fmtNum(conns);
    els.kpiTraffic.textContent = PX.fmtBytes(traffic);
  }

  function renderSortIndicators() {
    PX.$$('th[data-sort]').forEach(function (th) {
      var active = th.getAttribute('data-sort') === state.sort.key;
      if (active) th.setAttribute('aria-sort', state.sort.dir === 'asc' ? 'ascending' : 'descending');
      else th.removeAttribute('aria-sort');
      var indicator = th.querySelector('.sort-ind');
      if (indicator) indicator.textContent = active ? (state.sort.dir === 'asc' ? '↑' : '↓') : '↕';
    });
  }

  function statusBadge(row) {
    if (row.Status) {
      return PX.el('span', { class: 'badge badge--ok' }, [
        PX.el('span', { class: 'dot pulse' }), PX.el('span', { text: '运行中' })
      ]);
    }
    return PX.el('span', { class: 'badge badge--down' }, [
      PX.el('span', { class: 'dot' }), PX.el('span', { text: '重连中' })
    ]);
  }

  function typeBadge(type) {
    var label = type === 'TCP_UDP' ? 'TCP+UDP' : (type || 'TCP');
    var variant = type === 'UDP' ? 'badge--warn' : (type === 'TCP_UDP' ? 'badge--info' : 'badge--muted');
    return PX.el('span', { class: 'badge ' + variant, text: label });
  }

  function renderRows(rows) {
    PX.clear(els.tabData);
    if (!rows.length) {
      els.tabData.appendChild(PX.el('tr', {}, [
        PX.el('td', { colspan: '11' }, [
          PX.empty(
            state.rows.length ? '没有匹配的隧道' : '还没有隧道',
            state.rows.length ? '换个关键词试试' : '点击右上角「添加穿透」创建第一条隧道',
            'rocket'
          )
        ])
      ]));
      return;
    }

    var frag = document.createDocumentFragment();
    rows.forEach(function (row) {
      var domain = String(row.Domain || '');
      var checkbox = PX.el('input', {
        type: 'checkbox',
        'aria-label': '选择 ' + domain,
        dataset: { domain: domain },
        checked: !!state.selected[domain],
        onchange: function (ev) {
          if (ev.target.checked) state.selected[domain] = true;
          else delete state.selected[domain];
          syncSelectionUi();
        }
      });

      var stopButton = PX.el('button', {
        type: 'button', class: 'btn btn--danger btn--sm',
        onclick: function () { stopTunnel(domain); }
      }, [PX.iconNode('stop', 'icon--sm'), PX.el('span', { class: 'hide-sm', text: '停止' })]);

      frag.appendChild(PX.el('tr', {}, [
        PX.el('td', { 'data-label': '选择' }, [checkbox]),
        PX.el('td', { 'data-label': '域名' }, [
          PX.el('span', { class: 'mono', title: domain, text: domain || '-' })
        ]),
        PX.el('td', { 'data-label': '类型' }, [typeBadge(row.Type)]),
        PX.el('td', { 'data-label': '内网服务', text: row.Target || '-' }),
        PX.el('td', { 'data-label': '穿透服务器', text: row.Server || '-' }),
        PX.el('td', { 'data-label': '状态' }, [statusBadge(row)]),
        PX.el('td', { class: 'table__num', 'data-label': '入站', text: PX.fmtBytes(row.InBytes) }),
        PX.el('td', { class: 'table__num', 'data-label': '出站', text: PX.fmtBytes(row.OutBytes) }),
        PX.el('td', { class: 'table__num', 'data-label': '连接', text: PX.fmtNum(row.ConnCount) }),
        PX.el('td', { class: 'table__num', 'data-label': '时长', text: PX.fmtDuration(row.Uptime) }),
        PX.el('td', { class: 'table__actions', 'data-label': '操作' }, [stopButton])
      ]));
    });
    els.tabData.appendChild(frag);
  }

  function syncSelectionUi() {
    var domains = Object.keys(state.selected);
    els.batchStop.disabled = domains.length === 0;
    els.batchStop.querySelector('span:last-child').textContent =
      domains.length ? '批量停止 (' + domains.length + ')' : '批量停止';
    if (els.selectAll) {
      var allSelected = state.rows.length > 0 && domains.length >= state.rows.length;
      els.selectAll.checked = allSelected;
      els.selectAll.indeterminate = domains.length > 0 && !allSelected;
    }
    PX.$$('#tabData input[type="checkbox"][data-domain]').forEach(function (box) {
      box.checked = !!state.selected[box.dataset.domain];
    });
  }

  /* ---------------------------------------------------------------- *
   * 操作
   * ---------------------------------------------------------------- */
  function stopTunnel(domain) {
    if (!domain) return;
    PX.confirm({
      title: '停止隧道',
      message: '确定要停止「' + domain + '」吗？该域名的外部访问会立即中断。',
      okText: '停止',
      danger: true
    }).then(function (yes) {
      if (!yes) return;
      PX.api.post('/server/stop', { domain: domain }).then(function (payload) {
        if (PX.isOk(payload)) PX.toastOk(PX.msgOf(payload, '已停止'));
        else PX.toastErr(PX.msgOf(payload, '停止失败'));
        load();
      }).catch(function (err) { PX.toastErr(err.message); });
    });
  }

  function batchStop() {
    var domains = Object.keys(state.selected);
    if (!domains.length) return;
    PX.confirm({
      title: '批量停止',
      message: '确定停止选中的 ' + domains.length + ' 条隧道吗？',
      detail: domains.join('、'),
      okText: '全部停止',
      danger: true
    }).then(function (yes) {
      if (!yes) return;
      PX.api.postJson('/server/batchStop', { domains: domains }).then(function (payload) {
        state.selected = Object.create(null);
        if (PX.isOk(payload)) PX.toastOk(PX.msgOf(payload, '已停止'));
        else PX.toastErr(PX.msgOf(payload, '操作失败'));
        load();
      }).catch(function (err) { PX.toastErr(err.message); });
    });
  }

  function exportConfig() {
    PX.api.get('/console/config/export').then(function (payload) {
      var stamp = new Date().toISOString().replace(/[:T]/g, '-').slice(0, 19);
      PX.download('proxy-tunnels-' + stamp + '.json', JSON.stringify(payload, null, 2), 'application/json');
      PX.toastOk('已导出 ' + ((payload && payload.tunnels && payload.tunnels.length) || 0) + ' 条隧道配置');
    }).catch(function (err) { PX.toastErr(err.message); });
  }

  function checkCore() {
    PX.api.get('/core/version').then(function (res) {
      var body = PX.el('div', { class: 'stack' }, [
        PX.el('div', { class: 'row' }, [
          PX.el('span', {
            class: 'badge ' + (res.NeedUpdate ? 'badge--warn' : 'badge--ok'),
            text: res.NeedUpdate ? '发现新版本' : '已是最新'
          })
        ]),
        infoRow('当前版本', res.Current || '—'),
        infoRow('最新版本', res.Latest || '—'),
        res.CreateTime ? infoRow('发布时间', res.CreateTime) : null,
        res.UpdateContent ? infoRow('更新内容', res.UpdateContent) : null
      ]);
      showInfo('内核版本检查', body);
    }).catch(function (err) { PX.toastErr(err.message, '检查更新失败'); });
  }

  function infoRow(label, value) {
    return PX.el('div', { class: 'row row--between' }, [
      PX.el('span', { class: 'muted text-sm', text: label }),
      PX.el('span', { class: 'text-sm bold', style: { textAlign: 'right' }, text: value })
    ]);
  }

  function showInfo(title, body) {
    els.infoTitle.textContent = title;
    PX.clear(els.infoBody).appendChild(body);
    PX.modal.open(els.infoModal);
  }

  /* ---------------------------------------------------------------- *
   * 一键诊断
   *     服务端 GET /console/diagnose 返回结构化报告；所有文本（域名、错误消息）
   *     都可能来自远端，因此只允许用 textContent 渲染。
   * ---------------------------------------------------------------- */
  var diagnoseState = { report: null };

  // 结论状态 → 徽章样式；与服务端返回的 ok/warn/fail 一一对应。
  var DIAG_BADGE = { ok: 'badge--ok', warn: 'badge--warn', fail: 'badge--down' };

  function diagBadge(status) {
    return PX.el('span', {
      class: 'badge ' + (DIAG_BADGE[status] || 'badge--muted'),
      text: String(status || 'unknown')
    });
  }

  function diagRow(label, value, status) {
    var valueNode = value instanceof Node
      ? value
      : PX.el('span', { class: 'text-sm', style: { textAlign: 'right' }, text: String(value === undefined || value === null ? '—' : value) });
    return PX.el('div', { class: 'row row--between' }, [
      PX.el('span', { class: 'muted text-sm', text: label }),
      PX.el('span', { class: 'row', style: { gap: '0.5rem', justifyContent: 'flex-end' } }, [
        valueNode,
        status ? diagBadge(status) : null
      ])
    ]);
  }

  function diagSection(title, rows) {
    return PX.el('div', { class: 'stack' }, [
      PX.el('div', { class: 'text-sm bold', text: title }),
      PX.el('div', { class: 'stack' }, rows)
    ]);
  }

  /** 把结构化报告渲染成 DOM；纯文本节点，无任何 HTML 拼接。 */
  function renderDiagnose(report) {
    var host = report.host || {};
    var cloud = report.cloud || {};
    var config = report.config || {};
    var summary = report.summary || {};
    var tunnels = Array.isArray(report.tunnels) ? report.tunnels : [];

    var nodes = [];

    nodes.push(PX.el('div', { class: 'row row--between' }, [
      PX.el('span', { class: 'text-xs faint', text: '生成时间 ' + String(report.generatedAt || '—') + ' · 预算 ' + String(report.budgetMs || 0) + 'ms' }),
      PX.el('span', { class: 'row', style: { gap: '0.4rem' } }, [
        PX.el('span', { class: 'badge badge--ok', text: '正常 ' + (summary.ok || 0) }),
        PX.el('span', { class: 'badge badge--warn', text: '警告 ' + (summary.warn || 0) }),
        PX.el('span', { class: 'badge badge--down', text: '失败 ' + (summary.fail || 0) })
      ])
    ]));

    // 1. 本机服务
    nodes.push(diagSection('本机服务', [
      diagRow('控制台版本', String(host.coreVersion || '—') + ' · ' + String(host.consoleAddress || '—')),
      diagRow('设备 ID', String(host.deviceId || '—')),
      diagRow('隧道数量', String(host.tunnelCount || 0) + ' / ' + String(host.tunnelLimit || 0) + '（在线 ' + String(host.onlineCount || 0) + '）'),
      diagRow('goroutine 数量', String(host.goroutines || 0)),
      diagRow('日志通道连接', String(host.wsClients || 0))
    ]));

    // 2. 云端可达性
    nodes.push(diagSection('云端可达性', [
      diagRow('云端地址', String(cloud.url || '未配置')),
      diagRow('探测结果', String(cloud.detail || '—'), cloud.status)
    ]));

    // 3. 每条隧道
    var tunnelRows = [];
    if (!tunnels.length) {
      tunnelRows.push(PX.el('div', { class: 'text-xs muted', text: '当前没有隧道' }));
    } else {
      tunnels.forEach(function (t) {
        var target = String(t.target || '—');
        var detail = String(t.dialDetail || '');
        var row = PX.el('div', { class: 'stack diag-tunnel' }, [
          PX.el('div', { class: 'row row--between' }, [
            PX.el('span', { class: 'mono text-sm truncate', title: String(t.domain || ''), text: String(t.domain || '—') }),
            PX.el('span', { class: 'row', style: { gap: '0.4rem' } }, [
              PX.el('span', { class: 'badge badge--muted', text: String(t.type || 'TCP') }),
              diagBadge(t.status)
            ])
          ]),
          PX.el('div', { class: 'text-xs muted', text: '目标 ' + target + ' · 服务器 ' + String(t.server || '—') }),
          PX.el('div', { class: 'text-xs muted nums', text: '连接 ' + (Number(t.connCount) || 0) +
            ' · 入站 ' + PX.fmtBytes(t.inBytes) + ' · 出站 ' + PX.fmtBytes(t.outBytes) +
            ' · 运行 ' + PX.fmtDuration(t.uptime) }),
          PX.el('div', { class: 'text-xs', text: detail })
        ]);
        tunnelRows.push(row);
      });
    }
    nodes.push(diagSection('隧道检查（最多 ' + tunnels.length + ' 条）' +
      (report.tunnelProbeTruncated ? ' · 已按上限截断' : ''), tunnelRows));

    // 4. 配置与环境
    var hints = Array.isArray(config.hints) ? config.hints : [];
    nodes.push(diagSection('配置与环境', [
      diagRow('日志目录', String(config.logDir || '—') + '（' + (config.logWritable ? '可写' : '不可写') + '）',
        config.logWritable ? 'ok' : 'fail'),
      diagRow('日志级别 / 上限', String(config.logLevel || '—') + ' · ' + PX.fmtBytes(config.logMaxBytes) + ' · 保留 ' + String(config.logKeep || 0)),
      diagRow('SSL', config.sslEnabled ? '已启用' : '未启用', config.sslInsecureSkip ? 'warn' : 'ok'),
      diagRow('访问令牌强制', config.tokenEnforced ? '已启用' : '未启用（仅本机）', config.tokenEnforced ? 'ok' : 'warn'),
      diagRow('监听地址', String(config.bindHost || '默认回环') + (config.loopbackOnly ? '（回环）' : '（非回环）'),
        config.loopbackOnly ? 'ok' : 'warn'),
      diagRow('WEB_ALLOWED_HOSTS', config.allowedHostsConfigured
        ? (Array.isArray(config.allowedHosts) ? config.allowedHosts.join('、') : '已配置')
        : '未配置', config.allowedHostsConfigured ? 'ok' : 'warn'),
      config.logError ? diagRow('日志告警', String(config.logError), 'warn') : null,
      config.sslWarn ? diagRow('SSL 告警', String(config.sslWarn), 'warn') : null
    ].concat(hints.map(function (hint) {
      return PX.el('div', { class: 'text-xs', text: '提示：' + String(hint) });
    }))));

    return PX.el('div', { class: 'stack' }, nodes);
  }

  /** 生成纯文本版诊断报告（用于「复制诊断报告」）。 */
  function diagnoseText(report) {
    var lines = [];
    var host = report.host || {};
    var cloud = report.cloud || {};
    var config = report.config || {};
    var summary = report.summary || {};

    lines.push('Proxy 客户端诊断报告 ' + String(report.generatedAt || ''));
    lines.push('汇总: 正常 ' + (summary.ok || 0) + ' / 警告 ' + (summary.warn || 0) + ' / 失败 ' + (summary.fail || 0));
    lines.push('');
    lines.push('[本机服务]');
    lines.push('  控制台版本: ' + String(host.coreVersion || '-') + '  地址: ' + String(host.consoleAddress || '-'));
    lines.push('  设备 ID: ' + String(host.deviceId || '-'));
    lines.push('  隧道: ' + String(host.tunnelCount || 0) + '/' + String(host.tunnelLimit || 0) + '  在线: ' + String(host.onlineCount || 0));
    lines.push('  goroutine: ' + String(host.goroutines || 0) + '  日志通道连接: ' + String(host.wsClients || 0));
    lines.push('');
    lines.push('[云端可达性] ' + String(cloud.status || '-'));
    lines.push('  ' + String(cloud.url || '未配置') + ' -> ' + String(cloud.detail || '-'));
    lines.push('');
    lines.push('[隧道检查]');
    var tunnels = Array.isArray(report.tunnels) ? report.tunnels : [];
    if (!tunnels.length) lines.push('  （无隧道）');
    tunnels.forEach(function (t) {
      lines.push('  - ' + String(t.domain || '-') + ' [' + String(t.status || '-') + '] ' +
        String(t.type || '') + ' 目标 ' + String(t.target || '-') + ' 服务器 ' + String(t.server || '-'));
      lines.push('    连接 ' + (Number(t.connCount) || 0) + ' 入站 ' + PX.fmtBytes(t.inBytes) +
        ' 出站 ' + PX.fmtBytes(t.outBytes) + ' 运行 ' + PX.fmtDuration(t.uptime));
      lines.push('    ' + String(t.dialDetail || ''));
    });
    if (report.tunnelProbeTruncated) lines.push('  （探测数量已达上限，未覆盖全部隧道）');
    lines.push('');
    lines.push('[配置与环境]');
    lines.push('  日志目录: ' + String(config.logDir || '-') + ' 可写: ' + (config.logWritable ? '是' : '否') +
      ' 级别: ' + String(config.logLevel || '-'));
    lines.push('  日志上限: ' + PX.fmtBytes(config.logMaxBytes) + ' 保留: ' + String(config.logKeep || 0));
    lines.push('  SSL: ' + (config.sslEnabled ? '启用' : '未启用') + ' 跳过验证: ' + (config.sslInsecureSkip ? '是' : '否'));
    lines.push('  访问令牌强制: ' + (config.tokenEnforced ? '是' : '否'));
    lines.push('  监听地址: ' + String(config.bindHost || '默认回环') + ' 仅回环: ' + (config.loopbackOnly ? '是' : '否'));
    lines.push('  WEB_ALLOWED_HOSTS: ' + (Array.isArray(config.allowedHosts) && config.allowedHosts.length
      ? config.allowedHosts.join(', ') : '未配置'));
    if (config.logError) lines.push('  日志告警: ' + String(config.logError));
    if (config.sslWarn) lines.push('  SSL 告警: ' + String(config.sslWarn));
    var hints = Array.isArray(config.hints) ? config.hints : [];
    hints.forEach(function (hint) { lines.push('  提示: ' + String(hint)); });
    return lines.join('\n');
  }

  function runDiagnose() {
    els.diagnoseBtn.disabled = true;
    PX.clear(els.diagnoseBody).appendChild(PX.el('div', { class: 'text-sm muted', text: '正在诊断，请稍候（最多 10 秒）…' }));
    els.diagnoseFoot.textContent = '';
    PX.modal.open(els.diagnoseModal);

    PX.api.send('/console/diagnose', { method: 'GET' }).then(function (res) {
      if (!res.ok) {
        throw new PX.ApiError((res.data && (res.data.Msg || res.data.msg)) || ('诊断失败（HTTP ' + res.status + '）'), res.status, res.data);
      }
      var report = res.data && typeof res.data === 'object' ? res.data : {};
      diagnoseState.report = report;
      PX.clear(els.diagnoseBody).appendChild(renderDiagnose(report));
      var summary = report.summary || {};
      els.diagnoseFoot.textContent = '完成于 ' + PX.fmtTime() + ' · 耗时 ' + String(summary.elapsedMs || 0) + 'ms';
      if ((summary.fail || 0) > 0) PX.toastWarn('诊断完成：发现 ' + summary.fail + ' 项失败');
      else PX.toastOk('诊断完成：未发现失败项');
    }).catch(function (err) {
      PX.clear(els.diagnoseBody).appendChild(PX.el('div', { class: 'alert alert--danger' }, [
        PX.el('span', { icon: 'warn' }),
        PX.el('span', { text: '诊断失败：' + ((err && err.message) || '未知错误') })
      ]));
      els.diagnoseFoot.textContent = '诊断失败';
      PX.toastErr((err && err.message) || '诊断失败');
    }).then(function () {
      els.diagnoseBtn.disabled = false;
    });
  }

  function copyDiagnose() {
    if (!diagnoseState.report) {
      PX.toastWarn('请先执行一键诊断');
      return;
    }
    PX.copy(diagnoseText(diagnoseState.report));
  }

  /* ---------------------------------------------------------------- *
   * 添加穿透表单
   * ---------------------------------------------------------------- */
  function radioGroup(host, name, options, selected) {
    PX.clear(host);
    if (!options.length) {
      host.appendChild(PX.el('span', { class: 'text-xs muted', text: '暂无可选项' }));
      return;
    }
    options.forEach(function (option, index) {
      var id = name + '_' + index;
      var input = PX.el('input', {
        type: 'radio', name: name, id: id, value: String(option.value),
        checked: option.value === selected
      });
      host.appendChild(PX.el('label', { class: 'radio', for: id, title: option.hint || option.label }, [
        input, PX.el('span', { text: option.label })
      ]));
    });
  }

  function checkedValue(host) {
    var input = host.querySelector('input[type="radio"]:checked');
    return input ? input.value : '';
  }

  function buildTypeRadios() {
    radioGroup(els.typeRadios, 'proxy_type', TYPE_OPTIONS, 'TCP');
    els.typeRadios.addEventListener('change', function () {
      var isUdp = checkedValue(els.typeRadios) === 'UDP';
      els.domainField.classList.toggle('hidden', isUdp);
    });
  }

  function buildServerRadios(profile) {
    radioGroup(els.serverRadios, 'server_info', [], '');
    PX.api.get('/hp/load/data').then(function (payload) {
      var list = payload && payload.code === 200 && Array.isArray(payload.data) ? payload.data : [];
      var options = list.map(function (item) {
        return {
          value: String(item.ip) + ':' + String(item.port),
          label: String(item.name || '未命名') + '（' + (item.num || 0) + '）'
        };
      });
      options.push({ value: '-1', label: '自定义', hint: '手动填写穿透服务器地址' });
      radioGroup(els.serverRadios, 'server_info', options, options[0].value);
      els.serverRadios.addEventListener('change', function () {
        els.customServerField.classList.toggle('hidden', checkedValue(els.serverRadios) !== '-1');
      });
      els.customServerField.classList.add('hidden');
    }).catch(function () {
      radioGroup(els.serverRadios, 'server_info', [{ value: '-1', label: '自定义' }], '-1');
      els.customServerField.classList.remove('hidden');
    });
  }

  function buildDomainRadios(profile) {
    var domains = profile && profile.domains ? Object.keys(profile.domains) : [];
    if (!domains.length) {
      PX.clear(els.domainRadios).appendChild(PX.el('span', { class: 'text-xs muted' }, [
        PX.el('span', { text: '暂无可用域名，请先到 ' }),
        PX.el('a', { href: 'domain.html', text: '域名管理' }),
        PX.el('span', { text: ' 添加并刷新' })
      ]));
      return;
    }
    radioGroup(els.domainRadios, 'domain', domains.map(function (domain) {
      return { value: domain, label: domain };
    }), domains[0]);
  }

  function buildPortRadios(profile) {
    var ports = profile && Array.isArray(profile.ports) ? profile.ports : [];
    var options = [{ value: '0', label: '随机分配', hint: '由服务器随机分配外网端口' }];
    ports.forEach(function (port) {
      options.push({ value: String(port), label: String(port) });
    });
    radioGroup(els.portRadios, 'remote_port', options, '0');
  }

  function withCredentials() {
    if (PX.user.password()) return Promise.resolve(PX.user.credentials());
    return PX.user.askPassword().then(function () { return PX.user.credentials(); });
  }

  function openAddModal() {
    var profile = PX.user.profile();
    els.ip.value = '127.0.0.1';
    els.port.value = '';
    els.customServer.value = '';
    buildTypeRadios();
    buildDomainRadios(profile);
    buildPortRadios(profile);
    buildServerRadios(profile);
    els.domainField.classList.remove('hidden');
    PX.modal.open(els.addModal);
  }

  function submitAdd() {
    var ip = els.ip.value.trim();
    var port = els.port.value.trim();
    var type = checkedValue(els.typeRadios) || 'TCP';
    var serverInfo = checkedValue(els.serverRadios);
    var domain = type === 'UDP' ? '' : checkedValue(els.domainRadios);
    var remotePort = checkedValue(els.portRadios) || '0';

    if (!/^[0-9]{1,3}(\.[0-9]{1,3}){3}$|^[a-zA-Z0-9][a-zA-Z0-9.\-]*$/.test(ip)) {
      PX.toastWarn('内网 IP 格式不正确');
      return;
    }
    if (!/^\d+$/.test(port) || Number(port) < 1 || Number(port) > 65535) {
      PX.toastWarn('内网端口必须是 1-65535 的数字');
      return;
    }
    if (!serverInfo) {
      PX.toastWarn('请选择穿透服务器');
      return;
    }
    if (serverInfo === '-1') {
      serverInfo = els.customServer.value.trim();
      if (!/^[^\s:]+:\d{1,5}$/.test(serverInfo)) {
        PX.toastWarn('自定义穿透服务器格式应为 ip:端口');
        return;
      }
    }
    if (type !== 'UDP' && !domain) {
      PX.toastWarn('请选择穿透域名');
      return;
    }

    els.addSubmit.disabled = true;
    els.addSubmit.textContent = '提交中…';

    withCredentials().then(function (cred) {
      return PX.api.post('/server/proxy', {
        ip: ip,
        port: port,
        type: type,
        server_info: serverInfo,
        domain: domain,
        remote_port: remotePort,
        username: cred.username || '',
        password: cred.password || ''
      });
    }).then(function (payload) {
      if (PX.isOk(payload)) {
        PX.toastOk(PX.msgOf(payload, '添加成功'));
        PX.modal.close(els.addModal);
        load();
      } else {
        PX.toastErr(PX.msgOf(payload, '添加失败'));
      }
    }).catch(function (err) {
      PX.toastErr(err && err.message ? err.message : '添加失败');
    }).then(function () {
      els.addSubmit.disabled = false;
      els.addSubmit.textContent = '确定添加';
    });
  }

  /* ---------------------------------------------------------------- *
   * 自动刷新与实时日志
   * ---------------------------------------------------------------- */
  function startAuto() {
    stopAuto();
    state.timer = setInterval(function () { load(); }, state.interval);
  }

  function stopAuto() {
    if (state.timer) {
      clearInterval(state.timer);
      state.timer = null;
    }
  }

  function startLogs() {
    state.logs = PX.logConsole({ view: els.logPreview, limit: 40 });
    var count = 0;
    state.channel = PX.ws({
      onMessage: function (entry) {
        state.logs.push(entry);
        count += 1;
        if (count === 1) PX.shell.setStatus('实时通道已连接', 'ok');
      },
      onStatus: function (status) {
        if (status === 'open') PX.shell.setStatus('实时通道已连接', 'ok');
        else if (status === 'closed') PX.shell.setStatus('实时通道重连中', 'warn');
        else if (status === 'error') PX.shell.setStatus('实时通道异常', 'warn');
      }
    });
  }

  /* ---------------------------------------------------------------- *
   * 装配
   * ---------------------------------------------------------------- */
  function wireToolbar() {
    els.addBtn.addEventListener('click', openAddModal);
    els.refreshBtn.addEventListener('click', function () {
      load();
      PX.toast('已刷新隧道列表', { type: 'info', timeout: 1600 });
    });
    els.checkCore.addEventListener('click', checkCore);
    els.diagnoseBtn.addEventListener('click', runDiagnose);
    els.exportBtn.addEventListener('click', exportConfig);
    els.batchStop.addEventListener('click', batchStop);

    els.search.addEventListener('input', PX.debounce(function (ev) {
      state.keyword = ev.target.value;
      render();
    }, 160));

    els.autoRefresh.addEventListener('change', function (ev) {
      state.auto = ev.target.checked;
      if (state.auto) {
        startAuto();
        PX.toast('已开启自动刷新（每 ' + (state.interval / 1000) + ' 秒）', { type: 'info', timeout: 2000 });
      } else {
        stopAuto();
      }
      savePrefs();
    });

    els.interval.addEventListener('change', function (ev) {
      state.interval = Number(ev.target.value) || 5000;
      if (state.auto) startAuto();
      savePrefs();
    });

    els.selectAll.addEventListener('change', function (ev) {
      if (ev.target.checked) {
        state.rows.forEach(function (row) { state.selected[row.Domain] = true; });
      } else {
        state.selected = Object.create(null);
      }
      syncSelectionUi();
      PX.$$('#tabData input[type="checkbox"][data-domain]').forEach(function (box) {
        box.checked = !!state.selected[box.dataset.domain];
      });
    });

    PX.$$('th[data-sort]').forEach(function (th) {
      th.addEventListener('click', function () {
        var key = th.getAttribute('data-sort');
        if (state.sort.key === key) {
          state.sort.dir = state.sort.dir === 'asc' ? 'desc' : 'asc';
        } else {
          state.sort.key = key;
          state.sort.dir = key === 'Domain' ? 'asc' : 'desc';
        }
        render();
      });
    });
  }

  function wireModals() {
    function closeAdd() { PX.modal.close(els.addModal); }
    els.addClose.addEventListener('click', closeAdd);
    els.addCancel.addEventListener('click', closeAdd);
    els.addSubmit.addEventListener('click', submitAdd);
    els.addModal.addEventListener('click', function (ev) { if (ev.target === els.addModal) closeAdd(); });

    els.infoClose.addEventListener('click', function () { PX.modal.close(els.infoModal); });
    els.infoOk.addEventListener('click', function () { PX.modal.close(els.infoModal); });
    els.infoModal.addEventListener('click', function (ev) { if (ev.target === els.infoModal) PX.modal.close(els.infoModal); });

    els.diagnoseClose.addEventListener('click', function () { PX.modal.close(els.diagnoseModal); });
    els.diagnoseOk.addEventListener('click', function () { PX.modal.close(els.diagnoseModal); });
    els.diagnoseModal.addEventListener('click', function (ev) {
      if (ev.target === els.diagnoseModal) PX.modal.close(els.diagnoseModal);
    });
    els.diagnoseCopy.addEventListener('click', copyDiagnose);
  }

  function init() {
    var profile = PX.shell.mount({ active: 'center' });
    if (!profile) return;
    cacheElements();
    PX.applyIcons();
    restorePrefs();
    wireToolbar();
    wireModals();
    startLogs();
    load();
    if (state.auto) startAuto();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();

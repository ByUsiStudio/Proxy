/* 数据统计页：本机 KPI、按隧道流量柱状图、连接数趋势采样、云端流量记录与会话信息 */
(function () {
  'use strict';
  var PX = window.PX;

  var PREFS_KEY = 'px_stats_prefs';
  var REFRESH_MS = 5000;
  var SAMPLE_LIMIT = 30;
  var CLOUD_COLUMNS = 7;

  var TRAFFIC_SERIES = [
    { name: '入站', color: PX.chart.palette[0] },
    { name: '出站', color: PX.chart.palette[1] }
  ];

  var CONN_SERIES = [
    { name: '活跃连接', color: PX.chart.palette[0] }
  ];

  var SORT_TYPES = {
    id: 'number',
    port: 'number',
    receive: 'number',
    send: 'number',
    connectNum: 'number',
    packNum: 'number',
    createTime: 'string'
  };

  var state = {
    auto: true,
    timer: null,
    samples: [],
    trafficSpec: null,
    redrawTraffic: null,
    redrawConn: null,
    cloud: [],
    sort: { key: 'id', dir: 'desc' }
  };

  var els = {};

  function byId(id) { return document.getElementById(id); }

  function cacheElements() {
    [
      'kpiTunnels', 'kpiOnline', 'kpiConns', 'kpiIn', 'kpiOut',
      'autoRefresh', 'trafficStamp', 'trafficChart', 'trafficLegend', 'trafficFoot',
      'connChart', 'connLegend', 'connFoot',
      'cloudTable', 'cloudRows', 'cloudFoot', 'cloudStamp', 'cloudRefresh',
      'tokenBadge', 'coreVersion', 'deviceId', 'consoleAddress', 'apiUrl',
      'refreshAll'
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
        if (prefs && prefs.auto !== undefined) state.auto = !!prefs.auto;
      } catch (e) { /* 忽略损坏的偏好 */ }
    }
    els.autoRefresh.checked = state.auto;
  }

  function savePrefs() {
    try {
      localStorage.setItem(PREFS_KEY, JSON.stringify({ auto: state.auto }));
    } catch (e) { /* 忽略隐私模式写入失败 */ }
  }

  /* ---------------------------------------------------------------- *
   * 本机实时数据
   * ---------------------------------------------------------------- */
  function loadLocal() {
    return Promise.all([
      PX.api.get('/console/info').catch(function (err) { return { __error: err }; }),
      PX.api.get('/server/info').catch(function (err) { return { __error: err }; })
    ]).then(function (results) {
      var info = results[0];
      var tunnels = results[1];

      if (info && info.__error) {
        PX.toastErr(errorText(info.__error, '本机运行信息读取失败'), '实时数据');
      } else if (PX.isOk(info)) {
        var data = info.Data || {};
        renderOverview(data);
        renderSession(data);
        sample(data.activeConns);
      } else {
        PX.toastErr(PX.msgOf(info, '本机运行信息读取失败'), '实时数据');
      }

      if (tunnels && tunnels.__error) {
        els.trafficFoot.textContent = '加载失败：' + errorText(tunnels.__error, '隧道流量读取失败');
      } else {
        renderTraffic(Array.isArray(tunnels) ? tunnels : []);
      }
    });
  }

  function errorText(err, fallback) {
    if (err && err.status === 401) return '未授权：请从本机打开控制台或重新登录';
    return (err && err.message) || fallback;
  }

  function renderOverview(data) {
    els.kpiTunnels.textContent = PX.fmtNum(data.tunnelCount);
    els.kpiOnline.textContent = PX.fmtNum(data.onlineCount) + ' / ' + PX.fmtNum(data.tunnelCount);
    els.kpiConns.textContent = PX.fmtNum(data.activeConns);
    els.kpiIn.textContent = PX.fmtBytes(data.inBytes);
    els.kpiOut.textContent = PX.fmtBytes(data.outBytes);
  }

  /** 设备标识仅展示首尾各 4 位，避免完整指纹出现在截图或分享中。 */
  function shortId(value) {
    var text = String(value === null || value === undefined ? '' : value);
    if (!text) return '—';
    if (text.length <= 8) return text;
    return text.slice(0, 4) + '…' + text.slice(-4);
  }

  function renderSession(data) {
    els.coreVersion.textContent = data.coreVersion || '—';
    els.deviceId.textContent = shortId(data.deviceId);
    els.deviceId.title = data.deviceId ? String(data.deviceId) : '';
    els.consoleAddress.textContent = data.consoleAddress || '—';
    els.apiUrl.textContent = data.apiUrl || '—';

    var enforced = !!data.tokenEnforced;
    els.tokenBadge.className = 'badge ' + (enforced ? 'badge--warn' : 'badge--ok');
    els.tokenBadge.textContent = enforced ? '已启用访问令牌' : '本机免令牌';
    els.tokenBadge.title = enforced
      ? '已配置访问令牌，远程访问必须携带令牌'
      : '未配置访问令牌，仅允许本机访问控制台';
  }

  /* ---------------------------------------------------------------- *
   * 本地实时流量图
   * ---------------------------------------------------------------- */
  function renderTraffic(rows) {
    var labels = rows.map(function (row) { return String(row.Domain || '未命名'); });
    var inbound = rows.map(function (row) { return Number(row.InBytes) || 0; });
    var outbound = rows.map(function (row) { return Number(row.OutBytes) || 0; });

    state.trafficSpec = {
      type: 'bar',
      labels: labels,
      series: [
        { name: '入站', data: inbound, color: TRAFFIC_SERIES[0].color },
        { name: '出站', data: outbound, color: TRAFFIC_SERIES[1].color }
      ],
      yFormat: function (value) { return PX.fmtBytes(value); }
    };
    if (state.redrawTraffic) state.redrawTraffic();

    var totalIn = 0;
    var totalOut = 0;
    var conns = 0;
    rows.forEach(function (row) {
      totalIn += Number(row.InBytes) || 0;
      totalOut += Number(row.OutBytes) || 0;
      conns += Number(row.ConnCount) || 0;
    });

    els.trafficStamp.textContent = '更新于 ' + PX.fmtTime();
    els.trafficFoot.textContent = rows.length
      ? '共 ' + rows.length + ' 条隧道 · 入站 ' + PX.fmtBytes(totalIn) +
        ' · 出站 ' + PX.fmtBytes(totalOut) + ' · 当前连接 ' + PX.fmtNum(conns)
      : '暂无隧道，添加穿透后这里会实时展示流量';
  }

  /* ---------------------------------------------------------------- *
   * 连接数趋势（浏览器端采样）
   * ---------------------------------------------------------------- */
  function sample(value) {
    state.samples.push({ t: PX.fmtTime(), v: Number(value) || 0 });
    if (state.samples.length > SAMPLE_LIMIT) {
      state.samples.splice(0, state.samples.length - SAMPLE_LIMIT);
    }
    if (state.redrawConn) state.redrawConn();

    var latest = state.samples[state.samples.length - 1];
    els.connFoot.textContent = '已采样 ' + state.samples.length + ' 个点 · 最近 ' +
      latest.t + ' · 活跃连接 ' + PX.fmtNum(latest.v);
  }

  /* ---------------------------------------------------------------- *
   * 云端流量记录
   * ---------------------------------------------------------------- */
  function normalizeCloud(item) {
    return {
      id: item.id,
      username: item.username,
      port: item.port,
      receive: Number(item.receive) || 0,
      send: Number(item.send) || 0,
      connectNum: Number(item.connectNum) || 0,
      packNum: Number(item.packNum) || 0,
      createTime: item.createTime
    };
  }

  function plainText(value) {
    return value === undefined || value === null || value === '' ? '-' : String(value);
  }

  /** 数值列按千分位展示；端口属于标识而非数量，保持原样。 */
  function numberText(value) {
    if (value === undefined || value === null || value === '') return '-';
    var n = Number(value);
    return isNaN(n) ? String(value) : PX.fmtNum(n);
  }

  function cloudEmptyRow(title, detail) {
    PX.clear(els.cloudRows);
    els.cloudRows.appendChild(PX.el('tr', {}, [
      PX.el('td', { colspan: String(CLOUD_COLUMNS) }, [PX.empty(title, detail, 'inbox')])
    ]));
  }

  function renderCloudRows() {
    var rows = PX.sortRows(state.cloud, state.sort.key, state.sort.dir,
      SORT_TYPES[state.sort.key] || 'string');
    renderSortIndicators();
    PX.clear(els.cloudRows);

    if (!rows.length) {
      els.cloudRows.appendChild(PX.el('tr', {}, [
        PX.el('td', { colspan: String(CLOUD_COLUMNS) }, [
          PX.empty('暂无云端流量记录', '该账号下还没有统计数据，产生连接后会自动出现', 'inbox')
        ])
      ]));
      return;
    }

    var frag = document.createDocumentFragment();
    rows.forEach(function (row) {
      frag.appendChild(PX.el('tr', {}, [
        PX.el('td', { class: 'table__num', 'data-label': 'ID', text: numberText(row.id) }),
        PX.el('td', { class: 'table__num', 'data-label': '端口', text: plainText(row.port) }),
        PX.el('td', { class: 'table__num', 'data-label': '接收', text: PX.fmtBytes(row.receive) }),
        PX.el('td', { class: 'table__num', 'data-label': '发送', text: PX.fmtBytes(row.send) }),
        PX.el('td', { class: 'table__num', 'data-label': '连接数', text: PX.fmtNum(row.connectNum) }),
        PX.el('td', { class: 'table__num', 'data-label': '数据包', text: PX.fmtNum(row.packNum) }),
        PX.el('td', { 'data-label': '时间', text: plainText(row.createTime) })
      ]));
    });
    els.cloudRows.appendChild(frag);
  }

  function renderSortIndicators() {
    PX.$$('#cloudTable th[data-sort]').forEach(function (th) {
      var active = th.getAttribute('data-sort') === state.sort.key;
      if (active) {
        th.setAttribute('aria-sort', state.sort.dir === 'asc' ? 'ascending' : 'descending');
      } else {
        th.removeAttribute('aria-sort');
      }
      var indicator = th.querySelector('.sort-ind');
      if (indicator) {
        indicator.textContent = active ? (state.sort.dir === 'asc' ? '↑' : '↓') : '↕';
      }
    });
  }

  function loadCloud() {
    var profile = PX.user.profile();
    var username = (profile && profile.username) || '';
    if (!username) {
      cloudEmptyRow('未登录', '登录后才能读取云端流量记录');
      els.cloudFoot.textContent = '加载失败：登录状态已失效，请重新登录';
      PX.toastErr('登录状态已失效，请重新登录', '云端流量记录');
      return Promise.resolve();
    }

    els.cloudFoot.textContent = '正在加载云端流量记录…';
    return PX.api.get('/hp/statistics/getMyInfo', { username: username, page: 1 })
      .then(function (payload) {
        if (!PX.isOk(payload)) {
          var message = PX.msgOf(payload, '云端返回异常');
          cloudEmptyRow('云端记录不可用', message);
          els.cloudFoot.textContent = '加载失败：' + message;
          PX.toastErr(message, '云端流量记录');
          return;
        }
        var list = payload && payload.data && Array.isArray(payload.data.list)
          ? payload.data.list : [];
        state.cloud = list.map(normalizeCloud);
        renderCloudRows();
        els.cloudStamp.textContent = '更新于 ' + PX.fmtTime();
        els.cloudFoot.textContent = state.cloud.length
          ? '共 ' + state.cloud.length + ' 条云端流量记录'
          : '暂无云端流量记录';
      })
      .catch(function (err) {
        var message = errorText(err, '云端流量记录加载失败');
        cloudEmptyRow('加载失败', message);
        els.cloudFoot.textContent = '加载失败：' + message;
        PX.toastErr(message, '云端流量记录');
      });
  }

  /* ---------------------------------------------------------------- *
   * 自动刷新与图表
   * ---------------------------------------------------------------- */
  function startAuto() {
    stopAuto();
    state.timer = setInterval(function () { loadLocal(); }, REFRESH_MS);
  }

  function stopAuto() {
    if (state.timer) {
      clearInterval(state.timer);
      state.timer = null;
    }
  }

  function setupCharts() {
    PX.chart.legend(els.trafficLegend, TRAFFIC_SERIES);
    PX.chart.legend(els.connLegend, CONN_SERIES);

    state.redrawTraffic = PX.chart.auto(els.trafficChart, function () {
      return state.trafficSpec || { type: 'bar', labels: [], series: [] };
    });

    state.redrawConn = PX.chart.auto(els.connChart, function () {
      return {
        type: 'line',
        labels: state.samples.map(function (item) { return item.t; }),
        series: [{
          name: '活跃连接',
          data: state.samples.map(function (item) { return item.v; }),
          color: CONN_SERIES[0].color
        }],
        yFormat: function (value) { return PX.fmtNum(value); }
      };
    });
  }

  function wireToolbar() {
    els.refreshAll.addEventListener('click', function () {
      loadLocal();
      loadCloud();
      PX.toast('已刷新统计数据', { type: 'info', timeout: 1600 });
    });

    els.cloudRefresh.addEventListener('click', function () { loadCloud(); });

    els.autoRefresh.addEventListener('change', function (ev) {
      state.auto = ev.target.checked;
      if (state.auto) {
        startAuto();
        PX.toast('已开启自动刷新（每 ' + (REFRESH_MS / 1000) + ' 秒）', { type: 'info', timeout: 2000 });
      } else {
        stopAuto();
      }
      savePrefs();
    });

    PX.$$('#cloudTable th[data-sort]').forEach(function (th) {
      th.addEventListener('click', function () {
        var key = th.getAttribute('data-sort');
        if (state.sort.key === key) {
          state.sort.dir = state.sort.dir === 'asc' ? 'desc' : 'asc';
        } else {
          state.sort.key = key;
          state.sort.dir = 'desc';
        }
        renderCloudRows();
      });
    });
  }

  function init() {
    var profile = PX.shell.mount({ active: 'stats' });
    if (!profile) return;
    cacheElements();
    PX.applyIcons();

    restorePrefs();
    setupCharts();
    wireToolbar();
    loadLocal();
    loadCloud();
    if (state.auto) startAuto();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
  else init();
})();

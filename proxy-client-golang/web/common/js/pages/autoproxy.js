/* 自动穿透页：云端穿透配置的查看与增删，客户端按 deviceId 启动时自动创建隧道。
   所有动态数据仅通过 textContent / 属性赋值写入 DOM，绝不拼接 HTML。 */
(function () {
  'use strict';
  var PX = window.PX;

  var profile = PX.shell.mount({ active: 'autoproxy' });
  if (!profile) return;
  PX.applyIcons();

  var NO_ID = 'NO_ID';
  var CUSTOM_SERVER = '-1';
  /** 内网服务 / 自定义穿透服务器统一按 ip:端口 校验。 */
  var HOST_PATTERN = /^[^\s:]+:\d{1,5}$/;

  var state = {
    rows: [],
    keyword: '',
    sort: { key: 'deviceId', dir: 'asc' },
    deviceId: '',
    deviceReady: false,
    loading: false
  };

  var els = {};

  var SORT_TYPES = {
    deviceId: 'string',
    userHost: 'string',
    serverHost: 'string',
    type: 'string',
    domain: 'string',
    port: 'number'
  };

  var TYPE_OPTIONS = [
    { value: 'TCP', label: 'TCP', hint: '仅转发 TCP 流量' },
    { value: 'UDP', label: 'UDP', hint: '仅转发 UDP 流量' },
    { value: 'TCP_UDP', label: 'TCP + UDP', hint: '同时转发两种协议' }
  ];

  function byId(id) { return document.getElementById(id); }

  function cacheElements() {
    [
      'addBtn', 'refreshBtn', 'deviceChip', 'deviceIdValue', 'deviceWarn',
      'search', 'tabData', 'listFoot',
      'addModal', 'addClose', 'addCancel', 'addSubmit', 'addForm',
      'userHost', 'typeRadios', 'serverRadios', 'customServerField', 'customServer',
      'domainRadios', 'domainField', 'portRadios'
    ].forEach(function (id) { els[id] = byId(id); });
  }

  /* ---------------------------------------------------------------- *
   * 设备ID
   * ---------------------------------------------------------------- */
  function shortDeviceId(id) {
    var text = String(id || '');
    return text.length > 4 ? text.slice(0, 4) + '…' : text;
  }

  function applyDevice(rawId) {
    var value = String(rawId === null || rawId === undefined ? '' : rawId).trim();
    var valid = value !== '' && value !== NO_ID;
    state.deviceId = valid ? value : '';
    state.deviceReady = valid;

    els.deviceIdValue.textContent = valid ? value : '未设置';
    els.deviceChip.title = valid ? '当前设备ID：' + value : '当前客户端未设置设备ID';
    els.deviceChip.classList.toggle('chip--warn', !valid);
    els.deviceWarn.classList.toggle('hidden', valid);
    els.addBtn.disabled = !valid;
    els.addBtn.setAttribute('aria-disabled', valid ? 'false' : 'true');
  }

  function loadDevice() {
    return PX.api.get('/device/info').then(function (data) {
      var id = '';
      if (typeof data === 'string') id = data;
      else if (data && typeof data === 'object' && data.data !== undefined) id = String(data.data);
      applyDevice(id);
    }).catch(function (err) {
      applyDevice('');
      PX.toastWarn((err && err.message) || '设备ID获取失败', '设备信息');
    });
  }

  /* ---------------------------------------------------------------- *
   * 列表加载与渲染
   * ---------------------------------------------------------------- */
  function load() {
    if (state.loading) return Promise.resolve();
    state.loading = true;
    return PX.api.get('/hp/config/list', { userId: profile.id }).then(function (payload) {
      state.rows = payload && Array.isArray(payload.data) ? payload.data : [];
      render();
    }).catch(function (err) {
      els.listFoot.textContent = '加载失败：' + ((err && err.message) || '未知错误');
      PX.toastErr((err && err.message) || '云端配置加载失败');
    }).then(function () {
      state.loading = false;
    });
  }

  function render() {
    var rows = PX.filterRows(state.rows, state.keyword, ['deviceId', 'userHost', 'serverHost', 'type', 'domain', 'port']);
    rows = PX.sortRows(rows, state.sort.key, state.sort.dir, SORT_TYPES[state.sort.key] || 'string');

    renderSortIndicators();
    renderRows(rows);

    els.listFoot.textContent = '共 ' + state.rows.length + ' 条配置' +
      (state.keyword ? '（筛选出 ' + rows.length + ' 条）' : '') +
      ' · 最近更新 ' + PX.fmtTime();
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

  function typeBadge(type) {
    var value = String(type || '').toUpperCase();
    if (!value) return PX.el('span', { class: 'badge badge--muted', text: '-' });
    var label = value === 'TCP_UDP' ? 'TCP+UDP' : value;
    var variant = value === 'UDP' ? 'badge--warn' : (value === 'TCP_UDP' ? 'badge--info' : 'badge--muted');
    return PX.el('span', { class: 'badge ' + variant, text: label });
  }

  function renderRows(rows) {
    PX.clear(els.tabData);
    if (!rows.length) {
      els.tabData.appendChild(PX.el('tr', {}, [
        PX.el('td', { colspan: '7' }, [
          PX.empty(
            state.rows.length ? '没有匹配的配置' : '还没有云端配置',
            state.rows.length ? '换个关键词试试' : '点击右上角「添加配置」保存第一条自动穿透配置',
            'sync'
          )
        ])
      ]));
      return;
    }

    var frag = document.createDocumentFragment();
    rows.forEach(function (row) {
      var deviceId = String(row.deviceId || '');
      var userHost = String(row.userHost || '');
      var serverHost = String(row.serverHost || '');
      var domain = String(row.domain || '');
      var port = row.port === 0 || row.port ? String(row.port) : '';

      var removeButton = PX.el('button', {
        type: 'button', class: 'btn btn--danger btn--sm',
        title: '删除该配置',
        onclick: function () { removeConfig(row); }
      }, [PX.iconNode('trash', 'icon--sm'), PX.el('span', { class: 'hide-sm', text: '删除' })]);

      frag.appendChild(PX.el('tr', {}, [
        PX.el('td', { 'data-label': '设备ID' }, [
          PX.el('span', { class: 'mono', title: deviceId, text: shortDeviceId(deviceId) || '-' })
        ]),
        PX.el('td', { 'data-label': '内网服务' }, [
          PX.el('span', { class: 'mono', title: userHost, text: userHost || '-' })
        ]),
        PX.el('td', { 'data-label': '穿透服务器', text: serverHost || '-' }),
        PX.el('td', { 'data-label': '类型' }, [typeBadge(row.type)]),
        PX.el('td', { 'data-label': '域名', text: domain || '-' }),
        PX.el('td', { class: 'table__num', 'data-label': '端口', text: port || '-' }),
        PX.el('td', { class: 'table__actions', 'data-label': '操作' }, [removeButton])
      ]));
    });
    els.tabData.appendChild(frag);
  }

  /* ---------------------------------------------------------------- *
   * 删除
   * ---------------------------------------------------------------- */
  function removeConfig(row) {
    var id = row && row.id !== undefined && row.id !== null ? String(row.id) : '';
    if (!id) {
      PX.toastWarn('该配置缺少 ID，无法删除');
      return;
    }
    var userHost = String(row.userHost || '') || '该配置';
    PX.confirm({
      title: '删除配置',
      message: '确定删除「' + userHost + '」的云端穿透配置吗？删除后客户端重启将不再自动创建该隧道。',
      detail: '设备ID：' + (String(row.deviceId || '') || '未知'),
      okText: '删除',
      danger: true
    }).then(function (yes) {
      if (!yes) return;
      PX.api.get('/hp/config/remove', { id: id }).then(function (payload) {
        if (PX.isOk(payload)) {
          PX.toastOk(PX.msgOf(payload, '已删除该配置'));
          load();
        } else {
          PX.toastErr(PX.msgOf(payload, '删除失败'));
        }
      }).catch(function (err) {
        PX.toastErr((err && err.message) || '删除失败');
      });
    });
  }

  /* ---------------------------------------------------------------- *
   * 添加配置表单
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

  function buildServerRadios() {
    radioGroup(els.serverRadios, 'serverHost', [], '');
    return PX.api.get('/hp/load/data').then(function (payload) {
      var list = payload && PX.isOk(payload) && Array.isArray(payload.data) ? payload.data : [];
      var options = list.map(function (item) {
        var address = String(item.ip) + ':' + String(item.port);
        return {
          value: address,
          label: String(item.name || '未命名') + '（' + (Number(item.num) || 0) + '）',
          hint: '云端节点 ' + address
        };
      });
      options.push({ value: CUSTOM_SERVER, label: '自定义', hint: '手动填写穿透服务器地址' });
      radioGroup(els.serverRadios, 'serverHost', options, options[0].value);
      els.customServerField.classList.add('hidden');
    }).catch(function () {
      radioGroup(els.serverRadios, 'serverHost', [{ value: CUSTOM_SERVER, label: '自定义' }], CUSTOM_SERVER);
      els.customServerField.classList.remove('hidden');
      PX.toastWarn('穿透服务器列表加载失败，请手动填写服务器地址');
    });
  }

  function buildDomainRadios() {
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

  function buildPortRadios() {
    var ports = profile && Array.isArray(profile.ports) ? profile.ports : [];
    var options = [{ value: '0', label: '随机分配', hint: '由服务器随机分配外网端口' }];
    ports.forEach(function (port) {
      options.push({ value: String(port), label: String(port) });
    });
    radioGroup(els.portRadios, 'port', options, '0');
  }

  function withCredentials() {
    if (PX.user.password()) return Promise.resolve(PX.user.credentials());
    return PX.user.askPassword().then(function () { return PX.user.credentials(); });
  }

  function openAddModal() {
    if (!state.deviceReady) {
      PX.toastWarn('当前客户端没有可用的设备ID，无法保存云端配置');
      return;
    }
    els.userHost.value = '';
    els.customServer.value = '';
    radioGroup(els.typeRadios, 'type', TYPE_OPTIONS, 'TCP');
    els.domainField.classList.remove('hidden');
    buildDomainRadios();
    buildPortRadios();
    buildServerRadios();
    els.addSubmit.disabled = false;
    els.addSubmit.textContent = '确定添加';
    PX.modal.open(els.addModal);
  }

  function validHost(value) {
    if (!HOST_PATTERN.test(value)) return false;
    var port = Number(String(value).split(':').pop());
    return port >= 1 && port <= 65535;
  }

  function submitAdd() {
    if (!state.deviceReady) {
      PX.toastWarn('当前客户端没有可用的设备ID，无法保存云端配置');
      return;
    }
    var userHost = els.userHost.value.trim();
    var type = checkedValue(els.typeRadios) || 'TCP';
    var serverHost = checkedValue(els.serverRadios);
    var domain = type === 'UDP' ? '' : checkedValue(els.domainRadios);
    var port = checkedValue(els.portRadios) || '0';

    if (!validHost(userHost)) {
      PX.toastWarn('内网服务格式应为 ip:端口，例如 127.0.0.1:8080');
      return;
    }
    if (!serverHost) {
      PX.toastWarn('请选择穿透服务器');
      return;
    }
    if (serverHost === CUSTOM_SERVER) {
      serverHost = els.customServer.value.trim();
      if (!validHost(serverHost)) {
        PX.toastWarn('自定义穿透服务器格式应为 ip:端口，例如 1.2.3.4:9090');
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
      return PX.api.post('/hp/config/save', {
        userHost: userHost,
        type: type,
        serverHost: serverHost,
        domain: domain,
        port: port,
        userId: profile.id,
        username: cred && cred.username ? cred.username : '',
        password: cred && cred.password ? cred.password : '',
        deviceId: state.deviceId
      });
    }).then(function (payload) {
      if (PX.isOk(payload)) {
        PX.toastOk(PX.msgOf(payload, '配置已保存'));
        PX.modal.close(els.addModal);
        load();
      } else {
        PX.toastErr(PX.msgOf(payload, '保存失败'));
      }
    }).catch(function (err) {
      PX.toastErr((err && err.message) || '保存失败');
    }).then(function () {
      els.addSubmit.disabled = false;
      els.addSubmit.textContent = '确定添加';
    });
  }

  /* ---------------------------------------------------------------- *
   * 装配
   * ---------------------------------------------------------------- */
  function wireToolbar() {
    els.addBtn.addEventListener('click', openAddModal);
    els.refreshBtn.addEventListener('click', function () {
      loadDevice();
      load();
      PX.toast('已刷新云端配置', { type: 'info', timeout: 1600 });
    });

    els.search.addEventListener('input', PX.debounce(function (ev) {
      state.keyword = ev.target.value;
      render();
    }, 160));

    PX.$$('th[data-sort]').forEach(function (th) {
      th.addEventListener('click', function () {
        var key = th.getAttribute('data-sort');
        if (state.sort.key === key) {
          state.sort.dir = state.sort.dir === 'asc' ? 'desc' : 'asc';
        } else {
          state.sort.key = key;
          state.sort.dir = key === 'deviceId' ? 'asc' : 'desc';
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

    // 表单不使用原生提交，避免回车导致页面跳转。
    els.addForm.addEventListener('submit', function (ev) {
      ev.preventDefault();
      submitAdd();
    });

    els.typeRadios.addEventListener('change', function () {
      var isUdp = checkedValue(els.typeRadios) === 'UDP';
      els.domainField.classList.toggle('hidden', isUdp);
    });

    els.serverRadios.addEventListener('change', function () {
      var isCustom = checkedValue(els.serverRadios) === CUSTOM_SERVER;
      els.customServerField.classList.toggle('hidden', !isCustom);
      if (isCustom) els.customServer.focus();
    });
  }

  cacheElements();
  wireToolbar();
  wireModals();
  loadDevice();
  load();
})();

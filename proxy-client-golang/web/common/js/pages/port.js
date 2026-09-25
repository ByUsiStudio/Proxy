/* 端口管理页：外网端口列表、搜索排序、添加与删除
   约束：所有服务端数据一律通过 PX.el(..., {text}) / textContent 写入，绝不拼接 HTML。 */
(function () {
  'use strict';
  var PX = window.PX;

  var profile = PX.shell.mount({ active: 'port' });
  if (!profile) return;

  PX.applyIcons();

  var els = {};
  ['tabData', 'listFoot', 'search', 'addBtn', 'refreshBtn', 'addModal', 'addClose',
    'addCancel', 'addSubmit', 'portForm', 'port'].forEach(function (id) {
    els[id] = document.getElementById(id);
  });

  var state = {
    rows: [],
    sort: { key: 'port', dir: 'asc' },
    keyword: '',
    loading: false,
    submitting: false
  };

  /* ---------------------------------------------------------------- *
   * 数据加载与渲染
   * ---------------------------------------------------------------- */
  function currentUserId() {
    var current = PX.user.profile();
    return current && current.id ? current.id : (profile && profile.id ? profile.id : '');
  }

  function loadList() {
    var userId = currentUserId();
    if (!userId) {
      els.listFoot.textContent = '登录状态已失效，请重新登录。';
      PX.toastErr('登录状态已失效，请重新登录');
      return Promise.resolve();
    }
    if (state.loading) return Promise.resolve();
    state.loading = true;
    els.listFoot.textContent = '正在加载端口列表…';

    return PX.api.get('/hp/server/portList', { userId: userId }).then(function (payload) {
      if (!PX.isOk(payload)) {
        throw new PX.ApiError(PX.msgOf(payload, '端口列表加载失败'), 200, payload);
      }
      var list = payload && Array.isArray(payload.data) ? payload.data : [];
      state.rows = list.map(function (item) {
        var value = item && item.port !== undefined && item.port !== null ? item.port : '';
        var num = Number(value);
        return {
          port: isNaN(num) ? 0 : num,
          raw: value === '' ? '' : String(value)
        };
      }).filter(function (row) { return row.raw !== ''; });
      render();
    }).catch(function (err) {
      els.listFoot.textContent = '加载失败：' + ((err && err.message) || '未知错误');
      PX.toastErr((err && err.message) || '端口列表加载失败');
    }).then(function () {
      state.loading = false;
    });
  }

  function render() {
    var rows = PX.filterRows(state.rows, state.keyword, ['raw']);
    rows = PX.sortRows(rows, state.sort.key, state.sort.dir, 'number');
    renderSortIndicators();
    renderRows(rows);

    els.listFoot.textContent = '共 ' + state.rows.length + ' 个端口' +
      (state.keyword ? '（筛选出 ' + rows.length + ' 个）' : '') +
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

  function renderRows(rows) {
    PX.clear(els.tabData);
    if (!rows.length) {
      els.tabData.appendChild(PX.el('tr', {}, [
        PX.el('td', { colspan: '2' }, [
          PX.empty(
            state.rows.length ? '没有匹配的端口' : '还没有可用端口',
            state.rows.length ? '换个端口号关键词试试' : '点击右上角「添加端口」添加第一个外网端口',
            'server'
          )
        ])
      ]));
      return;
    }

    var frag = document.createDocumentFragment();
    rows.forEach(function (row) {
      var deleteButton = PX.el('button', {
        type: 'button',
        class: 'btn btn--danger btn--sm',
        title: '删除端口 ' + row.raw,
        onclick: function () { removePort(row.port); }
      }, [PX.iconNode('trash', 'icon--sm'), PX.el('span', { class: 'hide-sm', text: '删除' })]);

      frag.appendChild(PX.el('tr', {}, [
        PX.el('td', { 'data-label': '端口号' }, [
          PX.el('span', { class: 'mono', text: row.raw })
        ]),
        PX.el('td', { class: 'table__actions', 'data-label': '操作' }, [deleteButton])
      ]));
    });
    els.tabData.appendChild(frag);
  }

  /** 删除/添加成功后重新登录以刷新本地保存的端口配置，再重新拉取列表。 */
  function refreshProfileThenList() {
    return PX.user.refresh().then(function () {
      return loadList();
    }).catch(function (err) {
      PX.toastWarn((err && err.message) || '账号配置刷新失败，列表可能不是最新');
      return loadList();
    });
  }

  /* ---------------------------------------------------------------- *
   * 操作
   * ---------------------------------------------------------------- */
  function removePort(port) {
    var num = Number(port);
    if (!num || num < 1 || num > 65535) {
      PX.toastWarn('端口号无效，无法删除');
      return;
    }
    PX.confirm({
      title: '删除端口',
      message: '确定要删除外网端口「' + num + '」吗？',
      detail: '删除后，使用该端口创建的隧道将无法继续绑定，需要重新添加。',
      okText: '删除',
      danger: true
    }).then(function (yes) {
      if (!yes) return null;
      return PX.api.post('/hp/server/portRemove', {
        userId: currentUserId(),
        port: num
      }).then(function (payload) {
        if (PX.isOk(payload)) {
          PX.toastOk(PX.msgOf(payload, '端口已删除'));
          return refreshProfileThenList();
        }
        PX.toastErr(PX.msgOf(payload, '删除失败'));
        return null;
      }).catch(function (err) {
        PX.toastErr((err && err.message) || '删除失败');
        return null;
      });
    });
  }

  function openAddModal() {
    els.port.value = '';
    PX.modal.open(els.addModal);
  }

  function closeAddModal() {
    PX.modal.close(els.addModal);
  }

  function submitAdd() {
    if (state.submitting) return;
    var raw = els.port.value.trim();

    if (!raw) {
      PX.toastWarn('请输入外网端口号');
      return;
    }
    if (!/^\d+$/.test(raw)) {
      PX.toastWarn('端口号只能是数字，请勿输入空格或符号');
      return;
    }
    var num = Number(raw);
    if (num < 1024 || num > 65535) {
      PX.toastWarn('端口号必须在 1024-65535 之间');
      return;
    }
    var duplicated = state.rows.some(function (row) { return row.port === num; });
    if (duplicated) {
      PX.toastWarn('端口 ' + num + ' 已存在，请勿重复添加');
      return;
    }

    state.submitting = true;
    els.addSubmit.disabled = true;
    els.addSubmit.textContent = '提交中…';

    PX.api.post('/hp/server/portAdd', {
      userId: currentUserId(),
      port: num
    }).then(function (payload) {
      if (!PX.isOk(payload)) {
        PX.toastErr(PX.msgOf(payload, '添加失败'));
        return null;
      }
      PX.toastOk(PX.msgOf(payload, '端口添加成功'));
      closeAddModal();
      return refreshProfileThenList();
    }).catch(function (err) {
      PX.toastErr((err && err.message) || '添加失败');
      return null;
    }).then(function () {
      state.submitting = false;
      els.addSubmit.disabled = false;
      els.addSubmit.textContent = '确定添加';
    });
  }

  /* ---------------------------------------------------------------- *
   * 事件装配
   * ---------------------------------------------------------------- */
  els.addBtn.addEventListener('click', openAddModal);
  els.refreshBtn.addEventListener('click', function () {
    loadList();
    PX.toast('已刷新端口列表', { type: 'info', timeout: 1600 });
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
        state.sort.dir = 'asc';
      }
      render();
    });
  });

  els.addClose.addEventListener('click', closeAddModal);
  els.addCancel.addEventListener('click', closeAddModal);
  els.addSubmit.addEventListener('click', submitAdd);
  els.addModal.addEventListener('click', function (ev) { if (ev.target === els.addModal) closeAddModal(); });
  els.portForm.addEventListener('submit', function (ev) {
    ev.preventDefault();
    submitAdd();
  });
  els.port.addEventListener('keydown', function (ev) {
    if (ev.key === 'Enter') {
      ev.preventDefault();
      submitAdd();
    }
  });

  loadList();
})();

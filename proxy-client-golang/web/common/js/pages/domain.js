/* 域名管理页：二级域名 / 自定义域名列表、搜索排序、添加与删除
   约束：所有服务端数据一律通过 PX.el(..., {text}) / textContent 写入，绝不拼接 HTML。 */
(function () {
  'use strict';
  var PX = window.PX;

  var profile = PX.shell.mount({ active: 'domain' });
  if (!profile) return;

  PX.applyIcons();

  var els = {};
  ['tabData', 'listFoot', 'search', 'addBtn', 'refreshBtn', 'addModal', 'addClose',
    'addCancel', 'addSubmit', 'domainForm', 'domain', 'customDomain'].forEach(function (id) {
    els[id] = document.getElementById(id);
  });

  var state = {
    rows: [],
    sort: { key: 'domain', dir: 'asc' },
    keyword: '',
    loading: false,
    submitting: false
  };

  var SUBDOMAIN_RE = /^[a-zA-Z0-9]+$/;
  var HOSTNAME_RE = /^[a-zA-Z0-9]([a-zA-Z0-9.-]*[a-zA-Z0-9])?$/;

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
    els.listFoot.textContent = '正在加载域名列表…';

    return PX.api.get('/hp/server/domainList', { userId: userId }).then(function (payload) {
      if (!PX.isOk(payload)) {
        throw new PX.ApiError(PX.msgOf(payload, '域名列表加载失败'), 200, payload);
      }
      var list = payload && Array.isArray(payload.data) ? payload.data : [];
      state.rows = list.map(function (item) {
        return {
          domain: String((item && item.domain) || ''),
          customDomain: String((item && item.customDomain) || '')
        };
      }).filter(function (row) { return row.domain !== ''; });
      render();
    }).catch(function (err) {
      els.listFoot.textContent = '加载失败：' + ((err && err.message) || '未知错误');
      PX.toastErr((err && err.message) || '域名列表加载失败');
    }).then(function () {
      state.loading = false;
    });
  }

  function render() {
    var rows = PX.filterRows(state.rows, state.keyword, ['domain', 'customDomain']);
    rows = PX.sortRows(rows, state.sort.key, state.sort.dir, 'string');
    renderSortIndicators();
    renderRows(rows);

    els.listFoot.textContent = '共 ' + state.rows.length + ' 个域名' +
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
        PX.el('td', { colspan: '3' }, [
          PX.empty(
            state.rows.length ? '没有匹配的域名' : '还没有可用域名',
            state.rows.length ? '换个关键词试试' : '点击右上角「添加域名」添加第一个二级域名',
            'globe'
          )
        ])
      ]));
      return;
    }

    var frag = document.createDocumentFragment();
    rows.forEach(function (row) {
      var customCell = row.customDomain
        ? PX.el('span', { class: 'mono', title: row.customDomain, text: row.customDomain })
        : PX.el('span', { class: 'badge badge--muted', text: '未设置' });

      var deleteButton = PX.el('button', {
        type: 'button',
        class: 'btn btn--danger btn--sm',
        title: '删除域名 ' + row.domain,
        onclick: function () { removeDomain(row.domain); }
      }, [PX.iconNode('trash', 'icon--sm'), PX.el('span', { class: 'hide-sm', text: '删除' })]);

      frag.appendChild(PX.el('tr', {}, [
        PX.el('td', { 'data-label': '二级域名' }, [
          PX.el('span', { class: 'mono', title: row.domain, text: row.domain })
        ]),
        PX.el('td', { 'data-label': '自定义域名' }, [customCell]),
        PX.el('td', { class: 'table__actions', 'data-label': '操作' }, [deleteButton])
      ]));
    });
    els.tabData.appendChild(frag);
  }

  /** 删除/添加成功后重新登录以刷新本地保存的域名配置，再重新拉取列表。 */
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
  function removeDomain(domain) {
    var value = String(domain || '').trim();
    if (!value) {
      PX.toastWarn('域名无效，无法删除');
      return;
    }
    PX.confirm({
      title: '删除域名',
      message: '确定要删除二级域名「' + value + '」吗？',
      detail: '依赖该域名的隧道将无法继续使用，自定义域名绑定同时失效。',
      okText: '删除',
      danger: true
    }).then(function (yes) {
      if (!yes) return null;
      return PX.api.post('/hp/server/domainRemove', {
        userId: currentUserId(),
        domain: value
      }).then(function (payload) {
        if (PX.isOk(payload)) {
          PX.toastOk(PX.msgOf(payload, '域名已删除'));
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
    els.domain.value = '';
    els.customDomain.value = '';
    PX.modal.open(els.addModal);
  }

  function closeAddModal() {
    PX.modal.close(els.addModal);
  }

  function submitAdd() {
    if (state.submitting) return;
    var domain = els.domain.value.trim();
    var customDomain = els.customDomain.value.trim();

    if (!domain) {
      PX.toastWarn('请输入二级域名');
      return;
    }
    if (!SUBDOMAIN_RE.test(domain)) {
      PX.toastWarn('二级域名只能包含字母和数字，请勿使用符号或中文');
      return;
    }
    if (customDomain) {
      if (customDomain.indexOf('://') !== -1 || customDomain.indexOf('/') !== -1) {
        PX.toastWarn('自定义域名不要带 http:// 或路径，直接填写域名即可');
        return;
      }
      if (!HOSTNAME_RE.test(customDomain) || customDomain.indexOf('.') === -1) {
        PX.toastWarn('自定义域名格式不正确，例如 cdn.example.com');
        return;
      }
    }
    var duplicated = state.rows.some(function (row) { return row.domain === domain; });
    if (duplicated) {
      PX.toastWarn('二级域名 ' + domain + ' 已存在，请勿重复添加');
      return;
    }

    state.submitting = true;
    els.addSubmit.disabled = true;
    els.addSubmit.textContent = '提交中…';

    if (customDomain) {
      PX.toast('自定义域名需在域名服务商处把 ' + customDomain + ' CNAME 指向 ' + domain + '，解析生效后才可访问', {
        type: 'info',
        title: '解析提示',
        timeout: 5000
      });
    }

    PX.api.post('/hp/server/domainAdd', {
      userId: currentUserId(),
      domain: domain,
      customDomain: customDomain
    }).then(function (payload) {
      if (!PX.isOk(payload)) {
        PX.toastErr(PX.msgOf(payload, '添加失败'));
        return null;
      }
      PX.toastOk(PX.msgOf(payload, '域名添加成功'));
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
    PX.toast('已刷新域名列表', { type: 'info', timeout: 1600 });
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
  els.domainForm.addEventListener('submit', function (ev) {
    ev.preventDefault();
    submitAdd();
  });
  els.domain.addEventListener('keydown', function (ev) {
    if (ev.key === 'Enter') {
      ev.preventDefault();
      submitAdd();
    }
  });
  els.customDomain.addEventListener('keydown', function (ev) {
    if (ev.key === 'Enter') {
      ev.preventDefault();
      submitAdd();
    }
  });

  loadList();
})();

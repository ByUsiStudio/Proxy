<#include "./header.ftl">
<#--  用量配额
      model（QuotaController#page）：page / pageSize / totalRow / totalPage / list(List<QuotaEntity>) / username
      QuotaEntity：id / userId / username / maxTunnels / maxPorts / maxConns /
                   monthlyReceive / monthlySend / enabled / overLimit / overReason / note /
                   createTime / updateTime

      说明：
      · 流量上限在库里以「字节数」字符串保存，页面上按 MB / GB 输入，单位换算在服务端完成
        （MB = 1024²，GB = 1024³），避免前端伪造上限值。
      · 数值列同时输出一个隐藏 input 承载原始字节数，使得表头排序按真实大小比较，
        而不是按「1.20 GB / 900 MB」这类可读文本比较。
      · 所有插值均显式转义（框架不自动转义）。  -->
<main class="content">
    <div class="page-head">
        <div>
            <h1 class="page-head__title">用量配额</h1>
            <div class="page-head__desc">
                按账号限制隧道条数与月度流量；超限后云端会拒绝下发新的隧道配置，
                客户端也不会再收到既有隧道列表。并发上限与端口上限目前仅作参考元数据。
            </div>
        </div>
        <div class="toolbar">
            <label class="switch" title="按固定间隔自动刷新列表">
                <input type="checkbox" id="autoRefresh">
                <span class="switch__track"></span>
                <span>自动刷新</span>
            </label>
            <span class="refresh-hint" id="refreshHint"></span>
            <button type="button" class="btn btn--subtle" id="reloadBtn">
                <span data-icon="refresh" data-icon-class="icon--sm"></span>
                <span class="btn__label">立即刷新</span>
            </button>
            <button type="button" class="btn btn--primary" id="quotaCreate">
                <span data-icon="plus" data-icon-class="icon--sm"></span>
                <span>新增配额</span>
            </button>
        </div>
    </div>

    <section class="card">
        <div class="card__body">
            <form class="filter-bar" method="get" action="/admin/quota">
                <input class="input" id="username" name="username" type="text"
                       value="${(username!"")?html}" placeholder="用户名关键字" aria-label="按用户名筛选"/>
                <button class="btn btn--subtle" type="submit" id="selectButton">
                    <span data-icon="search" data-icon-class="icon--sm"></span>
                    <span>查询</span>
                </button>
                <a class="btn btn--ghost" href="/admin/quota">
                    <span data-icon="close" data-icon-class="icon--sm"></span>
                    <span>重置</span>
                </a>
                <span class="filter-bar__divider" aria-hidden="true"></span>
                <button type="button" class="btn btn--ghost" id="exportCsv">
                    <span data-icon="download" data-icon-class="icon--sm"></span>
                    <span>导出 CSV</span>
                </button>
                <button type="button" class="btn btn--ghost" id="exportJson">
                    <span data-icon="download" data-icon-class="icon--sm"></span>
                    <span>导出 JSON</span>
                </button>
                <span class="filter-bar__divider" aria-hidden="true"></span>
                <button type="button" class="btn btn--subtle" id="refreshQuota">
                    <span data-icon="sync" data-icon-class="icon--sm"></span>
                    <span>刷新超限状态</span>
                </button>
            </form>
        </div>
    </section>

    <section class="card">
        <div class="card__head">
            <div class="card__title">
                <span data-icon="shield" data-icon-class="icon--sm"></span>
                <span>配额列表</span>
                <span class="card__hint">共 ${totalRow?c} 条</span>
            </div>
            <div class="toolbar">
                <input class="input" type="search" data-table-filter="quotaTable"
                       placeholder="本页过滤" aria-label="本页快速过滤"/>
                <button type="button" class="btn btn--danger btn--sm" id="batchRemove"
                        data-label="批量删除" data-icon-name="trash" disabled>
                    <span data-icon="trash" data-icon-class="icon--sm"></span>
                    <span>批量删除</span>
                </button>
            </div>
        </div>
        <div class="card__body card__body--flush">
            <div class="bulk-bar">
                已选中 <span class="bulk-bar__count" id="bulkCount">0</span> 条配额
            </div>
            <div class="table-wrap">
                <table class="table" id="quotaTable" data-sortable="true">
                    <thead>
                    <tr>
                        <th class="col-check" data-sort-ignore>
                            <input type="checkbox" id="selectAll" aria-label="全选当前页"/>
                        </th>
                        <th>用户名</th>
                        <th data-sort-type="number">隧道上限</th>
                        <th data-sort-type="number">端口上限</th>
                        <th data-sort-type="number">并发上限</th>
                        <th data-sort-type="number">月度接收上限</th>
                        <th data-sort-type="number">月度发送上限</th>
                        <th>启用</th>
                        <th>超限状态</th>
                        <th>备注</th>
                        <th>更新时间</th>
                        <th data-sort-ignore>操作</th>
                    </tr>
                    </thead>
                    <tbody id="quotaRows">
                    <#if list??>
                        <#list list as quota>
                            <tr data-key="${(quota.id!"")?html}">
                                <td class="col-check">
                                    <input type="checkbox" data-row-key="${(quota.id!"")?html}"
                                           aria-label="选择配额 ${(quota.username!"")?html}"/>
                                </td>
                                <td>${(quota.username!"")?html}</td>
                                <td>${(quota.maxTunnels!0)?c}</td>
                                <td>${(quota.maxPorts!0)?c}</td>
                                <td>${(quota.maxConns!0)?c}</td>
                                <td>
                                    <span data-bytes="${(quota.monthlyReceive!"0")?html}"
                                          title="${(quota.monthlyReceive!"0")?html} 字节">${(quota.monthlyReceive!"0")?html}</span>
                                    <input type="hidden" value="${(quota.monthlyReceive!"0")?html}"/>
                                </td>
                                <td>
                                    <span data-bytes="${(quota.monthlySend!"0")?html}"
                                          title="${(quota.monthlySend!"0")?html} 字节">${(quota.monthlySend!"0")?html}</span>
                                    <input type="hidden" value="${(quota.monthlySend!"0")?html}"/>
                                </td>
                                <td>
                                    <#if (quota.enabled!0) == 1>
                                        <span class="badge badge--ok">启用</span>
                                    <#else>
                                        <span class="badge badge--muted">停用</span>
                                    </#if>
                                </td>
                                <td>
                                    <#if (quota.overLimit!"false") == "true">
                                        <span class="badge badge--fail"
                                              title="${(quota.overReason!"")?html}">已超限</span>
                                    <#else>
                                        <span class="badge badge--ok-soft"
                                              title="${(quota.overReason!"")?html}">正常</span>
                                    </#if>
                                </td>
                                <td>${(quota.note!"")?html}</td>
                                <td>${(quota.updateTime!"")?html}</td>
                                <td>
                                    <div class="cell-actions">
                                        <button type="button" class="btn btn--subtle btn--sm" data-quota-edit
                                                data-id="${(quota.id!"")?html}"
                                                data-username="${(quota.username!"")?html}"
                                                data-max-tunnels="${(quota.maxTunnels!0)?c}"
                                                data-max-ports="${(quota.maxPorts!0)?c}"
                                                data-max-conns="${(quota.maxConns!0)?c}"
                                                data-monthly-receive="${(quota.monthlyReceive!"0")?html}"
                                                data-monthly-send="${(quota.monthlySend!"0")?html}"
                                                data-enabled="${(quota.enabled!0)?c}"
                                                data-note="${(quota.note!"")?html}">
                                            <span data-icon="gear" data-icon-class="icon--sm"></span>
                                            <span>编辑</span>
                                        </button>
                                        <button type="button" class="btn btn--danger btn--sm" data-quota-remove
                                                data-id="${(quota.id!"")?html}"
                                                data-username="${(quota.username!"")?html}">
                                            <span data-icon="trash" data-icon-class="icon--sm"></span>
                                            <span>删除</span>
                                        </button>
                                    </div>
                                </td>
                            </tr>
                        </#list>
                    </#if>
                    </tbody>
                </table>
            </div>
        </div>
        <div id="box" class="pagger"
             data-pager-url="/admin/quota"
             data-pager-page="${page?c}"
             data-pager-total="${totalPage?c}"
             data-pager-params="username"></div>
    </section>
</main>

<#--  新增 / 编辑配额  -->
<div class="modal" id="quotaModal" role="dialog" aria-modal="true" aria-hidden="true"
     aria-labelledby="quotaModalTitle">
    <div class="modal__panel modal__panel--wide">
        <div class="modal__head">
            <h3 class="modal__title" id="quotaModalTitle">新增用量配额</h3>
            <button type="button" class="btn btn--ghost btn--icon" data-dialog-close aria-label="关闭">
                <span data-icon="close" data-icon-class="icon--sm"></span>
            </button>
        </div>
        <div class="modal__body">
            <div class="alert alert--info">
                <span data-icon="info"></span>
                <div>
                    数值上限填 <strong>0 表示不限制</strong>。月度流量按 <strong>MB / GB</strong> 输入
                    （1 GB = 1024 MB = 1024³ 字节），服务端换算成字节数保存。
                    <strong>当前真正强制的是「隧道上限」与「月度流量」</strong>；
                    并发上限、端口上限目前只作参考元数据（云端无法观测每个账号的实时连接数，
                    端口上限在数据模型里也没有明确口径）。
                </div>
            </div>
            <input type="hidden" id="quotaId"/>
            <div class="field">
                <label class="field__label" for="quotaUsername">
                    用户名
                    <span class="field__hint">必须是已存在的账号（编辑时不可改）</span>
                </label>
                <input class="input" id="quotaUsername" type="text" placeholder="账号（邮箱）" data-autofocus/>
            </div>
            <div class="field-row">
                <div class="field">
                    <label class="field__label" for="quotaMaxTunnels">隧道上限</label>
                    <input class="input" id="quotaMaxTunnels" type="number" min="0" max="10000" value="0"/>
                </div>
                <div class="field">
                    <label class="field__label" for="quotaMaxPorts">端口上限</label>
                    <input class="input" id="quotaMaxPorts" type="number" min="0" max="10000" value="0"/>
                </div>
                <div class="field">
                    <label class="field__label" for="quotaMaxConns">并发上限</label>
                    <input class="input" id="quotaMaxConns" type="number" min="0" max="1000000" value="0"/>
                </div>
            </div>
            <div class="field-row">
                <div class="field">
                    <label class="field__label" for="quotaMonthlyReceive">
                        月度接收上限
                        <span class="field__hint">0 = 不限制</span>
                    </label>
                    <div class="field-row">
                        <input class="input" id="quotaMonthlyReceive" type="number" min="0" step="0.01" value="0"/>
                        <select class="select" id="quotaReceiveUnit" aria-label="接收流量单位">
                            <option value="GB">GB</option>
                            <option value="MB">MB</option>
                        </select>
                    </div>
                </div>
                <div class="field">
                    <label class="field__label" for="quotaMonthlySend">
                        月度发送上限
                        <span class="field__hint">0 = 不限制</span>
                    </label>
                    <div class="field-row">
                        <input class="input" id="quotaMonthlySend" type="number" min="0" step="0.01" value="0"/>
                        <select class="select" id="quotaSendUnit" aria-label="发送流量单位">
                            <option value="GB">GB</option>
                            <option value="MB">MB</option>
                        </select>
                    </div>
                </div>
            </div>
            <div class="field">
                <label class="field__label" for="quotaEnabled">状态</label>
                <select class="select" id="quotaEnabled">
                    <option value="1">启用</option>
                    <option value="0">停用</option>
                </select>
            </div>
            <div class="field">
                <label class="field__label" for="quotaNote">备注</label>
                <input class="input" id="quotaNote" type="text" maxlength="200" placeholder="仅供后台记录"/>
            </div>
        </div>
        <div class="modal__foot">
            <button type="button" class="btn btn--ghost" data-dialog-close>取消</button>
            <button type="button" class="btn btn--primary" id="quotaSave">保存</button>
        </div>
    </div>
</div>

<script>
    Admin.setTitle('用量配额');

    (function () {
        'use strict';

        /* 单位换算基数：必须与 QuotaController#parseTraffic 完全一致 */
        var UNIT_MB = 1024 * 1024;
        var UNIT_GB = 1024 * 1024 * 1024;

        function byId(id) { return document.getElementById(id); }

        /* 把库里的原始字节数渲染成可读文本；0 表示不限制 */
        function formatByteCells() {
            Array.prototype.slice.call(document.querySelectorAll('[data-bytes]')).forEach(function (el) {
                var raw = Number(el.getAttribute('data-bytes')) || 0;
                el.textContent = raw > 0 ? Admin.fmtBytes(raw) : '不限制';
            });
        }

        /* 字节数 -> 表单值 + 单位（能整除 GB 就用 GB，否则用 MB），与服务端换算口径一致 */
        function fromBytes(bytes) {
            var n = Number(bytes) || 0;
            if (n <= 0) { return { value: 0, unit: 'GB' }; }
            if (n % UNIT_GB === 0) { return { value: n / UNIT_GB, unit: 'GB' }; }
            if (n >= UNIT_GB) { return { value: Math.round(n / UNIT_GB * 100) / 100, unit: 'GB' }; }
            return { value: Math.round(n / UNIT_MB * 100) / 100, unit: 'MB' };
        }

        function filterQuery(extraPage) {
            var params = new URLSearchParams();
            var u = byId('username');
            if (u && u.value.trim()) { params.set('username', u.value.trim()); }
            if (extraPage) { params.set('page', extraPage); }
            var s = params.toString();
            return s ? '?' + s : '';
        }

        function currentUrl(page) { return '/admin/quota' + filterQuery(page); }

        function download(url) {
            var link = document.createElement('a');
            link.href = url;
            link.rel = 'noopener';
            document.body.appendChild(link);
            link.click();
            link.remove();
        }

        function exportData(format) {
            var query = filterQuery();
            download('/admin/quota/export' + query + (query ? '&' : '?') + 'format=' + encodeURIComponent(format));
            Admin.toast('正在导出当前筛选条件下的全部配额', { type: 'info', timeout: 2600 });
        }

        /* ---------------- 新增 / 编辑 ---------------- */
        function openCreate() {
            byId('quotaModalTitle').textContent = '新增用量配额';
            byId('quotaId').value = '';
            byId('quotaUsername').value = '';
            byId('quotaUsername').readOnly = false;
            byId('quotaMaxTunnels').value = '0';
            byId('quotaMaxPorts').value = '0';
            byId('quotaMaxConns').value = '0';
            byId('quotaMonthlyReceive').value = '0';
            byId('quotaMonthlySend').value = '0';
            byId('quotaReceiveUnit').value = 'GB';
            byId('quotaSendUnit').value = 'GB';
            byId('quotaEnabled').value = '1';
            byId('quotaNote').value = '';
            Admin.dialog.open('quotaModal');
        }

        function openEdit(btn) {
            var receive = fromBytes(btn.getAttribute('data-monthly-receive'));
            var send = fromBytes(btn.getAttribute('data-monthly-send'));
            byId('quotaModalTitle').textContent = '编辑用量配额';
            byId('quotaId').value = btn.getAttribute('data-id') || '';
            byId('quotaUsername').value = btn.getAttribute('data-username') || '';
            /* 账号是配额的业务键，编辑时不允许改（改账号等于换一条配额） */
            byId('quotaUsername').readOnly = true;
            byId('quotaMaxTunnels').value = btn.getAttribute('data-max-tunnels') || '0';
            byId('quotaMaxPorts').value = btn.getAttribute('data-max-ports') || '0';
            byId('quotaMaxConns').value = btn.getAttribute('data-max-conns') || '0';
            byId('quotaMonthlyReceive').value = String(receive.value);
            byId('quotaReceiveUnit').value = receive.unit;
            byId('quotaMonthlySend').value = String(send.value);
            byId('quotaSendUnit').value = send.unit;
            byId('quotaEnabled').value = btn.getAttribute('data-enabled') === '0' ? '0' : '1';
            byId('quotaNote').value = btn.getAttribute('data-note') || '';
            Admin.dialog.open('quotaModal');
        }

        function saveQuota() {
            var username = byId('quotaUsername').value.trim();
            if (!username) {
                Admin.toastWarn('请填写用户名');
                return;
            }
            var button = byId('quotaSave');
            button.disabled = true;
            var original = button.textContent;
            button.textContent = '保存中…';
            Admin.post('/admin/quota/save', {
                id: byId('quotaId').value,
                username: username,
                maxTunnels: byId('quotaMaxTunnels').value,
                maxPorts: byId('quotaMaxPorts').value,
                maxConns: byId('quotaMaxConns').value,
                monthlyReceive: byId('quotaMonthlyReceive').value,
                monthlySend: byId('quotaMonthlySend').value,
                receiveUnit: byId('quotaReceiveUnit').value,
                sendUnit: byId('quotaSendUnit').value,
                enabled: byId('quotaEnabled').value,
                note: byId('quotaNote').value
            }).then(function (res) {
                var payload = res.data;
                if (!res.ok || !Admin.isOk(payload)) {
                    Admin.toastErr(Admin.msgOf(payload, '保存失败（HTTP ' + res.status + '）'));
                    return;
                }
                Admin.toastOk(Admin.msgOf(payload, '保存成功'));
                Admin.dialog.close(byId('quotaModal'));
                refreshList();
            }).catch(function (err) {
                Admin.toastErr(err.message || '保存失败');
            }).then(function () {
                button.disabled = false;
                button.textContent = original;
            });
        }

        /* ---------------- 批量删除 / 单条删除 ---------------- */
        var selection = null;

        function wireBatch() {
            selection = Admin.selection({
                table: 'quotaTable',
                selectAll: 'selectAll',
                actions: ['batchRemove'],
                onChange: function (keys) {
                    byId('bulkCount').textContent = String(keys.length);
                }
            });

            byId('batchRemove').addEventListener('click', function () {
                if (!selection) { return; }
                var ids = selection.keys();
                Admin.batchRemove({
                    url: '/admin/quota/batchRemove',
                    ids: ids,
                    title: '批量删除用量配额',
                    message: '确定删除选中的 ' + ids.length + ' 条配额吗？删除后对应账号将不再受配额限制。',
                    okText: '全部删除',
                    onDone: function () {
                        if (selection) { selection.clear(); }
                        refreshList();
                    }
                });
            });
        }

        function removeQuota(btn) {
            var username = btn.getAttribute('data-username') || '';
            var id = btn.getAttribute('data-id') || '';
            Admin.confirm({
                title: '删除用量配额',
                message: '确定删除账号 ' + username + ' 的用量配额吗？删除后该账号将不再受配额限制。',
                detail: id,
                okText: '确认删除',
                danger: true
            }).then(function (yes) {
                if (!yes) { return; }
                Admin.post('/admin/quota/remove', { id: id }).then(function (res) {
                    var payload = res.data;
                    if (!res.ok || !Admin.isOk(payload)) {
                        Admin.toastErr(Admin.msgOf(payload, '删除失败（HTTP ' + res.status + '）'));
                        return;
                    }
                    Admin.toastOk(Admin.msgOf(payload, '已删除'));
                    refreshList();
                }).catch(function (err) {
                    Admin.toastErr(err.message || '删除失败');
                });
            });
        }

        /* ---------------- 刷新 ---------------- */
        function refreshList() {
            return Admin.refreshRegions(currentUrl(), ['#quotaRows', '#box']).then(function () {
                Admin.bindSortableTables();
                Admin.initPagers();
                Admin.bindTableFilters();
                formatByteCells();
                if (selection) { selection.sync(); }
            }).catch(function (err) {
                Admin.toastWarn(err.message || '列表刷新失败');
            });
        }

        function init() {
            formatByteCells();
            wireBatch();

            Admin.autoRefresh({
                toggle: 'autoRefresh',
                interval: 30000,
                storageKey: 'px_admin_quota_autorefresh',
                countdown: 'refreshHint',
                onTick: refreshList
            });

            byId('reloadBtn').addEventListener('click', function () {
                refreshList();
                Admin.toast('已刷新配额列表', { type: 'info', timeout: 1600 });
            });
            byId('quotaCreate').addEventListener('click', openCreate);
            byId('quotaSave').addEventListener('click', saveQuota);
            byId('exportCsv').addEventListener('click', function () { exportData('csv'); });
            byId('exportJson').addEventListener('click', function () { exportData('json'); });

            byId('refreshQuota').addEventListener('click', function () {
                var button = byId('refreshQuota');
                button.disabled = true;
                Admin.post('/admin/quota/refresh', {}).then(function (res) {
                    var payload = res.data;
                    if (!res.ok || !Admin.isOk(payload)) {
                        Admin.toastErr(Admin.msgOf(payload, '刷新失败（HTTP ' + res.status + '）'));
                        return;
                    }
                    Admin.toastOk(Admin.msgOf(payload, '已刷新超限状态'));
                    refreshList();
                }).catch(function (err) {
                    Admin.toastErr(err.message || '刷新失败');
                }).then(function () {
                    button.disabled = false;
                });
            });

            /* 表格内的编辑 / 删除按钮：事件委托，避免为每一行绑定监听器 */
            var table = byId('quotaTable');
            if (table) {
                table.addEventListener('click', function (ev) {
                    var target = ev.target;
                    var editBtn = target.closest && target.closest('[data-quota-edit]');
                    if (editBtn) { openEdit(editBtn); return; }
                    var removeBtn = target.closest && target.closest('[data-quota-remove]');
                    if (removeBtn) { removeQuota(removeBtn); }
                });
            }
        }

        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', init);
        } else {
            init();
        }
    })();
</script>
</body>
</html>

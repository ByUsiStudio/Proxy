<#include "./header.ftl">
<#--  审计日志
      model（AuditController#index）：page / pageSize / totalRow / totalPage
                                   / list(List<AuditLogEntity>) / actor / action / result
      AuditLogEntity：id / actorType / actor / action / target / detail / result / ip / userAgent / createTime
      （createTime 已由 AuditService 格式化为「yyyy-MM-dd HH:mm:ss」）

      交互说明：
        · 服务端筛选（操作者 / 动作 / 结果）+ 分页，条件随分页链接保持；
        · 导出当前筛选条件下的全量记录（CSV / JSON）；
        · 批量删除走 POST /admin/audit/batchRemove（该路由不在只读 GET 白名单内，本来就是状态变更）；
        · 「详情」用弹窗展示完整字段，避免把长 detail 塞进表格；
          弹窗内容由 data-* 属性 + Admin.el 构建，全程不使用 innerHTML。  -->
<main class="content">
    <div class="page-head">
        <div>
            <h1 class="page-head__title">审计日志</h1>
            <div class="page-head__desc">后台操作与用户登录失败的留痕，可按操作者、动作与结果检索。</div>
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
        </div>
    </div>

    <#--  服务端条件查询：条件随分页链接一同保留  -->
    <section class="card">
        <div class="card__body">
            <form class="filter-bar" method="get" action="/admin/audit">
                <input class="input" id="actor" name="actor" type="text"
                       value="${(actor!"")?html}" placeholder="操作者关键字" aria-label="按操作者筛选"/>
                <input class="input" id="action" name="action" type="text"
                       value="${(action!"")?html}" placeholder="动作关键字（如 user.）" aria-label="按动作筛选"/>
                <select class="select" id="result" name="result" aria-label="按结果筛选">
                    <option value="">全部结果</option>
                    <option value="ok" <#if result == "ok">selected</#if>>成功</option>
                    <option value="fail" <#if result == "fail">selected</#if>>失败</option>
                </select>
                <button class="btn btn--subtle" type="submit" id="selectButton">
                    <span data-icon="search" data-icon-class="icon--sm"></span>
                    <span>查询</span>
                </button>
                <a class="btn btn--ghost" href="/admin/audit">
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
            </form>
        </div>
    </section>

    <#--  概览条：由 /admin/audit/stats 异步填充（成功/失败/近 24h 失败/队列丢弃 + 高频动作）  -->
    <section class="card">
        <div class="card__head">
            <div class="card__title">
                <span data-icon="chart" data-icon-class="icon--sm"></span>
                <span>审计概览</span>
                <span class="card__hint" id="statHint">加载中…</span>
            </div>
        </div>
        <div class="card__body">
            <div class="stat-grid" id="statGrid"></div>
            <div class="bar-list mt-4" id="actionList" aria-label="高频动作排行"></div>
        </div>
    </section>

    <section class="card">
        <div class="card__head">
            <div class="card__title">
                <span data-icon="shieldCheck" data-icon-class="icon--sm"></span>
                <span>事件列表</span>
                <span class="card__hint">共 ${totalRow?c} 条</span>
            </div>
            <div class="toolbar">
                <input class="input" type="search" data-table-filter="auditTable"
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
                已选中 <span class="bulk-bar__count" id="bulkCount">0</span> 条记录
            </div>
            <div class="table-wrap">
                <table class="table" id="auditTable" data-sortable="true">
                    <thead>
                    <tr>
                        <th class="col-check" data-sort-ignore>
                            <input type="checkbox" id="selectAll" aria-label="全选当前页"/>
                        </th>
                        <th>时间</th>
                        <th>操作者</th>
                        <th>类型</th>
                        <th>动作</th>
                        <th>目标</th>
                        <th>结果</th>
                        <th>来源IP</th>
                        <th data-sort-ignore>操作</th>
                    </tr>
                    </thead>
                    <tbody id="auditRows">
                    <#if list??>
                        <#list list as event>
                            <tr data-key="${(event.id!"")?html}">
                                <td class="col-check">
                                    <input type="checkbox" data-row-key="${(event.id!"")?html}"
                                           aria-label="选择记录 ${(event.id!"")?html}"/>
                                </td>
                                <#--  绝对时间留在首个文本节点（保证「时间」列排序仍按时间戳比较），
                                      相对时间放在下一行由 JS 填充  -->
                                <td class="nowrap">
                                    <div>${(event.createTime!"")?html}</div>
                                    <div class="text-xs faint" data-relative-time="${(event.createTime!"")?html}"></div>
                                </td>
                                <td>${(event.actor!"")?html}</td>
                                <td><span class="badge badge--muted">${(event.actorType!"")?html}</span></td>
                                <td class="mono">${(event.action!"")?html}</td>
                                <td class="truncate">${(event.target!"")?html}</td>
                                <td>
                                    <#if event.result == "fail">
                                        <span class="badge badge--fail">失败</span>
                                    <#else>
                                        <span class="badge badge--ok">成功</span>
                                    </#if>
                                </td>
                                <td class="mono">${(event.ip!"")?html}</td>
                                <td>
                                    <div class="cell-actions">
                                        <#--  详情按钮把整行字段放进 data-*，弹窗据此构建（不使用 innerHTML）  -->
                                        <button type="button" class="btn btn--subtle btn--sm" data-audit-detail
                                                data-id="${(event.id!"")?html}"
                                                data-time="${(event.createTime!"")?html}"
                                                data-actor-type="${(event.actorType!"")?html}"
                                                data-actor="${(event.actor!"")?html}"
                                                data-action="${(event.action!"")?html}"
                                                data-target="${(event.target!"")?html}"
                                                data-detail="${(event.detail!"")?html}"
                                                data-result="${(event.result!"")?html}"
                                                data-ip="${(event.ip!"")?html}"
                                                data-ua="${(event.userAgent!"")?html}">
                                            <span data-icon="eye" data-icon-class="icon--sm"></span>
                                            <span>详情</span>
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
             data-pager-url="/admin/audit"
             data-pager-page="${page?c}"
             data-pager-total="${totalPage?c}"
             data-pager-params="actor,action,result"></div>
    </section>
</main>

<#--  事件详情弹窗：内容全部由脚本用 Admin.el + .kv 构建  -->
<div class="modal" id="detailModal" role="dialog" aria-modal="true" aria-hidden="true" aria-labelledby="detailTitle">
    <div class="modal__panel">
        <div class="modal__head">
            <h3 class="modal__title" id="detailTitle">审计事件详情</h3>
            <button type="button" class="btn btn--ghost btn--icon" data-dialog-close aria-label="关闭">
                <span data-icon="close" data-icon-class="icon--sm"></span>
            </button>
        </div>
        <div class="modal__body" id="detailBody"></div>
        <div class="modal__foot">
            <button type="button" class="btn btn--ghost" id="detailCopy">
                <span data-icon="copy" data-icon-class="icon--sm"></span>
                <span>复制详情</span>
            </button>
            <button type="button" class="btn btn--primary" data-dialog-close>关闭</button>
        </div>
    </div>
</div>
<script>
    Admin.setTitle('审计日志');

    (function () {
        'use strict';

        function byId(id) { return document.getElementById(id); }

        /* 详情弹窗里要展示的字段（顺序即展示顺序），值全部来自行上的 data-* 属性 */
        var DETAIL_FIELDS = [
            ['事件ID', 'data-id'],
            ['时间', 'data-time'],
            ['操作者类型', 'data-actor-type'],
            ['操作者', 'data-actor'],
            ['动作', 'data-action'],
            ['目标', 'data-target'],
            ['结果', 'data-result'],
            ['来源IP', 'data-ip'],
            ['User-Agent', 'data-ua'],
            ['详情', 'data-detail']
        ];

        var detailText = '';

        /* ---------------- 筛选条件与局部刷新 ---------------- */

        function filterQuery(extraPage) {
            var params = new URLSearchParams();
            var actor = byId('actor');
            var action = byId('action');
            var result = byId('result');
            if (actor && actor.value.trim()) { params.set('actor', actor.value.trim()); }
            if (action && action.value.trim()) { params.set('action', action.value.trim()); }
            if (result && result.value) { params.set('result', result.value); }
            if (extraPage) { params.set('page', extraPage); }
            var query = params.toString();
            return query ? '?' + query : '';
        }

        function currentUrl(page) {
            return '/admin/audit' + filterQuery(page);
        }

        /* ---------------- 相对时间 ---------------- */

        function relativeText(value) {
            if (!value) { return ''; }
            var date = new Date(String(value).replace(' ', 'T'));
            if (isNaN(date.getTime())) { return ''; }
            var diff = Math.round((Date.now() - date.getTime()) / 1000);
            if (diff < 0) { return '刚刚'; }
            if (diff < 60) { return diff + ' 秒前'; }
            if (diff < 3600) { return Math.floor(diff / 60) + ' 分钟前'; }
            if (diff < 86400) { return Math.floor(diff / 3600) + ' 小时前'; }
            return Math.floor(diff / 86400) + ' 天前';
        }

        function fillRelativeTimes(root) {
            var nodes = (root || document).querySelectorAll('[data-relative-time]');
            Array.prototype.forEach.call(nodes, function (node) {
                var text = relativeText(node.getAttribute('data-relative-time'));
                if (text) { node.textContent = text; }
            });
        }

        /* ---------------- 概览（统计 + 高频动作） ---------------- */

        function statCard(label, value, extraClass) {
            return Admin.el('div', { class: 'stat-card' }, [
                Admin.el('div', { class: 'stat-card__label', text: label }),
                Admin.el('div', {
                    class: 'stat-card__value' + (extraClass ? ' ' + extraClass : ''),
                    text: value
                })
            ]);
        }

        function loadStats() {
            return Admin.get('/admin/audit/stats', { days: 14, recent: 6 }).then(function (res) {
                var data = res.data || {};
                var total = data.total || {};

                var grid = byId('statGrid');
                grid.textContent = '';
                grid.appendChild(statCard('事件总数', Admin.fmtNum(total.total)));
                grid.appendChild(statCard('成功', Admin.fmtNum(total.ok)));
                grid.appendChild(statCard('失败', Admin.fmtNum(total.fail), (Number(total.fail) || 0) > 0 ? 'mono' : ''));
                grid.appendChild(statCard('近 24 小时失败', Admin.fmtNum(total.recentFail)));

                Admin.chart.bars(byId('actionList'), (data.byAction || []).map(function (item) {
                    return { label: String(item.action || ''), value: Number(item.count) || 0 };
                }), { format: Admin.fmtNum, emptyText: '暂无审计动作数据' });

                var hint = '近 14 天取样 ' + Admin.fmtNum(total.total) + ' 条';
                if (Number(total.dropped) > 0) {
                    hint += ' · 写入队列丢弃 ' + Admin.fmtNum(total.dropped) + ' 条';
                }
                byId('statHint').textContent = hint;
            }).catch(function (err) {
                byId('statHint').textContent = '概览加载失败';
                Admin.toastErr(err.message || '审计概览加载失败');
            });
        }

        /**
         * 顶栏失败徽标：单独请求 /admin/audit/stats（days=1, recent=1）。
         * 与概览条分开取数，是为了让徽标口径（近 24 小时失败数）不受概览天数影响。
         */
        function loadBadge() {
            return Admin.get('/admin/audit/stats', { days: 1, recent: 1 }).then(function (res) {
                var total = (res.data && res.data.total) || {};
                var count = Number(total.recentFail) || 0;
                Admin.renderAlertBadge(count, '/admin/audit?result=fail', '近 24 小时有 ' + count + ' 条失败事件');
            }).catch(function () {
                // 徽标失败不影响页面其它内容
            });
        }

        /* ---------------- 导出 ---------------- */

        /** 触发浏览器原生下载（附件响应由服务端给出文件名与 Content-Disposition）。 */
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
            download('/admin/audit/export' + query + (query ? '&' : '?') + 'format=' + encodeURIComponent(format));
            Admin.toast('正在导出当前筛选条件下的全部审计记录', { type: 'info', timeout: 2600 });
        }

        /* ---------------- 详情弹窗 ---------------- */

        function openDetail(button) {
            var host = byId('detailBody');
            host.textContent = '';
            var kv = Admin.el('div', { class: 'kv' });
            var lines = [];
            DETAIL_FIELDS.forEach(function (field) {
                var raw = button.getAttribute(field[1]) || '';
                var value = raw;
                if (field[1] === 'data-result') {
                    value = raw === 'fail' ? '失败' : '成功';
                }
                kv.appendChild(Admin.el('div', { class: 'kv__key', text: field[0] }));
                kv.appendChild(Admin.el('div', { class: 'kv__val' + (field[1] === 'data-detail' ? '' : ' mono'), text: value || '—' }));
                lines.push(field[0] + ': ' + raw);
            });
            host.appendChild(kv);
            detailText = lines.join('\n');
            Admin.dialog.open('detailModal');
        }

        /* ---------------- 批量删除与自动刷新 ---------------- */

        var selection = null;

        function wireEvents() {
            // 详情按钮：事件委托，行是服务端渲染的，局部刷新后无需重新绑定
            byId('auditTable').addEventListener('click', function (ev) {
                var button = ev.target.closest && ev.target.closest('[data-audit-detail]');
                if (!button) { return; }
                openDetail(button);
            });

            byId('detailCopy').addEventListener('click', function () {
                if (!detailText) { Admin.toastWarn('当前没有可复制的内容'); return; }
                Admin.copy(detailText);
            });

            selection = Admin.selection({
                table: 'auditTable',
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
                    url: '/admin/audit/batchRemove',
                    ids: ids,
                    title: '批量删除审计记录',
                    message: '确定删除选中的 ' + ids.length + ' 条审计记录吗？删除操作不可恢复，且删除行为本身会被记录。',
                    okText: '全部删除',
                    onDone: function () {
                        if (selection) { selection.clear(); }
                        refreshList();
                        loadStats();
                        loadBadge();
                    }
                });
            });
        }

        /**
         * 局部刷新：只替换 tbody 与分页容器，不整页重载。
         * Admin.refreshRegions 内部用 DOMParser 解析并替换节点，不执行脚本、不解析 HTML 字符串。
         */
        function refreshList() {
            return Admin.refreshRegions(currentUrl(), ['#auditRows', '#box']).then(function () {
                Admin.bindSortableTables();
                Admin.initPagers();
                Admin.bindTableFilters();
                if (selection) { selection.sync(); }
                fillRelativeTimes();
            }).catch(function (err) {
                Admin.toastWarn(err.message || '列表刷新失败');
            });
        }

        function init() {
            fillRelativeTimes();
            wireEvents();
            loadStats();
            loadBadge();

            Admin.autoRefresh({
                toggle: 'autoRefresh',
                interval: 30000,
                storageKey: 'px_admin_audit_autorefresh',
                countdown: 'refreshHint',
                onTick: function () {
                    refreshList();
                    loadStats();
                }
            });

            byId('reloadBtn').addEventListener('click', function () {
                refreshList();
                loadStats();
                loadBadge();
                Admin.toast('已刷新审计日志与概览', { type: 'info', timeout: 1600 });
            });
            byId('exportCsv').addEventListener('click', function () { exportData('csv'); });
            byId('exportJson').addEventListener('click', function () { exportData('json'); });
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

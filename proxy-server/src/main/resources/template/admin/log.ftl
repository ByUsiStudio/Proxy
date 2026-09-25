<#include "./header.ftl">
<#--  用户日志（流量统计）
      model（LogController#log）：page / pageSize / totalRow / totalPage / list(List<StatisticsEntity>)
                                 / username / port
      StatisticsEntity：id / username / port / receive / send / connectNum / packNum / createTime

      本轮新增：条件搜索（用户名/端口）、全量导出（CSV/JSON）、流量图表（按端口 / 按天）、
                列表自动刷新、批量删除。所有插值均已按框架约定显式转义。  -->
<main class="content">
    <div class="page-head">
        <div>
            <h1 class="page-head__title">用户日志</h1>
            <div class="page-head__desc">各端口的流量、连接数与数据包统计，支持条件筛选、图表分析与导出。</div>
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
            <form class="filter-bar" method="get" action="/admin/log">
                <input class="input" id="username" name="username" type="text"
                       value="${(username!"")?html}" placeholder="用户名关键字" aria-label="按用户名筛选"/>
                <input class="input" id="port" name="port" type="number" min="0" max="65535"
                       value="${(port!"")?html}" placeholder="端口" aria-label="按端口筛选"/>
                <button class="btn btn--subtle" type="submit" id="selectButton">
                    <span data-icon="search" data-icon-class="icon--sm"></span>
                    <span>查询</span>
                </button>
                <a class="btn btn--ghost" href="/admin/log">
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

    <#--  图表区：数据由 /admin/log/stats 异步获取，条件与当前筛选一致  -->
    <section class="chart-grid" aria-label="流量统计图表">
        <div class="card">
            <div class="card__head">
                <div class="card__title">
                    <span data-icon="chart" data-icon-class="icon--sm"></span>
                    <span>按天流量趋势</span>
                    <span class="card__hint" id="dayHint">加载中…</span>
                </div>
            </div>
            <div class="card__body">
                <div class="chart-box">
                    <canvas id="dayChart" role="img" aria-label="按天流量趋势图"></canvas>
                </div>
                <div class="legend" id="dayLegend"></div>
            </div>
        </div>

        <div class="card">
            <div class="card__head">
                <div class="card__title">
                    <span data-icon="server" data-icon-class="icon--sm"></span>
                    <span>按端口流量分布</span>
                    <span class="card__hint" id="portHint">加载中…</span>
                </div>
            </div>
            <div class="card__body">
                <div class="chart-box">
                    <canvas id="portChart" role="img" aria-label="按端口流量分布图"></canvas>
                </div>
                <div class="legend" id="portLegend"></div>
                <#--  图表之外的精确数值：用条形占比列出流量最高的端口  -->
                <div class="bar-list mt-4" id="portList" aria-label="端口流量排行"></div>
            </div>
        </div>
    </section>

    <section class="card">
        <div class="card__head">
            <div class="card__title">
                <span data-icon="list" data-icon-class="icon--sm"></span>
                <span>流量统计</span>
                <span class="card__hint">共 ${totalRow?c} 条</span>
            </div>
            <div class="toolbar">
                <input class="input" type="search" data-table-filter="logTable"
                       placeholder="本页过滤" aria-label="本页快速过滤"/>
                <button type="button" class="btn btn--danger btn--sm" id="batchRemove"
                        data-label="批量删除" data-icon-name="trash" disabled>
                    <span data-icon="trash" data-icon-class="icon--sm"></span>
                    <span>批量删除</span>
                </button>
            </div>
        </div>
        <div class="card__body card__body--flush">
            <div class="bulk-bar" id="bulkBar">
                已选中 <span class="bulk-bar__count" id="bulkCount">0</span> 条记录
            </div>
            <div class="table-wrap">
                <table class="table" id="logTable" data-sortable="true">
                    <thead>
                    <tr>
                        <th class="col-check" data-sort-ignore>
                            <input type="checkbox" id="selectAll" aria-label="全选当前页"/>
                        </th>
                        <th>id</th>
                        <th>用户名</th>
                        <th data-sort-type="number">端口</th>
                        <th data-sort-type="number">接收</th>
                        <th data-sort-type="number">发送</th>
                        <th data-sort-type="number">连接数</th>
                        <th data-sort-type="number">数据包数</th>
                        <th>时间</th>
                        <th data-sort-ignore>操作</th>
                    </tr>
                    </thead>
                    <tbody id="logRows">
                    <#if list??>
                        <#list list as statistics>
                            <tr data-key="${(statistics.id!"")?html}">
                                <td class="col-check">
                                    <input type="checkbox" data-row-key="${(statistics.id!"")?html}"
                                           aria-label="选择记录 ${(statistics.id!"")?html}"/>
                                </td>
                                <td class="mono">${(statistics.id!"")?html}</td>
                                <td>${(statistics.username!"")?html}</td>
                                <td>${(statistics.port!0)?c}</td>
                                <td><span class="badge badge--info">${(statistics.receive!"")?html} 字节</span></td>
                                <td><span class="badge badge--ok">${(statistics.send!"")?html} 字节</span></td>
                                <td>${(statistics.connectNum!"")?html}</td>
                                <td>${(statistics.packNum!"")?html}</td>
                                <td>${(statistics.createTime!"")?html}</td>
                                <td>
                                    <div class="cell-actions">
                                        <#--  保持原 href 不变  -->
                                        <a class="btn btn--danger btn--sm"
                                           href="/admin/log/remove?page=${page?c}&id=${(statistics.id!"")?url}"
                                           data-confirm="确定删除该条统计记录吗？"
                                           data-confirm-title="删除记录">
                                            <span data-icon="trash" data-icon-class="icon--sm"></span>
                                            <span>删除</span>
                                        </a>
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
             data-pager-url="/admin/log"
             data-pager-page="${page?c}"
             data-pager-total="${totalPage?c}"
             data-pager-params="username,port"></div>
    </section>
</main>
<script>
    Admin.setTitle('用户日志');

    (function () {
        'use strict';

        /* 当前筛选条件：图表接口与局部刷新都必须带上，保证三处数据一致 */
        function filterQuery(extraPage) {
            var params = new URLSearchParams();
            var u = document.getElementById('username');
            var p = document.getElementById('port');
            if (u && u.value.trim()) { params.set('username', u.value.trim()); }
            if (p && p.value.trim()) { params.set('port', p.value.trim()); }
            if (extraPage) { params.set('page', extraPage); }
            var s = params.toString();
            return s ? '?' + s : '';
        }

        function currentUrl(page) {
            return '/admin/log' + filterQuery(page);
        }

        /* ---------------- 图表 ---------------- */
        var DAY_SERIES = [
            { name: '接收', color: Admin.chart.palette[0] },
            { name: '发送', color: Admin.chart.palette[1] }
        ];
        var PORT_SERIES = [
            { name: '接收', color: Admin.chart.palette[0] },
            { name: '发送', color: Admin.chart.palette[1] }
        ];

        var daySpec = { type: 'line', labels: [], series: [], yFormat: Admin.fmtBytes };
        var portSpec = { type: 'bar', labels: [], series: [], yFormat: Admin.fmtBytes };

        var redrawDay = null;
        var redrawPort = null;

        function setupCharts() {
            Admin.chart.legend(document.getElementById('dayLegend'), DAY_SERIES);
            Admin.chart.legend(document.getElementById('portLegend'), PORT_SERIES);
            redrawDay = Admin.chart.auto(document.getElementById('dayChart'), function () { return daySpec; });
            redrawPort = Admin.chart.auto(document.getElementById('portChart'), function () { return portSpec; });
        }

        function loadStats() {
            Admin.get('/admin/log/stats', {
                username: (document.getElementById('username') || {}).value || '',
                port: (document.getElementById('port') || {}).value || ''
            }).then(function (res) {
                var data = res.data || {};
                var byDay = Array.isArray(data.byDay) ? data.byDay : [];
                var byPort = Array.isArray(data.byPort) ? data.byPort : [];
                var total = data.total || {};

                daySpec = {
                    type: 'line',
                    labels: byDay.map(function (d) { return String(d.day || '').slice(5); }),
                    series: [
                        { name: '接收', data: byDay.map(function (d) { return Number(d.receive) || 0; }), color: DAY_SERIES[0].color },
                        { name: '发送', data: byDay.map(function (d) { return Number(d.send) || 0; }), color: DAY_SERIES[1].color }
                    ],
                    yFormat: Admin.fmtBytes
                };

                // 柱状图取入站流量前 12 个端口，避免 X 轴过密
                var topPorts = byPort.slice(0, 12);
                portSpec = {
                    type: 'bar',
                    labels: topPorts.map(function (d) { return String(d.port); }),
                    series: [
                        { name: '接收', data: topPorts.map(function (d) { return Number(d.receive) || 0; }), color: PORT_SERIES[0].color },
                        { name: '发送', data: topPorts.map(function (d) { return Number(d.send) || 0; }), color: PORT_SERIES[1].color }
                    ],
                    yFormat: Admin.fmtBytes
                };

                if (redrawDay) { redrawDay(); }
                if (redrawPort) { redrawPort(); }

                // 精确数值排行（条形占比），与柱状图同源
                Admin.chart.bars(document.getElementById('portList'),
                    byPort.slice(0, 8).map(function (d) {
                        return {
                            label: String(d.port),
                            value: (Number(d.receive) || 0) + (Number(d.send) || 0)
                        };
                    }),
                    { format: Admin.fmtBytes, emptyText: '暂无端口数据' });

                document.getElementById('dayHint').textContent =
                    '累计接收 ' + Admin.fmtBytes(total.receive) + ' · 发送 ' + Admin.fmtBytes(total.send);
                document.getElementById('portHint').textContent = byPort.length
                    ? '涉及 ' + byPort.length + ' 个端口 · 连接 ' + Admin.fmtNum(total.connectNum) +
                      ' · 数据包 ' + Admin.fmtNum(total.packNum)
                    : '暂无端口数据';
            }).catch(function (err) {
                document.getElementById('dayHint').textContent = '图表加载失败';
                document.getElementById('portHint').textContent = '图表加载失败';
                Admin.toastErr(err.message || '流量图表加载失败');
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
            download('/admin/log/export' + query + (query ? '&' : '?') + 'format=' + encodeURIComponent(format));
            Admin.toast('正在导出当前筛选条件下的全部记录', { type: 'info', timeout: 2600 });
        }

        /* ---------------- 批量删除与自动刷新 ---------------- */
        var selection = null;

        function wireBatch() {
            selection = Admin.selection({
                table: 'logTable',
                selectAll: 'selectAll',
                actions: ['batchRemove'],
                onChange: function (keys) {
                    document.getElementById('bulkCount').textContent = String(keys.length);
                }
            });

            document.getElementById('batchRemove').addEventListener('click', function () {
                if (!selection) { return; }
                var ids = selection.keys();
                Admin.batchRemove({
                    url: '/admin/log/batchRemove',
                    ids: ids,
                    title: '批量删除统计记录',
                    message: '确定删除选中的 ' + ids.length + ' 条统计记录吗？该操作不可恢复。',
                    okText: '全部删除',
                    extra: {
                        username: (document.getElementById('username') || {}).value || '',
                        port: (document.getElementById('port') || {}).value || ''
                    },
                    onDone: function () {
                        if (selection) { selection.clear(); }
                        refreshList();
                        loadStats();
                    }
                });
            });
        }

        /**
         * 局部刷新：只替换 tbody 与分页容器，不整页重载。
         * 替换以 DOM 节点方式进行（Admin.refreshRegions 内部用 DOMParser，不执行脚本），
         * 刷新后会重新绑定表头排序与分页。
         */
        function refreshList() {
            return Admin.refreshRegions(currentUrl(), ['#logRows', '#box']).then(function () {
                Admin.bindSortableTables();
                Admin.initPagers();
                Admin.bindTableFilters();
                if (selection) { selection.sync(); }
                // 替换后重新套用本页过滤关键字
                Admin.bindTableFilters();
            }).catch(function (err) {
                Admin.toastWarn(err.message || '列表刷新失败');
            });
        }

        function wireAutoRefresh() {
            var auto = Admin.autoRefresh({
                toggle: 'autoRefresh',
                interval: 30000,
                storageKey: 'px_admin_log_autorefresh',
                countdown: 'refreshHint',
                onTick: function () {
                    refreshList();
                    loadStats();
                }
            });
            return auto;
        }

        function init() {
            setupCharts();
            loadStats();
            wireBatch();
            wireAutoRefresh();

            document.getElementById('reloadBtn').addEventListener('click', function () {
                refreshList();
                loadStats();
                Admin.toast('已刷新列表与图表', { type: 'info', timeout: 1600 });
            });
            document.getElementById('exportCsv').addEventListener('click', function () { exportData('csv'); });
            document.getElementById('exportJson').addEventListener('click', function () { exportData('json'); });
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

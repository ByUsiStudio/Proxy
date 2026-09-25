<#include "./header.ftl">
<#--  统计报表
      model（ReportController#page）：
        days / rows(List<Map>) / totals(Map) / byPort(List<Map>) / generatedAt / reportJson(String)
        / reportDir / intervalHours
      rows 每项：day / receive / receiveText / send / sendText / connectNum / packNum / newUsers
      totals：receive / receiveText / send / sendText / connectNum / packNum / newUsers
              / userTotal / sampledStats / sampledUsers
      byPort 每项：port / receive / receiveText / send / sendText / connectNum / packNum

      说明：
      · 首屏由服务端渲染（KPI + 每日表格 + 图表数据），因此即使额外的 JSON 接口不可访问也能完整显示；
      · 切换天数时前端读取 /admin/report/download?format=json（只读白名单内）后重绘，无需刷新页面；
      · 图表数据以 data-report 属性承载（?html 转义，前端 JSON.parse 还原），不使用 inline script 插值；
      · 报表<b>不含任何口令/令牌</b>，且数据来自有界取样（见 ReportService 类注释）。  -->
<main class="content">
    <div class="page-head">
        <div>
            <h1 class="page-head__title">统计报表</h1>
            <div class="page-head__desc">
                按天汇总流量、连接数与新增账号，支持 PNG 图表导出、CSV / JSON 下载与手动生成报表文件。
            </div>
        </div>
        <div class="toolbar">
            <label class="field__label" for="reportDays">统计范围</label>
            <select class="select" id="reportDays" aria-label="统计天数">
                <option value="7" <#if days == 7>selected</#if>>最近 7 天</option>
                <option value="14" <#if days == 14>selected</#if>>最近 14 天</option>
                <option value="30" <#if days == 30>selected</#if>>最近 30 天</option>
            </select>
            <button type="button" class="btn btn--ghost" id="exportCsv">
                <span data-icon="download" data-icon-class="icon--sm"></span>
                <span>导出 CSV</span>
            </button>
            <button type="button" class="btn btn--ghost" id="exportJson">
                <span data-icon="download" data-icon-class="icon--sm"></span>
                <span>导出 JSON</span>
            </button>
            <button type="button" class="btn btn--primary" id="generateReport">
                <span data-icon="clock" data-icon-class="icon--sm"></span>
                <span>立即生成报表</span>
            </button>
        </div>
    </div>

    <#--  图表数据载体：属性值经 ?html 转义，前端用 getAttribute + JSON.parse 还原  -->
    <div id="reportData" class="hidden" data-report="${reportJson?html}"></div>

    <section class="stat-grid" aria-label="汇总指标">
        <div class="stat-card">
            <div class="stat-card__label">接收流量</div>
            <div class="stat-card__value" id="kpiReceive">${(totals.receiveText!"0 B")?html}</div>
        </div>
        <div class="stat-card">
            <div class="stat-card__label">发送流量</div>
            <div class="stat-card__value" id="kpiSend">${(totals.sendText!"0 B")?html}</div>
        </div>
        <div class="stat-card">
            <div class="stat-card__label">连接数</div>
            <div class="stat-card__value" id="kpiConnect">${(totals.connectNum!0)?c}</div>
        </div>
        <div class="stat-card">
            <div class="stat-card__label">数据包数</div>
            <div class="stat-card__value" id="kpiPack">${(totals.packNum!0)?c}</div>
        </div>
        <div class="stat-card">
            <div class="stat-card__label">新增账号</div>
            <div class="stat-card__value" id="kpiNewUsers">${(totals.newUsers!0)?c}</div>
            <div class="card__hint" id="kpiUserTotalHint">账号总数（全量）
                <#if totals??>${(totals.userTotal!0)?c}<#else>0</#if>
            </div>
        </div>
    </section>

    <section class="chart-grid" aria-label="统计图表">
        <div class="card">
            <div class="card__head">
                <div class="card__title">
                    <span data-icon="chart" data-icon-class="icon--sm"></span>
                    <span>流量趋势</span>
                    <span class="card__hint" id="trafficHint">加载中…</span>
                </div>
                <div class="toolbar">
                    <button type="button" class="btn btn--ghost btn--sm"
                            data-chart-export="reportTrafficChart" data-chart-name="traffic-trend">
                        <span data-icon="download" data-icon-class="icon--sm"></span>
                        <span>导出 PNG</span>
                    </button>
                </div>
            </div>
            <div class="card__body">
                <div class="chart-box">
                    <canvas id="reportTrafficChart" role="img" aria-label="每日流量趋势图"></canvas>
                </div>
                <div class="legend" id="trafficLegend"></div>
            </div>
        </div>

        <div class="card">
            <div class="card__head">
                <div class="card__title">
                    <span data-icon="activity" data-icon-class="icon--sm"></span>
                    <span>连接数 / 数据包数</span>
                    <span class="card__hint" id="connHint">加载中…</span>
                </div>
                <div class="toolbar">
                    <button type="button" class="btn btn--ghost btn--sm"
                            data-chart-export="reportConnChart" data-chart-name="connections">
                        <span data-icon="download" data-icon-class="icon--sm"></span>
                        <span>导出 PNG</span>
                    </button>
                </div>
            </div>
            <div class="card__body">
                <div class="chart-box">
                    <canvas id="reportConnChart" role="img" aria-label="每日连接数与数据包数图"></canvas>
                </div>
                <div class="legend" id="connLegend"></div>
            </div>
        </div>

        <div class="card">
            <div class="card__head">
                <div class="card__title">
                    <span data-icon="server" data-icon-class="icon--sm"></span>
                    <span>按端口流量</span>
                    <span class="card__hint" id="portHint">加载中…</span>
                </div>
                <div class="toolbar">
                    <button type="button" class="btn btn--ghost btn--sm"
                            data-chart-export="reportPortChart" data-chart-name="by-port">
                        <span data-icon="download" data-icon-class="icon--sm"></span>
                        <span>导出 PNG</span>
                    </button>
                </div>
            </div>
            <div class="card__body">
                <div class="chart-box">
                    <canvas id="reportPortChart" role="img" aria-label="按端口流量图"></canvas>
                </div>
                <div class="legend" id="portLegend"></div>
            </div>
        </div>
    </section>

    <section class="card">
        <div class="card__head">
            <div class="card__title">
                <span data-icon="list" data-icon-class="icon--sm"></span>
                <span>每日明细</span>
                <span class="card__hint" id="reportHint">
                    生成于 ${(generatedAt!"")?html} · 目录 ${(reportDir!"report")?html} · 定时周期
                    <#if (intervalHours!0) <= 0>已关闭（可手动生成）<#else>${(intervalHours!0)?c} 小时</#if>
                </span>
            </div>
            <div class="toolbar">
                <input class="input" type="search" data-table-filter="reportTable"
                       placeholder="本页过滤" aria-label="本页快速过滤"/>
            </div>
        </div>
        <div class="card__body card__body--flush">
            <div class="table-wrap">
                <table class="table" id="reportTable" data-sortable="true">
                    <thead>
                    <tr>
                        <th>日期</th>
                        <th data-sort-type="number">接收</th>
                        <th data-sort-type="number">发送</th>
                        <th data-sort-type="number">连接数</th>
                        <th data-sort-type="number">数据包数</th>
                        <th data-sort-type="number">新增账号</th>
                    </tr>
                    </thead>
                    <tbody id="reportRows">
                    <#if rows??>
                        <#list rows as row>
                            <tr>
                                <td>${(row.day!"")?html}</td>
                                <td>
                                    <span>${(row.receiveText!"0 B")?html}</span>
                                    <input type="hidden" value="${(row.receive!0)?c}"/>
                                </td>
                                <td>
                                    <span>${(row.sendText!"0 B")?html}</span>
                                    <input type="hidden" value="${(row.send!0)?c}"/>
                                </td>
                                <td>
                                    <span>${(row.connectNum!0)?c}</span>
                                    <input type="hidden" value="${(row.connectNum!0)?c}"/>
                                </td>
                                <td>
                                    <span>${(row.packNum!0)?c}</span>
                                    <input type="hidden" value="${(row.packNum!0)?c}"/>
                                </td>
                                <td>
                                    <span>${(row.newUsers!0)?c}</span>
                                    <input type="hidden" value="${(row.newUsers!0)?c}"/>
                                </td>
                            </tr>
                        </#list>
                    </#if>
                    </tbody>
                </table>
            </div>
        </div>
    </section>
</main>

<script>
    Admin.setTitle('统计报表');

    (function () {
        'use strict';

        function byId(id) { return document.getElementById(id); }

        /* 服务端渲染的图表数据（data-report 属性 → JSON） */
        function readEmbedded() {
            var host = byId('reportData');
            if (!host) { return {}; }
            try {
                return JSON.parse(host.getAttribute('data-report') || '{}');
            } catch (e) {
                return {};
            }
        }

        var DATA = readEmbedded();

        var TRAFFIC_SERIES = [
            { name: '接收', color: Admin.chart.palette[0] },
            { name: '发送', color: Admin.chart.palette[1] }
        ];
        var CONN_SERIES = [
            { name: '连接数', color: Admin.chart.palette[0] },
            { name: '数据包数', color: Admin.chart.palette[2] }
        ];
        var PORT_SERIES = [
            { name: '接收', color: Admin.chart.palette[0] },
            { name: '发送', color: Admin.chart.palette[1] }
        ];

        var trafficSpec = { type: 'line', labels: [], series: [], yFormat: Admin.fmtBytes };
        var connSpec = { type: 'bar', labels: [], series: [] };
        var portSpec = { type: 'bar', labels: [], series: [], yFormat: Admin.fmtBytes };

        var redrawTraffic = null;
        var redrawConn = null;
        var redrawPort = null;

        function setupCharts() {
            Admin.chart.legend(byId('trafficLegend'), TRAFFIC_SERIES);
            Admin.chart.legend(byId('connLegend'), CONN_SERIES);
            Admin.chart.legend(byId('portLegend'), PORT_SERIES);
            /* Admin.chart.auto 已内置「主题切换 / 尺寸变化自动重绘」 */
            redrawTraffic = Admin.chart.auto(byId('reportTrafficChart'), function () { return trafficSpec; });
            redrawConn = Admin.chart.auto(byId('reportConnChart'), function () { return connSpec; });
            redrawPort = Admin.chart.auto(byId('reportPortChart'), function () { return portSpec; });
        }

        function num(value) { return Number(value) || 0; }

        /* 单元格：可见文本 + 隐藏的原始数值，使表头排序按真实大小比较 */
        function numericCell(text, raw) {
            var cell = document.createElement('td');
            cell.appendChild(Admin.el('span', { text: String(text) }));
            var hidden = Admin.el('input', { type: 'hidden' });
            hidden.value = String(raw);
            cell.appendChild(hidden);
            return cell;
        }

        function renderTable(rows) {
            var tbody = byId('reportRows');
            if (!tbody) { return; }
            tbody.textContent = '';
            rows.forEach(function (row) {
                var tr = document.createElement('tr');
                tr.appendChild(Admin.el('td', { text: String(row.day || '') }));
                tr.appendChild(numericCell(row.receiveText || Admin.fmtBytes(num(row.receive)), num(row.receive)));
                tr.appendChild(numericCell(row.sendText || Admin.fmtBytes(num(row.send)), num(row.send)));
                tr.appendChild(numericCell(String(num(row.connectNum)), num(row.connectNum)));
                tr.appendChild(numericCell(String(num(row.packNum)), num(row.packNum)));
                tr.appendChild(numericCell(String(num(row.newUsers)), num(row.newUsers)));
                tbody.appendChild(tr);
            });
        }

        function render(data) {
            var rows = Array.isArray(data.rows) ? data.rows : [];
            var byPort = Array.isArray(data.byPort) ? data.byPort : [];
            var totals = data.totals || {};
            var labels = rows.map(function (r) { return String(r.day || '').slice(5); });

            trafficSpec = {
                type: 'line',
                labels: labels,
                series: [
                    { name: '接收', data: rows.map(function (r) { return num(r.receive); }), color: TRAFFIC_SERIES[0].color },
                    { name: '发送', data: rows.map(function (r) { return num(r.send); }), color: TRAFFIC_SERIES[1].color }
                ],
                yFormat: Admin.fmtBytes
            };
            connSpec = {
                type: 'bar',
                labels: labels,
                series: [
                    { name: '连接数', data: rows.map(function (r) { return num(r.connectNum); }), color: CONN_SERIES[0].color },
                    { name: '数据包数', data: rows.map(function (r) { return num(r.packNum); }), color: CONN_SERIES[1].color }
                ]
            };
            /* 端口柱状图只取入站流量前 10 个端口，避免 X 轴过密 */
            var topPorts = byPort.slice(0, 10);
            portSpec = {
                type: 'bar',
                labels: topPorts.map(function (p) { return String(p.port); }),
                series: [
                    { name: '接收', data: topPorts.map(function (p) { return num(p.receive); }), color: PORT_SERIES[0].color },
                    { name: '发送', data: topPorts.map(function (p) { return num(p.send); }), color: PORT_SERIES[1].color }
                ],
                yFormat: Admin.fmtBytes
            };

            if (redrawTraffic) { redrawTraffic(); }
            if (redrawConn) { redrawConn(); }
            if (redrawPort) { redrawPort(); }

            if (byId('kpiReceive')) { byId('kpiReceive').textContent = totals.receiveText || Admin.fmtBytes(num(totals.receive)); }
            if (byId('kpiSend')) { byId('kpiSend').textContent = totals.sendText || Admin.fmtBytes(num(totals.send)); }
            if (byId('kpiConnect')) { byId('kpiConnect').textContent = Admin.fmtNum(totals.connectNum); }
            if (byId('kpiPack')) { byId('kpiPack').textContent = Admin.fmtNum(totals.packNum); }
            if (byId('kpiNewUsers')) { byId('kpiNewUsers').textContent = Admin.fmtNum(totals.newUsers); }
            if (byId('kpiUserTotalHint')) {
                byId('kpiUserTotalHint').textContent = '账号总数（全量） ' + Admin.fmtNum(totals.userTotal);
            }

            if (byId('trafficHint')) {
                byId('trafficHint').textContent = '接收 ' + (totals.receiveText || '0 B')
                    + ' · 发送 ' + (totals.sendText || '0 B');
            }
            if (byId('connHint')) {
                byId('connHint').textContent = '连接 ' + Admin.fmtNum(totals.connectNum)
                    + ' · 数据包 ' + Admin.fmtNum(totals.packNum);
            }
            if (byId('portHint')) {
                byId('portHint').textContent = byPort.length
                    ? '涉及 ' + byPort.length + ' 个端口' : '暂无端口数据';
            }
            if (byId('reportHint') && data.generatedAt) {
                byId('reportHint').textContent = '生成于 ' + data.generatedAt
                    + ' · 覆盖 ' + (data.days || rows.length) + ' 天'
                    + ' · 统计样本 ' + Admin.fmtNum(totals.sampledStats) + ' 行';
            }
            renderTable(rows);
            Admin.bindTableFilters();
        }

        /* ---------------- 天数切换：读取白名单内的只读 JSON 接口并重绘 ---------------- */
        function currentDays() {
            var select = byId('reportDays');
            return select ? select.value : String((DATA && DATA.days) || 14);
        }

        function loadDays(days) {
            return Admin.get('/admin/report/download', { days: days, format: 'json' }).then(function (res) {
                var data = res.data;
                if (!res.ok || !data || typeof data !== 'object' || !Array.isArray(data.rows)) {
                    throw new Error('返回数据格式不正确');
                }
                DATA = data;
                render(data);
            });
        }

        function download(url) {
            var link = document.createElement('a');
            link.href = url;
            link.rel = 'noopener';
            document.body.appendChild(link);
            link.click();
            link.remove();
        }

        function init() {
            setupCharts();
            /* 首屏：用服务端渲染的数据直接绘制（不依赖额外请求） */
            if (DATA && Array.isArray(DATA.rows)) {
                render(DATA);
            }

            byId('reportDays').addEventListener('change', function () {
                var days = currentDays();
                loadDays(days).then(function () {
                    Admin.toastOk('已切换到最近 ' + days + ' 天');
                }).catch(function (err) {
                    Admin.toastErr(err.message || '统计数据加载失败');
                });
            });

            byId('exportCsv').addEventListener('click', function () {
                download('/admin/report/download?days=' + encodeURIComponent(currentDays()) + '&format=csv');
                Admin.toast('正在导出报表 CSV', { type: 'info', timeout: 2600 });
            });
            byId('exportJson').addEventListener('click', function () {
                download('/admin/report/download?days=' + encodeURIComponent(currentDays()) + '&format=json');
                Admin.toast('正在导出报表 JSON', { type: 'info', timeout: 2600 });
            });

            byId('generateReport').addEventListener('click', function () {
                var button = byId('generateReport');
                button.disabled = true;
                Admin.post('/admin/report/generate', { days: currentDays() }).then(function (res) {
                    var payload = res.data;
                    if (!res.ok || !Admin.isOk(payload)) {
                        Admin.toastErr(Admin.msgOf(payload, '生成报表失败（HTTP ' + res.status + '）'));
                        return;
                    }
                    Admin.toastOk(Admin.msgOf(payload, '报表已生成'));
                }).catch(function (err) {
                    Admin.toastErr(err.message || '生成报表失败');
                }).then(function () {
                    button.disabled = false;
                });
            });
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

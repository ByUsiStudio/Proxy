<#include "./header.ftl">
<#--  后台首页仪表盘
      model（DashboardController#dashboard / AdminController#index → AdminDashboardService#pageModel）：
        userCount / recentUsers(List<UserVo>)
        domainCount / configCount / statRowCount
        nodeCount / nodeConnections
        trafficReceive / trafficSend / trafficConnect / trafficSample
        todayReceive / todaySend / monthReceive / monthSend / topPorts
        auditTotal / auditOk / auditFail / auditRecentFail / auditDropped
        auditRecent(List<AuditLogEntity>) / auditByAction

      设计说明：
        1. 关键 KPI 由服务端直接渲染，禁用 JS 也能看到数字；图表与「最近事件」再由
           /admin/dashboard/data 异步刷新；
        2. 所有数字都以 ?c 输出（纯数值不可能承载 HTML），所有文本都以 ?html 输出；
        3. 「最近事件」表格里的相对时间由 JS 填充（title 保留绝对时间），
           字节数同理（data-bytes 保存原始字节，加载后格式化为 KB/MB/GB）。  -->
<main class="content">
    <div class="page-head">
        <div>
            <h1 class="page-head__title">仪表盘</h1>
            <div class="page-head__desc">集群、用户、流量与审计的整体概览。</div>
        </div>
        <div class="toolbar">
            <label class="switch" title="按固定间隔自动刷新图表与事件（30 秒）">
                <input type="checkbox" id="autoRefresh">
                <span class="switch__track"></span>
                <span>自动刷新</span>
            </label>
            <span class="refresh-hint" id="refreshHint"></span>
            <button type="button" class="btn btn--subtle" id="reloadBtn">
                <span data-icon="refresh" data-icon-class="icon--sm"></span>
                <span class="btn__label">刷新</span>
            </button>
        </div>
    </div>

    <#--  关键指标：服务端渲染，JS 只做单位换算  -->
    <section class="stat-grid" id="kpiGrid" aria-label="关键指标">
        <div class="stat-card">
            <div class="stat-card__label">用户总数</div>
            <div class="stat-card__value">${(userCount!0)?c}</div>
        </div>
        <div class="stat-card">
            <div class="stat-card__label">域名总数</div>
            <div class="stat-card__value">${(domainCount!0)?c}</div>
        </div>
        <div class="stat-card">
            <div class="stat-card__label">自动穿透配置</div>
            <div class="stat-card__value">${(configCount!0)?c}</div>
        </div>
        <div class="stat-card">
            <div class="stat-card__label">流量统计记录</div>
            <div class="stat-card__value">${(statRowCount!0)?c}</div>
        </div>
        <div class="stat-card">
            <div class="stat-card__label">在线节点</div>
            <div class="stat-card__value">${(nodeCount!0)?c}</div>
        </div>
        <div class="stat-card">
            <div class="stat-card__label">节点连接数</div>
            <div class="stat-card__value">${(nodeConnections!0)?c}</div>
        </div>
        <div class="stat-card">
            <div class="stat-card__label">今日入站流量</div>
            <div class="stat-card__value" data-bytes="${(todayReceive!0)?c}">${(todayReceive!0)?c} 字节</div>
        </div>
        <div class="stat-card">
            <div class="stat-card__label">本月入站流量</div>
            <div class="stat-card__value" data-bytes="${(monthReceive!0)?c}">${(monthReceive!0)?c} 字节</div>
        </div>
    </section>

    <#--  趋势图表：数据由 /admin/dashboard/data 提供，主题切换/尺寸变化自动重绘  -->
    <section class="chart-grid" aria-label="趋势图表">
        <div class="card">
            <div class="card__head">
                <div class="card__title">
                    <span data-icon="chart" data-icon-class="icon--sm"></span>
                    <span>流量趋势（按天）</span>
                    <span class="card__hint" id="trafficHint">加载中…</span>
                </div>
                <div class="toolbar">
                    <button type="button" class="btn btn--ghost btn--sm"
                            data-chart-export="trafficChart" data-chart-name="proxy-traffic">
                        <span data-icon="download" data-icon-class="icon--sm"></span>
                        <span class="btn__label">导出 PNG</span>
                    </button>
                </div>
            </div>
            <div class="card__body">
                <div class="chart-box">
                    <canvas id="trafficChart" role="img" aria-label="按天流量趋势图"></canvas>
                </div>
                <div class="legend" id="trafficLegend"></div>
            </div>
        </div>

        <div class="card">
            <div class="card__head">
                <div class="card__title">
                    <span data-icon="shieldCheck" data-icon-class="icon--sm"></span>
                    <span>审计事件趋势（按天）</span>
                    <span class="card__hint" id="auditHint">加载中…</span>
                </div>
                <div class="toolbar">
                    <button type="button" class="btn btn--ghost btn--sm"
                            data-chart-export="auditChart" data-chart-name="proxy-audit">
                        <span data-icon="download" data-icon-class="icon--sm"></span>
                        <span class="btn__label">导出 PNG</span>
                    </button>
                </div>
            </div>
            <div class="card__body">
                <div class="chart-box">
                    <canvas id="auditChart" role="img" aria-label="按天审计事件趋势图"></canvas>
                </div>
                <div class="legend" id="auditLegend"></div>
            </div>
        </div>
    </section>

    <section class="chart-grid" aria-label="分布排行">
        <div class="card">
            <div class="card__head">
                <div class="card__title">
                    <span data-icon="server" data-icon-class="icon--sm"></span>
                    <span>端口流量排行（入站前 8）</span>
                </div>
            </div>
            <div class="card__body">
                <div class="bar-list" id="portList" aria-label="端口流量排行"></div>
            </div>
        </div>
        <div class="card">
            <div class="card__head">
                <div class="card__title">
                    <span data-icon="activity" data-icon-class="icon--sm"></span>
                    <span>高频审计动作</span>
                </div>
                <a class="btn btn--ghost btn--sm" href="/admin/audit">查看审计日志</a>
            </div>
            <div class="card__body">
                <div class="bar-list" id="actionList" aria-label="高频审计动作"></div>
            </div>
        </div>
    </section>

    <section class="grid grid--tight" aria-label="最近动态">
        <div class="card">
            <div class="card__head">
                <div class="card__title">
                    <span data-icon="clock" data-icon-class="icon--sm"></span>
                    <span>最近事件</span>
                </div>
                <a class="btn btn--ghost btn--sm" href="/admin/audit">全部事件</a>
            </div>
            <div class="card__body">
                <#--  无 JS 时展示服务端渲染的最近事件；JS 刷新后会用同样的结构重建  -->
                <ul class="event-list" id="recentEvents">
                    <#if auditRecent?? && (auditRecent?size > 0)>
                        <#list auditRecent as event>
                            <li class="event-item">
                                <#if event.result == "fail">
                                    <span class="event-item__icon event-item__icon--fail">
                                        <span data-icon="warn" data-icon-class="icon--sm"></span>
                                    </span>
                                <#else>
                                    <span class="event-item__icon event-item__icon--ok">
                                        <span data-icon="check" data-icon-class="icon--sm"></span>
                                    </span>
                                </#if>
                                <div class="event-item__body">
                                    <div class="event-item__title">
                                        <span class="mono">${(event.action!"")?html}</span>
                                        <#if event.result == "fail">
                                            <span class="badge badge--fail">失败</span>
                                        <#else>
                                            <span class="badge badge--ok">成功</span>
                                        </#if>
                                    </div>
                                    <div class="event-item__meta">${(event.actorType!"")?html} · ${(event.actor!"")?html}<#if (event.target!"")?has_content> · ${(event.target!"")?html}</#if><#if (event.detail!"")?has_content> · ${(event.detail!"")?html}</#if></div>
                                </div>
                                <span class="event-item__time"
                                      data-relative-time="${(event.createTime!"")?html}"
                                      title="${(event.createTime!"")?html}">${(event.createTime!"")?html}</span>
                            </li>
                        </#list>
                    <#else>
                        <li class="event-item">
                            <span class="event-item__icon"><span data-icon="info" data-icon-class="icon--sm"></span></span>
                            <div class="event-item__body">
                                <div class="event-item__title">暂无审计事件</div>
                                <div class="event-item__meta">后台操作与用户登录失败都会记录在这里。</div>
                            </div>
                            <span class="event-item__time"></span>
                        </li>
                    </#if>
                </ul>
            </div>
        </div>

        <div class="card" id="recentUsersCard">
            <div class="card__head">
                <div class="card__title">
                    <span data-icon="users" data-icon-class="icon--sm"></span>
                    <span>最近注册</span>
                    <span class="card__hint">共 ${(userCount!0)?c} 个账号</span>
                </div>
                <a class="btn btn--ghost btn--sm" href="/admin/user">用户管理</a>
            </div>
            <div class="card__body">
                <ul class="event-list">
                    <#if recentUsers?? && (recentUsers?size > 0)>
                        <#list recentUsers as user>
                            <li class="event-item">
                                <span class="event-item__icon"><span data-icon="users" data-icon-class="icon--sm"></span></span>
                                <div class="event-item__body">
                                    <div class="event-item__title">
                                        <span class="truncate">${(user.username!"")?html}</span>
                                        <#if user.type?? && user.type == -1>
                                            <span class="badge badge--fail">已封禁</span>
                                        <#else>
                                            <span class="badge badge--ok-soft">正常</span>
                                        </#if>
                                    </div>
                                    <div class="event-item__meta">级别 ${(user.level!0)?c}</div>
                                </div>
                                <span class="event-item__time"
                                      data-relative-time="${(user.createTime!"")?html}"
                                      title="${(user.createTime!"")?html}">${(user.createTime!"")?html}</span>
                            </li>
                        </#list>
                    <#else>
                        <li class="event-item">
                            <span class="event-item__icon"><span data-icon="info" data-icon-class="icon--sm"></span></span>
                            <div class="event-item__body">
                                <div class="event-item__title">暂无注册用户</div>
                                <div class="event-item__meta">新注册的账号会出现在这里。</div>
                            </div>
                            <span class="event-item__time"></span>
                        </li>
                    </#if>
                </ul>
            </div>
        </div>
    </section>

    <section class="card">
        <div class="card__head">
            <div class="card__title">
                <span data-icon="zap" data-icon-class="icon--sm"></span>
                <span>快捷入口</span>
            </div>
        </div>
        <div class="card__body">
            <div class="row">
                <a class="btn btn--subtle" href="/admin/proxy">
                    <span data-icon="layers" data-icon-class="icon--sm"></span><span>穿透集群</span>
                </a>
                <a class="btn btn--subtle" href="/admin/config">
                    <span data-icon="sync" data-icon-class="icon--sm"></span><span>自动穿透</span>
                </a>
                <a class="btn btn--subtle" href="/admin/user">
                    <span data-icon="users" data-icon-class="icon--sm"></span><span>用户管理</span>
                </a>
                <a class="btn btn--subtle" href="/admin/domain">
                    <span data-icon="globe" data-icon-class="icon--sm"></span><span>域名管理</span>
                </a>
                <a class="btn btn--subtle" href="/admin/log">
                    <span data-icon="list" data-icon-class="icon--sm"></span><span>用户日志</span>
                </a>
                <a class="btn btn--subtle" href="/admin/audit">
                    <span data-icon="shieldCheck" data-icon-class="icon--sm"></span><span>审计日志</span>
                </a>
                <a class="btn btn--subtle" href="/admin/syslog">
                    <span data-icon="terminal" data-icon-class="icon--sm"></span><span>系统日志</span>
                </a>
                <a class="btn btn--subtle" href="/admin/tips">
                    <span data-icon="info" data-icon-class="icon--sm"></span><span>公告管理</span>
                </a>
            </div>
        </div>
        <div class="card__foot">
            指标口径：数量类来自各表分页接口的 totalRow；流量与审计为<b>取样聚合</b>
            （分别取最近 2 万条统计记录与 1 万条审计记录），因此是与业务量无关的常数级开销，
            数值为样本口径而非全量精确值。
            <#if (auditDropped!0) gt 0>
                当前有 ${(auditDropped!0)?c} 条审计事件因写入队列已满被丢弃（服务端日志有记录）。
            </#if>
        </div>
    </section>
</main>
<script>
    Admin.setTitle('仪表盘');

    (function () {
        'use strict';

        function byId(id) { return document.getElementById(id); }

        /* 图表系列：颜色统一取自 Admin.chart.palette，图例交给 Admin.chart.legend */
        var TRAFFIC_SERIES = [
            { name: '入站', color: Admin.chart.palette[0] },
            { name: '出站', color: Admin.chart.palette[1] }
        ];
        var AUDIT_SERIES = [
            { name: '成功', color: Admin.chart.palette[1] },
            { name: '失败', color: Admin.chart.palette[3] }
        ];

        var trafficSpec = { type: 'line', labels: [], series: [], yFormat: Admin.fmtBytes };
        var auditSpec = { type: 'bar', labels: [], series: [], yFormat: Admin.fmtNum };
        var redrawTraffic = null;
        var redrawAudit = null;

        function setupCharts() {
            Admin.chart.legend(byId('trafficLegend'), TRAFFIC_SERIES);
            Admin.chart.legend(byId('auditLegend'), AUDIT_SERIES);
            // Admin.chart.auto 已负责尺寸变化与 admin:themechange 重绘，这里不再自己监听主题
            redrawTraffic = Admin.chart.auto(byId('trafficChart'), function () { return trafficSpec; });
            redrawAudit = Admin.chart.auto(byId('auditChart'), function () { return auditSpec; });
        }

        /** 相对时间文案；解析失败返回空串（保留服务端渲染的绝对时间）。 */
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

        /** 把 [data-relative-time] 的文本替换为相对时间，title 里保留绝对时间。 */
        function fillRelativeTimes(root) {
            var nodes = (root || document).querySelectorAll('[data-relative-time]');
            Array.prototype.forEach.call(nodes, function (node) {
                var text = relativeText(node.getAttribute('data-relative-time'));
                if (text) { node.textContent = text; }
            });
        }

        /** 把 [data-bytes] 的原始字节数格式化成人类可读单位（无 JS 时显示原始字节）。 */
        function formatByteNodes(root) {
            var nodes = (root || document).querySelectorAll('[data-bytes]');
            Array.prototype.forEach.call(nodes, function (node) {
                node.textContent = Admin.fmtBytes(Number(node.getAttribute('data-bytes')) || 0);
            });
        }

        /** 用 Admin.el 构造事件节点：字符串一律以文本节点写入，绝不使用 innerHTML。 */
        function renderEvents(container, events) {
            if (!container) { return; }
            container.textContent = '';
            var list = Array.isArray(events) ? events : [];
            if (!list.length) {
                container.appendChild(Admin.el('div', { class: 'empty', text: '暂无审计事件' }));
                return;
            }
            list.forEach(function (item) {
                var failed = String(item.result || '').toLowerCase() === 'fail';
                var icon = Admin.el('span', {
                    class: 'event-item__icon ' + (failed ? 'event-item__icon--fail' : 'event-item__icon--ok')
                });
                icon.appendChild(Admin.svgNode(failed ? 'warn' : 'check', 'icon--sm'));

                var meta = [item.actorType, item.actor, item.target, item.ip]
                    .filter(function (value) { return value; }).join(' · ');
                if (item.detail) {
                    meta = meta ? meta + ' · ' + item.detail : String(item.detail);
                }

                container.appendChild(Admin.el('li', { class: 'event-item' }, [
                    icon,
                    Admin.el('div', { class: 'event-item__body' }, [
                        Admin.el('div', { class: 'event-item__title' }, [
                            Admin.el('span', { class: 'mono', text: String(item.action || '') }),
                            Admin.el('span', {
                                class: 'badge ' + (failed ? 'badge--fail' : 'badge--ok'),
                                text: failed ? '失败' : '成功'
                            })
                        ]),
                        Admin.el('div', { class: 'event-item__meta', text: meta })
                    ]),
                    Admin.el('span', {
                        class: 'event-item__time',
                        text: Admin.fmtDateTime(item.createTime),
                        title: String(item.createTime || '')
                    })
                ]));
            });
        }

        /** 异步刷新图表与事件流。 */
        function loadData() {
            return Admin.get('/admin/dashboard/data', { days: 14 }).then(function (res) {
                var data = res.data || {};
                var traffic = Array.isArray(data.trafficByDay) ? data.trafficByDay : [];
                var audit = Array.isArray(data.auditByDay) ? data.auditByDay : [];

                trafficSpec = {
                    type: 'line',
                    labels: traffic.map(function (d) { return String(d.day || '').slice(5); }),
                    series: [
                        {
                            name: '入站',
                            data: traffic.map(function (d) { return Number(d.receive) || 0; }),
                            color: TRAFFIC_SERIES[0].color
                        },
                        {
                            name: '出站',
                            data: traffic.map(function (d) { return Number(d.send) || 0; }),
                            color: TRAFFIC_SERIES[1].color
                        }
                    ],
                    yFormat: Admin.fmtBytes
                };
                auditSpec = {
                    type: 'bar',
                    labels: audit.map(function (d) { return String(d.day || '').slice(5); }),
                    series: [
                        {
                            name: '成功',
                            data: audit.map(function (d) { return Number(d.ok) || 0; }),
                            color: AUDIT_SERIES[0].color
                        },
                        {
                            name: '失败',
                            data: audit.map(function (d) { return Number(d.fail) || 0; }),
                            color: AUDIT_SERIES[1].color
                        }
                    ],
                    yFormat: Admin.fmtNum
                };
                if (redrawTraffic) { redrawTraffic(); }
                if (redrawAudit) { redrawAudit(); }

                Admin.chart.bars(byId('portList'), (data.trafficByPort || []).slice(0, 8).map(function (d) {
                    return {
                        label: String(d.port),
                        value: (Number(d.receive) || 0) + (Number(d.send) || 0)
                    };
                }), { format: Admin.fmtBytes, emptyText: '暂无端口流量数据' });

                Admin.chart.bars(byId('actionList'), (data.auditByAction || []).slice(0, 8).map(function (d) {
                    return { label: String(d.action || ''), value: Number(d.count) || 0 };
                }), { format: Admin.fmtNum, emptyText: '暂无审计动作数据' });

                renderEvents(byId('recentEvents'), data.auditRecent || []);

                var trafficTotal = data.trafficTotal || {};
                byId('trafficHint').textContent = '样本 ' + Admin.fmtNum(trafficTotal.rowCount) + ' 行 · 入站 ' +
                    Admin.fmtBytes(trafficTotal.receive) + ' · 出站 ' + Admin.fmtBytes(trafficTotal.send);
                var auditTotal = data.auditTotal || {};
                byId('auditHint').textContent = '成功 ' + Admin.fmtNum(auditTotal.ok) + ' · 失败 ' +
                    Admin.fmtNum(auditTotal.fail) + ' · 近 24 小时失败 ' + Admin.fmtNum(auditTotal.recentFail);
            }).catch(function (err) {
                byId('trafficHint').textContent = '图表加载失败';
                byId('auditHint').textContent = '图表加载失败';
                Admin.toastErr(err.message || '仪表盘数据加载失败');
            });
        }

        /**
         * 顶栏失败徽标：单独请求 /admin/audit/stats（days=1, recent=1）。
         * 不复用图表数据是有意为之——徽标的语义固定是「近 24 小时失败数」，
         * 与图表的天数参数解耦，以后调整图表区间不会意外改掉告警口径。
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

        /** 局部刷新服务端渲染的 KPI 与最近注册（DOMParser 替换节点，不整页重载）。 */
        function refreshRegions() {
            return Admin.refreshRegions(window.location.pathname, ['#kpiGrid', '#recentUsersCard']).then(function () {
                // 新节点里的 data-icon 占位需要重新套用图标；幂等，可安全重复调用
                Admin.applyIcons();
                formatByteNodes();
                fillRelativeTimes();
            }).catch(function (err) {
                Admin.toastWarn(err.message || '概览刷新失败');
            });
        }

        function init() {
            setupCharts();
            formatByteNodes();
            fillRelativeTimes();
            loadData();
            loadBadge();

            Admin.autoRefresh({
                toggle: 'autoRefresh',
                interval: 30000,
                storageKey: 'px_admin_dashboard_autorefresh',
                countdown: 'refreshHint',
                onTick: function () {
                    // 自动刷新只走 JSON 数据（图表 + 事件流 + 徽标），不重新渲染整页：
                    // 仪表盘是落地页，避免每 30 秒触发一次服务端整页聚合渲染
                    loadData();
                    loadBadge();
                }
            });

            byId('reloadBtn').addEventListener('click', function () {
                refreshRegions();
                loadData();
                loadBadge();
                Admin.toast('正在刷新仪表盘', { type: 'info', timeout: 1600 });
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

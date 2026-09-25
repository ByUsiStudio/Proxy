<#include "./header_index.ftl">
<#--  我的用量（GET /index/usage）
      model（PortalController#usage）：
        username(String，来自 AuthFilter 注入的会话身份)
        days(int) / usage(PortalUsageVo) / quota(PortalQuotaVo，可为空) / hasQuota(boolean)
        userLevel / userType / userCreateTime
        myDomains(List<DomainEntity>) / myPorts(List<PortEntity>)
      安全：FreeMarker 不自动转义，所有插值都显式 ?html / ?c / ?url；页面不含任何口令字段。  -->
<div class="usage-wrap">
    <div class="usage-head">
        <div>
            <h1 class="usage-title">我的用量</h1>
            <div class="usage-sub">
                账号 <span class="mono">${username?html}</span>
                · 等级 <#if (userLevel!0) gt 0>${userLevel?c}<#else>普通</#if>
                · 注册时间 ${userCreateTime?html}
            </div>
        </div>
        <div class="usage-actions">
            <a class="mdui-btn mdui-btn-dense mdui-ripple" href="/index/log">穿透日志</a>
            <a class="mdui-btn mdui-btn-dense mdui-ripple" href="/index/export?type=statistics&amp;format=csv">导出流量 CSV</a>
            <a class="mdui-btn mdui-btn-dense mdui-ripple" href="/index/export?type=statistics&amp;format=json">导出流量 JSON</a>
            <a class="mdui-btn mdui-btn-dense mdui-ripple" href="/index/export?type=config&amp;format=csv">导出隧道配置 CSV</a>
        </div>
    </div>

    <#--  数据口径必须如实告知：统计基于采样（见 PortalService#usage）  -->
    <div class="usage-note">
        统计口径：服务端按时间倒序最多读取 ${usage.sampleLimit?c} 条流量记录后汇总
        （已采样 ${usage.sampleCount?c} 条）；若历史记录超过该上限，「累计 / 本月」的数值会偏小。
    </div>

    <#--  ============ 本月关键指标 ============  -->
    <div class="usage-grid">
        <div class="usage-stat">
            <div class="usage-stat__label">本月接收</div>
            <div class="usage-stat__value" id="statMonthReceive">${usage.monthReceive?c}</div>
            <div class="usage-stat__unit">字节</div>
        </div>
        <div class="usage-stat">
            <div class="usage-stat__label">本月发送</div>
            <div class="usage-stat__value" id="statMonthSend">${usage.monthSend?c}</div>
            <div class="usage-stat__unit">字节</div>
        </div>
        <div class="usage-stat">
            <div class="usage-stat__label">本月连接数</div>
            <div class="usage-stat__value" id="statMonthConnect">${usage.monthConnect?c}</div>
            <div class="usage-stat__unit">次</div>
        </div>
        <div class="usage-stat">
            <div class="usage-stat__label">本月数据包</div>
            <div class="usage-stat__value" id="statMonthPack">${usage.monthPack?c}</div>
            <div class="usage-stat__unit">个</div>
        </div>
    </div>

    <div class="usage-grid">
        <div class="usage-stat usage-stat--soft">
            <div class="usage-stat__label">累计接收</div>
            <div class="usage-stat__value" id="statTotalReceive">${usage.totalReceive?c}</div>
        </div>
        <div class="usage-stat usage-stat--soft">
            <div class="usage-stat__label">累计发送</div>
            <div class="usage-stat__value" id="statTotalSend">${usage.totalSend?c}</div>
        </div>
        <div class="usage-stat usage-stat--soft">
            <div class="usage-stat__label">累计连接数</div>
            <div class="usage-stat__value" id="statTotalConnect">${usage.totalConnect?c}</div>
        </div>
        <div class="usage-stat usage-stat--soft">
            <div class="usage-stat__label">累计数据包</div>
            <div class="usage-stat__value" id="statTotalPack">${usage.totalPack?c}</div>
        </div>
    </div>

    <#--  ============ 趋势图（Canvas 自绘，数据来自 /index/usage/data，会话维度） ============  -->
    <div class="usage-card">
        <div class="usage-card__head">
            <div class="usage-card__title">最近 ${usage.days?c} 天流量趋势</div>
            <div class="usage-hint" id="chartHint">图表加载中…</div>
        </div>
        <div class="usage-chart">
            <canvas id="usageChart" role="img" aria-label="最近流量趋势图"></canvas>
        </div>
        <div class="usage-legend">
            <span class="usage-legend__item"><i class="usage-dot usage-dot--receive"></i>接收</span>
            <span class="usage-legend__item"><i class="usage-dot usage-dot--send"></i>发送</span>
        </div>
    </div>

    <#--  ============ 按端口分布 ============  -->
    <div class="usage-card">
        <div class="usage-card__head">
            <div class="usage-card__title">按端口分布</div>
            <div class="usage-hint">共 ${usage.byPort?size?c} 个端口</div>
        </div>
        <div class="mdui-table-fluid">
            <table class="mdui-table">
                <thead>
                <tr>
                    <th>端口</th>
                    <th>接收（字节）</th>
                    <th>发送（字节）</th>
                    <th>连接数</th>
                    <th>数据包数</th>
                </tr>
                </thead>
                <tbody>
                <#if usage.byPort?size gt 0>
                    <#list usage.byPort as row>
                        <tr>
                            <td class="mono">${row.port?c}</td>
                            <td>${row.receive?c}</td>
                            <td>${row.send?c}</td>
                            <td>${row.connectNum?c}</td>
                            <td>${row.packNum?c}</td>
                        </tr>
                    </#list>
                <#else>
                    <tr>
                        <td colspan="5" class="usage-muted">暂无端口流量数据</td>
                    </tr>
                </#if>
                </tbody>
            </table>
        </div>
    </div>

    <#--  ============ 配额 ============  -->
    <div class="usage-card">
        <div class="usage-card__head">
            <div class="usage-card__title">我的配额</div>
            <div class="usage-hint">
                <#if hasQuota>
                    <#if quota.enabledFlag>已启用<#else>已停用</#if>
                <#else>
                    未设置配额
                </#if>
            </div>
        </div>
        <#if hasQuota>
            <div class="usage-kv-grid">
                <div class="usage-kv"><span class="usage-kv__key">最大隧道数</span><span class="usage-kv__val"><#if quota.maxTunnels??>${quota.maxTunnels?c}<#else>-</#if></span></div>
                <div class="usage-kv"><span class="usage-kv__key">最大端口数</span><span class="usage-kv__val"><#if quota.maxPorts??>${quota.maxPorts?c}<#else>-</#if></span></div>
                <div class="usage-kv"><span class="usage-kv__key">最大并发连接</span><span class="usage-kv__val"><#if quota.maxConns??>${quota.maxConns?c}<#else>-</#if></span></div>
                <div class="usage-kv"><span class="usage-kv__key">月度接收上限</span><span class="usage-kv__val"><#if (quota.monthlyReceive)?has_content>${quota.monthlyReceive?html} 字节<#else>-</#if></span></div>
                <div class="usage-kv"><span class="usage-kv__key">月度发送上限</span><span class="usage-kv__val"><#if (quota.monthlySend)?has_content>${quota.monthlySend?html} 字节<#else>-</#if></span></div>
                <div class="usage-kv"><span class="usage-kv__key">超限状态</span><span class="usage-kv__val"><#if quota.over>已超限<#else>正常</#if></span></div>
            </div>
            <#if quota.over && (quota.overReason)?has_content>
                <div class="usage-warn">超限原因：${quota.overReason?html}</div>
            </#if>
            <#if (quota.note)?has_content>
                <div class="usage-muted">备注：${quota.note?html}</div>
            </#if>
        <#else>
            <div class="usage-empty">未设置配额（表示当前账号没有单独的配额限制，如需调整请联系管理员）。</div>
        </#if>
    </div>

    <#--  ============ 我的域名 + 自助申请 ============  -->
    <div class="usage-card">
        <div class="usage-card__head">
            <div class="usage-card__title">我的域名</div>
            <div class="usage-hint">共 ${myDomains?size?c} 个（自助申请每人最多 3 个）</div>
        </div>
        <div class="mdui-table-fluid">
            <table class="mdui-table">
                <thead>
                <tr>
                    <th>域名</th>
                    <th>自定义域名</th>
                    <th>创建时间</th>
                </tr>
                </thead>
                <tbody>
                <#if myDomains?size gt 0>
                    <#list myDomains as item>
                        <tr>
                            <td class="mono">${(item.domain!"")?html}</td>
                            <td class="mono"><#if (item.customDomain!"")?has_content>${item.customDomain?html}<#else>未绑定</#if></td>
                            <td>${(item.createTime!"")?html}</td>
                        </tr>
                    </#list>
                <#else>
                    <tr><td colspan="3" class="usage-muted">还没有域名</td></tr>
                </#if>
                </tbody>
            </table>
        </div>
        <form class="usage-form" id="applyDomainForm" autocomplete="off">
            <label class="usage-form__label" for="applyDomain">申请新域名</label>
            <input class="usage-input" id="applyDomain" name="domain" type="text" maxlength="253"
                   placeholder="例如 myapp（只允许字母、数字、点与横线，4~253 位）" required/>
            <button class="mdui-btn mdui-btn-raised mdui-color-theme" type="submit" id="applyDomainBtn">提交申请</button>
            <div class="usage-form__status" id="applyDomainStatus" role="status"></div>
        </form>
        <div class="usage-muted">提交后状态为「已提交，等待解析生效」：系统会先建立归属记录，解析生效需要 DNS 传播时间。</div>
    </div>

    <#--  ============ 我的固定端口 + 自助申请 ============  -->
    <div class="usage-card">
        <div class="usage-card__head">
            <div class="usage-card__title">我的固定端口</div>
            <div class="usage-hint">共 ${myPorts?size?c} 个（自助申请每人最多 3 个）</div>
        </div>
        <div class="mdui-table-fluid">
            <table class="mdui-table">
                <thead>
                <tr>
                    <th>端口</th>
                    <th>创建时间</th>
                </tr>
                </thead>
                <tbody>
                <#if myPorts?size gt 0>
                    <#list myPorts as item>
                        <tr>
                            <td class="mono">${(item.port!0)?c}</td>
                            <td>${(item.createTime!"")?html}</td>
                        </tr>
                    </#list>
                <#else>
                    <tr><td colspan="2" class="usage-muted">还没有固定端口</td></tr>
                </#if>
                </tbody>
            </table>
        </div>
        <form class="usage-form" id="applyPortForm" autocomplete="off">
            <label class="usage-form__label" for="applyPort">申请固定端口</label>
            <#--  范围与服务端 PortalServiceImpl.PORT_MIN/PORT_MAX 保持一致：
                  10000 以下属系统与节点保留端口，不对自助申请开放  -->
            <input class="usage-input" id="applyPort" name="port" type="number" min="10000" max="60000"
                   placeholder="10000~60000" required/>
            <button class="mdui-btn mdui-btn-raised mdui-color-theme" type="submit" id="applyPortBtn">提交申请</button>
            <div class="usage-form__status" id="applyPortStatus" role="status"></div>
        </form>
        <div class="usage-muted">提交后状态为「已提交，等待解析生效」；端口是否可用取决于节点侧监听情况。</div>
    </div>
</div>

<script>
    /*
     * 我的用量：图表 + 自助申请。
     * 说明（本页为什么没有引入 /common/js/admin.js）：
     *   admin.js 是可独立运行的 IIFE，但它的 boot() 会执行 Admin.theme.init()，
     *   用 localStorage 键 px_admin_theme 覆盖 <html data-theme>，与站点主题键 px_site_theme 冲突，
     *   且其组件样式都依赖只在后台加载的 admin.css（门户页不会加载它）。
     *   因此这里改为页面内自绘 Canvas 图表，只读取 index.css 的 CSS 变量，零额外依赖。
     * 安全：全部使用 DOM API / textContent，绝不使用 innerHTML，也不拼接 HTML 字符串。
     */
    (function () {
        'use strict';

        var DAYS = ${usage.days?c};

        function cssVar(name, fallback) {
            var value = getComputedStyle(document.documentElement).getPropertyValue(name);
            return (value && value.trim()) || fallback;
        }

        function fmtBytes(value) {
            var v = Number(value) || 0;
            var units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB'];
            var i = 0;
            while (v >= 1024 && i < units.length - 1) {
                v = v / 1024;
                i++;
            }
            return (i === 0 ? String(v) : v.toFixed(v >= 100 ? 0 : 1)) + ' ' + units[i];
        }

        function setText(id, text) {
            var node = document.getElementById(id);
            if (node) { node.textContent = text; }
        }

        /* ---------------- Canvas 折线图（自绘，跟随主题与容器宽度重绘） ---------------- */
        var canvas = document.getElementById('usageChart');
        var spec = { labels: [], receive: [], send: [] };

        function draw() {
            if (!canvas) { return; }
            var ctx = canvas.getContext('2d');
            var dpr = window.devicePixelRatio || 1;
            var rect = canvas.getBoundingClientRect();
            var width = Math.max(rect.width, 260);
            var height = Math.max(rect.height, 180);
            canvas.width = Math.round(width * dpr);
            canvas.height = Math.round(height * dpr);
            ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
            ctx.clearRect(0, 0, width, height);

            var border = cssVar('--border', '#e2e8f2');
            var faint = cssVar('--text-faint', '#8b97ab');
            var surface = cssVar('--bg-elevated', '#ffffff');
            var labels = spec.labels;
            var series = [
                { name: '接收', data: spec.receive, color: cssVar('--brand-500', '#2f6bff') },
                { name: '发送', data: spec.send, color: cssVar('--success-500', '#10b981') }
            ];

            if (!labels.length) {
                ctx.fillStyle = faint;
                ctx.font = '13px ' + cssVar('--font', 'sans-serif');
                ctx.textAlign = 'center';
                ctx.textBaseline = 'middle';
                ctx.fillText('暂无流量数据', width / 2, height / 2);
                return;
            }

            var padding = { top: 16, right: 14, bottom: 30, left: 62 };
            var plotW = Math.max(width - padding.left - padding.right, 10);
            var plotH = Math.max(height - padding.top - padding.bottom, 10);
            var max = 0;
            series.forEach(function (s) {
                s.data.forEach(function (v) { if (v > max) { max = v; } });
            });
            if (max <= 0) { max = 1; }
            var magnitude = Math.pow(10, Math.floor(Math.log(max) / Math.LN10));
            var niceMax = Math.ceil(max / (magnitude / 2)) * (magnitude / 2);
            if (niceMax <= 0) { niceMax = 1; }

            ctx.strokeStyle = border;
            ctx.lineWidth = 1;
            ctx.fillStyle = faint;
            ctx.font = '11px ' + cssVar('--font', 'sans-serif');
            ctx.textAlign = 'right';
            ctx.textBaseline = 'middle';
            var ticks = 4;
            for (var t = 0; t <= ticks; t++) {
                var value = (niceMax / ticks) * t;
                var y = padding.top + plotH - (plotH * (value / niceMax));
                ctx.beginPath();
                ctx.moveTo(padding.left, Math.round(y) + 0.5);
                ctx.lineTo(padding.left + plotW, Math.round(y) + 0.5);
                ctx.stroke();
                ctx.fillText(fmtBytes(value), padding.left - 6, y);
            }

            function xAt(index) {
                if (labels.length === 1) { return padding.left + plotW / 2; }
                return padding.left + (plotW * index) / (labels.length - 1);
            }
            function yAt(v) {
                return padding.top + plotH - (plotH * (v / niceMax));
            }

            ctx.textAlign = 'center';
            ctx.textBaseline = 'top';
            var step = Math.ceil(labels.length / Math.max(2, Math.floor(plotW / 70)));
            for (var i = 0; i < labels.length; i++) {
                if (i % step !== 0 && i !== labels.length - 1) { continue; }
                ctx.fillText(String(labels[i]).slice(5), xAt(i), padding.top + plotH + 8);
            }

            series.forEach(function (s) {
                if (!s.data.length) { return; }
                ctx.beginPath();
                s.data.forEach(function (v, index) {
                    if (index === 0) { ctx.moveTo(xAt(index), yAt(v)); }
                    else { ctx.lineTo(xAt(index), yAt(v)); }
                });
                ctx.strokeStyle = s.color;
                ctx.lineWidth = 2;
                ctx.lineJoin = 'round';
                ctx.stroke();

                ctx.lineTo(xAt(s.data.length - 1), padding.top + plotH);
                ctx.lineTo(xAt(0), padding.top + plotH);
                ctx.closePath();
                var gradient = ctx.createLinearGradient(0, padding.top, 0, padding.top + plotH);
                gradient.addColorStop(0, s.color + '44');
                gradient.addColorStop(1, s.color + '05');
                ctx.fillStyle = gradient;
                ctx.fill();

                ctx.fillStyle = surface;
                ctx.strokeStyle = s.color;
                s.data.forEach(function (v, index) {
                    ctx.beginPath();
                    ctx.arc(xAt(index), yAt(v), 2.4, 0, Math.PI * 2);
                    ctx.fill();
                    ctx.stroke();
                });
            });

            ctx.strokeStyle = border;
            ctx.beginPath();
            ctx.moveTo(padding.left, padding.top);
            ctx.lineTo(padding.left, padding.top + plotH);
            ctx.lineTo(padding.left + plotW, padding.top + plotH);
            ctx.stroke();
        }

        function loadChart() {
            var hint = document.getElementById('chartHint');
            fetch('/index/usage/data?days=' + encodeURIComponent(DAYS), {
                method: 'GET',
                credentials: 'same-origin',
                cache: 'no-store',
                headers: { 'Accept': 'application/json' }
            }).then(function (response) {
                return response.json();
            }).then(function (data) {
                if (!data || data.code !== 200) {
                    if (hint) { hint.textContent = '图表加载失败'; }
                    return;
                }
                spec.labels = [];
                spec.receive = [];
                spec.send = [];
                (data.byDay || []).forEach(function (point) {
                    spec.labels.push(String(point.day || ''));
                    spec.receive.push(Number(point.receive) || 0);
                    spec.send.push(Number(point.send) || 0);
                });
                draw();

                var month = data.month || {};
                var total = data.total || {};
                setText('statMonthReceive', fmtBytes(month.receive));
                setText('statMonthSend', fmtBytes(month.send));
                setText('statMonthConnect', String(Number(month.connectNum) || 0));
                setText('statMonthPack', String(Number(month.packNum) || 0));
                setText('statTotalReceive', fmtBytes(total.receive));
                setText('statTotalSend', fmtBytes(total.send));
                setText('statTotalConnect', String(Number(total.connectNum) || 0));
                setText('statTotalPack', String(Number(total.packNum) || 0));
                if (hint) {
                    hint.textContent = '本月 ' + (data.monthLabel || '') +
                        ' · 采样 ' + (Number(data.sampleCount) || 0) + ' 条';
                }
            }).catch(function () {
                if (hint) { hint.textContent = '图表加载失败'; }
            });
        }

        /* ---------------- 自助申请（写操作，服务端校验来源 + 会话身份） ---------------- */
        function submitApply(url, fieldId, statusId, buttonId) {
            var input = document.getElementById(fieldId);
            var status = document.getElementById(statusId);
            var button = document.getElementById(buttonId);
            if (!input || !status) { return; }
            var value = String(input.value || '').trim();
            if (!value) {
                status.textContent = '请先填写内容';
                return;
            }
            status.textContent = '提交中…';
            if (button) { button.disabled = true; }
            var body = new URLSearchParams();
            body.append(fieldId === 'applyDomain' ? 'domain' : 'port', value);
            fetch(url, {
                method: 'POST',
                credentials: 'same-origin',
                cache: 'no-store',
                headers: {
                    'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
                    'X-Requested-With': 'XMLHttpRequest'
                },
                body: body.toString()
            }).then(function (response) {
                return response.json();
            }).then(function (result) {
                if (result && result.code === 200) {
                    status.textContent = (result.msg || '已提交，等待解析生效');
                    input.value = '';
                    // 稍后刷新页面，让新记录出现在上面的列表里
                    window.setTimeout(function () { window.location.reload(); }, 1200);
                } else {
                    status.textContent = (result && result.msg) ? result.msg : '提交失败，请稍后重试';
                    if (button) { button.disabled = false; }
                }
            }).catch(function () {
                status.textContent = '网络错误，请稍后重试';
                if (button) { button.disabled = false; }
            });
        }

        var domainForm = document.getElementById('applyDomainForm');
        if (domainForm) {
            domainForm.addEventListener('submit', function (event) {
                event.preventDefault();
                submitApply('/index/apply/domain', 'applyDomain', 'applyDomainStatus', 'applyDomainBtn');
            });
        }
        var portForm = document.getElementById('applyPortForm');
        if (portForm) {
            portForm.addEventListener('submit', function (event) {
                event.preventDefault();
                submitApply('/index/apply/port', 'applyPort', 'applyPortStatus', 'applyPortBtn');
            });
        }

        /* ---------------- 初始化 ---------------- */
        loadChart();
        if (window.ResizeObserver && canvas) {
            new ResizeObserver(function () { draw(); }).observe(canvas);
        } else {
            window.addEventListener('resize', draw);
        }
        // 主题切换后重绘（颜色取自 CSS 变量）
        document.addEventListener('px:themechange', draw);
    })();
</script>
</body>
</html>

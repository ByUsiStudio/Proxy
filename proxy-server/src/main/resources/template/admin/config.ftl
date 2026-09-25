<#include "./header.ftl">
<#--  自动穿透配置
      model（AutoConfigController#log）：page / pageSize / totalRow / totalPage / list(List<ConfigEntity>)
                                        / username / deviceId
      ConfigEntity：id / userId / username / deviceId / userHost / serverHost / type / domain / port / createTime

      本轮新增：条件筛选、JSON/CSV 导出、JSON 导入、二维码分享、批量删除、列表自动刷新。
      安全提示：配置导出与二维码内容<b>永不包含 password</b>（客户端隧道凭据）。  -->
<main class="content">
    <div class="page-head">
        <div>
            <h1 class="page-head__title">自动穿透</h1>
            <div class="page-head__desc">客户端自动生成的穿透配置，可筛选、导出、导入并扫码分享。</div>
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

    <section class="card">
        <div class="card__body">
            <form class="filter-bar" method="get" action="/admin/config">
                <input class="input" id="username" name="username" type="text"
                       value="${(username!"")?html}" placeholder="用户名关键字" aria-label="按用户名筛选"/>
                <input class="input" id="deviceId" name="deviceId" type="text"
                       value="${(deviceId!"")?html}" placeholder="设备ID关键字" aria-label="按设备ID筛选"/>
                <button class="btn btn--subtle" type="submit" id="selectButton">
                    <span data-icon="search" data-icon-class="icon--sm"></span>
                    <span>查询</span>
                </button>
                <a class="btn btn--ghost" href="/admin/config">
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
                <button type="button" class="btn btn--primary" id="importOpen">
                    <span data-icon="upload" data-icon-class="icon--sm"></span>
                    <span>导入配置</span>
                </button>
                <button type="button" class="btn btn--subtle" id="qrOpen">
                    <span data-icon="qr" data-icon-class="icon--sm"></span>
                    <span>二维码分享</span>
                </button>
            </form>
        </div>
    </section>

    <section class="card">
        <div class="card__head">
            <div class="card__title">
                <span data-icon="sync" data-icon-class="icon--sm"></span>
                <span>配置列表</span>
                <span class="card__hint">共 ${totalRow?c} 条</span>
            </div>
            <div class="toolbar">
                <input class="input" type="search" data-table-filter="configTable"
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
                已选中 <span class="bulk-bar__count" id="bulkCount">0</span> 条配置
            </div>
            <div class="table-wrap">
                <table class="table" id="configTable" data-sortable="true">
                    <thead>
                    <tr>
                        <th class="col-check" data-sort-ignore>
                            <input type="checkbox" id="selectAll" aria-label="全选当前页"/>
                        </th>
                        <th>id</th>
                        <th>用户名</th>
                        <th>用户内网</th>
                        <th>外网服务</th>
                        <th>类型</th>
                        <th>域名</th>
                        <th data-sort-type="number">端口</th>
                        <th>设备ID</th>
                        <th>时间</th>
                        <th data-sort-ignore>操作</th>
                    </tr>
                    </thead>
                    <tbody id="configRows">
                    <#if list??>
                        <#list list as statistics>
                            <tr data-key="${(statistics.id!"")?html}">
                                <td class="col-check">
                                    <input type="checkbox" data-row-key="${(statistics.id!"")?html}"
                                           aria-label="选择配置 ${(statistics.id!"")?html}"/>
                                </td>
                                <td class="mono">${(statistics.id!"")?html}</td>
                                <td>${(statistics.username!"")?html}</td>
                                <td class="mono">${(statistics.userHost!"")?html}</td>
                                <td class="mono">${(statistics.serverHost!"")?html}</td>
                                <td><span class="badge badge--info">${(statistics.type!"")?html}</span></td>
                                <td>${(statistics.domain!"")?html}</td>
                                <td>
                                    <#if statistics.port?? && statistics.port != "">
                                        <span class="badge badge--muted">${statistics.port?html}</span>
                                    <#else>
                                        <span class="muted text-xs">随机</span>
                                    </#if>
                                </td>
                                <td class="mono">${(statistics.deviceId!"")?html}</td>
                                <td>${(statistics.createTime!"")?html}</td>
                                <td>
                                    <div class="cell-actions">
                                        <#--  保持原 href 不变  -->
                                        <a class="btn btn--danger btn--sm"
                                           href="/admin/config/remove?page=${page?c}&id=${(statistics.id!"")?url}"
                                           data-confirm="确定删除该条自动穿透配置吗？"
                                           data-confirm-title="删除配置">
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
             data-pager-url="/admin/config"
             data-pager-page="${page?c}"
             data-pager-total="${totalPage?c}"
             data-pager-params="username,deviceId"></div>
    </section>
</main>

<#--  导入配置  -->
<div class="modal" id="importModal" role="dialog" aria-modal="true" aria-hidden="true" aria-labelledby="importTitle">
    <div class="modal__panel modal__panel--wide">
        <div class="modal__head">
            <h3 class="modal__title" id="importTitle">导入自动穿透配置</h3>
            <button type="button" class="btn btn--ghost btn--icon" data-dialog-close aria-label="关闭">
                <span data-icon="close" data-icon-class="icon--sm"></span>
            </button>
        </div>
        <div class="modal__body">
            <div class="alert alert--info">
                <span data-icon="info"></span>
                <div>
                    支持与「导出 JSON」一致的格式：<code>{"tunnels":[ ... ]}</code> 或直接粘贴数组。
                    每条记录包含 username / deviceId / userHost / serverHost / type / domain / port。
                    <strong>出于安全考虑，导入内容不包含也不会写入隧道密码。</strong>
                </div>
            </div>
            <div class="field">
                <label class="field__label" for="importFile">从文件读取（可选）</label>
                <input class="input" id="importFile" type="file" accept=".json,application/json"/>
            </div>
            <div class="field">
                <label class="field__label" for="importText">配置内容（JSON）</label>
                <textarea class="code-block" id="importText" spellcheck="false"
                          placeholder='{"tunnels":[{"username":"a@b.com","deviceId":"abc1234567","userHost":"127.0.0.1:8080","serverHost":"1.2.3.4:9090","type":"TCP","domain":"demo","port":"8080"}]}'></textarea>
            </div>
            <div class="alert hidden" id="importResult"></div>
        </div>
        <div class="modal__foot">
            <button type="button" class="btn btn--ghost" data-dialog-close>取消</button>
            <button type="button" class="btn btn--primary" id="importSubmit">开始导入</button>
        </div>
    </div>
</div>

<#--  二维码分享  -->
<div class="modal" id="qrModal" role="dialog" aria-modal="true" aria-hidden="true" aria-labelledby="qrTitle">
    <div class="modal__panel">
        <div class="modal__head">
            <h3 class="modal__title" id="qrTitle">扫码分享配置</h3>
            <button type="button" class="btn btn--ghost btn--icon" data-dialog-close aria-label="关闭">
                <span data-icon="close" data-icon-class="icon--sm"></span>
            </button>
        </div>
        <div class="modal__body">
            <div class="alert alert--warn">
                <span data-icon="warn"></span>
                <div>
                    二维码内容取自<b>当前页可见的配置行</b>，<b>不包含隧道密码</b>。
                    二维码容量有限（最多约 213 字节），超出部分会被自动截断，完整内容请使用「导出 JSON」。
                </div>
            </div>
            <div class="qr-box">
                <canvas id="qrCanvas" aria-label="配置分享二维码"></canvas>
                <div class="qr-meta" id="qrMeta">正在生成…</div>
            </div>
            <div class="field">
                <label class="field__label" for="qrText">分享内容</label>
                <textarea class="code-block" id="qrText" readonly spellcheck="false"></textarea>
            </div>
            <div class="alert alert--warn hidden" id="qrWarn"></div>
        </div>
        <div class="modal__foot">
            <button type="button" class="btn btn--ghost" id="qrCopy">
                <span data-icon="copy" data-icon-class="icon--sm"></span>
                <span>复制内容</span>
            </button>
            <button type="button" class="btn btn--ghost" id="qrDownload">
                <span data-icon="download" data-icon-class="icon--sm"></span>
                <span>下载二维码</span>
            </button>
            <button type="button" class="btn btn--primary" data-dialog-close>完成</button>
        </div>
    </div>
</div>

<script>
    Admin.setTitle('自动穿透');

    (function () {
        'use strict';

        /* 二维码可承载的字节上限（版本 10 / 纠错级别 M 的字节模式容量） */
        var QR_MAX_BYTES = 213;

        function byId(id) { return document.getElementById(id); }

        function filterQuery(extraPage) {
            var params = new URLSearchParams();
            var u = byId('username');
            var d = byId('deviceId');
            if (u && u.value.trim()) { params.set('username', u.value.trim()); }
            if (d && d.value.trim()) { params.set('deviceId', d.value.trim()); }
            if (extraPage) { params.set('page', extraPage); }
            var s = params.toString();
            return s ? '?' + s : '';
        }

        function currentUrl(page) { return '/admin/config' + filterQuery(page); }

        /* ---------------- 导出 ---------------- */
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
            download('/admin/config/export' + query + (query ? '&' : '?') + 'format=' + encodeURIComponent(format));
            Admin.toast('正在导出当前筛选条件下的全部配置', { type: 'info', timeout: 2600 });
        }

        /* ---------------- 二维码分享 ---------------- */
        /** UTF-8 字节数，用于判断二维码容量。 */
        function byteLength(text) {
            if (typeof TextEncoder !== 'undefined') { return new TextEncoder().encode(text).length; }
            return encodeURIComponent(text).replace(/%[0-9A-F]{2}/g, 'x').length;
        }

        /**
         * 用当前可见行构造精简分享内容。
         * 字段名压缩以节省二维码容量，逐条加入直到超出容量上限为止。
         */
        function buildSharePayload() {
            var parsed = Admin.tableRecords('configTable', { skipColumns: [0] });
            var header = parsed.headers;
            function col(name) {
                var idx = header.indexOf(name);
                return idx < 0 ? '' : idx;
            }
            var idxUsername = col('用户名');
            var idxUserHost = col('用户内网');
            var idxServerHost = col('外网服务');
            var idxType = col('类型');
            var idxDomain = col('域名');
            var idxPort = col('端口');
            var idxDevice = col('设备ID');

            var included = [];
            var truncated = 0;
            for (var i = 0; i < parsed.rows.length; i++) {
                var row = parsed.rows[i];
                var item = {
                    u: String(row[idxUsername] || ''),
                    d: String(row[idxDevice] || ''),
                    h: String(row[idxUserHost] || ''),
                    s: String(row[idxServerHost] || ''),
                    y: String(row[idxType] || 'TCP'),
                    m: String(row[idxDomain] || ''),
                    p: String(row[idxPort] === '随机' ? '' : (row[idxPort] || ''))
                };
                included.push(item);
                var payload = JSON.stringify({ v: '16.0', t: included });
                if (byteLength(payload) > QR_MAX_BYTES) {
                    included.pop();
                    truncated = parsed.rows.length - included.length;
                    break;
                }
            }
            return {
                text: JSON.stringify({ v: '16.0', t: included }),
                count: included.length,
                total: parsed.rows.length,
                truncated: truncated
            };
        }

        var qrPayload = '';

        function openQr() {
            var share = buildSharePayload();
            qrPayload = share.text;
            byId('qrText').value = qrPayload;
            byId('qrWarn').classList.add('hidden');

            if (!share.count) {
                byId('qrMeta').textContent = '当前页没有可分享的配置';
                var ctx = byId('qrCanvas').getContext('2d');
                ctx.clearRect(0, 0, byId('qrCanvas').width, byId('qrCanvas').height);
                Admin.dialog.open('qrModal');
                return;
            }

            var result = Admin.qrcodeCanvas(byId('qrCanvas'), qrPayload, { scale: 5, quiet: 4 });
            if (!result) {
                byId('qrMeta').textContent = '内容超出二维码容量，请改用「导出 JSON」';
            } else {
                byId('qrMeta').textContent =
                    '已包含 ' + share.count + ' / ' + share.total + ' 条配置 · ' +
                    byteLength(qrPayload) + ' 字节 · 版本 ' + result.version;
            }
            if (share.truncated) {
                var warn = byId('qrWarn');
                warn.textContent = '二维码容量有限，已省略 ' + share.truncated +
                    ' 条配置。全部 ' + share.total + ' 条请使用「导出 JSON」。';
                warn.classList.remove('hidden');
            }
            Admin.dialog.open('qrModal');
        }

        /* ---------------- 导入 ---------------- */
        function showImportResult(message, failures, isError) {
            var host = byId('importResult');
            host.textContent = '';
            host.className = 'alert ' + (isError ? 'alert--warn' : 'alert--info');
            host.appendChild(Admin.svgNode(isError ? 'warn' : 'check'));
            var body = Admin.el('div', { class: 'stack' }, [
                Admin.el('div', { class: 'text-sm bold', text: message })
            ]);
            var list = Array.isArray(failures) ? failures : [];
            if (list.length) {
                var box = Admin.el('div', { class: 'stack' });
                box.appendChild(Admin.el('div', { class: 'text-xs muted', text: '明细（共 ' + list.length + ' 条）：' }));
                list.slice(0, 50).forEach(function (item) {
                    box.appendChild(Admin.el('div', { class: 'text-xs mono', text: String(item) }));
                });
                if (list.length > 50) {
                    box.appendChild(Admin.el('div', { class: 'text-xs muted', text: '…仅显示前 50 条' }));
                }
                body.appendChild(box);
            }
            host.appendChild(body);
            host.classList.remove('hidden');
        }

        function doImport() {
            var raw = byId('importText').value;
            if (!raw.trim()) {
                showImportResult('请先粘贴或选择要导入的配置内容', null, true);
                Admin.toastWarn('请先粘贴或选择要导入的配置内容');
                return;
            }
            var button = byId('importSubmit');
            button.disabled = true;
            button.textContent = '导入中…';

            Admin.post('/admin/config/import', { payload: raw }).then(function (res) {
                var payload = res.data;
                var message = Admin.msgOf(payload, '导入失败（HTTP ' + res.status + '）');
                var failures = payload && Array.isArray(payload.failures) ? payload.failures : [];
                var ok = res.ok && Admin.isOk(payload);
                showImportResult(message, failures, !ok);
                if (ok) {
                    Admin.toastOk(message);
                    byId('importText').value = '';
                    refreshList();
                } else {
                    Admin.toastErr(message);
                }
            }).catch(function (err) {
                showImportResult(err.message || '导入失败', null, true);
                Admin.toastErr(err.message || '导入失败');
            }).then(function () {
                button.disabled = false;
                button.textContent = '开始导入';
            });
        }

        /* ---------------- 批量删除与自动刷新 ---------------- */
        var selection = null;

        function wireBatch() {
            selection = Admin.selection({
                table: 'configTable',
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
                    url: '/admin/config/batchRemove',
                    ids: ids,
                    title: '批量删除自动穿透配置',
                    message: '确定删除选中的 ' + ids.length + ' 条配置吗？删除后对应客户端将无法自动创建隧道。',
                    okText: '全部删除',
                    onDone: function () {
                        if (selection) { selection.clear(); }
                        refreshList();
                    }
                });
            });
        }

        function refreshList() {
            return Admin.refreshRegions(currentUrl(), {
                '#configRows': true,
                '#box': true
            }).then(function () {
                Admin.bindSortableTables();
                Admin.initPagers();
                Admin.bindTableFilters();
                if (selection) { selection.sync(); }
            }).catch(function (err) {
                Admin.toastWarn(err.message || '列表刷新失败');
            });
        }

        /* ---------------- 装配 ---------------- */
        function init() {
            wireBatch();

            Admin.autoRefresh({
                toggle: 'autoRefresh',
                interval: 30000,
                storageKey: 'px_admin_config_autorefresh',
                countdown: 'refreshHint',
                onTick: refreshList
            });

            byId('reloadBtn').addEventListener('click', function () {
                refreshList();
                Admin.toast('已刷新配置列表', { type: 'info', timeout: 1600 });
            });
            byId('exportCsv').addEventListener('click', function () { exportData('csv'); });
            byId('exportJson').addEventListener('click', function () { exportData('json'); });
            byId('importOpen').addEventListener('click', function () {
                byId('importResult').classList.add('hidden');
                Admin.dialog.open('importModal');
            });
            byId('importSubmit').addEventListener('click', doImport);
            byId('importFile').addEventListener('change', function (ev) {
                var file = ev.target.files && ev.target.files[0];
                if (!file) { return; }
                var reader = new FileReader();
                reader.onload = function () {
                    byId('importText').value = String(reader.result || '');
                    Admin.toast('已读取文件 ' + file.name, { type: 'info', timeout: 2000 });
                };
                reader.onerror = function () { Admin.toastErr('文件读取失败，请直接粘贴 JSON 内容'); };
                reader.readAsText(file);
                ev.target.value = '';
            });

            byId('qrOpen').addEventListener('click', openQr);
            byId('qrCopy').addEventListener('click', function () {
                if (!qrPayload) { Admin.toastWarn('当前没有可复制的内容'); return; }
                Admin.copy(qrPayload);
            });
            byId('qrDownload').addEventListener('click', function () {
                var canvas = byId('qrCanvas');
                if (!canvas || !canvas.toDataURL) { return; }
                var link = document.createElement('a');
                link.href = canvas.toDataURL('image/png');
                link.download = 'proxy-config-qr-' + Admin.timestamp() + '.png';
                document.body.appendChild(link);
                link.click();
                link.remove();
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

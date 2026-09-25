<#include "./header.ftl">
<#--  系统日志（日志文件轮转后的在线查看与下载）
      model（SysLogController#index）：dir / files(List<Map>) / activeFile / maxLines
      files 每项：name / size / sizeText / modTime / modTimeText / active

      安全说明：
        · 文件名只用于展示与选择，前端不拼接任何路径；
        · 服务端对 file 参数做「字符白名单 + 后缀 + 目录包含 + 符号链接复核」四层校验，
          杜绝 ../../app.properties 这类任意文件读取；
        · 下载走普通 <a download> 链接（该路由在只读 GET 白名单内，Cookie 会自动携带），
          不需要 fetch + blob，逻辑更少、更不容易出错。  -->
<main class="content">
    <div class="page-head">
        <div>
            <h1 class="page-head__title">系统日志</h1>
            <div class="page-head__desc">
                服务器运行日志的在线查看与下载 · 目录 <span class="mono">${(dir!"")?html}</span>
            </div>
        </div>
        <div class="toolbar">
            <span class="refresh-hint" id="logMeta"></span>
            <button type="button" class="btn btn--subtle" id="reloadBtn">
                <span data-icon="refresh" data-icon-class="icon--sm"></span>
                <span class="btn__label">刷新</span>
            </button>
            <a class="btn btn--ghost" id="downloadLink" href="#" download rel="noopener">
                <span data-icon="download" data-icon-class="icon--sm"></span>
                <span class="btn__label">下载当前文件</span>
            </a>
        </div>
    </div>

    <div class="grid grid--tight">
        <section class="card">
            <div class="card__head">
                <div class="card__title">
                    <span data-icon="inbox" data-icon-class="icon--sm"></span>
                    <span>日志文件</span>
                    <span class="card__hint"><#if files??>${files?size} 个</#if></span>
                </div>
            </div>
            <div class="card__body">
                <ul class="file-list" id="fileList">
                    <#if files?? && (files?size > 0)>
                        <#list files as f>
                            <li class="file-item<#if (f.name!"") == (activeFile!"")> is-active</#if>"
                                data-name="${(f.name!"")?html}" role="button" tabindex="0"
                                title="点击查看 ${(f.name!"")?html}">
                                <span class="file-item__name">
                                    <span data-icon="terminal" data-icon-class="icon--sm"></span>
                                    <span class="truncate">${(f.name!"")?html}</span>
                                </span>
                                <span class="file-item__meta">
                                    ${(f.sizeText!"")?html} · ${(f.modTimeText!"")?html}
                                </span>
                            </li>
                        </#list>
                    <#else>
                        <li class="empty">日志目录不存在或暂时没有 .log 文件</li>
                    </#if>
                </ul>
                <div class="card__foot">
                    归档文件（<span class="mono">proxy-server-YYYY-MM-DD.N.log</span>）由 logback 按天与大小滚动产生；
                    查看只读取文件末尾最多 4MB，不会把整个文件读入内存。
                </div>
            </div>
        </section>

        <section class="card">
            <div class="card__head">
                <div class="card__title">
                    <span data-icon="terminal" data-icon-class="icon--sm"></span>
                    <span>日志内容</span>
                    <span class="card__hint" id="fileHint"></span>
                </div>
            </div>
            <div class="card__body">
                <div class="filter-bar">
                    <select class="select" id="level" aria-label="按日志级别筛选">
                        <option value="all">全部级别</option>
                        <option value="error">ERROR / FATAL</option>
                        <option value="warn">WARN</option>
                        <option value="info">INFO</option>
                        <option value="debug">DEBUG / TRACE</option>
                    </select>
                    <input class="input" id="keyword" type="search"
                           placeholder="关键字过滤" aria-label="按关键字过滤日志"/>
                    <select class="select" id="lines" aria-label="显示行数">
                        <option value="200">200 行</option>
                        <option value="500" selected>500 行</option>
                        <option value="1000">1000 行</option>
                        <option value="2000">2000 行</option>
                    </select>
                    <span class="muted text-xs">单次上限 ${maxLines?c} 行</span>
                    <span class="filter-bar__divider" aria-hidden="true"></span>
                    <button type="button" class="btn btn--subtle" id="queryBtn">
                        <span data-icon="search" data-icon-class="icon--sm"></span>
                        <span>查询</span>
                    </button>
                </div>
                <#--  日志行由 Admin.logLines 渲染：按级别着色，全部以文本节点写入  -->
                <div class="log-viewer" id="logView" role="log" aria-label="日志内容"></div>
            </div>
        </section>
    </div>
</main>
<script>
    Admin.setTitle('系统日志');

    (function () {
        'use strict';

        /* 服务端下发的行数上限，前端只做展示与传参，真正的收敛在服务端 */
        var MAX_LINES = ${maxLines?c};
        /* 当前选中的文件名；服务端已给出默认值（正在写入的日志） */
        var currentFile = '${(activeFile!"")?js_string}';

        function byId(id) { return document.getElementById(id); }

        /** 用 Admin.el 重建文件列表（字符串一律以文本节点写入）。 */
        function renderFiles(files) {
            var host = byId('fileList');
            host.textContent = '';
            if (!files.length) {
                host.appendChild(Admin.el('li', { class: 'empty', text: '日志目录不存在或暂时没有 .log 文件' }));
                return;
            }
            files.forEach(function (file) {
                var item = Admin.el('li', {
                    class: 'file-item' + (file.name === currentFile ? ' is-active' : ''),
                    dataset: { name: String(file.name || '') },
                    role: 'button',
                    tabindex: '0',
                    title: '点击查看 ' + String(file.name || '')
                }, [
                    Admin.el('span', { class: 'file-item__name' }, [
                        Admin.svgNode('terminal', 'icon--sm'),
                        Admin.el('span', { class: 'truncate', text: String(file.name || '') })
                    ]),
                    Admin.el('span', {
                        class: 'file-item__meta',
                        text: Admin.fmtBytes(file.size) + ' · ' + Admin.fmtDateTime(file.modTime)
                    })
                ]);
                host.appendChild(item);
            });
        }

        function markActive() {
            var nodes = document.querySelectorAll('#fileList .file-item');
            Array.prototype.forEach.call(nodes, function (node) {
                node.classList.toggle('is-active', node.getAttribute('data-name') === currentFile);
            });
        }

        function updateDownloadLink() {
            var link = byId('downloadLink');
            if (!currentFile) {
                link.setAttribute('href', '#');
                link.setAttribute('aria-disabled', 'true');
                return;
            }
            // 文件名只做 URL 编码，不拼接路径；服务端会再次校验
            link.setAttribute('href', '/admin/syslog/download?file=' + encodeURIComponent(currentFile));
            link.removeAttribute('aria-disabled');
        }

        /** 拉取文件清单（最近修改在前），并修正当前选中项。 */
        function loadFileList() {
            return Admin.get('/admin/syslog/list').then(function (res) {
                var data = res.data || {};
                var files = Array.isArray(data.files) ? data.files : [];
                var exists = files.some(function (file) { return file.name === currentFile; });
                if (!exists) {
                    // 当前文件已被轮转清理：退回列表中的第一个（最近修改的）
                    currentFile = files.length ? String(files[0].name) : '';
                }
                renderFiles(files);
                updateDownloadLink();
                return files;
            }).catch(function (err) {
                Admin.toastWarn(err.message || '日志文件列表加载失败');
                return [];
            });
        }

        /** 读取当前文件的日志尾部并渲染。 */
        function loadLines() {
            var view = byId('logView');
            if (!currentFile) {
                view.textContent = '';
                view.appendChild(Admin.el('div', { class: 'empty', text: '请选择左侧的日志文件' }));
                byId('fileHint').textContent = '';
                byId('logMeta').textContent = '';
                return Promise.resolve();
            }
            var params = new URLSearchParams();
            params.set('file', currentFile);
            params.set('lines', byId('lines').value);
            params.set('level', byId('level').value);
            var keyword = byId('keyword').value.trim();
            if (keyword) { params.set('keyword', keyword); }

            byId('fileHint').textContent = '加载中…';
            return Admin.get('/admin/syslog/view?' + params.toString()).then(function (res) {
                var data = res.data || {};
                if (!res.ok || !Admin.isOk(data)) {
                    var message = Admin.msgOf(data, '读取日志失败');
                    view.textContent = '';
                    view.appendChild(Admin.el('div', { class: 'empty', text: message }));
                    byId('fileHint').textContent = '读取失败';
                    Admin.toastErr(message);
                    return;
                }
                var lines = Array.isArray(data.lines) ? data.lines : [];
                var text = lines.map(function (line) { return String(line.text || ''); }).join('\n');
                var startLine = lines.length ? (Number(lines[0].n) || 1) : 1;
                // Admin.logLines 会按 ERROR/WARN/DEBUG 着色，并以文本节点写入
                var shown = Admin.logLines(view, text, {
                    keyword: keyword,
                    limit: MAX_LINES,
                    startLine: startLine
                });
                byId('fileHint').textContent = String(data.file || currentFile) + ' · ' + Admin.fmtBytes(data.size);
                byId('logMeta').textContent = '显示 ' + shown + ' / 匹配 ' + (Number(data.matched) || 0) +
                    ' 行 · 读取窗口 ' + (Number(data.window) || 0) + ' 行' +
                    (data.truncated ? ' · 已截断（仅末尾窗口）' : '');
            }).catch(function (err) {
                byId('fileHint').textContent = '读取失败';
                Admin.toastErr(err.message || '读取日志失败');
            });
        }

        function selectFile(name) {
            if (!name || name === currentFile) { return; }
            currentFile = String(name);
            markActive();
            updateDownloadLink();
            loadLines();
        }

        /**
         * 顶栏失败徽标：单独请求 /admin/audit/stats（days=1, recent=1），
         * 让「最近 24 小时失败数」在系统日志页也能一眼看到（与仪表盘/审计页口径一致）。
         */
        function loadBadge() {
            return Admin.get('/admin/audit/stats', { days: 1, recent: 1 }).then(function (res) {
                var total = (res.data && res.data.total) || {};
                var count = Number(total.recentFail) || 0;
                Admin.renderAlertBadge(count, '/admin/audit?result=fail', '近 24 小时有 ' + count + ' 条失败事件');
            }).catch(function () {
                // 徽标失败不影响日志查看
            });
        }

        function init() {
            var list = byId('fileList');
            // 事件委托：文件列表可能在刷新后被整体重建
            list.addEventListener('click', function (ev) {
                var item = ev.target.closest && ev.target.closest('.file-item');
                if (!item) { return; }
                selectFile(item.getAttribute('data-name'));
            });
            list.addEventListener('keydown', function (ev) {
                if (ev.key !== 'Enter' && ev.key !== ' ') { return; }
                var item = ev.target.closest && ev.target.closest('.file-item');
                if (!item) { return; }
                ev.preventDefault();
                selectFile(item.getAttribute('data-name'));
            });

            byId('level').addEventListener('change', function () { loadLines(); });
            byId('lines').addEventListener('change', function () { loadLines(); });
            byId('queryBtn').addEventListener('click', function () { loadLines(); });
            byId('keyword').addEventListener('keydown', function (ev) {
                if (ev.key === 'Enter') {
                    ev.preventDefault();
                    loadLines();
                }
            });
            byId('reloadBtn').addEventListener('click', function () {
                loadFileList().then(function () {
                    loadLines();
                    Admin.toast('已刷新日志文件列表与内容', { type: 'info', timeout: 1600 });
                });
            });

            markActive();
            updateDownloadLink();
            loadLines();
            loadBadge();
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

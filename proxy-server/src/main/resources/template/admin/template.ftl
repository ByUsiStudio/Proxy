<#include "./header.ftl">
<#--  隧道模板
      model（TemplateController#page）：page / pageSize / totalRow / totalPage /
                                        list(List<TemplateEntity>) / keyword / itemCounts(Map<id,条目数>)
      TemplateEntity：id / name / description / items(JSON 数组) / createdBy / applyCount /
                      createTime / updateTime

      安全提示：
      · 模板条目与导出内容都<b>不含口令</b>（下发时 password 留空，客户端 bootstrap 时用当前凭据补齐）；
      · 模板预览用 Admin.el 构建 DOM（绝不使用 innerHTML），条目文本一律当纯文本插入；
      · 导出 JSON 里的 items 是真实 JSON 数组，可直接被「导入模板」回读。  -->
<main class="content">
    <div class="page-head">
        <div>
            <h1 class="page-head__title">隧道模板</h1>
            <div class="page-head__desc">
                把一组隧道条目保存成模板，对多个账号一键下发（同时受单账号隧道上限与用量配额约束）。
                模板不含口令。
            </div>
        </div>
        <div class="toolbar">
            <button type="button" class="btn btn--ghost" id="templateImportOpen">
                <span data-icon="upload" data-icon-class="icon--sm"></span>
                <span class="btn__label">导入模板</span>
            </button>
            <button type="button" class="btn btn--primary" id="templateCreate">
                <span data-icon="plus" data-icon-class="icon--sm"></span>
                <span>新增模板</span>
            </button>
        </div>
    </div>

    <section class="card">
        <div class="card__body">
            <form class="filter-bar" method="get" action="/admin/template">
                <input class="input" id="keyword" name="keyword" type="text"
                       value="${(keyword!"")?html}" placeholder="模板名称 / 描述关键字" aria-label="按关键字筛选"/>
                <button class="btn btn--subtle" type="submit" id="selectButton">
                    <span data-icon="search" data-icon-class="icon--sm"></span>
                    <span>查询</span>
                </button>
                <a class="btn btn--ghost" href="/admin/template">
                    <span data-icon="close" data-icon-class="icon--sm"></span>
                    <span>重置</span>
                </a>
                <span class="filter-bar__divider" aria-hidden="true"></span>
                <button type="button" class="btn btn--ghost" id="exportJson">
                    <span data-icon="download" data-icon-class="icon--sm"></span>
                    <span>导出 JSON</span>
                </button>
                <button type="button" class="btn btn--ghost" id="exportCsv">
                    <span data-icon="download" data-icon-class="icon--sm"></span>
                    <span>导出 CSV</span>
                </button>
            </form>
        </div>
    </section>

    <section class="card">
        <div class="card__head">
            <div class="card__title">
                <span data-icon="box" data-icon-class="icon--sm"></span>
                <span>模板列表</span>
                <span class="card__hint">共 ${totalRow?c} 个</span>
            </div>
            <div class="toolbar">
                <input class="input" type="search" data-table-filter="templateTable"
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
                已选中 <span class="bulk-bar__count" id="bulkCount">0</span> 个模板
            </div>
            <div class="table-wrap">
                <table class="table" id="templateTable" data-sortable="true">
                    <thead>
                    <tr>
                        <th class="col-check" data-sort-ignore>
                            <input type="checkbox" id="selectAll" aria-label="全选当前页"/>
                        </th>
                        <th>名称</th>
                        <th>描述</th>
                        <th data-sort-type="number">条目数</th>
                        <th data-sort-type="number">下发次数</th>
                        <th>创建人</th>
                        <th>更新时间</th>
                        <th data-sort-ignore>操作</th>
                    </tr>
                    </thead>
                    <tbody id="templateRows">
                    <#if list??>
                        <#list list as template>
                            <tr data-key="${(template.id!"")?html}">
                                <td class="col-check">
                                    <input type="checkbox" data-row-key="${(template.id!"")?html}"
                                           aria-label="选择模板 ${(template.name!"")?html}"/>
                                </td>
                                <td>${(template.name!"")?html}</td>
                                <td>${(template.description!"")?html}</td>
                                <td>${(itemCounts[template.id]!0)?c}</td>
                                <td>${(template.applyCount!0)?c}</td>
                                <td>${(template.createdBy!"")?html}</td>
                                <td>${(template.updateTime!"")?html}</td>
                                <td>
                                    <div class="cell-actions">
                                        <button type="button" class="btn btn--ghost btn--sm" data-template-preview
                                                data-name="${(template.name!"")?html}"
                                                data-items="${(template.items!"[]")?html}">
                                            <span data-icon="eye" data-icon-class="icon--sm"></span>
                                            <span>预览</span>
                                        </button>
                                        <button type="button" class="btn btn--subtle btn--sm" data-template-edit
                                                data-id="${(template.id!"")?html}"
                                                data-name="${(template.name!"")?html}"
                                                data-description="${(template.description!"")?html}"
                                                data-items="${(template.items!"[]")?html}">
                                            <span data-icon="gear" data-icon-class="icon--sm"></span>
                                            <span>编辑</span>
                                        </button>
                                        <button type="button" class="btn btn--primary btn--sm" data-template-apply
                                                data-id="${(template.id!"")?html}"
                                                data-name="${(template.name!"")?html}"
                                                data-count="${(itemCounts[template.id]!0)?c}">
                                            <span data-icon="rocket" data-icon-class="icon--sm"></span>
                                            <span>一键下发</span>
                                        </button>
                                        <button type="button" class="btn btn--danger btn--sm" data-template-remove
                                                data-id="${(template.id!"")?html}"
                                                data-name="${(template.name!"")?html}">
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
             data-pager-url="/admin/template"
             data-pager-page="${page?c}"
             data-pager-total="${totalPage?c}"
             data-pager-params="keyword"></div>
    </section>
</main>

<#--  新增 / 编辑模板  -->
<div class="modal" id="templateModal" role="dialog" aria-modal="true" aria-hidden="true"
     aria-labelledby="templateModalTitle">
    <div class="modal__panel modal__panel--wide">
        <div class="modal__head">
            <h3 class="modal__title" id="templateModalTitle">新增隧道模板</h3>
            <button type="button" class="btn btn--ghost btn--icon" data-dialog-close aria-label="关闭">
                <span data-icon="close" data-icon-class="icon--sm"></span>
            </button>
        </div>
        <div class="modal__body">
            <div class="alert alert--info">
                <span data-icon="info"></span>
                <div>
                    条目为 JSON 数组，每个元素字段：<code>type</code>（TCP/UDP）、
                    <code>userHost</code>（内网 host:port）、<code>serverHost</code>（穿透服务 host:port）、
                    <code>domain</code>（非 UDP 必填）、<code>port</code>（可留空由服务端分配）。
                    单个模板最多 50 条，<strong>不含口令</strong>。
                </div>
            </div>
            <input type="hidden" id="templateId"/>
            <div class="field">
                <label class="field__label" for="templateName">模板名称</label>
                <input class="input" id="templateName" type="text" maxlength="64"
                       placeholder="例如：Web 服务三件套" data-autofocus/>
            </div>
            <div class="field">
                <label class="field__label" for="templateDescription">模板说明</label>
                <input class="input" id="templateDescription" type="text" maxlength="200"
                       placeholder="可选，最多 200 字"/>
            </div>
            <div class="field">
                <label class="field__label" for="templateItems">
                    条目（JSON 数组）
                    <span class="field__hint">保存时逐条校验，任何一条不合法将整体拒绝</span>
                </label>
                <textarea class="code-block" id="templateItems" spellcheck="false"
                          placeholder='[{"type":"TCP","userHost":"127.0.0.1:8080","serverHost":"1.2.3.4:9090","domain":"demo","port":"8080"}]'></textarea>
            </div>
            <div class="field">
                <label class="field__label" for="rowsHelper">
                    按行填写助手
                    <span class="field__hint">每行一条：类型,内网服务,外网服务,域名,端口</span>
                </label>
                <textarea class="code-block" id="rowsHelper" spellcheck="false"
                          placeholder="TCP,127.0.0.1:8080,1.2.3.4:9090,demo,8080"></textarea>
                <div class="field-row mt-2">
                    <button type="button" class="btn btn--ghost btn--sm" id="rowsToJson">
                        <span data-icon="arrowDown" data-icon-class="icon--sm"></span>
                        <span>生成 JSON（覆盖上方条目）</span>
                    </button>
                    <button type="button" class="btn btn--ghost btn--sm" id="jsonToRows">
                        <span data-icon="arrowUp" data-icon-class="icon--sm"></span>
                        <span>从 JSON 反填行</span>
                    </button>
                </div>
            </div>
            <div class="alert hidden" id="templateSaveResult"></div>
        </div>
        <div class="modal__foot">
            <button type="button" class="btn btn--ghost" data-dialog-close>取消</button>
            <button type="button" class="btn btn--primary" id="templateSave">保存模板</button>
        </div>
    </div>
</div>

<#--  导入模板  -->
<div class="modal" id="importModal" role="dialog" aria-modal="true" aria-hidden="true"
     aria-labelledby="importTitle">
    <div class="modal__panel modal__panel--wide">
        <div class="modal__head">
            <h3 class="modal__title" id="importTitle">导入隧道模板</h3>
            <button type="button" class="btn btn--ghost btn--icon" data-dialog-close aria-label="关闭">
                <span data-icon="close" data-icon-class="icon--sm"></span>
            </button>
        </div>
        <div class="modal__body">
            <div class="alert alert--info">
                <span data-icon="info"></span>
                <div>
                    支持与「导出 JSON」一致的格式：<code>{"templates":[{"name":"...","description":"...","items":[...]}]}</code>
                    或直接粘贴数组。单次最多 200 个模板。
                    <strong>导入内容不包含也不会写入隧道口令。</strong>
                </div>
            </div>
            <div class="field">
                <label class="field__label" for="importFile">从文件读取（可选）</label>
                <input class="input" id="importFile" type="file" accept=".json,application/json"/>
            </div>
            <div class="field">
                <label class="field__label" for="importText">模板内容（JSON）</label>
                <textarea class="code-block" id="importText" spellcheck="false"
                          placeholder='{"templates":[{"name":"Web","description":"示例","items":[{"type":"TCP","userHost":"127.0.0.1:8080","serverHost":"1.2.3.4:9090","domain":"demo","port":"8080"}]}]}'></textarea>
            </div>
            <div class="alert hidden" id="importResult"></div>
        </div>
        <div class="modal__foot">
            <button type="button" class="btn btn--ghost" data-dialog-close>取消</button>
            <button type="button" class="btn btn--primary" id="importSubmit">开始导入</button>
        </div>
    </div>
</div>

<#--  一键下发  -->
<div class="modal" id="applyModal" role="dialog" aria-modal="true" aria-hidden="true"
     aria-labelledby="applyTitle">
    <div class="modal__panel modal__panel--wide">
        <div class="modal__head">
            <h3 class="modal__title" id="applyTitle">一键下发模板</h3>
            <button type="button" class="btn btn--ghost btn--icon" data-dialog-close aria-label="关闭">
                <span data-icon="close" data-icon-class="icon--sm"></span>
            </button>
        </div>
        <div class="modal__body">
            <div class="alert alert--warn">
                <span data-icon="warn"></span>
                <div>
                    下发会为每个账号创建自动穿透配置，<strong>同时受单账号隧道上限与用量配额约束</strong>；
                    被拒绝的账号会在下方逐条列出原因。模板条目本身不含口令。
                </div>
            </div>
            <div class="field">
                <label class="field__label" for="applyTemplateName">模板</label>
                <input class="input" id="applyTemplateName" type="text" readonly/>
                <input type="hidden" id="applyTemplateId"/>
            </div>
            <div class="field">
                <label class="field__label" for="applyDeviceId">
                    设备 ID
                    <span class="field__hint">可选；留空使用占位值 template（不会绑定到具体设备）</span>
                </label>
                <input class="input" id="applyDeviceId" type="text" maxlength="64"
                       placeholder="例如手机 IMEI（6-64 位字母数字）"/>
            </div>
            <div class="field">
                <label class="field__label" for="applyUsernames">
                    目标账号
                    <span class="field__hint">逗号或换行分隔，单次最多 200 个</span>
                </label>
                <textarea class="code-block" id="applyUsernames" spellcheck="false"
                          placeholder="a@qq.com, b@qq.com"></textarea>
            </div>
            <div class="alert hidden" id="applyResult"></div>
        </div>
        <div class="modal__foot">
            <button type="button" class="btn btn--ghost" data-dialog-close>取消</button>
            <button type="button" class="btn btn--primary" id="applySubmit">开始下发</button>
        </div>
    </div>
</div>

<#--  模板条目预览（内容由 Admin.el 动态构建，不使用 innerHTML）  -->
<div class="modal" id="previewModal" role="dialog" aria-modal="true" aria-hidden="true"
     aria-labelledby="previewTitle">
    <div class="modal__panel modal__panel--wide">
        <div class="modal__head">
            <h3 class="modal__title" id="previewTitle">模板条目预览</h3>
            <button type="button" class="btn btn--ghost btn--icon" data-dialog-close aria-label="关闭">
                <span data-icon="close" data-icon-class="icon--sm"></span>
            </button>
        </div>
        <div class="modal__body">
            <div class="card__hint" id="previewHint"></div>
            <div class="stack mt-2" id="previewBody"></div>
        </div>
        <div class="modal__foot">
            <button type="button" class="btn btn--ghost" data-dialog-close>关闭</button>
        </div>
    </div>
</div>

<script>
    Admin.setTitle('隧道模板');

    (function () {
        'use strict';

        function byId(id) { return document.getElementById(id); }

        function filterQuery(extraPage) {
            var params = new URLSearchParams();
            var k = byId('keyword');
            if (k && k.value.trim()) { params.set('keyword', k.value.trim()); }
            if (extraPage) { params.set('page', extraPage); }
            var s = params.toString();
            return s ? '?' + s : '';
        }

        function currentUrl(page) { return '/admin/template' + filterQuery(page); }

        function download(url) {
            var link = document.createElement('a');
            link.href = url;
            link.rel = 'noopener';
            document.body.appendChild(link);
            link.click();
            link.remove();
        }

        /* 统一的结果展示：只以文本节点写入，失败明细逐条列出（最多 50 条） */
        function showResult(hostId, message, failures, isError) {
            var host = byId(hostId);
            if (!host) { return; }
            host.textContent = '';
            host.className = 'alert ' + (isError ? 'alert--warn' : 'alert--info');
            var body = Admin.el('div', { class: 'stack' }, [
                Admin.el('div', { class: 'text-sm bold', text: message })
            ]);
            var list = Array.isArray(failures) ? failures : [];
            if (list.length) {
                var box = Admin.el('div', { class: 'stack' });
                box.appendChild(Admin.el('div', {
                    class: 'text-xs muted', text: '明细（共 ' + list.length + ' 条）：'
                }));
                list.slice(0, 50).forEach(function (item) {
                    box.appendChild(Admin.el('div', { class: 'text-xs mono', text: String(item) }));
                });
                if (list.length > 50) {
                    box.appendChild(Admin.el('div', { class: 'text-xs muted', text: '…仅显示前 50 条' }));
                }
                body.appendChild(box);
            }
            host.appendChild(Admin.svgNode(isError ? 'warn' : 'check'));
            host.appendChild(body);
            host.classList.remove('hidden');
        }

        /* ---------------- 条目编辑助手 ---------------- */
        function rowsToJson() {
            var lines = String(byId('rowsHelper').value || '').split(/\r?\n/);
            var items = [];
            for (var i = 0; i < lines.length; i++) {
                var line = lines[i].trim();
                if (!line) { continue; }
                var parts = line.split(',');
                items.push({
                    type: String(parts[0] || '').trim(),
                    userHost: String(parts[1] || '').trim(),
                    serverHost: String(parts[2] || '').trim(),
                    domain: String(parts[3] || '').trim(),
                    port: String(parts[4] || '').trim()
                });
            }
            if (!items.length) {
                Admin.toastWarn('请先在「按行填写助手」里输入至少一行');
                return;
            }
            byId('templateItems').value = JSON.stringify(items, null, 2);
            Admin.toastOk('已生成 ' + items.length + ' 条 JSON 条目');
        }

        function jsonToRows() {
            var raw = String(byId('templateItems').value || '').trim();
            if (!raw) {
                Admin.toastWarn('请先填写条目 JSON');
                return;
            }
            var parsed;
            try {
                parsed = JSON.parse(raw);
            } catch (e) {
                Admin.toastErr('条目不是合法的 JSON：' + e.message);
                return;
            }
            if (!Array.isArray(parsed)) {
                Admin.toastErr('条目必须是 JSON 数组');
                return;
            }
            var lines = parsed.map(function (item) {
                return [item.type, item.userHost, item.serverHost, item.domain, item.port]
                    .map(function (v) { return v === undefined || v === null ? '' : String(v); })
                    .join(',');
            });
            byId('rowsHelper').value = lines.join('\n');
            Admin.toastOk('已反填 ' + lines.length + ' 行');
        }

        /* ---------------- 新增 / 编辑 ---------------- */
        function openCreate() {
            byId('templateModalTitle').textContent = '新增隧道模板';
            byId('templateId').value = '';
            byId('templateName').value = '';
            byId('templateDescription').value = '';
            byId('templateItems').value = '';
            byId('rowsHelper').value = '';
            byId('templateSaveResult').classList.add('hidden');
            Admin.dialog.open('templateModal');
        }

        function openEdit(btn) {
            byId('templateModalTitle').textContent = '编辑隧道模板';
            byId('templateId').value = btn.getAttribute('data-id') || '';
            byId('templateName').value = btn.getAttribute('data-name') || '';
            byId('templateDescription').value = btn.getAttribute('data-description') || '';
            byId('templateItems').value = btn.getAttribute('data-items') || '';
            byId('rowsHelper').value = '';
            byId('templateSaveResult').classList.add('hidden');
            Admin.dialog.open('templateModal');
        }

        function saveTemplate() {
            var name = byId('templateName').value.trim();
            var items = byId('templateItems').value.trim();
            if (!name) {
                Admin.toastWarn('请填写模板名称');
                return;
            }
            if (!items) {
                Admin.toastWarn('请填写条目 JSON（可用「按行填写助手」生成）');
                return;
            }
            var button = byId('templateSave');
            button.disabled = true;
            var original = button.textContent;
            button.textContent = '保存中…';
            Admin.post('/admin/template/save', {
                id: byId('templateId').value,
                name: name,
                description: byId('templateDescription').value,
                items: items
            }).then(function (res) {
                var payload = res.data;
                var message = Admin.msgOf(payload, '保存失败（HTTP ' + res.status + '）');
                var failures = payload && Array.isArray(payload.failures) ? payload.failures : [];
                var ok = res.ok && Admin.isOk(payload);
                showResult('templateSaveResult', message, failures, !ok);
                if (ok) {
                    Admin.toastOk(message);
                    Admin.dialog.close(byId('templateModal'));
                    refreshList();
                } else {
                    Admin.toastErr(message);
                }
            }).catch(function (err) {
                showResult('templateSaveResult', err.message || '保存失败', null, true);
                Admin.toastErr(err.message || '保存失败');
            }).then(function () {
                button.disabled = false;
                button.textContent = original;
            });
        }

        /* ---------------- 预览 ---------------- */
        function openPreview(btn) {
            var name = btn.getAttribute('data-name') || '';
            var body = byId('previewBody');
            body.textContent = '';
            var items;
            try {
                items = JSON.parse(btn.getAttribute('data-items') || '[]');
            } catch (e) {
                items = null;
            }
            if (!Array.isArray(items) || !items.length) {
                byId('previewHint').textContent = '模板「' + name + '」没有可预览的条目';
                body.appendChild(Admin.el('div', { class: 'empty', text: '条目为空或不是合法 JSON' }));
                Admin.dialog.open('previewModal');
                return;
            }
            byId('previewHint').textContent = '模板「' + name + '」共 ' + items.length + ' 条条目（不含口令）';
            var table = Admin.el('table', { class: 'table' });
            var head = Admin.el('thead', null, [
                Admin.el('tr', null, [
                    Admin.el('th', { text: '#' }),
                    Admin.el('th', { text: '类型' }),
                    Admin.el('th', { text: '内网服务' }),
                    Admin.el('th', { text: '外网服务' }),
                    Admin.el('th', { text: '域名' }),
                    Admin.el('th', { text: '端口' })
                ])
            ]);
            var tbody = Admin.el('tbody');
            items.forEach(function (item, index) {
                tbody.appendChild(Admin.el('tr', null, [
                    Admin.el('td', { text: String(index + 1) }),
                    Admin.el('td', { text: item && item.type ? String(item.type) : '—' }),
                    Admin.el('td', { class: 'mono', text: item && item.userHost ? String(item.userHost) : '—' }),
                    Admin.el('td', { class: 'mono', text: item && item.serverHost ? String(item.serverHost) : '—' }),
                    Admin.el('td', { text: item && item.domain ? String(item.domain) : '—' }),
                    Admin.el('td', { text: item && item.port ? String(item.port) : '随机' })
                ]));
            });
            table.appendChild(head);
            table.appendChild(tbody);
            var wrap = Admin.el('div', { class: 'table-wrap' });
            wrap.appendChild(table);
            body.appendChild(wrap);
            Admin.dialog.open('previewModal');
        }

        /* ---------------- 导入 ---------------- */
        function doImport() {
            var raw = byId('importText').value;
            if (!raw.trim()) {
                showResult('importResult', '请先粘贴或选择要导入的模板内容', null, true);
                Admin.toastWarn('请先粘贴或选择要导入的模板内容');
                return;
            }
            var button = byId('importSubmit');
            button.disabled = true;
            var original = button.textContent;
            button.textContent = '导入中…';
            Admin.post('/admin/template/import', { payload: raw }).then(function (res) {
                var payload = res.data;
                var message = Admin.msgOf(payload, '导入失败（HTTP ' + res.status + '）');
                var failures = payload && Array.isArray(payload.failures) ? payload.failures : [];
                var ok = res.ok && Admin.isOk(payload);
                showResult('importResult', message, failures, !ok);
                if (ok) {
                    Admin.toastOk(message);
                    byId('importText').value = '';
                    refreshList();
                } else {
                    Admin.toastErr(message);
                }
            }).catch(function (err) {
                showResult('importResult', err.message || '导入失败', null, true);
                Admin.toastErr(err.message || '导入失败');
            }).then(function () {
                button.disabled = false;
                button.textContent = original;
            });
        }

        /* ---------------- 一键下发 ---------------- */
        function openApply(btn) {
            byId('applyTemplateId').value = btn.getAttribute('data-id') || '';
            byId('applyTemplateName').value = (btn.getAttribute('data-name') || '')
                + '（' + (btn.getAttribute('data-count') || '0') + ' 条条目）';
            byId('applyUsernames').value = '';
            byId('applyDeviceId').value = '';
            byId('applyResult').classList.add('hidden');
            Admin.dialog.open('applyModal');
        }

        function doApply() {
            var id = byId('applyTemplateId').value;
            var usernames = String(byId('applyUsernames').value || '').replace(/\s*\n\s*/g, ',').trim();
            if (!id) {
                Admin.toastWarn('模板不存在，请刷新页面');
                return;
            }
            if (!usernames) {
                showResult('applyResult', '请填写要下发的账号', null, true);
                Admin.toastWarn('请填写要下发的账号');
                return;
            }
            var button = byId('applySubmit');
            button.disabled = true;
            var original = button.textContent;
            button.textContent = '下发中…';
            Admin.post('/admin/template/apply', {
                id: id,
                usernames: usernames,
                deviceId: byId('applyDeviceId').value
            }).then(function (res) {
                var payload = res.data;
                var message = Admin.msgOf(payload, '下发失败（HTTP ' + res.status + '）');
                var failures = payload && Array.isArray(payload.failures) ? payload.failures : [];
                var ok = res.ok && Admin.isOk(payload);
                showResult('applyResult', message, failures, !ok);
                if (ok) {
                    Admin.toastOk(message);
                    refreshList();
                } else {
                    Admin.toastErr(message);
                }
            }).catch(function (err) {
                showResult('applyResult', err.message || '下发失败', null, true);
                Admin.toastErr(err.message || '下发失败');
            }).then(function () {
                button.disabled = false;
                button.textContent = original;
            });
        }

        /* ---------------- 删除 / 批量删除 ---------------- */
        var selection = null;

        function wireBatch() {
            selection = Admin.selection({
                table: 'templateTable',
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
                    url: '/admin/template/batchRemove',
                    ids: ids,
                    title: '批量删除隧道模板',
                    message: '确定删除选中的 ' + ids.length + ' 个模板吗？已下发的配置不受影响。',
                    okText: '全部删除',
                    onDone: function () {
                        if (selection) { selection.clear(); }
                        refreshList();
                    }
                });
            });
        }

        function removeTemplate(btn) {
            var id = btn.getAttribute('data-id') || '';
            var name = btn.getAttribute('data-name') || '';
            Admin.confirm({
                title: '删除隧道模板',
                message: '确定删除模板「' + name + '」吗？已下发的自动穿透配置不受影响。',
                detail: id,
                okText: '确认删除',
                danger: true
            }).then(function (yes) {
                if (!yes) { return; }
                Admin.post('/admin/template/remove', { id: id }).then(function (res) {
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

        /* ---------------- 列表刷新 ---------------- */
        function refreshList() {
            return Admin.refreshRegions(currentUrl(), ['#templateRows', '#box']).then(function () {
                Admin.bindSortableTables();
                Admin.initPagers();
                Admin.bindTableFilters();
                if (selection) { selection.sync(); }
            }).catch(function (err) {
                Admin.toastWarn(err.message || '列表刷新失败');
            });
        }

        function init() {
            wireBatch();

            byId('templateCreate').addEventListener('click', openCreate);
            byId('templateSave').addEventListener('click', saveTemplate);
            byId('rowsToJson').addEventListener('click', rowsToJson);
            byId('jsonToRows').addEventListener('click', jsonToRows);
            byId('applySubmit').addEventListener('click', doApply);
            byId('importSubmit').addEventListener('click', doImport);

            byId('templateImportOpen').addEventListener('click', function () {
                byId('importResult').classList.add('hidden');
                Admin.dialog.open('importModal');
            });
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

            byId('exportJson').addEventListener('click', function () {
                var query = filterQuery();
                download('/admin/template/export' + query + (query ? '&' : '?') + 'format=json');
                Admin.toast('正在导出模板 JSON（可再次导入）', { type: 'info', timeout: 2600 });
            });
            byId('exportCsv').addEventListener('click', function () {
                var query = filterQuery();
                download('/admin/template/export' + query + (query ? '&' : '?') + 'format=csv');
                Admin.toast('正在导出模板 CSV（供查阅，不可回导）', { type: 'info', timeout: 2600 });
            });

            /* 表格内的操作按钮：事件委托 */
            var table = byId('templateTable');
            if (table) {
                table.addEventListener('click', function (ev) {
                    var target = ev.target;
                    var btn;
                    btn = target.closest && target.closest('[data-template-preview]');
                    if (btn) { openPreview(btn); return; }
                    btn = target.closest && target.closest('[data-template-edit]');
                    if (btn) { openEdit(btn); return; }
                    btn = target.closest && target.closest('[data-template-apply]');
                    if (btn) { openApply(btn); return; }
                    btn = target.closest && target.closest('[data-template-remove]');
                    if (btn) { removeTemplate(btn); }
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

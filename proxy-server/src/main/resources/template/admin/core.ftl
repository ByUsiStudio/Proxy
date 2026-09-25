<#include "./header.ftl">
<#--  内核版本
      model（CoreController#index）：page / pageSize / totalRow / totalPage / list(List<CoreEntity>)
      CoreEntity：id / versionCode / updateContent / createTime  -->
<main class="content">
    <div class="page-head">
        <div>
            <h1 class="page-head__title">内核版本</h1>
            <div class="page-head__desc">发布内核版本号与更新说明。</div>
        </div>
        <div class="toolbar">
            <button type="button" class="btn btn--primary" data-dialog-open="add">
                <span data-icon="plus" data-icon-class="icon--sm"></span>
                <span>添加版本</span>
            </button>
        </div>
    </div>

    <section class="card">
        <div class="card__head">
            <div class="card__title">
                <span data-icon="cpu" data-icon-class="icon--sm"></span>
                <span>版本列表</span>
                <span class="card__hint">共 ${totalRow?c} 条</span>
            </div>
            <div class="toolbar">
                <input class="input" type="search" data-table-filter="coreTable"
                       placeholder="本页过滤" aria-label="本页快速过滤"/>
            </div>
        </div>
        <div class="table-wrap card__body--flush">
            <table class="table" id="coreTable" data-sortable="true">
                <thead>
                <tr>
                    <th>id</th>
                    <th>版本号</th>
                    <th>更新内容</th>
                    <th>创建时间</th>
                    <th data-sort-ignore>操作</th>
                </tr>
                </thead>
                <tbody>
                <#if list??>
                    <#list list as core>
                        <tr>
                            <td class="mono">${(core.id!"")?html}</td>
                            <td><span class="badge badge--info">${(core.versionCode!"")?html}</span></td>
                            <td>${(core.updateContent!"")?html}</td>
                            <td>${(core.createTime!"")?html}</td>
                            <td>
                                <div class="cell-actions">
                                    <#--  保持原 href 不变  -->
                                    <a class="btn btn--danger btn--sm"
                                       href="/admin/core/remove?page=${page?c}&id=${(core.id!"")?url}"
                                       data-confirm="确定删除内核版本 ${(core.versionCode!"")?html} 吗？"
                                       data-confirm-title="删除版本">
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
        <div id="box" class="pagger"
             data-pager-url="/admin/core"
             data-pager-page="${page?c}"
             data-pager-total="${totalPage?c}"></div>
    </section>

    <#--  添加弹窗：与原实现相同的 form action / 字段名  -->
    <div class="modal" id="add" role="dialog" aria-modal="true" aria-hidden="true" aria-label="添加版本">
        <form class="modal__panel" method="post" action="/admin/core/add?page=${page?c}">
            <div class="modal__head">
                <h3 class="modal__title">添加版本</h3>
                <button type="button" class="btn btn--ghost btn--icon" data-dialog-close aria-label="关闭">
                    <span data-icon="close" data-icon-class="icon--sm"></span>
                </button>
            </div>
            <div class="modal__body">
                <div class="field">
                    <label class="field__label" for="core-version">版本号</label>
                    <input class="input" id="core-version" name="versionCode" placeholder="版本号" type="text"
                           data-autofocus required/>
                </div>
                <div class="field">
                    <label class="field__label" for="core-content">更新内容</label>
                    <input class="input" id="core-content" name="updateContent" placeholder="更新类容" type="text"/>
                </div>
            </div>
            <div class="modal__foot">
                <button type="button" class="btn btn--ghost" data-dialog-close>取消</button>
                <button type="submit" class="btn btn--primary">确定</button>
            </div>
        </form>
    </div>
</main>
<script>
    Admin.setTitle('内核版本');
</script>
</body>
</html>

<#include "./header.ftl">
<#--  域名管理
      model（DomainController#index）：page / pageSize / totalRow / totalPage / list(List<DomainVo>) / usernameSearch
      DomainVo：id / userId / username / domain / customDomain / createTime  -->
<main class="content">
    <div class="page-head">
        <div>
            <h1 class="page-head__title">域名管理</h1>
            <div class="page-head__desc">为用户分配二级域名与自定义域名。</div>
        </div>
        <div class="toolbar">
            <button type="button" class="btn btn--primary" data-dialog-open="add">
                <span data-icon="plus" data-icon-class="icon--sm"></span>
                <span>添加域名</span>
            </button>
        </div>
    </div>

    <section class="card">
        <div class="card__head">
            <div class="card__title">
                <span data-icon="globe" data-icon-class="icon--sm"></span>
                <span>域名列表</span>
                <span class="card__hint">共 ${totalRow?c} 条</span>
            </div>
            <div class="toolbar">
                <#--  服务端查询：路由与参数名保持不变  -->
                <form class="field-row" method="get" action="/admin/domain">
                    <input class="input" id="usernameSearch" name="usernameSearch" type="text"
                           value="${usernameSearch?html}" placeholder="用户名" aria-label="按用户名查询"/>
                    <button class="btn btn--subtle" type="submit" id="selectButton">
                        <span data-icon="search" data-icon-class="icon--sm"></span>
                        <span>查询</span>
                    </button>
                </form>
                <input class="input" type="search" data-table-filter="domainTable"
                       placeholder="本页过滤" aria-label="本页快速过滤"/>
            </div>
        </div>
        <div class="table-wrap card__body--flush">
            <table class="table" id="domainTable" data-sortable="true">
                <thead>
                <tr>
                    <th>id</th>
                    <th>用户名</th>
                    <th>二级域名</th>
                    <th>自定义域名</th>
                    <th>创建时间</th>
                    <th data-sort-ignore>操作</th>
                </tr>
                </thead>
                <tbody>
                <#if list??>
                    <#list list as app>
                        <tr>
                            <td class="mono">${(app.id!"")?html}</td>
                            <td>${(app.username!"")?html}</td>
                            <td>${(app.domain!"")?html}</td>
                            <td>${(app.customDomain!"")?html}</td>
                            <td>${(app.createTime!"")?html}</td>
                            <td>
                                <div class="cell-actions">
                                    <#--  保持原 href 不变  -->
                                    <a class="btn btn--danger btn--sm"
                                       href="/admin/domain/remove?page=${page?c}&id=${(app.id!"")?url}&usernameSearch=${usernameSearch?url}"
                                       data-confirm="确定删除该域名吗？"
                                       data-confirm-title="删除域名">
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
             data-pager-url="/admin/domain"
             data-pager-page="${page?c}"
             data-pager-total="${totalPage?c}"
             data-pager-param="usernameSearch"
             data-pager-input="usernameSearch"></div>
    </section>

    <#--  添加弹窗：与原实现相同的 form action / 字段名  -->
    <div class="modal" id="add" role="dialog" aria-modal="true" aria-hidden="true" aria-label="添加域名">
        <form class="modal__panel" method="post"
              action="/admin/domain/add?page=${page?c}&usernameSearch=${usernameSearch?url}">
            <div class="modal__head">
                <h3 class="modal__title">添加域名</h3>
                <button type="button" class="btn btn--ghost btn--icon" data-dialog-close aria-label="关闭">
                    <span data-icon="close" data-icon-class="icon--sm"></span>
                </button>
            </div>
            <div class="modal__body">
                <div class="field">
                    <label class="field__label" for="add-domain-username">用户名</label>
                    <input class="input" id="add-domain-username" name="username" placeholder="用户名" type="text"
                           data-autofocus required/>
                </div>
                <div class="field">
                    <label class="field__label" for="add-domain-name">二级域名</label>
                    <input class="input" id="add-domain-name" name="domain" placeholder="二级域名" type="text"/>
                </div>
                <div class="field">
                    <label class="field__label" for="add-domain-custom">自定义域名</label>
                    <input class="input" id="add-domain-custom" name="customDomain" placeholder="自定义域名" type="text"/>
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
    Admin.setTitle('域名管理');
</script>
</body>
</html>

<#include "./header.ftl">
<#--  打赏管理
      model（PayController#index）：page / pageSize / totalRow / totalPage / list(List<PayEntity>) / totalPrice
      PayEntity：id / username / price / createTime
      totalPrice 由 payService.countPrice() 提供，原页面直接输出，这里保持同样输出并放进 KPI 卡片。  -->
<main class="content">
    <div class="page-head">
        <div>
            <h1 class="page-head__title">打赏管理</h1>
            <div class="page-head__desc">记录打赏明细并统计累计收益。</div>
        </div>
        <div class="toolbar">
            <button type="button" class="btn btn--primary" data-dialog-open="add">
                <span data-icon="plus" data-icon-class="icon--sm"></span>
                <span>添加打赏</span>
            </button>
        </div>
    </div>

    <div class="grid grid--tight">
        <div class="kpi">
            <div class="kpi__icon kpi__icon--warn"><span data-icon="coins"></span></div>
            <div class="kpi__body">
                <div class="kpi__value">${totalPrice?html}</div>
                <div class="kpi__label">累计收益</div>
            </div>
        </div>
        <div class="kpi">
            <div class="kpi__icon"><span data-icon="list"></span></div>
            <div class="kpi__body">
                <div class="kpi__value">${totalRow?c}</div>
                <div class="kpi__label">打赏记录数</div>
            </div>
        </div>
    </div>

    <section class="card">
        <div class="card__head">
            <div class="card__title">
                <span data-icon="coins" data-icon-class="icon--sm"></span>
                <span>打赏记录</span>
                <span class="card__hint">共 ${totalRow?c} 条</span>
            </div>
            <div class="toolbar">
                <input class="input" type="search" data-table-filter="payTable"
                       placeholder="本页过滤" aria-label="本页快速过滤"/>
            </div>
        </div>
        <div class="table-wrap card__body--flush">
            <table class="table" id="payTable" data-sortable="true">
                <thead>
                <tr>
                    <th>id</th>
                    <th>打赏人</th>
                    <th data-sort-type="number">打赏金额</th>
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
                            <td><span class="badge badge--warn">${(app.price!"")?html}</span></td>
                            <td>${(app.createTime!"")?html}</td>
                            <td>
                                <div class="cell-actions">
                                    <#--  保持原 href 不变  -->
                                    <a class="btn btn--danger btn--sm"
                                       href="/admin/pay/remove?page=${page?c}&id=${(app.id!"")?url}"
                                       data-confirm="确定删除该条打赏记录吗？"
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
        <div id="box" class="pagger"
             data-pager-url="/admin/pay"
             data-pager-page="${page?c}"
             data-pager-total="${totalPage?c}"></div>
    </section>

    <#--  添加弹窗：与原实现相同的 form action / 字段名  -->
    <div class="modal" id="add" role="dialog" aria-modal="true" aria-hidden="true" aria-label="添加打赏">
        <form class="modal__panel" method="post" action="/admin/pay/add?page=${page?c}">
            <div class="modal__head">
                <h3 class="modal__title">添加打赏</h3>
                <button type="button" class="btn btn--ghost btn--icon" data-dialog-close aria-label="关闭">
                    <span data-icon="close" data-icon-class="icon--sm"></span>
                </button>
            </div>
            <div class="modal__body">
                <div class="field">
                    <label class="field__label" for="pay-username">打赏人</label>
                    <input class="input" id="pay-username" name="username" placeholder="打赏人" type="text"
                           data-autofocus required/>
                </div>
                <div class="field">
                    <label class="field__label" for="pay-price">打赏价格</label>
                    <input class="input" id="pay-price" name="price" placeholder="打赏价格" type="text"/>
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
    Admin.setTitle('打赏管理');
</script>
</body>
</html>

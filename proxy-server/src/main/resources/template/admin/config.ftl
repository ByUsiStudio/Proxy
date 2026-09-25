<#include "./header.ftl">
<#--  自动穿透配置
      model（AutoConfigController#log）：page / pageSize / totalRow / totalPage / list(List<ConfigEntity>)
      ConfigEntity：id / userId / username / deviceId / userHost / serverHost / type / domain / port / createTime  -->
<main class="content">
    <div class="page-head">
        <div>
            <h1 class="page-head__title">自动穿透</h1>
            <div class="page-head__desc">客户端自动生成的穿透配置，可在此核对与清理。</div>
        </div>
    </div>

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
            </div>
        </div>
        <div class="table-wrap card__body--flush">
            <table class="table" id="configTable" data-sortable="true">
                <thead>
                <tr>
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
                <tbody>
                <#if list??>
                    <#list list as statistics>
                        <tr>
                            <td class="mono">${(statistics.id!"")?html}</td>
                            <td>${(statistics.username!"")?html}</td>
                            <td class="mono">${(statistics.userHost!"")?html}</td>
                            <td class="mono">${(statistics.serverHost!"")?html}</td>
                            <td><span class="badge badge--info">${(statistics.type!"")?html}</span></td>
                            <td>${(statistics.domain!"")?html}</td>
                            <td>
                                <#if statistics.port??>
                                    <span class="badge badge--muted">${statistics.port?html}</span>
                                <#else>
                                    <span class="muted text-xs">无端口</span>
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
        <div id="box" class="pagger"
             data-pager-url="/admin/config"
             data-pager-page="${page?c}"
             data-pager-total="${totalPage?c}"></div>
    </section>
</main>
<script>
    Admin.setTitle('自动穿透');
</script>
</body>
</html>

<#include "./header.ftl">
<#--  用户日志（流量统计）
      model（LogController#log）：page / pageSize / totalRow / totalPage / list(List<StatisticsEntity>)
      StatisticsEntity：id / username / port / receive / send / connectNum / packNum / createTime
      receive、send 在实体中是字符串（字节数），原页面直接输出，这里保持同样的数值输出。  -->
<main class="content">
    <div class="page-head">
        <div>
            <h1 class="page-head__title">用户日志</h1>
            <div class="page-head__desc">各端口的流量、连接数与数据包统计。</div>
        </div>
    </div>

    <section class="card">
        <div class="card__head">
            <div class="card__title">
                <span data-icon="list" data-icon-class="icon--sm"></span>
                <span>流量统计</span>
                <span class="card__hint">共 ${totalRow?c} 条</span>
            </div>
            <div class="toolbar">
                <input class="input" type="search" data-table-filter="logTable"
                       placeholder="本页过滤" aria-label="本页快速过滤"/>
            </div>
        </div>
        <div class="table-wrap card__body--flush">
            <table class="table" id="logTable" data-sortable="true">
                <thead>
                <tr>
                    <th>id</th>
                    <th>用户名</th>
                    <th data-sort-type="number">端口</th>
                    <th data-sort-type="number">接收</th>
                    <th data-sort-type="number">发送</th>
                    <th data-sort-type="number">连接数</th>
                    <th data-sort-type="number">数据包数</th>
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
                            <td>${(statistics.port!0)?c}</td>
                            <td><span class="badge badge--info">${(statistics.receive!"")?html} 字节</span></td>
                            <td><span class="badge badge--ok">${(statistics.send!"")?html} 字节</span></td>
                            <td>${(statistics.connectNum!"")?html}</td>
                            <td>${(statistics.packNum!"")?html}</td>
                            <td>${(statistics.createTime!"")?html}</td>
                            <td>
                                <div class="cell-actions">
                                    <#--  保持原 href 不变  -->
                                    <a class="btn btn--danger btn--sm"
                                       href="/admin/log/remove?page=${page?c}&id=${(statistics.id!"")?url}"
                                       data-confirm="确定删除该条统计记录吗？"
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
             data-pager-url="/admin/log"
             data-pager-page="${page?c}"
             data-pager-total="${totalPage?c}"></div>
    </section>
</main>
<script>
    Admin.setTitle('用户日志');
</script>
</body>
</html>

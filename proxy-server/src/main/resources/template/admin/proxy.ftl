<#include "./header.ftl">
<#--  穿透集群
      model（ProxyController#tips）：list（ProxyServerEntity 集合）/ token（ConstConfig.REG_TOKEN）
                                  / reg_code（ConstConfig.REG_CODE）
      ProxyServerEntity：name / ip / port / num / level
      统计/黑名单/图片过滤的目标地址沿用原实现：http://{ip}/xxx?token={token}
      （原实现即固定 http 且不加端口，这里保持不变，只做 URL 编码避免参数拼接异常）。  -->
<main class="content">
    <div class="page-head">
        <div>
            <h1 class="page-head__title">穿透集群</h1>
            <div class="page-head__desc">当前在线的代理节点与通用注册验证码。</div>
        </div>
    </div>

    <div class="grid grid--tight">
        <div class="kpi">
            <div class="kpi__icon"><span data-icon="layers"></span></div>
            <div class="kpi__body">
                <div class="kpi__value">${(list![])?size}</div>
                <div class="kpi__label">在线节点数</div>
            </div>
        </div>
        <div class="kpi">
            <div class="kpi__icon kpi__icon--warn"><span data-icon="key"></span></div>
            <div class="kpi__body">
                <div class="kpi__value mono">${(reg_code!"")?html}</div>
                <div class="kpi__label">通用注册验证码（一小时变化一次）</div>
            </div>
        </div>
    </div>

    <section class="card">
        <div class="card__head">
            <div class="card__title">
                <span data-icon="server" data-icon-class="icon--sm"></span>
                <span>节点列表</span>
            </div>
            <div class="toolbar">
                <input class="input" type="search" data-table-filter="proxyTable"
                       placeholder="本页过滤" aria-label="本页快速过滤"/>
            </div>
        </div>
        <div class="table-wrap card__body--flush">
            <table class="table" id="proxyTable" data-sortable="true">
                <thead>
                <tr>
                    <th>名字</th>
                    <th>主机地址</th>
                    <th data-sort-type="number">端口</th>
                    <th data-sort-type="number">连接数量</th>
                    <th data-sort-type="number">用户级别限定</th>
                    <th data-sort-ignore>查看统计</th>
                </tr>
                </thead>
                <tbody>
                <#if list??>
                    <#list list as app>
                        <tr>
                            <td>${(app.name!"")?html}</td>
                            <td class="mono">${(app.ip!"")?html}</td>
                            <td>${(app.port!"")?html}</td>
                            <td>${(app.num!0)?c}</td>
                            <td>${(app.level!0)?c}</td>
                            <td>
                                <div class="cell-actions">
                                    <a class="btn btn--subtle btn--sm" target="_blank" rel="noopener"
                                       href="http://${(app.ip!"")?html}/statistics?token=${(token!"")?url}">
                                        <span data-icon="chart" data-icon-class="icon--sm"></span>
                                        <span>数据分析</span>
                                    </a>
                                    <a class="btn btn--subtle btn--sm" target="_blank" rel="noopener"
                                       href="http://${(app.ip!"")?html}/backList?token=${(token!"")?url}">
                                        <span data-icon="list" data-icon-class="icon--sm"></span>
                                        <span>黑名单</span>
                                    </a>
                                    <a class="btn btn--subtle btn--sm" target="_blank" rel="noopener"
                                       href="http://${(app.ip!"")?html}/photoList?token=${(token!"")?url}">
                                        <span data-icon="eye" data-icon-class="icon--sm"></span>
                                        <span>图片过滤</span>
                                    </a>
                                </div>
                            </td>
                        </tr>
                    </#list>
                </#if>
                </tbody>
            </table>
        </div>
        <div class="card__foot">
            节点列表来自内存缓存（20 秒无访问即过期），仅显示最近活跃的代理节点。
        </div>
    </section>
</main>
<script>
    Admin.setTitle('穿透集群');
</script>
</body>
</html>

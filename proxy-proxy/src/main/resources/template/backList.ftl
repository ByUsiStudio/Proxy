<#include "/header.ftl">
<#--监控页面-->
<#--
  【安全修复 J5】FreeMarker 默认不转义：HTML 文本/双引号属性用 ?html，查询串用 ?url。
  token 是集群管理凭据，按原有契约仅做 URL 编码传递（节点响应已统一加 Referrer-Policy: no-referrer）。
-->
<div style="padding: 1rem">
    <a href="/clearAll?token=${token?url}">清除所有</a>
    <div class="mdui-card">
        <#if (dataSize>0)>
            <div class="mdui-card-actions mdui-card-actions-stacked">
                <table class="mdui-table">
                    <thead>
                    <tr>
                        <th>Ip</th>
                        <th>用户</th>
                        <th>错误次数</th>
                        <th>操作</th>
                    </tr>
                    </thead>
                    <tbody>

                    <#list data?keys as key>
                        <tr>
                            <td>
                                ${key?html}
                            </td>
                            <td>
                              ${data[key].username?html}
                            </td>
                            <td>
                              ${(data[key].count!0)?c}
                            </td>
                            <td><a href="/clearBack?ip=${key?url}&token=${token?url}">清除记录</a></td>
                        </tr>

                    </#list>
                    </tbody>
                </table>
            </div>
        </#if>

    </div>
</div>
</body>
</html>

<#include "/header.ftl">
<#--监控页面-->
<#--
  【安全修复 J5】FreeMarker 默认不转义：URL 片段用 ?url 编码。
  【安全修复 J6】/photoDetail/{path} 已补 @CheckApi。<img src> 无法携带自定义请求头，
  因此按节点端既有契约把 token 放进查询串（HookCheckApi 读取 request.query("token")）。
  token 会进入 URL，节点响应已统一追加 Referrer-Policy: no-referrer 以防止 Referer 泄露。
-->
<div style="padding: 1rem">
    <div class="mdui-card">
        <#if (dataSize>0)>
            <div class="mdui-card-actions mdui-card-actions-stacked">
                <table class="mdui-table">
                    <thead>
                    <tr>
                        <th>图片</th>
                        <th>操作</th>
                    </tr>
                    </thead>
                    <tbody>
                    <#list data as key>
                        <tr>
                            <td>
                                <img style="height: 100px" src="/photoDetail/${time?url}/${key?url}?token=${token?url}"/>
                            </td>
                            <td><a href="/photoRemove/${time?url}/${key?url}?token=${token?url}">删除</a></td>
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

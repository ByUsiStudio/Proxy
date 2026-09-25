<#include "/header.ftl">
<#--监控页面-->
<#--
  【安全修复 J5】FreeMarker 默认不转义：目录名用 ?html 输出，URL 片段用 ?url 编码。
  【安全修复 J6】/photo/* 与 /photoRemoveAll/* 已补 @CheckApi：模板必须在链接里带上 token，
  否则管理页的查看/删除链接会全部被拒绝（token 走查询串是节点端既有契约）。
-->
<div style="padding: 1rem">
    <div class="mdui-card">
        <#if (dataSize>0)>
            <div class="mdui-card-actions mdui-card-actions-stacked">
                <table class="mdui-table">
                    <thead>
                    <tr>
                        <th>时间</th>
                        <th>操作</th>
                    </tr>
                    </thead>
                    <tbody>
                    <#list data as key>
                        <tr>
                            <td>
                                ${key?html}
                            </td>
                            <td>
                                <a href="/photo/${key?url}?token=${token?url}">查看</a>
                                <a href="/photoRemoveAll/${key?url}?token=${token?url}">删除</a>
                            </td>
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

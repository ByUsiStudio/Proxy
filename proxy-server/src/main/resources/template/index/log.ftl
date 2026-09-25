<#include "./header_index.ftl">
<#--  穿透日志（GET /index/log）
      model（controller/index/LogController#log）：
        page(int) / pageSize(int) / totalRow / totalPage / port(String，可为空) / username(String)
        list(List<StatisticsEntity>) —— 只包含会话账号本人的记录（服务端 andEq 精确匹配）
      本轮增强：按端口筛选 + 入口链接（我的用量 / 导出）。
      安全：所有插值显式转义；脚本里的字符串用 ?js_string。  -->
<div class="usage-wrap">
    <div class="usage-head">
        <div>
            <h1 class="usage-title">穿透日志</h1>
            <div class="usage-sub">
                账号 <span class="mono">${username?html}</span> 的流量明细 · 每页 ${pageSize?c} 条 · 共 ${totalRow?c} 条
                <#if (port!"")?has_content>· 已筛选端口 ${port?html}</#if>
            </div>
        </div>
        <div class="usage-actions">
            <a class="mdui-btn mdui-btn-dense mdui-ripple" href="/index/usage">我的用量</a>
            <a class="mdui-btn mdui-btn-dense mdui-ripple" href="/index/export?type=statistics&amp;format=csv">导出 CSV</a>
            <a class="mdui-btn mdui-btn-dense mdui-ripple" href="/index/export?type=statistics&amp;format=json">导出 JSON</a>
        </div>
    </div>

    <#--  端口筛选：只在自己账号的数据范围内进一步过滤（无法借此读取他人记录）  -->
    <form class="usage-form" method="get" action="/index/log">
        <label class="usage-form__label" for="port">按端口筛选</label>
        <input class="usage-input" id="port" name="port" type="number" min="1" max="65535"
               value="${(port!"")?html}" placeholder="端口（留空表示全部）"/>
        <button class="mdui-btn mdui-btn-dense mdui-btn-raised mdui-color-theme" type="submit">查询</button>
        <a class="mdui-btn mdui-btn-dense mdui-ripple" href="/index/log">重置</a>
    </form>

    <div class="usage-card">
        <div class="mdui-table-fluid">
            <table class="mdui-table">
                <thead>
                <tr>
                    <th>id</th>
                    <th>用户名</th>
                    <th>端口</th>
                    <th>接收</th>
                    <th>发送</th>
                    <th>连接数</th>
                    <th>数据包数</th>
                    <th>时间</th>
                </tr>
                </thead>
                <tbody>
                <#if (list??) && (list?size gt 0)>
                    <#list list as statistics>
                        <tr>
                            <td class="mono">${(statistics.id!"")?html}</td>
                            <td>${(statistics.username!"")?html}</td>
                            <td class="mono">${(statistics.port!0)?c}</td>
                            <td>${(statistics.receive!"0")?html} 字节</td>
                            <td>${(statistics.send!"0")?html} 字节</td>
                            <td>${(statistics.connectNum!"0")?html}</td>
                            <td>${(statistics.packNum!"0")?html}</td>
                            <td>${(statistics.createTime!"")?html}</td>
                        </tr>
                    </#list>
                <#else>
                    <tr>
                        <td colspan="8" class="usage-muted">暂无流量记录</td>
                    </tr>
                </#if>
                </tbody>
            </table>
        </div>
        <div class="pagger-box pagger" id="box"></div>
    </div>
</div>

<script>
    var pageConst = ${page?c};
    var portFilter = "${(port!"")?js_string}";
    $('#box').paging({
        initPageNo: ${page?c}, // 初始页码
        totalPages: <#if (totalPage!0) gt 0>${totalPage?c}<#else>1</#if>, // 总页数
        slideSpeed: 600, // 缓动速度。单位毫秒
        jump: true, // 是否支持跳转
        callback: function (page) { // 回调函数
            if (pageConst != page) {
                // 翻页时保留端口筛选条件（端口已在服务端做 1~65535 校验）
                location.href = "/index/log?page=" + page + (portFilter ? "&port=" + encodeURIComponent(portFilter) : "");
            }
        }
    });
</script>
</body>
</html>

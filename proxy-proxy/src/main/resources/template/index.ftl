<#include "/header.ftl">
<#--监控页面-->
<#--
  【安全修复 J5】FreeMarker 默认不转义：所有进入 HTML 文本/双引号属性的插值必须显式 ?html，
  进入查询串的必须 ?url，进入 <script> 的必须用 ?js_string（数字用 ?c）。
  审计确认 FreeMarker 2.3.31 的 ?html 会转义 < > & "，但不转义单引号，
  因此所有插值属性一律使用双引号包裹。
-->
<div style="padding: 1rem">

    <div class="mdui-card">
        <div class="mdui-card-actions mdui-card-actions-stacked">
            <button class="mdui-btn mdui-ripple">连接使用数：${statisticsSize?c}</button>
        </div>
        <div class="mdui-card-actions mdui-card-actions-stacked" id="chart" style="height: 800px;width: 100%">
        </div>
        <#if (statisticsSize>0)>
            <div class="mdui-card-actions mdui-card-actions-stacked">
                <table class="mdui-table">
                    <thead>
                    <tr>
                        <th>用户名</th>
                        <th>域名</th>
                        <th>自定义域名</th>
                        <th>前往穿透</th>
                        <th>来源IP</th>
                        <th>连接时间</th>
                        <th>端口</th>
                        <th>操作</th>
                    </tr>
                    </thead>
                    <tbody>
                    <#if statisticsData?exists>
                        <#list statisticsData as  app>
                            <#if app??>
                                <tr>
                                    <td>
                                        <#if app.username??> ${app.username?html}<#else>未知</#if>
                                    </td>
                                    <td>${app.domain?html}</td>
                                    <td>
                                        <#if app.customDomain??> ${app.customDomain?html}<#else>未自定义</#if>
                                    </td>
                                    <td><a href="//${app.domain?html}.${host?html}" target="_blank">访问${app.domain?html}</a></td>
                                    <td>${app.ip?html}</td>
                                    <td>${app.date?html}</td>
                                    <td>${app.port?html}</td>
                                    <td><a href="/offline?domain=${app.domain?url}&token=${token?url}">强制下线</a></td>
                                </tr>
                            </#if>
                        </#list>
                    </#if>
                    </tbody>
                </table>
            </div>
        </#if>

    </div>
</div>


<script type="text/javascript">
    var dom = document.getElementById('chart');
    var myChart = echarts.init(dom, null, {
        renderer: 'canvas',
        useDirtyRect: false
    });
    var app = {};

    var option;
    /* 【安全修复 J5】服务端只传结构化数据：日期用 ?js_string 转义成 JS 字符串字面量，
       数值用 ?c 保证只输出数字，任何服务端数据都不可能越出脚本上下文。 */
    const dateList = [<#if flowType??><#list flowType as item>"${item?js_string}"<#sep>,</#list></#if>]
    const flowSend = [<#if flowSend??><#list flowSend as item>${(item!0)?c}<#sep>,</#list></#if>]
    const flowReceive = [<#if flowReceive??><#list flowReceive as item>${(item!0)?c}<#sep>,</#list></#if>]
    option = {
        // Make gradient line here
        visualMap: [
            {
                show: false,
                type: 'continuous',
                seriesIndex: 0,
                min: 0,
                max: 400
            },
            {
                show: false,
                type: 'continuous',
                seriesIndex: 1,
                dimension: 0,
                min: 0,
                max: dateList.length - 1
            }
        ],
        title: [
            {
                left: 'center',
                text: '入网'
            },
            {
                top: '55%',
                left: 'center',
                text: '出网'
            }
        ],
        tooltip: {
            trigger: 'axis'
        },
        xAxis: [
            {
                data: dateList
            },
            {
                data: dateList,
                gridIndex: 1
            }
        ],
        yAxis: [
            {
                axisLabel: {
                    formatter: '{value}/KB'
                }
            },
            {
                gridIndex: 1,
                axisLabel: {
                    formatter: '{value}/KB'
                }
            }
        ],
        grid: [
            {
                bottom: '60%'
            },
            {
                top: '60%'
            }
        ],
        series: [
            {
                type: 'line',
                showSymbol: false,
                data: flowSend,
                smooth: true,
                name: '入网',
                stack: 'Total',
            },
            {
                showSymbol: false,
                type: 'line',
                smooth: true,
                name: '出网',
                stack: 'Total',
                data: flowReceive,
                xAxisIndex: 1,
                yAxisIndex: 1
            }
        ]
    };

    if (option && typeof option === 'object') {
        myChart.setOption(option);
    }

    window.addEventListener('resize', myChart.resize);
</script>


</body>
</html>

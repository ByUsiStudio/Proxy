<#include "./header.ftl">
<#--  全局搜索（GET /admin/search）
      model（SearchController#search）：
        keyword(String) / type(String) / groupLimit(int) / searchable(boolean) / totalCount(int)
        users(List<UserVo>) / userCount            —— UserVo 已被服务端重建，不含 password
        domains(List<PortalSearchVo.DomainRow>) / domainCount
        configs(List<ConfigEntity>) / configCount  —— 模板只渲染非口令列，绝不输出 password
        statistics(List<StatisticsEntity>) / statisticsCount
        audits(List<PortalSearchVo.AuditRow>) / auditCount / auditAvailable(boolean)
        searchError(String，可选)
      安全说明：FreeMarker 不自动转义，所有插值都显式 ?html / ?url / ?c。  -->
<main class="content">
    <div class="page-head">
        <div>
            <h1 class="page-head__title">全局搜索</h1>
            <div class="page-head__desc">
                一个入口检索账号、域名、自动穿透配置、流量统计与审计日志；每组最多展示 ${groupLimit?c} 条，请用更精确的关键字缩小范围。
            </div>
        </div>
    </div>

    <#--  检索表单：GET /admin/search 是后台白名单里的只读地址，因此所有维度都通过 type 参数切换  -->
    <section class="card mt-4">
        <div class="card__body">
            <form class="filter-bar" method="get" action="/admin/search" role="search">
                <input class="input" id="q" name="q" type="search" maxlength="64"
                       value="${keyword?html}" placeholder="账号 / 域名 / 设备ID / 端口关键字"
                       aria-label="搜索关键字" autocomplete="off"/>
                <select class="select" id="type" name="type" aria-label="搜索类型">
                    <option value="all"<#if type == "all"> selected</#if>>全部</option>
                    <option value="user"<#if type == "user"> selected</#if>>仅用户</option>
                    <option value="domain"<#if type == "domain"> selected</#if>>仅域名</option>
                    <option value="config"<#if type == "config"> selected</#if>>仅自动穿透配置</option>
                    <option value="statistics"<#if type == "statistics"> selected</#if>>仅流量统计</option>
                    <option value="audit"<#if type == "audit"> selected</#if>>仅审计日志</option>
                </select>
                <button class="btn btn--subtle" type="submit">
                    <span data-icon="search" data-icon-class="icon--sm"></span>
                    <span>搜索</span>
                </button>
                <a class="btn btn--ghost" href="/admin/search">
                    <span data-icon="close" data-icon-class="icon--sm"></span>
                    <span>清空</span>
                </a>
            </form>
        </div>
    </section>

    <#if searchError??>
        <div class="alert alert--warn mt-4" role="alert">${searchError?html}</div>
    </#if>

    <#if !searchable>
        <#--  未输入关键字：给出「可以搜什么」的提示清单，而不是空白页  -->
        <section class="card mt-4">
            <div class="card__body">
                <div class="empty">
                    <span data-icon="search" data-icon-class="icon"></span>
                    <div class="empty__title">输入关键字开始搜索</div>
                    <div class="text-xs">支持模糊匹配，关键字最长 64 个字符。</div>
                </div>
                <div class="kv">
                    <div class="kv__key">账号</div>
                    <div class="kv__val">账号名（邮箱）模糊匹配；输入完整账号时还会做一次精确查询并置顶。</div>
                </div>
                <div class="kv">
                    <div class="kv__key">域名</div>
                    <div class="kv__val">sys_domain 的 domain 与 custom_domain 两个字段。</div>
                </div>
                <div class="kv">
                    <div class="kv__key">自动穿透配置</div>
                    <div class="kv__val">按账号模糊匹配，展示设备ID / 内网服务 / 外网服务 / 类型 / 域名 / 端口（不含口令）。</div>
                </div>
                <div class="kv">
                    <div class="kv__key">流量统计</div>
                    <div class="kv__val">按账号模糊匹配流量明细（接收 / 发送 / 连接数 / 数据包数）。</div>
                </div>
                <div class="kv">
                    <div class="kv__key">审计日志</div>
                    <div class="kv__val">按操作者 / 动作 / 目标模糊匹配（不含 detail 与 UA，避免自由文本泄露敏感内容）。</div>
                </div>
            </div>
        </section>
    <#elseif totalCount == 0>
        <#--  有检索但无结果  -->
        <section class="card mt-4">
            <div class="card__body">
                <div class="empty">
                    <span data-icon="info" data-icon-class="icon"></span>
                    <div class="empty__title">未找到与 “${keyword?html}” 相关的结果</div>
                    <div class="text-xs">可以尝试更短的关键字（例如只输入账号的一部分），或切换检索类型。</div>
                </div>
            </div>
        </section>
    <#else>
        <#--  ============ 用户 ============  -->
        <#if users??>
            <section class="card mt-4">
                <div class="card__head">
                    <div class="card__title">
                        <span data-icon="users" data-icon-class="icon--sm"></span>
                        <span>用户</span>
                        <span class="badge badge--info">${userCount?c} 条</span>
                        <#if userCount == groupLimit>
                            <span class="card__hint">已达展示上限，请细化关键字</span>
                        </#if>
                    </div>
                    <a class="btn btn--ghost btn--sm" href="/admin/user?username=${keyword?url}">在用户管理中打开</a>
                </div>
                <div class="card__body card__body--flush">
                    <div class="table-wrap">
                        <table class="table">
                            <thead>
                            <tr>
                                <th>账号</th>
                                <th>级别</th>
                                <th>状态</th>
                                <th>端口数</th>
                                <th>最近登录 IP</th>
                                <th>最近登录时间</th>
                                <th data-sort-ignore>操作</th>
                            </tr>
                            </thead>
                            <tbody>
                            <#if userCount gt 0>
                                <#list users as item>
                                    <tr>
                                        <td class="truncate"><a href="/admin/user?username=${(item.username!"")?url}">${(item.username!"")?html}</a></td>
                                        <td>${(item.level!0)?c}</td>
                                        <td>
                                            <#if (item.type!0) == -1>
                                                <span class="badge badge--down">封号</span>
                                            <#else>
                                                <span class="badge badge--ok">正常</span>
                                            </#if>
                                        </td>
                                        <td><#if item.ports??>${item.ports?size?c}<#else>0</#if></td>
                                        <td class="mono truncate"><#if (item.loginIp!"")?has_content>${item.loginIp?html}<#else>-</#if></td>
                                        <td class="text-xs"><#if (item.loginTime!"")?has_content>${item.loginTime?html}<#else>-</#if></td>
                                        <td>
                                            <div class="cell-actions">
                                                <a class="btn btn--ghost btn--sm" href="/admin/user?username=${(item.username!"")?url}">详情</a>
                                                <a class="btn btn--ghost btn--sm" href="/admin/log?username=${(item.username!"")?url}">流量</a>
                                            </div>
                                        </td>
                                    </tr>
                                </#list>
                            <#else>
                                <tr><td colspan="7" class="muted">没有匹配的用户</td></tr>
                            </#if>
                            </tbody>
                        </table>
                    </div>
                </div>
            </section>
        </#if>

        <#--  ============ 域名 ============  -->
        <#if domains??>
            <section class="card mt-4">
                <div class="card__head">
                    <div class="card__title">
                        <span data-icon="globe" data-icon-class="icon--sm"></span>
                        <span>域名</span>
                        <span class="badge badge--info">${domainCount?c} 条</span>
                        <#if domainCount == groupLimit>
                            <span class="card__hint">已达展示上限，请细化关键字</span>
                        </#if>
                    </div>
                    <a class="btn btn--ghost btn--sm" href="/admin/domain?usernameSearch=${keyword?url}">在域名管理中打开</a>
                </div>
                <div class="card__body card__body--flush">
                    <div class="table-wrap">
                        <table class="table">
                            <thead>
                            <tr>
                                <th>域名</th>
                                <th>自定义域名</th>
                                <th>归属账号</th>
                                <th>创建时间</th>
                                <th data-sort-ignore>操作</th>
                            </tr>
                            </thead>
                            <tbody>
                            <#if domainCount gt 0>
                                <#list domains as item>
                                    <tr>
                                        <td class="mono truncate">${item.domain?html}</td>
                                        <td class="mono truncate"><#if item.customDomain?has_content>${item.customDomain?html}<#else>-</#if></td>
                                        <td class="truncate"><#if item.username?has_content>${item.username?html}<#else>-</#if></td>
                                        <td class="text-xs">${item.createTime?html}</td>
                                        <td>
                                            <div class="cell-actions">
                                                <a class="btn btn--ghost btn--sm" href="/admin/domain?usernameSearch=${item.username?url}">查看归属</a>
                                            </div>
                                        </td>
                                    </tr>
                                </#list>
                            <#else>
                                <tr><td colspan="5" class="muted">没有匹配的域名</td></tr>
                            </#if>
                            </tbody>
                        </table>
                    </div>
                </div>
            </section>
        </#if>

        <#--  ============ 自动穿透配置（绝不渲染 password） ============  -->
        <#if configs??>
            <section class="card mt-4">
                <div class="card__head">
                    <div class="card__title">
                        <span data-icon="sync" data-icon-class="icon--sm"></span>
                        <span>自动穿透配置</span>
                        <span class="badge badge--info">${configCount?c} 条</span>
                        <#if configCount == groupLimit>
                            <span class="card__hint">已达展示上限，请细化关键字</span>
                        </#if>
                    </div>
                    <a class="btn btn--ghost btn--sm" href="/admin/config?username=${keyword?url}">在自动穿透中打开</a>
                </div>
                <div class="card__body card__body--flush">
                    <div class="table-wrap">
                        <table class="table">
                            <thead>
                            <tr>
                                <th>账号</th>
                                <th>设备ID</th>
                                <th>内网服务</th>
                                <th>外网服务</th>
                                <th>类型</th>
                                <th>域名</th>
                                <th>端口</th>
                                <th>创建时间</th>
                            </tr>
                            </thead>
                            <tbody>
                            <#if configCount gt 0>
                                <#list configs as item>
                                    <tr>
                                        <td class="truncate"><a href="/admin/config?username=${(item.username!"")?url}">${(item.username!"")?html}</a></td>
                                        <td class="mono truncate">${(item.deviceId!"")?html}</td>
                                        <td class="mono truncate">${(item.userHost!"")?html}</td>
                                        <td class="mono truncate">${(item.serverHost!"")?html}</td>
                                        <td><span class="badge badge--muted">${(item.type!"")?html}</span></td>
                                        <td class="mono truncate">${item.domain?html}</td>
                                        <td class="mono">${(item.port!"")?html}</td>
                                        <td class="text-xs">${(item.createTime!"")?html}</td>
                                    </tr>
                                </#list>
                            <#else>
                                <tr><td colspan="8" class="muted">没有匹配的配置</td></tr>
                            </#if>
                            </tbody>
                        </table>
                    </div>
                </div>
            </section>
        </#if>

        <#--  ============ 流量统计 ============  -->
        <#if statistics??>
            <section class="card mt-4">
                <div class="card__head">
                    <div class="card__title">
                        <span data-icon="list" data-icon-class="icon--sm"></span>
                        <span>流量统计</span>
                        <span class="badge badge--info">${statisticsCount?c} 条</span>
                        <#if statisticsCount == groupLimit>
                            <span class="card__hint">已达展示上限，请细化关键字</span>
                        </#if>
                    </div>
                    <a class="btn btn--ghost btn--sm" href="/admin/log?username=${keyword?url}">在用户日志中打开</a>
                </div>
                <div class="card__body card__body--flush">
                    <div class="table-wrap">
                        <table class="table">
                            <thead>
                            <tr>
                                <th>账号</th>
                                <th>端口</th>
                                <th>接收</th>
                                <th>发送</th>
                                <th>连接数</th>
                                <th>数据包数</th>
                                <th>时间</th>
                            </tr>
                            </thead>
                            <tbody>
                            <#if statisticsCount gt 0>
                                <#list statistics as item>
                                    <tr>
                                        <td class="truncate"><a href="/admin/log?username=${(item.username!"")?url}">${(item.username!"")?html}</a></td>
                                        <td class="mono">${(item.port!0)?c}</td>
                                        <td><span class="badge badge--info">${(item.receive!"")?html} 字节</span></td>
                                        <td><span class="badge badge--ok">${(item.send!"")?html} 字节</span></td>
                                        <td>${(item.connectNum!"")?html}</td>
                                        <td>${(item.packNum!"")?html}</td>
                                        <td class="text-xs">${(item.createTime!"")?html}</td>
                                    </tr>
                                </#list>
                            <#else>
                                <tr><td colspan="7" class="muted">没有匹配的流量记录</td></tr>
                            </#if>
                            </tbody>
                        </table>
                    </div>
                </div>
            </section>
        </#if>

        <#--  ============ 审计日志 ============  -->
        <#if audits??>
            <section class="card mt-4">
                <div class="card__head">
                    <div class="card__title">
                        <span data-icon="shieldCheck" data-icon-class="icon--sm"></span>
                        <span>审计日志</span>
                        <span class="badge badge--info">${auditCount?c} 条</span>
                        <#if auditCount == groupLimit>
                            <span class="card__hint">已达展示上限，请细化关键字</span>
                        </#if>
                    </div>
                    <a class="btn btn--ghost btn--sm" href="/admin/audit">打开审计日志</a>
                </div>
                <div class="card__body card__body--flush">
                    <#if !auditAvailable>
                        <div class="empty">
                            <span data-icon="warn" data-icon-class="icon"></span>
                            <div class="empty__title">审计日志不可用</div>
                            <div class="text-xs">当前数据库缺少 sys_audit_log 表，请先执行 db/migrate-v16.1.sql。</div>
                        </div>
                    <#else>
                        <div class="table-wrap">
                            <table class="table">
                                <thead>
                                <tr>
                                    <th>操作者</th>
                                    <th>类型</th>
                                    <th>动作</th>
                                    <th>目标</th>
                                    <th>结果</th>
                                    <th>来源 IP</th>
                                    <th>时间</th>
                                </tr>
                                </thead>
                                <tbody>
                                <#if auditCount gt 0>
                                    <#list audits as item>
                                        <tr>
                                            <td class="truncate">${item.actor?html}</td>
                                            <td><span class="badge badge--muted">${item.actorType?html}</span></td>
                                            <td class="mono truncate">${item.action?html}</td>
                                            <td class="truncate">${item.target?html}</td>
                                            <td>
                                                <#if item.result == "fail">
                                                    <span class="badge badge--fail">失败</span>
                                                <#else>
                                                    <span class="badge badge--ok">成功</span>
                                                </#if>
                                            </td>
                                            <td class="mono">${item.ip?html}</td>
                                            <td class="text-xs">${item.createTime?html}</td>
                                        </tr>
                                    </#list>
                                <#else>
                                    <tr><td colspan="7" class="muted">没有匹配的审计记录</td></tr>
                                </#if>
                                </tbody>
                            </table>
                        </div>
                    </#if>
                </div>
            </section>
        </#if>
    </#if>
</main>
<script>
    Admin.setTitle('全局搜索');
    (function () {
        'use strict';
        // 进入搜索页自动聚焦输入框；纯 DOM 操作，不涉及任何 HTML 字符串拼接
        var input = document.getElementById('q');
        if (input) {
            input.focus();
            // 光标移到末尾，方便继续补关键字
            try { input.setSelectionRange(input.value.length, input.value.length); } catch (e) { /* 类型不支持时忽略 */ }
        }
    })();
</script>
</body>
</html>

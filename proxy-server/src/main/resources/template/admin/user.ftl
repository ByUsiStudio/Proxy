<#include "./header.ftl">
<#--  用户管理
      model（UserController#index）：page / pageSize / totalRow / totalPage / list(List<UserVo>) / username
      UserVo：id / username / password / type / hasCloseCheckPhoto / level / ports(List<Integer>)
              / createTime / loginTime / loginIp / ipSource  -->
<main class="content">
    <div class="page-head">
        <div>
            <h1 class="page-head__title">用户管理</h1>
            <div class="page-head__desc">维护穿透账号、端口、级别与校验开关。</div>
        </div>
        <div class="toolbar">
            <button type="button" class="btn btn--primary" data-dialog-open="addUser">
                <span data-icon="plus" data-icon-class="icon--sm"></span>
                <span>添加用户</span>
            </button>
        </div>
    </div>

    <section class="card">
        <div class="card__head">
            <div class="card__title">
                <span data-icon="users" data-icon-class="icon--sm"></span>
                <span>用户列表</span>
                <span class="card__hint">共 ${totalRow?c} 条</span>
            </div>
            <div class="toolbar">
                <#--  服务端查询：路由与参数名保持不变  -->
                <form class="field-row" method="get" action="/admin/user">
                    <input class="input" id="username" name="username" type="text"
                           value="${(username!"")?html}" placeholder="用户名" aria-label="按用户名查询"/>
                    <button class="btn btn--subtle" type="submit" id="selectButton">
                        <span data-icon="search" data-icon-class="icon--sm"></span>
                        <span>查询</span>
                    </button>
                </form>
                <#--  前端即时过滤：仅过滤当前页已渲染的行  -->
                <input class="input" type="search" data-table-filter="userTable"
                       placeholder="本页过滤" aria-label="本页快速过滤"/>
            </div>
        </div>
        <div class="table-wrap card__body--flush">
            <table class="table" id="userTable" data-sortable="true">
                <thead>
                <tr>
                    <th>用户名</th>
                    <th>密码</th>
                    <th data-sort-type="number">开通端口</th>
                    <th data-sort-type="number">用户级别</th>
                    <th>关闭鉴别校验</th>
                    <th>最近登录IP</th>
                    <th>IP来源</th>
                    <th>最近登录时间</th>
                    <th>创建时间</th>
                    <th data-sort-type="number">账号类型</th>
                    <th data-sort-ignore>操作</th>
                </tr>
                </thead>
                <tbody>
                <#if list??>
                    <#list list as userVo>
                        <tr>
                            <td>${(userVo.username!"")?html}</td>
                             <td>
                                <#--  【安全加固】不在列表里输出口令明文：
                                      一页 10 个账号的口令同时出现在屏幕上，任何截图、投屏或
                                      旁观都会造成批量凭据泄露。这里只显示「是否已设置」，
                                      需要查看/修改时进入编辑弹窗（弹窗内默认打码，可点「显示」）。  -->
                                <#if (userVo.password!"")?has_content>
                                    <span class="badge badge--muted">已设置</span>
                                <#else>
                                    <span class="badge badge--down">未设置</span>
                                </#if>
                            </td>
                            <td>
                                <#list userVo.ports! as port>${port?c}<#if port_has_next>, </#if></#list>
                            </td>
                            <td>${(userVo.level!0)?c}</td>
                            <td>${(userVo.hasCloseCheckPhoto!"")?html}</td>
                            <td>${(userVo.loginIp!"")?html}</td>
                            <td>${(userVo.ipSource!"")?html}</td>
                            <td>${(userVo.loginTime!"")?html}</td>
                            <td>${(userVo.createTime!"")?html}</td>
                            <td>
                                <#if userVo.type?? && userVo.type == -1>
                                    <span class="badge badge--down">封号(${(userVo.type!"")?html})</span>
                                <#else>
                                    <span class="badge badge--ok">正常(${(userVo.type!0)?c})</span>
                                </#if>
                            </td>
                            <td>
                                <div class="cell-actions">
                                    <button type="button" class="btn btn--subtle btn--sm"
                                            data-dialog-open="edit-user-${userVo.id?html}">
                                        <span data-icon="gear" data-icon-class="icon--sm"></span>
                                        <span>编辑</span>
                                    </button>
                                    <#--  保持原 href 不变（是否改为 POST 由后端决定）  -->
                                    <a class="btn btn--danger btn--sm"
                                       href="/admin/user/remove?page=${page?c}&username=${userVo.username?url}"
                                       data-confirm="确定删除用户 ${userVo.username?html} 吗？"
                                       data-confirm-title="删除用户">
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
             data-pager-url="/admin/user"
             data-pager-page="${page?c}"
             data-pager-total="${totalPage?c}"
             data-pager-param="username"
             data-pager-input="username"></div>
    </section>

    <#--  每行编辑弹窗：与原实现相同的 form action / 字段名  -->
    <#if list??>
        <#list list as userVo>
            <div class="modal" id="edit-user-${userVo.id?html}" role="dialog" aria-modal="true"
                 aria-hidden="true" aria-label="编辑用户">
                <form class="modal__panel" method="post" action="/admin/user/edit?page=${page?c}">
                    <div class="modal__head">
                        <h3 class="modal__title">编辑用户</h3>
                        <button type="button" class="btn btn--ghost btn--icon" data-dialog-close aria-label="关闭">
                            <span data-icon="close" data-icon-class="icon--sm"></span>
                        </button>
                    </div>
                    <div class="modal__body">
                        <div class="field">
                            <label class="field__label">用户名</label>
                            <input class="input" name="username" readonly type="text" value="${userVo.username?html}"/>
                        </div>
                        <div class="field">
                            <label class="field__label" for="pwd-${userVo.id?html}">
                                密码
                                <span class="field__hint">留空表示不修改；点击「显示」可查看当前值</span>
                            </label>
                            <div class="field-row">
                                <input class="input" id="pwd-${userVo.id?html}" name="password" type="password"
                                       autocomplete="new-password" value="${(userVo.password!"")?html}"/>
                                <button type="button" class="btn btn--ghost btn--sm" data-toggle-password="pwd-${userVo.id?html}"
                                        aria-label="显示或隐藏密码">
                                    <span data-icon="eye" data-icon-class="icon--sm"></span>
                                    <span>显示</span>
                                </button>
                            </div>
                        </div>
                        <div class="field">
                            <label class="field__label">类型：-1(封号) 1(正常) 2(待审核，目前也是正常后期放开)</label>
                            <input class="input" name="type" type="text" value="${(userVo.type!0)?c}"/>
                        </div>
                        <div class="field">
                            <label class="field__label">鉴别：false需要校验，true，关闭了校验</label>
                            <input class="input" name="hasCloseCheckPhoto" type="text"
                                   value="${(userVo.hasCloseCheckPhoto!"")?html}"/>
                        </div>
                        <div class="field">
                            <label class="field__label">TCP端口号固定 多个逗号隔开</label>
                            <input class="input" name="ports" type="text"
                                   value="<#list userVo.ports! as port>${port?c}<#if port_has_next>,</#if></#list>"/>
                        </div>
                        <div class="field">
                            <label class="field__label">用户级别</label>
                            <input class="input" name="level" type="text" value="${(userVo.level!0)?c}"/>
                        </div>
                    </div>
                    <div class="modal__foot">
                        <button type="button" class="btn btn--ghost" data-dialog-close>取消</button>
                        <button type="submit" class="btn btn--primary">确定</button>
                    </div>
                </form>
            </div>
        </#list>
    </#if>

    <#--  添加弹窗：与原实现相同的 form action / 字段名  -->
    <div class="modal" id="addUser" role="dialog" aria-modal="true" aria-hidden="true" aria-label="添加用户">
        <form class="modal__panel" method="post" action="/admin/user/add?page=${page?c}">
            <div class="modal__head">
                <h3 class="modal__title">添加用户</h3>
                <button type="button" class="btn btn--ghost btn--icon" data-dialog-close aria-label="关闭">
                    <span data-icon="close" data-icon-class="icon--sm"></span>
                </button>
            </div>
            <div class="modal__body">
                <div class="field">
                    <label class="field__label" for="add-username">用户名</label>
                    <input class="input" id="add-username" name="username" placeholder="用户名" type="text"
                           data-autofocus required/>
                </div>
                <div class="field">
                    <label class="field__label" for="add-password">密码</label>
                    <input class="input" id="add-password" name="password" type="password"
                           autocomplete="new-password" placeholder="密码"/>
                </div>
                <div class="field">
                    <label class="field__label" for="add-ports">端口号</label>
                    <input class="input" id="add-ports" name="ports" placeholder="端口号 逗号分隔" type="text"/>
                </div>
                <div class="field">
                    <label class="field__label" for="add-level">用户级别</label>
                    <input class="input" id="add-level" name="level" placeholder="用户级别" type="text"/>
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
    Admin.setTitle('用户管理');

    /* 密码显隐切换：仅切换 input 的 type，不复制也不缓存口令值。 */
    document.addEventListener('click', function (ev) {
        var btn = ev.target.closest && ev.target.closest('[data-toggle-password]');
        if (!btn) { return; }
        var input = document.getElementById(btn.getAttribute('data-toggle-password'));
        if (!input) { return; }
        var show = input.type === 'password';
        input.type = show ? 'text' : 'password';
        btn.setAttribute('aria-pressed', show ? 'true' : 'false');
        var label = btn.querySelector('span:last-child');
        if (label) { label.textContent = show ? '隐藏' : '显示'; }
    });
</script>
</body>
</html>

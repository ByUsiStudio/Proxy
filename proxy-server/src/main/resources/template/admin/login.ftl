<#--  管理后台登录页
      契约（与 AdminController 锁定）：
        - 表单 method="post" action="/admin/login"，唯一密码字段 name="password"
        - 不再使用任何 JS 写 cookie（旧实现 document.cookie = "auth=" + value 已移除）
        - 失败时控制器 render 本模板并传入 error；未登录访问后台也由 AuthFilter render 本模板
      model：error（可选，仅失败时存在）  -->
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <title>认证识别</title>
    <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
    <meta name="renderer" content="webkit">
    <script>
        /* 与 header.ftl 相同的首屏主题脚本（纯静态、无服务端插值）。 */
        (function () {
            try {
                var mode = localStorage.getItem('px_admin_theme');
                var dark = mode === 'dark' || (mode !== 'light' &&
                    window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
                document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
                document.documentElement.style.colorScheme = dark ? 'dark' : 'light';
            } catch (e) { /* 隐私模式下忽略 */ }
        })();
    </script>
    <link rel="stylesheet" href="/common/css/admin.css"/>
</head>
<body>
<div class="auth">
    <aside class="auth__aside">
        <div class="auth__slogan">Proxy 内网穿透<br>管理后台</div>
        <div class="auth__features">
            <div class="auth__feature">
                <span data-icon="shieldCheck"></span>
                <h3>服务端会话</h3>
                <p>登录由服务端校验密码并下发会话，凭据不再写入前端 Cookie。</p>
            </div>
            <div class="auth__feature">
                <span data-icon="layers"></span>
                <h3>穿透集群</h3>
                <p>集中查看代理节点、用户、域名与自动穿透配置。</p>
            </div>
        </div>
    </aside>

    <main class="auth__main">
        <div class="auth__card">
            <div class="card">
                <div class="card__body">
                    <div class="stack">
                        <div class="brand">
                            <span class="brand__mark"><span data-icon="rocket" data-icon-class="icon--sm"></span></span>
                            <span>认证识别</span>
                        </div>
                        <p class="muted text-sm mb-0">请输入后台管理密码以继续。</p>
                    </div>

                    <#--  error 仅在登录失败时由控制器放入 model，未登录首次访问时不存在，
                          因此必须用 ?? / !"" 双重防御，避免“变量缺失”导致模板渲染失败。  -->
                    <#if (error!"")?has_content>
                        <div class="alert alert--danger mt-4" role="alert">
                            <span data-icon="warn" data-icon-class="icon--sm"></span>
                            <span>${error?html}</span>
                        </div>
                    </#if>

                    <form method="post" action="/admin/login" autocomplete="off" class="mt-4">
                        <div class="field">
                            <label class="field__label" for="login-password">指纹标识</label>
                            <input class="input" type="password" id="login-password" name="password"
                                   data-autofocus required autocomplete="current-password"
                                   placeholder="请输入后台密码"/>
                            <span class="field__hint">密码在服务端进行常量时间比对，会话有效期 30 分钟。</span>
                        </div>
                        <button class="btn btn--primary btn--block" type="submit">
                            <span data-icon="key" data-icon-class="icon--sm"></span>
                            <span>登录</span>
                        </button>
                    </form>
                </div>
                <div class="card__foot">
                    忘记密码？请修改服务端 app.properties 中的 password 配置后重启服务。
                </div>
            </div>
        </div>
    </main>
</div>
<script src="/common/js/admin.js"></script>
</body>
</html>

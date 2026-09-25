<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover"/>
    <meta name="color-scheme" content="light dark">
    <meta name="referrer" content="no-referrer">
    <meta name="description" content="Proxy 内网穿透：数据转发实现，无需公网 IP，支持 TCP/UDP 与 http/https/ws/wss，免费赠送二级域名。">
    <link rel="stylesheet" href="/common/css/mdui.min.css"/>
    <link rel="stylesheet" href="/common/css/paging.css"/>
    <link rel="stylesheet" href="/index/css/index.css"/>
    <script src="/common/js/jquery.min.js"></script>
    <script src="/common/js/paging.js"></script>
    <script src="/common/js/mdui.min.js"></script>
    <link rel="shortcut icon" href="favicon.ico" type="image/x-icon">
    <title>Proxy内网穿透</title>
    <script>
        /* 主题引导：在样式生效前写入 data-theme，避免闪烁（唯一的内联脚本，不含任何服务端数据） */
        (function () {
            var saved = null;
            try { saved = localStorage.getItem('px_site_theme'); } catch (e) { saved = null; }
            var mql = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;
            var theme = (saved === 'light' || saved === 'dark') ? saved : (mql && mql.matches ? 'dark' : 'light');
            document.documentElement.setAttribute('data-theme', theme);
            document.documentElement.style.colorScheme = theme;
        })();
    </script>
</head>
<body class="mdui-theme-primary-indigo mdui-theme-accent-pink mdui-theme-layout-auto">
<header class="mdui-appbar mdui-appbar-fixed">
    <div class="mdui-toolbar">
        <a href="/index/index" aria-label="Proxy 内网穿透首页">
            <svg viewBox="0 0 24 24" style="width:1.35rem;height:1.35rem;fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round;stroke-linejoin:round;">
                <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/>
                <path d="m9 12 2 2 4-4"/>
            </svg>
            <span>Proxy 穿透</span>
        </a>
        <div class="mdui-toolbar-spacer"></div>
        <#--  已登录用户可直接进入自助门户：/index/usage 由 AuthFilter 校验会话，未登录会回到本页  -->
        <a class="mdui-btn mdui-btn-dense mdui-ripple mdui-hidden-xs" href="/index/usage">我的用量</a>
        <button type="button" class="theme-toggle" id="themeToggle" aria-label="切换深色 / 浅色主题" title="切换主题">
            <svg viewBox="0 0 24 24" id="themeIcon"><path d="M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10"/><path d="M12 1v3M12 20v3M4.2 4.2l2.1 2.1M17.7 17.7l2.1 2.1M1 12h3M20 12h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1"/></svg>
        </button>
        <button type="button" class="mdui-btn mdui-btn-dense mdui-ripple" mdui-dialog="{target: '#login_dialog'}">登录</button>
        <button type="button" class="mdui-btn mdui-btn-dense mdui-btn-raised mdui-color-theme mdui-ripple" mdui-dialog="{target: '#register_dialog'}">注册</button>
    </div>
</header>

<!-- 登录 -->
<div class="mdui-dialog mc-account mc-login" id="login_dialog">
    <div>
        <button type="button" id="closeLogin" mdui-dialog-close="{target: '#login_dialog'}" class="mdui-btn mdui-btn-icon close" aria-label="关闭">
            <i class="mdui-icon material-icons">close</i>
        </button>
        <div class="mdui-dialog-title">登录</div>
    </div>
    <form id="login_form" autocomplete="on">
        <div class="mdui-textfield mdui-textfield-floating-label">
            <label class="mdui-textfield-label" for="username">账号（邮箱）</label>
            <input id="username" class="mdui-textfield-input" name="username" type="text" autocomplete="username" required>
        </div>
        <div class="mdui-textfield mdui-textfield-floating-label">
            <label class="mdui-textfield-label" for="password">密码</label>
            <input id="password" class="mdui-textfield-input" name="password" type="password" autocomplete="current-password" required>
        </div>
        <div class="actions mdui-clearfix">
            <button type="submit" id="login_btn" class="mdui-btn mdui-btn-raised mdui-color-theme action-btn">登录</button>
        </div>
    </form>
</div>

<!-- 注册 -->
<div class="mc-account mc-login mdui-dialog" id="register_dialog">
    <div>
        <button type="button" id="closeReg" mdui-dialog-close="{target: '#register_dialog'}" class="mdui-btn mdui-btn-icon close" aria-label="关闭">
            <i class="mdui-icon material-icons">close</i>
        </button>
        <div class="mdui-dialog-title">创建新账号</div>
    </div>
    <form id="register_form" autocomplete="on">
        <div class="mdui-textfield mdui-textfield-floating-label">
            <label class="mdui-textfield-label" for="reg_username">用户名（也是你的二级域名名字）</label>
            <input id="reg_username" class="mdui-textfield-input" name="username" type="text" autocomplete="username" required>
        </div>
        <div class="mdui-textfield mdui-textfield-floating-label">
            <label class="mdui-textfield-label" for="reg_password">密码</label>
            <input id="reg_password" class="mdui-textfield-input" name="password" type="password" autocomplete="new-password" required>
        </div>
        <div class="actions mdui-clearfix">
            <button type="submit" id="reg_btn" class="mdui-btn mdui-btn-raised mdui-color-theme action-btn">注册并登录</button>
        </div>
        <div class="mdui-textfield" style="padding-top: 0;">
            <span class="mdui-textfield-helper">注册即表示同意站点使用申明；账号即二级域名。</span>
        </div>
    </form>
</div>

<script>
    (function () {
        'use strict';

        /* ---------- 主题切换 ---------- */
        /* 图标路径以常量数组保存，运行时用 DOM API 构造 SVG，避免任何 innerHTML 解析。 */
        var SUN_D = [
            'M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10',
            'M12 1v3M12 20v3M4.2 4.2l2.1 2.1M17.7 17.7l2.1 2.1M1 12h3M20 12h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1'
        ];
        var MOON_D = ['M21 13A9 9 0 1 1 11 3a7 7 0 0 0 10 10z'];

        function currentTheme() {
            return document.documentElement.getAttribute('data-theme') === 'dark' ? 'dark' : 'light';
        }

        function setThemeIcon(theme) {
            var icon = document.getElementById('themeIcon');
            if (!icon) { return; }
            var paths = theme === 'dark' ? MOON_D : SUN_D;
            while (icon.firstChild) { icon.removeChild(icon.firstChild); }
            for (var i = 0; i < paths.length; i++) {
                var path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
                path.setAttribute('d', paths[i]);
                icon.appendChild(path);
            }
        }

        function applyTheme(theme) {
            document.documentElement.setAttribute('data-theme', theme);
            document.documentElement.style.colorScheme = theme;
            setThemeIcon(theme);
        }

        applyTheme(currentTheme());
        var toggle = document.getElementById('themeToggle');
        if (toggle) {
            toggle.addEventListener('click', function () {
                var next = currentTheme() === 'dark' ? 'light' : 'dark';
                try { localStorage.setItem('px_site_theme', next); } catch (e) { /* 忽略 */ }
                applyTheme(next);
            });
        }

        /* ---------- 会话 Cookie ----------
         * 【安全修复】旧实现把「账号|明文密码」写进 cookie（authUser=...），
         * 明文密码可被任意脚本读取并随请求发送。
         * 【安全修复 J9】user_session 现在由**服务端**在 /user/login、/user/reg
         * 的响应里用 Set-Cookie 下发（Path=/; HttpOnly; SameSite=Lax，HTTPS 时再加 Secure）。
         * JS 通过 document.cookie 写入的 Cookie 永远不可能是 HttpOnly，
         * 因此这里不再由前端设置会话 Cookie，只负责清理历史遗留的明文凭据 Cookie。 */
        function saveSession(result) {
            // 清理历史版本遗留的明文凭据 cookie
            document.cookie = 'authUser=; path=/; max-age=0';
        }

        function submit($btn, url, data, $close, $errorHost) {
            $btn.prop('disabled', true);
            $.post(url, data, function (result) {
                if (result.code === 200) {
                    saveSession(result);
                    location.href = '/index/index';
                } else {
                    if ($close) { $close.click(); }
                    mdui.alert(result.msg || '操作失败');
                }
            }).fail(function () {
                if ($errorHost) { $errorHost.text('网络错误，请稍后重试'); }
                mdui.alert('网络错误，请稍后重试');
            }).always(function () {
                $btn.prop('disabled', false);
            });
        }

        $(function () {
            $('#login_form').on('submit', function (ev) {
                ev.preventDefault();
                var username = $('#username').val();
                var password = $('#password').val();
                if (!username || !password) { return; }
                submit($('#login_btn'), '/user/login', { username: username, password: password }, $('#closeLogin'));
            });

            $('#register_form').on('submit', function (ev) {
                ev.preventDefault();
                var username = $('#reg_username').val();
                var password = $('#reg_password').val();
                if (!username || !password) { return; }
                submit($('#reg_btn'), '/user/reg', { username: username, password: password }, $('#closeReg'));
            });
        });
    })();
</script>

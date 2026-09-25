<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0,maximum-scale=1.0, user-scalable=no"/>
    <meta http-equiv="X-UA-Compatible" content="IE=edge,chrome=1"/>
    <meta name="renderer" content="webkit">
    <meta http-equiv="Cache-Control" content="no-siteapp"/>
    <meta name="color-scheme" content="light dark">
    <link rel="stylesheet" href="/common/css/mdui.min.css"/>
    <link rel="stylesheet" href="/common/css/paging.css"/>
    <#--  站点设计令牌与门户样式：与 / 落地页共用同一套变量（index.css 里定义 :root 令牌）  -->
    <link rel="stylesheet" href="/index/css/index.css"/>
    <script src="/common/js/jquery.min.js"></script>
    <script src="/common/js/paging.js"></script>
    <script src="/common/js/mdui.min.js"></script>
    <title>Proxy内网穿透</title>
    <script>
        /* 主题引导：与落地页共用 localStorage 键 px_site_theme，避免登录后页面主题跳变。
           纯静态脚本，不含任何服务端插值。 */
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
<body class="mdui-drawer-body-left mdui-appbar-with-toolbar  mdui-theme-primary-indigo mdui-theme-accent-pink mdui-theme-layout-auto">
<header class="mdui-appbar mdui-appbar-fixed">
    <div class="mdui-toolbar mdui-color-theme">
        <span class="mdui-btn mdui-btn-icon mdui-ripple mdui-ripple-white"
              mdui-drawer="{target: '#main-drawer', swipe: true}"><i class="mdui-icon material-icons">menu</i></span>
        <a href="/index/index" class="mdui-typo-headline mdui-hidden-xs">Proxy内网穿透</a>
        <div class="mdui-toolbar-spacer"></div>
        <#--  快捷入口：我的用量 / 导出自己的流量数据（身份由服务端会话决定）  -->
        <a class="mdui-btn mdui-btn-dense mdui-ripple mdui-hidden-xs" href="/index/usage">我的用量</a>
        <a class="mdui-btn mdui-btn-dense mdui-ripple mdui-hidden-xs"
           href="/index/export?type=statistics&amp;format=csv">导出数据</a>
        <button type="button" class="theme-toggle" id="themeToggle" aria-label="切换深色 / 浅色主题" title="切换主题">
            <svg viewBox="0 0 24 24" id="themeIcon" aria-hidden="true"><path d="M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10"/><path d="M12 1v3M12 20v3M4.2 4.2l2.1 2.1M17.7 17.7l2.1 2.1M1 12h3M20 12h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1"/></svg>
        </button>
        <div id="login_out" class=" mdui-btn mdui-btn-dense">退出</div>
    </div>
</header>
<div class="mdui-drawer" id="main-drawer">
    <div class="mdui-list" mdui-collapse="{accordion: true}" style="margin-bottom: 76px;">
        <div class="mdui-collapse-item mdui-collapse-item-open">
            <a class="mdui-collapse-item-header mdui-list-item mdui-ripple" href="/index/index">
                <i class="mdui-list-item-icon mdui-icon material-icons mdui-text-color-blue">near_me</i>
                <div class="mdui-list-item-content">服务列表</div>
            </a>
            <a class="mdui-collapse-item mdui-collapse-item-open" href="/index/log">
                <div class="mdui-collapse-item-header mdui-list-item mdui-ripple">
                    <i class="mdui-list-item-icon mdui-icon material-icons mdui-text-color-blue">layers</i>
                    <div class="mdui-list-item-content">穿透日志</div>
                </div>
            </a>
            <a class="mdui-collapse-item mdui-collapse-item-open" href="/index/usage">
                <div class="mdui-collapse-item-header mdui-list-item mdui-ripple">
                    <i class="mdui-list-item-icon mdui-icon material-icons mdui-text-color-blue">insert_chart</i>
                    <div class="mdui-list-item-content">我的用量</div>
                </div>
            </a>
            <a class="mdui-collapse-item mdui-collapse-item-open" href="/index/export?type=statistics&amp;format=csv">
                <div class="mdui-collapse-item-header mdui-list-item mdui-ripple">
                    <i class="mdui-list-item-icon mdui-icon material-icons mdui-text-color-blue">file_download</i>
                    <div class="mdui-list-item-content">导出我的流量（CSV）</div>
                </div>
            </a>
            <a class="mdui-collapse-item mdui-collapse-item-open" href="/index/export?type=config&amp;format=json">
                <div class="mdui-collapse-item-header mdui-list-item mdui-ripple">
                    <i class="mdui-list-item-icon mdui-icon material-icons mdui-text-color-blue">file_download</i>
                    <div class="mdui-list-item-content">导出隧道配置（JSON，不含口令）</div>
                </div>
            </a>
        </div>
    </div>
</div>

<script>
    /*
     * 【安全修复 J9】登出必须让**服务端会话失效**。
     * 原实现只是用 JS 删掉浏览器 Cookie，服务端 UserSessionStore 里的会话仍然有效
     * （空闲 12 小时过期），被窃取的 Cookie 在登出后依然能用。
     * 现在先 POST /user/logout（服务端作废会话 + 下发 Max-Age=0 清 Cookie），
     * 请求失败也照样跳转，保证登出按钮始终可用。
     */
    function clearLegacyAuthCookie() {
        // 仅清理历史版本遗留的明文凭据 Cookie（authUser=账号|密码）
        document.cookie = 'authUser=; path=/; max-age=0';
    }
    $(function () {
        $("#login_out").click(function () {
            function finish() {
                clearLegacyAuthCookie();
                location.href = '/';
            }
            $.post('/user/logout').always(finish);
        })
    })

    /* ---------- 主题切换（与落地页共用 px_site_theme，行为保持一致） ---------- */
    (function () {
        'use strict';
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
            icon.textContent = '';
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
            // 通知需要跟随主题重绘的组件（例如「我的用量」的自绘 Canvas 图表）
            try {
                document.dispatchEvent(new CustomEvent('px:themechange', { detail: { theme: theme } }));
            } catch (e) { /* 老浏览器忽略 */ }
        }

        applyTheme(currentTheme());
        var toggle = document.getElementById('themeToggle');
        if (toggle) {
            toggle.addEventListener('click', function () {
                var next = currentTheme() === 'dark' ? 'light' : 'dark';
                try { localStorage.setItem('px_site_theme', next); } catch (e) { /* 隐私模式下忽略 */ }
                applyTheme(next);
            });
        }
    })();
</script>

<#--  管理后台公共外壳：顶栏 + 分组导航抽屉 + 主题控制 + 退出登录
      静态资源路径 / 导航路由与原模板完全一致，只替换视觉与交互实现。
      主题初始化脚本为纯静态内联脚本（不含任何服务端插值），用于消除首屏闪烁。  -->
<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0"/>
    <meta http-equiv="X-UA-Compatible" content="IE=edge,chrome=1"/>
    <meta name="renderer" content="webkit">
    <meta http-equiv="Cache-Control" content="no-siteapp"/>
    <title>Proxy内网穿透</title>
    <script>
        /* 首屏主题：与 admin.js 使用同一个 localStorage 键（px_admin_theme），
           仅做字符串比较取值，不拼接任何服务端数据。 */
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
    <link rel="stylesheet" href="/common/css/mdui.min.css"/>
    <link rel="stylesheet" href="/common/css/paging.css"/>
    <link rel="stylesheet" href="/common/css/admin.css"/>
    <script src="/common/js/jquery.min.js"></script>
    <script src="/common/js/paging.js"></script>
    <script src="/common/js/mdui.min.js"></script>
    <script src="/common/js/admin.js"></script>
    <#--  纯前端二维码编码器：仅「自动穿透」页的分享功能使用，无外部依赖  -->
    <script src="/common/js/qrcode.js"></script>
</head>
<body class="mdui-theme-layout-auto">
<header class="admin-appbar">
    <button type="button" class="btn btn--ghost btn--icon show-md" data-nav-toggle aria-label="打开菜单">
        <span data-icon="menu"></span>
    </button>
    <a class="admin-appbar__title" href="/admin/proxy">
        <span class="brand__mark"><span data-icon="rocket" data-icon-class="icon--sm"></span></span>
        <span class="truncate">内网穿透</span>
        <span class="brand__sub" data-page-title></span>
    </a>
    <div class="admin-appbar__actions">
        <span id="themeSlot" class="icon-slot"></span>
        <a class="btn btn--ghost btn--sm" href="/admin/logout">
            <span data-icon="logout" data-icon-class="icon--sm"></span>
            <span class="btn__label">退出登录</span>
        </a>
    </div>
</header>

<div class="admin-shell">
    <aside class="admin-drawer" id="main-drawer" aria-label="后台导航">
        <nav class="nav">
            <div class="nav__group">穿透服务</div>
            <a class="nav__link" href="/admin/proxy">
                <span data-icon="layers"></span><span>穿透集群</span>
            </a>
            <a class="nav__link" href="/admin/config">
                <span data-icon="sync"></span><span>自动穿透</span>
            </a>

            <div class="nav__group">用户与域名</div>
            <a class="nav__link" href="/admin/user">
                <span data-icon="users"></span><span>用户管理</span>
            </a>
            <a class="nav__link" href="/admin/domain">
                <span data-icon="globe"></span><span>域名管理</span>
            </a>
            <a class="nav__link" href="/admin/log">
                <span data-icon="list"></span><span>用户日志</span>
            </a>

            <div class="nav__group">内容与版本</div>
            <a class="nav__link" href="/admin/tips">
                <span data-icon="info"></span><span>公告管理</span>
            </a>
            <a class="nav__link" href="/admin/core">
                <span data-icon="cpu"></span><span>内核版本</span>
            </a>
            <a class="nav__link" href="/admin/app">
                <span data-icon="box"></span><span>app版本</span>
            </a>

            <div class="nav__group">运营配置</div>
            <a class="nav__link" href="/admin/reg">
                <span data-icon="key"></span><span>注册管理</span>
            </a>
            <a class="nav__link" href="/admin/pay">
                <span data-icon="coins"></span><span>打赏管理</span>
            </a>

            <div class="nav__foot">
                <a class="nav__link nav__link--danger" href="/admin/logout" style="margin-bottom:.5rem">
                    <span data-icon="logout" data-icon-class="icon--sm"></span><span>退出登录</span>
                </a>
                <div>Proxy 内网穿透 · 管理后台</div>
            </div>
        </nav>
    </aside>
    <div class="scrim" id="drawer-scrim" data-nav-close></div>
    <div class="admin-main">

<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0,maximum-scale=1.0, user-scalable=no"/>
    <meta http-equiv="X-UA-Compatible" content="IE=edge,chrome=1"/>
    <meta name="renderer" content="webkit">
    <meta http-equiv="Cache-Control" content="no-siteapp"/>
    <link rel="stylesheet" href="/common/css/mdui.min.css"/>
    <link rel="stylesheet" href="/common/css/paging.css"/>
    <script src="/common/js/jquery.min.js"></script>
    <script src="/common/js/paging.js"></script>
    <script src="/common/js/mdui.min.js"></script>
    <title>Proxy内网穿透</title>
</head>
<body class="mdui-drawer-body-left mdui-appbar-with-toolbar  mdui-theme-primary-indigo mdui-theme-accent-pink mdui-theme-layout-auto">
<header class="mdui-appbar mdui-appbar-fixed">
    <div class="mdui-toolbar mdui-color-theme">
        <span class="mdui-btn mdui-btn-icon mdui-ripple mdui-ripple-white"
              mdui-drawer="{target: '#main-drawer', swipe: true}"><i class="mdui-icon material-icons">menu</i></span>
        <a href="/index/index" class="mdui-typo-headline mdui-hidden-xs">Proxy内网穿透</a>
        <div class="mdui-toolbar-spacer"></div>
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
</script>
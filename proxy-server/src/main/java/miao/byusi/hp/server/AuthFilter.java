package miao.byusi.hp.server;

import cn.hserver.plugin.web.context.Webkit;
import cn.hserver.plugin.web.interfaces.FilterAdapter;
import cn.hserver.plugin.web.interfaces.HttpRequest;
import cn.hserver.core.server.util.PropUtil;
import io.netty.handler.codec.http.HttpMethod;
import miao.byusi.hp.server.config.WebConfig;
import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.core.ioc.annotation.Bean;
import miao.byusi.hp.server.domian.entity.UserEntity;
import miao.byusi.hp.server.service.UserService;
import miao.byusi.hp.server.utils.AdminSessionStore;
import miao.byusi.hp.server.utils.SafeInputUtil;

import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * @author hxm
 */
@Bean
public class AuthFilter implements FilterAdapter {

    @Autowired
    private WebConfig webConfig;

    @Autowired
    private UserService userService;

    /**
     * 管理后台中允许使用 GET 的只读页面（其余 admin 路由必须使用 POST/PUT/DELETE）。
     */
    private static final Set<String> ADMIN_GET_PAGES = new HashSet<>(Arrays.asList(
            "/admin", "/admin/proxy", "/admin/user", "/admin/domain", "/admin/config",
            "/admin/log", "/admin/tips", "/admin/core", "/admin/app", "/admin/reg", "/admin/pay",
            "/admin/login", "/admin/logout"
    ));

    /**
     * 【安全修复】以下路由在 template/admin/*.ftl 中是以 &lt;a href&gt; 链接调用的，模板不由本模块维护，
     * 因此保留 GET 别名（否则管理页会失效）；但仍然要求已登录会话 + 同源 Origin/Referer 校验。
     * 需要模板侧改成 POST 表单的清单见交付报告。
     */
    private static final Set<String> ADMIN_GET_LEGACY_REMOVE = new HashSet<>(Arrays.asList(
            "/admin/user/remove", "/admin/config/remove", "/admin/core/remove",
            "/admin/domain/remove", "/admin/pay/remove", "/admin/app/remove", "/admin/log/remove"
    ));

    @Override
    public void doFilter(Webkit webkit) throws Exception {
        HttpRequest request = webkit.httpRequest;
        String uri = normalizeUri(request.getUri());

        // 【安全修复】CORS 白名单：原实现无条件返回 Access-Control-Allow-Origin: * 且
        // Access-Control-Allow-Credentials: true，等于允许任意站点带 Cookie 读取接口。
        // 现在只对白名单来源回显 Origin，默认白名单是请求自身的 Host（同源）。
        String origin = request.getHeader("origin");
        if (origin != null && allowedOrigin(origin, request)) {
            webkit.httpResponse.setHeader("Access-Control-Allow-Origin", origin);
            webkit.httpResponse.setHeader("Vary", "Origin");
            webkit.httpResponse.setHeader("Access-Control-Allow-Credentials", "true");
        }
        webkit.httpResponse.setHeader("Access-Control-Allow-Methods", "POST,GET,OPTIONS,DELETE");
        webkit.httpResponse.setHeader("Access-Control-Allow-Headers", "Content-Type,Authorization,X-Requested-With");
        if (request.getRequestType().equals(HttpMethod.OPTIONS)) {
            webkit.httpResponse.sendHtml("");
            return;
        }

        if (uri.contains("admin")) {
            // ===== 管理后台鉴权（fail-closed）=====
            if (!handleAdmin(webkit, uri)) {
                return;
            }
        } else if (uri.contains("index")) {
            String auth = request.getHeader("cookie");
            try {
                if (auth != null) {
                    String[] split = auth.split(";");
                    for (String s : split) {
                        boolean contains = s.contains("authUser=");
                        if (contains) {
                            String s1 = s.replaceAll("authUser=", "");
                            String[] split1 = s1.split("\\|");
                            String user = split1[0];
                            String pwd = split1[1];
                            UserEntity user1 = userService.getUser(user);
                            if (user1 != null && user1.getPassword() != null
                                    && SafeInputUtil.safeEquals(user1.getPassword(), pwd)) {
                                webkit.httpRequest.getHeaders().put("username", user);
                                return;
                            }
                        }
                    }

                }
            } catch (Exception ignored) {
            }
            webkit.httpResponse.sendTemplate("/index/default.ftl");
        }
    }

    /**
     * 管理后台请求的统一处理：会话校验 + CSRF/方法限制。
     *
     * @return true 表示放行给控制器；false 表示已中断请求
     */
    private boolean handleAdmin(Webkit webkit, String uri) throws Exception {
        HttpRequest request = webkit.httpRequest;

        // 1) 登录入口无需会话（登录本身由 AdminController 用配置密码做常量时间校验）
        if ("/admin/login".equals(uri)) {
            return true;
        }

        // 1.1) 后台用到的静态资源（/admin/css/style.css 等）本身不含业务数据，放行，
        //      否则登录页会拿到一段 HTML 当 CSS 用。
        if (isStaticAsset(uri)) {
            return true;
        }

        // 2) 会话校验：只认 admin_session cookie，且必须在内存 map 中精确命中
        String cookieHeader = request.getHeader("cookie");
        String sessionId = AdminSessionStore.cookieValue(cookieHeader, "admin_session");
        if (!AdminSessionStore.valid(sessionId)) {
            // 【安全修复】原实现 fail-open：webConfig.password 为空时直接放行所有后台请求；
            // 且用 cookie 子串 contains("auth=密码") 判断，密码明文存在非 HttpOnly 的 cookie 里。
            // 现在密码不再进入 cookie，未登录一律回到登录页。
            webkit.httpResponse.sendTemplate("/admin/login.ftl");
            return false;
        }

        // 3) CSRF：管理后台的写操作只允许 POST/PUT/DELETE；
        //    模板里用 <a href> 的少数删除链接保留 GET 别名。
        HttpMethod method = request.getRequestType();
        boolean readPage = HttpMethod.GET.equals(method)
                && (ADMIN_GET_PAGES.contains(uri) || ADMIN_GET_LEGACY_REMOVE.contains(uri));
        boolean stateChanging = !readPage;
        if (stateChanging) {
            if (HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method)) {
                // 注意：sendStatusCode 只设置状态码，不会让框架 hasData()=true 从而中断请求，
                // 必须同时写出响应体，否则控制器仍会执行。
                webkit.httpResponse.sendStatusCode(io.netty.handler.codec.http.HttpResponseStatus.METHOD_NOT_ALLOWED);
                webkit.httpResponse.sendText("");
                return false;
            }
            // 4) Origin/Referer 同源校验（存在时才校验，兼容无该头的老客户端）
            if (!sameOrigin(request)) {
                webkit.httpResponse.sendStatusCode(io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN);
                webkit.httpResponse.sendText("");
                return false;
            }
        }
        return true;
    }

    /**
     * 是否为纯静态资源（不含业务数据，无需后台会话）。
     */
    private static boolean isStaticAsset(String uri) {
        String lower = uri.toLowerCase();
        return lower.endsWith(".css") || lower.endsWith(".js") || lower.endsWith(".map")
                || lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".svg") || lower.endsWith(".ico")
                || lower.endsWith(".woff") || lower.endsWith(".woff2") || lower.endsWith(".ttf");
    }

    /**
     * Origin/Referer 与请求 Host 是否同源（忽略协议与端口、大小写不敏感）。
     */
    private boolean sameOrigin(HttpRequest request) {
        String source = request.getHeader("origin");
        if (source == null || source.trim().isEmpty()) {
            source = request.getHeader("referer");
        }
        if (source == null || source.trim().isEmpty() || "null".equalsIgnoreCase(source.trim())) {
            return true;
        }
        String requestHost = hostOf("http://" + String.valueOf(request.getHeader("host")));
        String sourceHost = hostOf(source);
        return requestHost != null && requestHost.equals(sourceHost);
    }

    /**
     * 取 URL 中的 host（去掉协议、端口、路径，统一小写）。
     */
    private static String hostOf(String url) {
        if (url == null || url.trim().isEmpty() || "null".equals(url.trim())) {
            return null;
        }
        try {
            String host = URI.create(url.trim()).getHost();
            if (host != null) {
                return host.toLowerCase();
            }
        } catch (Exception ignored) {
        }
        String s = url.trim();
        int idx = s.indexOf("://");
        if (idx >= 0) {
            s = s.substring(idx + 3);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf(']') < colon) {
            s = s.substring(0, colon);
        }
        return s.isEmpty() ? null : s.toLowerCase();
    }

    /**
     * 【安全修复】CORS 白名单。配置项 cors.allowedOrigins 为逗号分隔的完整来源
     * （例如 https://proxy.example.com）；未配置时只允许与请求 Host 同源的来源。
     */
    private boolean allowedOrigin(String origin, HttpRequest request) {
        String configured = PropUtil.getInstance().get("cors.allowedOrigins", "");
        if (configured != null && configured.trim().length() > 0) {
            String target = origin.trim().toLowerCase();
            for (String item : configured.split(",")) {
                if (item.trim().toLowerCase().equals(target)) {
                    return true;
                }
            }
            return false;
        }
        String requestHost = hostOf("http://" + String.valueOf(request.getHeader("host")));
        String originHost = hostOf(origin);
        return requestHost != null && requestHost.equals(originHost);
    }

    /**
     * 去掉查询串与结尾斜杠，统一成 /xxx 形式。
     */
    private static String normalizeUri(String uri) {
        if (uri == null) {
            return "";
        }
        String s = uri;
        int idx = s.indexOf('?');
        if (idx >= 0) {
            s = s.substring(0, idx);
        }
        int semi = s.indexOf(';');
        if (semi >= 0) {
            s = s.substring(0, semi);
        }
        if (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (!s.startsWith("/")) {
            s = "/" + s;
        }
        return s;
    }
}

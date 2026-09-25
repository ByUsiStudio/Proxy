package miao.byusi.hp.server.controller.admin;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.plugin.web.annotation.Controller;
import cn.hserver.plugin.web.annotation.GET;
import cn.hserver.plugin.web.annotation.POST;
import cn.hserver.plugin.web.interfaces.HttpRequest;
import cn.hserver.plugin.web.interfaces.HttpResponse;
import miao.byusi.hp.server.config.WebConfig;
import miao.byusi.hp.server.utils.AdminSessionStore;
import miao.byusi.hp.server.utils.SafeInputUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

@Controller
public class AdminController {
    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    @Autowired
    private WebConfig webConfig;

    @GET("/admin")
    public void index(HttpResponse response) {
        response.redirect("/admin/proxy");
    }

    /**
     * 【安全修复】管理后台登录。
     * 原实现由前端 JS 直接把密码写进 cookie（auth=密码），既非 HttpOnly 也会被校验逻辑子串匹配。
     * 现在改为服务端校验密码并下发随机会话ID。
     * 契约：表单字段 password；失败时渲染登录页；成功跳转 /admin/proxy。
     */
    @POST("/admin/login")
    public void login(String password, HttpRequest request, HttpResponse response) {
        String configured = webConfig.getPassword();
        // fail-closed：未配置后台密码时一律拒绝，不再“密码为空就放行”
        if (SafeInputUtil.isBlank(configured) || SafeInputUtil.isBlank(password)
                || !SafeInputUtil.safeEquals(configured.trim(), password.trim())) {
            log.warn("后台登录失败，来源IP：{}", request.getIpAddress());
            Map<String, Object> data = new HashMap<>(2);
            data.put("error", SafeInputUtil.isBlank(configured) ? "后台密码未配置，请先设置 app.properties 的 password" : "密码错误");
            response.sendTemplate("/admin/login.ftl", data);
            return;
        }
        AdminSessionStore.cleanExpired();
        String sessionId = AdminSessionStore.create();
        response.setHeader("Set-Cookie", buildCookie(sessionId, request) + "; Max-Age=1800");
        response.redirect("/admin/proxy");
    }

    /**
     * 【安全修复】登出：作废服务端会话并清空 cookie，然后回到登录页。
     */
    @GET("/admin/logout")
    public void logout(HttpRequest request, HttpResponse response) {
        String sessionId = AdminSessionStore.cookieValue(request.getHeader("cookie"), "admin_session");
        AdminSessionStore.invalidate(sessionId);
        response.setHeader("Set-Cookie", buildCookie("", request) + "; Max-Age=0");
        response.redirect("/admin/login");
    }

    /**
     * 组装后台会话 cookie：Path=/; HttpOnly; SameSite=Strict（https 时追加 Secure）。
     */
    private String buildCookie(String sessionId, HttpRequest request) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("admin_session=").append(sessionId).append("; Path=/; HttpOnly; SameSite=Strict");
        if (isHttps(request)) {
            sb.append("; Secure");
        }
        return sb.toString();
    }

    private boolean isHttps(HttpRequest request) {
        String proto = request.getHeader("x-forwarded-proto");
        if (proto != null && proto.trim().equalsIgnoreCase("https")) {
            return true;
        }
        try {
            return request.getPort() == 443;
        } catch (Exception e) {
            return false;
        }
    }
}

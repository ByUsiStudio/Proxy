package miao.byusi.hp.server.controller.admin;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.plugin.web.annotation.Controller;
import cn.hserver.plugin.web.annotation.GET;
import cn.hserver.plugin.web.annotation.POST;
import cn.hserver.plugin.web.interfaces.HttpRequest;
import cn.hserver.plugin.web.interfaces.HttpResponse;
import miao.byusi.hp.server.config.WebConfig;
import miao.byusi.hp.server.service.AdminDashboardService;
import miao.byusi.hp.server.service.AuditService;
import miao.byusi.hp.server.utils.AdminAudit;
import miao.byusi.hp.server.utils.AdminSessionStore;
import miao.byusi.hp.server.utils.LoginFailureLimiter;
import miao.byusi.hp.server.utils.NetUtil;
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

    /** 仪表盘数据装配：/admin 与 /admin/dashboard 共用 */
    @Autowired
    private AdminDashboardService adminDashboardService;

    /** 审计日志：后台登录成功/失败、登出都必须留痕 */
    @Autowired
    private AuditService auditService;

    /**
     * 后台首页：直接渲染仪表盘。
     * <p>
     * 原先这里 302 到 /admin/proxy（穿透集群），把「集群页」当成了首页；
     * 现在首页是仪表盘，需要看节点详情再从导航进入 /admin/proxy（该入口保持不变）。
     */
    @GET("/admin")
    public void index(HttpResponse response) {
        response.sendTemplate("/admin/dashboard.ftl", adminDashboardService.pageModel());
    }

    /**
     * 【安全修复】管理后台登录。
     * 原实现由前端 JS 直接把密码写进 cookie（auth=密码），既非 HttpOnly 也会被校验逻辑子串匹配。
     * 现在改为服务端校验密码并下发随机会话ID。
     * 契约：表单字段 password；失败时渲染登录页；成功跳转 /admin/dashboard。
     * <p>
     * 【审计】登录成功与失败都写审计（action=login.success / login.fail）。
     * <b>提交的口令本身绝不进入审计</b>——不记录口令、口令长度、口令哈希，
     * 只记录来源 IP、User-Agent（由 AuditService 从请求里取）与失败原因类别；
     * 是否「密码错」还是「未配置」属于运维关心的信息，但不构成凭据泄露。
     */
    @POST("/admin/login")
    public void login(String password, HttpRequest request, HttpResponse response) {
        // 【安全修复 J4】后台登录同样做失败限流（账号固定为 admin，按来源IP计数）
        String clientIp = NetUtil.clientIp(request);
        if (LoginFailureLimiter.isBlocked("admin", clientIp)) {
            log.warn("后台登录已被临时限流，来源IP：{}", clientIp);
            AdminAudit.record(auditService, request, "login.fail", AdminAudit.ACTOR,
                    "登录失败：来源 IP 已被临时限流", false);
            Map<String, Object> data = new HashMap<>(2);
            data.put("error", "登录失败次数过多，请稍后再试");
            response.sendTemplate("/admin/login.ftl", data);
            return;
        }
        String configured = webConfig.getPassword();
        // fail-closed：未配置后台密码时一律拒绝，不再“密码为空就放行”
        if (SafeInputUtil.isBlank(configured) || SafeInputUtil.isBlank(password)
                || !SafeInputUtil.safeEquals(configured.trim(), password.trim())) {
            LoginFailureLimiter.recordFailure("admin", clientIp);
            log.warn("后台登录失败，来源IP：{}", clientIp);
            boolean notConfigured = SafeInputUtil.isBlank(configured);
            AdminAudit.record(auditService, request, "login.fail", AdminAudit.ACTOR,
                    notConfigured ? "登录失败：后台密码未配置" : "登录失败：口令校验未通过", false);
            Map<String, Object> data = new HashMap<>(2);
            data.put("error", notConfigured ? "后台密码未配置，请先设置 app.properties 的 password" : "密码错误");
            response.sendTemplate("/admin/login.ftl", data);
            return;
        }
        LoginFailureLimiter.clear("admin", clientIp);
        AdminSessionStore.cleanExpired();
        String sessionId = AdminSessionStore.create();
        response.setHeader("Set-Cookie", buildCookie(sessionId, request) + "; Max-Age=1800");
        AdminAudit.record(auditService, request, "login.success", AdminAudit.ACTOR,
                "后台登录成功", true);
        response.redirect("/admin/dashboard");
    }

    /**
     * 【安全修复】登出：作废服务端会话并清空 cookie，然后回到登录页。
     * <p>
     * 【审计】登出同样留痕，便于把「谁在什么时候结束了会话」与后续操作串起来。
     */
    @GET("/admin/logout")
    public void logout(HttpRequest request, HttpResponse response) {
        String sessionId = AdminSessionStore.cookieValue(request.getHeader("cookie"), "admin_session");
        AdminSessionStore.invalidate(sessionId);
        response.setHeader("Set-Cookie", buildCookie("", request) + "; Max-Age=0");
        AdminAudit.record(auditService, request, "admin.logout", AdminAudit.ACTOR,
                "后台退出登录", true);
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

    /**
     * 【安全修复 J13】判断当前请求是否为 HTTPS。
     * <p>
     * 原实现直接相信 {@code x-forwarded-proto}（任何调用方都能伪造），
     * 否则比较 {@code request.getPort() == 443}——但 HServer 的 getPort() 返回的是
     * **客户端源端口**，该判断几乎恒为 false。现在统一交给
     * {@link NetUtil#isHttps}：本机 TLS 以 pipeline 中的 SslHandler 为准，
     * 仅在 trusted.proxy=true 时才参考 X-Forwarded-Proto，
     * 无法判断时按明文 HTTP 处理（不追加 Secure，避免纯 HTTP 部署不可用）。
     */
    private boolean isHttps(HttpRequest request) {
        return NetUtil.isHttps(request);
    }
}

package miao.byusi.hp.server.utils;

import cn.hserver.plugin.web.interfaces.HttpRequest;
import miao.byusi.hp.server.service.AuditService;

/**
 * 后台管理操作的审计留痕助手。
 * <p>
 * 为什么需要它：
 * <ul>
 *   <li><b>身份统一</b>：后台没有独立的「管理员账号表」，登录只校验 app.properties 里的单一密码，
 *       因此所有后台操作的 actor 固定为 {@link #ACTOR}。集中在一个常量里，
 *       将来接入多管理员体系时只需改这一处，而不用去每个控制器里找散落的字符串。</li>
 *   <li><b>不吞异常</b>：{@link AuditService#record} 本身永不抛异常、永不阻塞
 *       （见 AuditServiceImpl：有界队列 + 守护写线程），因此审计调用可以直接放在业务代码的
 *       正常路径与异常路径上，不需要额外的 try/catch 保护，也绝不会影响业务主流程。</li>
 * </ul>
 * <b>铁律</b>：{@code detail} 只允许写业务标识（用户名、id、文件名、条目数、字段摘要），
 * <b>严禁写入口令、令牌、会话 ID、完整请求体</b>——哪怕只是口令的长度。
 * AuditServiceImpl 里还有一层正则脱敏兜底（password=/token=/secret= → ***），
 * 但那是「调用方写错时的最后一道防线」，不是可以依赖的常规手段。
 */
public final class AdminAudit {

    /** 操作者类型：后台管理员 */
    public static final String ACTOR_TYPE = "admin";

    /** 操作者标识：后台没有账号体系，统一使用固定标识，避免与站点用户混淆 */
    public static final String ACTOR = "admin";

    private AdminAudit() {
    }

    /**
     * 记录一条后台操作审计。
     *
     * @param auditService 审计服务（可为 null，为 null 时静默跳过，便于单元测试/降级）
     * @param request      当前请求，用于取真实 TCP 对端 IP 与 User-Agent，可为 null
     * @param action       动作标识，如 user.add / config.import
     * @param target       操作目标（用户名 / id / 文件名），可为 null 或空
     * @param detail       简短的人类可读摘要，<b>不得包含任何凭据</b>
     * @param success      业务是否成功
     */
    public static void record(AuditService auditService, HttpRequest request, String action,
                              String target, String detail, boolean success) {
        if (auditService == null) {
            return;
        }
        auditService.record(ACTOR_TYPE, ACTOR, action, target, detail, success ? "ok" : "fail", request);
    }
}

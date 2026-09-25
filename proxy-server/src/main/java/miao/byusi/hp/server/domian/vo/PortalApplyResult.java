package miao.byusi.hp.server.domian.vo;

/**
 * 门户自助申请（域名 / 固定端口）的结果。
 * <p>
 * 设计说明（为什么没有「审批」这一步）：
 * 现有库表里没有审批流表，而本任务不允许改动 db/**（init.sql / migrate 脚本由其它 worker 负责），
 * 因此选定的实现是：校验收通过后<b>直接为该账号写入一条归属记录</b>
 * （sys_domain 的 custom_domain 留空 / sys_port 新增一行），
 * 并在 UI 上明确标注「已提交，等待解析生效」。
 * 真实的审批工作流需要新增一张审批表（申请单状态机 + 管理员审批页），属本轮范围外，
 * 已在 {@code PortalServiceImpl} 中留下中文注释说明。
 */
public class PortalApplyResult {

    private final boolean ok;
    private final String message;
    /** 成功时回显的值（域名 / 端口），失败为 null */
    private final String value;

    private PortalApplyResult(boolean ok, String message, String value) {
        this.ok = ok;
        this.message = message;
        this.value = value;
    }

    public static PortalApplyResult ok(String message, String value) {
        return new PortalApplyResult(true, message, value);
    }

    public static PortalApplyResult fail(String message) {
        return new PortalApplyResult(false, message, null);
    }

    public boolean isOk() {
        return ok;
    }

    public String getMessage() {
        return message == null ? "" : message;
    }

    public String getValue() {
        return value == null ? "" : value;
    }
}

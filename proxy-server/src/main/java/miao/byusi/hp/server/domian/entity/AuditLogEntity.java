package miao.byusi.hp.server.domian.entity;

import org.beetl.sql.annotation.entity.AssignID;
import org.beetl.sql.annotation.entity.Table;

/**
 * 审计日志（后台与开放接口的可追责留痕）。
 * <p>
 * 安全约定：{@code detail} 只记录业务标识（用户名、配置 id、文件名、条目数等），
 * <b>绝不写入任何口令、令牌、会话 ID 或完整请求体</b>。
 * 表名与字段名由 BeetlSQL 的 UnderlinedNameConversion 自动映射到 snake_case。
 */
@Table(name = "sys_audit_log")
public class AuditLogEntity {

    @AssignID
    private String id;

    /** 操作者账号（后台管理员账号，或站点用户/节点标识） */
    private String actor;

    /** 操作者类型：admin / user / node / system / anonymous */
    private String actorType;

    /** 动作标识，形如 login.success、user.remove、config.import、tunnel.batchStop */
    private String action;

    /** 操作目标（用户名、配置 id、文件名……），可为空 */
    private String target;

    /** 补充说明（已截断），不得包含敏感信息 */
    private String detail;

    /** 结果：ok / fail */
    private String result;

    /** 真实 TCP 对端 IP（不信任转发头） */
    private String ip;

    /** User-Agent（截断保存，仅用于排查异常客户端） */
    private String userAgent;

    /** 毫秒时间戳字符串 */
    private String createTime;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getActor() {
        return actor;
    }

    public void setActor(String actor) {
        this.actor = actor;
    }

    public String getActorType() {
        return actorType;
    }

    public void setActorType(String actorType) {
        this.actorType = actorType;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getDetail() {
        return detail;
    }

    public void setDetail(String detail) {
        this.detail = detail;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }
}

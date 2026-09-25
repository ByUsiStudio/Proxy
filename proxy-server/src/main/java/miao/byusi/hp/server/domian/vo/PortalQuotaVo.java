package miao.byusi.hp.server.domian.vo;

import org.beetl.sql.annotation.entity.AssignID;
import org.beetl.sql.annotation.entity.Table;

/**
 * 门户「我的用量」里的配额只读视图（{@code sys_quota} 表）。
 * <p>
 * 为什么用 VO + {@code @Table} 而不是直接复用其它模块的 QuotaEntity：
 * 本任务禁止改动/依赖其它 worker 正在并行编写的 {@code service/Quota*.java}，
 * 直接依赖对方未完成的类型会把编译结果绑死在别人的进度上。
 * 这里只做<b>只读查询</b>（按 user_id 精确匹配，参数绑定），
 * 表不存在（旧库未执行 migrate-v16.1.sql）时由服务层捕获异常并降级为「未设置配额」。
 */
@Table(name = "sys_quota")
public class PortalQuotaVo {

    @AssignID
    private String id;
    private String userId;
    private String username;
    private Integer maxTunnels;
    private Integer maxPorts;
    private Integer maxConns;
    private String monthlyReceive;
    private String monthlySend;
    private Integer enabled;
    private String overLimit;
    private String overReason;
    private String note;
    private String createTime;
    private String updateTime;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId == null ? "" : userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username == null ? "" : username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public Integer getMaxTunnels() {
        return maxTunnels;
    }

    public void setMaxTunnels(Integer maxTunnels) {
        this.maxTunnels = maxTunnels;
    }

    public Integer getMaxPorts() {
        return maxPorts;
    }

    public void setMaxPorts(Integer maxPorts) {
        this.maxPorts = maxPorts;
    }

    public Integer getMaxConns() {
        return maxConns;
    }

    public void setMaxConns(Integer maxConns) {
        this.maxConns = maxConns;
    }

    public String getMonthlyReceive() {
        return monthlyReceive == null ? "" : monthlyReceive;
    }

    public void setMonthlyReceive(String monthlyReceive) {
        this.monthlyReceive = monthlyReceive;
    }

    public String getMonthlySend() {
        return monthlySend == null ? "" : monthlySend;
    }

    public void setMonthlySend(String monthlySend) {
        this.monthlySend = monthlySend;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public String getOverLimit() {
        return overLimit == null ? "" : overLimit;
    }

    public void setOverLimit(String overLimit) {
        this.overLimit = overLimit;
    }

    public String getOverReason() {
        return overReason == null ? "" : overReason;
    }

    public void setOverReason(String overReason) {
        this.overReason = overReason;
    }

    public String getNote() {
        return note == null ? "" : note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getCreateTime() {
        return createTime == null ? "" : createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public String getUpdateTime() {
        return updateTime == null ? "" : updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }

    /** 是否启用（null 视为启用，与后台语义一致） */
    public boolean isEnabledFlag() {
        return enabled == null || enabled != 0;
    }

    /** 是否已超限 */
    public boolean isOver() {
        return "1".equals(overLimit) || "true".equalsIgnoreCase(overLimit);
    }
}

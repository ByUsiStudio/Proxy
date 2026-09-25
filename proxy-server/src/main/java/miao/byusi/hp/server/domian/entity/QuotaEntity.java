package miao.byusi.hp.server.domian.entity;

import org.beetl.sql.annotation.entity.AssignID;
import org.beetl.sql.annotation.entity.Table;

/**
 * 用量配额（表 sys_quota）。
 * <p>
 * 字段与 {@code db/init.sql} 完全对应，表名/列名由 BeetlSQL 的
 * {@code UnderlinedNameConversion} 自动完成 camelCase → snake_case 映射。
 * <p>
 * <b>为什么流量字段是 String：</b>sys_statistics / sys_config 等既有表在本项目里
 * 一贯把「数值」以 TEXT 存储（见 {@code StatisticsEntity.receive} 等），
 * sys_quota 的 monthly_receive / monthly_send 也一样是 TEXT。
 * 因此这里保持字符串保存「字节数」，读取时用 {@code NumberFormatException}
 * 兜底解析（损坏数据按 0 = 不限制处理），避免依赖数据库驱动做隐式类型转换。
 * <p>
 * <b>over_limit 为什么也是 String：</b>该列在 schema 中是 TEXT，
 * 这里用 "true"/"false" 两个字面量表达布尔语义，避免 SQLite 的 TEXT 亲和性
 * 把整数写回后产生 "1" 与 1 混用导致的读取歧义。
 */
@Table(name = "sys_quota")
public class QuotaEntity {

    @AssignID
    private String id;

    /** 账号 ID（sys_user.id），配额以账号为维度 */
    private String userId;

    /** 账号名（冗余保存，便于后台列表直接展示与按名查询） */
    private String username;

    /** 最大隧道（自动穿透配置）条数，0 或 null 表示不限制 */
    private Integer maxTunnels;

    /** 最大端口数，0 或 null 表示不限制 */
    private Integer maxPorts;

    /**
     * 最大并发连接数。
     * <p>
     * 【诚实说明】云端无法可靠观测到每个账号的实时并发连接数（连接建立在内网客户端与
     * 代理节点之间，云端只拿到周期性的流量汇总），因此该字段目前是
     * <b>参考性元数据</b>，不做实时强制；真正生效的是
     * 「隧道条数 / 端口数 / 月度流量」这类可在配置下发时判定的限制。
     */
    private Integer maxConns;

    /** 月度接收流量上限（字节数字符串），"0"/null/空 表示不限制 */
    private String monthlyReceive;

    /** 月度发送流量上限（字节数字符串），"0"/null/空 表示不限制 */
    private String monthlySend;

    /** 是否启用该配额：1 启用，0（或 null）不参与判定 */
    private Integer enabled;

    /** 是否已超限："true"/"false"，由定时任务 refreshOverLimit 刷新 */
    private String overLimit;

    /**
     * 超限原因（人类可读的中文说明）。
     * 未超限时写入「未超限 + 当前用量」摘要，便于后台悬浮查看用量，
     * 避免为了展示用量再对每个账号跑一次统计聚合。
     */
    private String overReason;

    /** 备注（后台管理用，不含任何敏感信息） */
    private String note;

    /** 创建时间（毫秒时间戳字符串） */
    private String createTime;

    /** 最近更新时间（毫秒时间戳字符串） */
    private String updateTime;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
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
        return monthlyReceive;
    }

    public void setMonthlyReceive(String monthlyReceive) {
        this.monthlyReceive = monthlyReceive;
    }

    public String getMonthlySend() {
        return monthlySend;
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
        return overLimit;
    }

    public void setOverLimit(String overLimit) {
        this.overLimit = overLimit;
    }

    public String getOverReason() {
        return overReason;
    }

    public void setOverReason(String overReason) {
        this.overReason = overReason;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public String getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }

    /**
     * 便捷判定：是否已超限。
     * <p>
     * 刻意不命名为 {@code isOverLimit()} / {@code getXxx()}：BeetlSQL 与模板层都会把
     * {@code getXxx} / {@code isXxx} 形式当作 Bean 属性，而该列在模板中按字符串比较即可，
     * 多出来的「属性」反而可能被误当成数据库列。
     */
    public boolean overLimitFlag() {
        return "true".equalsIgnoreCase(overLimit == null ? "" : overLimit.trim());
    }

    /** 便捷判定：配额是否启用（enabled == 1）。同上，不使用 isXxx / getXxx 命名。 */
    public boolean enabledFlag() {
        return enabled != null && enabled == 1;
    }
}

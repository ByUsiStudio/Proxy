package miao.byusi.hp.server.domian.entity;

import org.beetl.sql.annotation.entity.AssignID;
import org.beetl.sql.annotation.entity.Table;

/**
 * 隧道模板（表 sys_template）。
 * <p>
 * 一张模板 = 一组隧道条目，可对多个账号「一键下发」（见 TemplateService#applyTo）。
 * <p>
 * <b>items 是 JSON 数组文本，且永远不含口令：</b>
 * <pre>
 * [{"type":"TCP","userHost":"127.0.0.1:8080","serverHost":"1.2.3.4:9090","domain":"demo","port":"8080"}]
 * </pre>
 * 口令属于账号级凭据（客户端在 bootstrap 时用当前登录凭据补齐），
 * 一旦写进模板就意味着「一次导出 = 批量凭据泄露」，因此模板与导出内容
 * 都不包含 password 字段（与 config.ftl 的导出约定一致）。
 */
@Table(name = "sys_template")
public class TemplateEntity {

    @AssignID
    private String id;

    /** 模板名称（后台唯一识别用，可为中文描述性名称） */
    private String name;

    /** 模板说明 */
    private String description;

    /**
     * 条目 JSON 数组（不含口令）。
     * 解析/校验一律走 Jackson，绝不手工拼 JSON。
     */
    private String items;

    /** 创建者（后台管理员账号名或 "admin"） */
    private String createdBy;

    /** 累计下发次数（按成功下发的账号数累加，见 TemplateServiceImpl#applyTo） */
    private Integer applyCount;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getItems() {
        return items;
    }

    public void setItems(String items) {
        this.items = items;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Integer getApplyCount() {
        return applyCount;
    }

    public void setApplyCount(Integer applyCount) {
        this.applyCount = applyCount;
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
}

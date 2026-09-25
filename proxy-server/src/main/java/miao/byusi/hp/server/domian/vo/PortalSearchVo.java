package miao.byusi.hp.server.domian.vo;

/**
 * 全局搜索（后台 {@code /admin/search}）使用的只读结果载体。
 * <p>
 * 为什么单独定义 VO 而不是直接把实体塞进模板：
 * <ol>
 *   <li>{@code sys_config} / {@code sys_user} 里带 {@code password} 字段，
 *       直接渲染实体很容易在后续迭代中「顺手」把口令写进页面；
 *       这里只暴露展示需要的字段，从类型层面杜绝口令外泄；</li>
 *   <li>审计日志的 {@code detail} 字段虽然按约定只写业务标识，但它是自由文本，
 *       可能被上游误写入敏感内容，因此搜索结果<b>刻意不包含 detail / user_agent</b>。</li>
 * </ol>
 * 所有字段都会在 FreeMarker 模板里用 {@code ?html} 转义后渲染。
 */
public class PortalSearchVo {

    /**
     * 域名搜索结果行（sys_domain + 归属账号）。
     */
    public static class DomainRow {

        private String id;
        private String domain;
        private String customDomain;
        private String ownerId;
        /** 归属账号名；账号已被删除时给出占位文案，绝不返回用户主键之外的账号信息 */
        private String username;
        private String createTime;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getDomain() {
            return domain == null ? "" : domain;
        }

        public void setDomain(String domain) {
            this.domain = domain;
        }

        public String getCustomDomain() {
            return customDomain == null ? "" : customDomain;
        }

        public void setCustomDomain(String customDomain) {
            this.customDomain = customDomain;
        }

        public String getOwnerId() {
            return ownerId == null ? "" : ownerId;
        }

        public void setOwnerId(String ownerId) {
            this.ownerId = ownerId;
        }

        public String getUsername() {
            return username == null ? "" : username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getCreateTime() {
            return createTime == null ? "" : createTime;
        }

        public void setCreateTime(String createTime) {
            this.createTime = createTime;
        }
    }

    /**
     * 审计日志搜索结果行。
     * <p>
     * 【安全】不含 {@code detail} 与 {@code user_agent}：这两列是自由文本，
     * 万一上游把查询串/凭据写进去，搜索结果就会变成凭据泄露渠道。
     */
    public static class AuditRow {

        private String id;
        private String actor;
        private String actorType;
        private String action;
        private String target;
        private String result;
        private String ip;
        private String createTime;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getActor() {
            return actor == null ? "" : actor;
        }

        public void setActor(String actor) {
            this.actor = actor;
        }

        public String getActorType() {
            return actorType == null ? "" : actorType;
        }

        public void setActorType(String actorType) {
            this.actorType = actorType;
        }

        public String getAction() {
            return action == null ? "" : action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public String getTarget() {
            return target == null ? "" : target;
        }

        public void setTarget(String target) {
            this.target = target;
        }

        public String getResult() {
            return result == null ? "" : result;
        }

        public void setResult(String result) {
            this.result = result;
        }

        public String getIp() {
            return ip == null ? "" : ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        public String getCreateTime() {
            return createTime == null ? "" : createTime;
        }

        public void setCreateTime(String createTime) {
            this.createTime = createTime;
        }
    }
}

package miao.byusi.hp.server.service;

import miao.byusi.hp.server.domian.entity.StatisticsEntity;
import miao.byusi.hp.server.domian.vo.PortalApplyResult;
import miao.byusi.hp.server.domian.vo.PortalQuotaVo;
import miao.byusi.hp.server.domian.vo.PortalSearchVo;
import miao.byusi.hp.server.domian.vo.PortalUsageVo;

import java.util.List;

/**
 * 用户自助门户 + 后台全局搜索所需的跨表查询服务。
 * <p>
 * 【身份来源约定】本接口所有方法的 {@code username} 参数<b>必须</b>由控制器从
 * {@code AuthFilter} 注入的请求头（{@code request.getHeader("username")}，
 * 即服务端会话里保存的账号）取得，<b>绝不允许</b>来自请求参数/请求体/客户端可伪造的头。
 * 这样即使攻击者篡改前端提交的 username/userId，也只能操作自己账号的数据（避免 IDOR）。
 * <p>
 * 安全约定：所有 SQL 均通过 BeetlSQL 的 LambdaQuery / 实体映射做参数绑定，
 * 所有查询都有条数上限，绝不出现「整表扫描」或字符串拼接 SQL。
 */
public interface PortalService {

    /**
     * 「我的用量」：按会话账号聚合流量、按天趋势、按端口分布，并夹紧图表天数。
     *
     * @param username 会话账号（服务端注入）
     * @param days     图表天数，&lt;=0 用默认值 14，超过 30 一律夹紧为 30
     */
    PortalUsageVo usage(String username, int days);

    /**
     * 导出用：只取「会话账号本人」的流量记录（按账号精确匹配 + 行数上限）。
     * <p>
     * 【越权规避】不复用 {@code StatisticsService.listForExport}：它按 LIKE 匹配账号，
     * 会把形如 {@code xa@qq.com} 的他人记录带出来；这里用 andEq 精确匹配。
     *
     * @param username 会话账号（服务端注入）
     * @param limit    最大返回条数
     */
    List<StatisticsEntity> myStatistics(String username, int limit);

    /**
     * 全局搜索：按 {@code domain} / {@code custom_domain} 模糊匹配域名记录。
     *
     * @param keyword 关键字（服务层会 trim + 长度夹紧 + 危险字符拒绝）
     * @param limit   最大返回条数（服务层会夹紧到 1..20）
     */
    List<PortalSearchVo.DomainRow> searchDomains(String keyword, int limit);

    /**
     * 全局搜索：审计日志（actor / action / target 模糊匹配），参数绑定。
     * <p>
     * 返回行<b>不含</b> detail / user_agent 字段；旧库缺少 {@code sys_audit_log} 表时
     * 不抛异常，只记录服务端日志并返回空列表（可用 {@link #auditSearchAvailable()} 区分）。
     *
     * @param keyword 关键字
     * @param limit   最大返回条数（夹紧到 1..20）
     */
    List<PortalSearchVo.AuditRow> searchAudit(String keyword, int limit);

    /**
     * 审计日志表是否可用（只探测 1 条，代价可控）。
     * 用于前台区分「没有匹配结果」与「审计表不可用」。
     */
    boolean auditSearchAvailable();

    /**
     * 读取某账号的配额（只读）。userId 必须来自会话推导出的用户主键。
     *
     * @return 配额行；未设置或表不存在时返回 {@code null}
     */
    PortalQuotaVo quota(String userId);

    /**
     * 自助申请域名：校验格式 → 频率限制 → 数量上限 → 全局唯一性 → 写入 sys_domain（custom_domain 留空）。
     *
     * @param username  会话账号（服务端注入）
     * @param rawDomain 用户提交的原始域名
     */
    PortalApplyResult applyDomain(String username, String rawDomain);

    /**
     * 自助申请固定端口：校验格式与范围 → 频率限制 → 数量上限 → 占用检查 → 写入 sys_port。
     *
     * @param username 会话账号（服务端注入）
     * @param rawPort  用户提交的原始端口字符串
     */
    PortalApplyResult applyPort(String username, String rawPort);
}

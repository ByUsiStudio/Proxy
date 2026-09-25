package miao.byusi.hp.server.service;

import cn.hserver.plugin.web.interfaces.HttpRequest;
import miao.byusi.hp.server.domian.entity.AuditLogEntity;
import org.beetl.sql.core.page.PageResult;

import java.util.List;
import java.util.Map;

/**
 * 审计日志服务：记录「谁 / 何时 / 来源 IP / 做了什么 / 结果」，并提供查询与统计。
 * <p>
 * 关键约束（实现方必须遵守）：
 * <ol>
 *   <li>{@link #record} <b>永不抛异常、永不阻塞业务线程</b>：写日志失败不能影响主流程；</li>
 *   <li>{@code detail} 只放业务标识，<b>严禁写入口令、令牌、会话 ID、完整请求体</b>；</li>
 *   <li>队列有界，写不过来时丢弃并计数（丢弃数可通过 {@link #droppedCount()} 观测），
 *       而不是无限占用内存。</li>
 * </ol>
 */
public interface AuditService {

    /**
     * 记录一条审计事件（非阻塞，失败仅记服务端日志）。
     *
     * @param actorType 操作者类型：admin / user / node / system / anonymous
     * @param actor     操作者账号，可为空
     * @param action    动作标识，如 login.success、user.remove、config.import
     * @param target    操作目标，可为空
     * @param detail    补充说明（内部会截断），不得包含敏感信息
     * @param result    结果：ok / fail
     * @param request   当前请求（用于取真实对端 IP 与 User-Agent），可为空
     */
    void record(String actorType, String actor, String action, String target,
                String detail, String result, HttpRequest request);

    /**
     * 便捷重载：不需要目标与详情的场景。
     */
    void record(String actorType, String actor, String action, String result, HttpRequest request);

    /**
     * 分页查询（按时间倒序）。
     *
     * @param page    页码，从 1 开始
     * @param pageSize 每页条数
     * @param actor   操作者关键字（模糊），可为空
     * @param action  动作标识关键字（模糊），可为空
     * @param result  结果过滤：ok / fail，可为空
     */
    PageResult<AuditLogEntity> list(int page, int pageSize, String actor, String action, String result);

    /**
     * 按条件取全量（受 limit 限制），用于导出。
     */
    List<AuditLogEntity> listForExport(String actor, String action, String result, int limit);

    /**
     * 汇总统计，供仪表盘与告警徽标使用。
     * <pre>
     * {
     *   "total": 123, "ok": 120, "fail": 3,
     *   "recentFail": 2,                       // 近 24 小时的失败数（告警徽标）
     *   "byAction": [{"action":"login.success","count":30}, ...],
     *   "byDay":    [{"day":"2026-09-25","ok":10,"fail":1}, ...],
     *   "recent":   [AuditLogEntity...]        // 最近若干条事件
     * }
     * </pre>
     */
    Map<String, Object> summary(int dayRange, int recentLimit);

    /**
     * 批量删除（id 由调用方做字符白名单校验）。
     */
    int removeBatch(List<String> ids);

    /**
     * 删除超过指定天数的历史记录，由定时任务调用。
     *
     * @return 删除条数
     */
    int removeExpired(int keepDays);

    /**
     * 因队列满而被丢弃的审计事件数（仅用于观测，非精确计数）。
     */
    long droppedCount();
}

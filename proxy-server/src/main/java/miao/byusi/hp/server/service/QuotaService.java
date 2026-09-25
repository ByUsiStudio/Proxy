package miao.byusi.hp.server.service;

import miao.byusi.hp.server.domian.entity.QuotaEntity;
import org.beetl.sql.core.page.PageResult;

import java.util.List;
import java.util.Map;

/**
 * 用量配额与限流管理。
 * <p>
 * <b>配额在哪里强制、哪里只是元数据（诚实说明）：</b>
 * <ul>
 *   <li><b>真正强制</b>：月度流量（超限后拒绝下发新的隧道配置，并让
 *       {@code /config/listDevice} 不再返回隧道列表，从而阻止客户端开机自动建隧道）、
 *       隧道条数（下发时实时比对 {@code max_tunnels}，同时保持既有的
 *       {@code ConstConfig.PROXY_SIZE} 硬上限）。</li>
 *   <li><b>不强制（仅参考/元数据）</b>：{@code max_conns}（并发连接数）与
 *       {@code max_ports}（端口数）。并发连接数无法强制的原因见下；
 *       端口数则是因为「端口上限」在数据模型里没有明确定义——{@code sys_port} 记录的是
 *       管理员为该账号开通的固定端口，而不是隧道实际占用的端口数，
 *       直接拿两者相比会误伤正常账号，因此本模块只保存与展示，不做拦截。</li>
 *   <li><b>强制时机</b>：配额是在<b>配置下发阶段</b>（{@code ConfigController.save}、
 *       {@code TemplateService.applyTo}、{@code ConfigController.listDevice}）判定并
 *       拒绝的，而不是在数据面的连接建立阶段。这样做的原因同上：云端无法观测实时连接，
 *       但完全掌控「给不给这个账号新的隧道配置」，因此选择在唯一可靠的边界上收紧。</li>
 * </ul>
 * <p>
 * 另外：{@code max_conns} 之所以无法强制，是因为云端只拿到客户端周期性上报的流量汇总
 * （sys_statistics），<b>看不到每个账号的实时连接数</b>，代理节点也没有把「按账号的连接计数」
 * 回传云端。
 * <p>
 * 所有用法都应先通过 {@link #checkTunnelQuota(String, int)}（可读的中文拒绝原因，
 * 允许时返回 {@code null}）判定，避免各处重复实现超限逻辑。
 */
public interface QuotaService {

    /** 按账号 ID 取配额，未配置返回 null。 */
    QuotaEntity getByUserId(String userId);

    /** 按账号名取配额（精确匹配），未配置返回 null。 */
    QuotaEntity getByUsername(String username);

    /** 后台分页列表（用户名模糊匹配）。 */
    PageResult<QuotaEntity> list(Integer page, Integer pageSize, String username);

    /** 取全量（受 limit 限制），用于导出。 */
    List<QuotaEntity> listAll(int limit);

    /**
     * 新增或更新配额。
     * <p>按 {@code userId} 判重：同一账号只保留一条配额记录（sys_quota 的唯一业务键）。
     */
    boolean saveOrUpdate(QuotaEntity quota);

    boolean remove(String id);

    /** 批量删除，返回实际删除条数。 */
    int removeBatch(List<String> ids);

    /**
     * 评估单个配额是否超限。
     * <p>只评估<b>月度流量</b>（隧道/端口条数在配置下发时实时校验，见
     * {@link #checkTunnelQuota(String, int)}），因为流量是唯一能按账号可靠汇总的指标。
     *
     * @param quota         配额（null 视为不限制）
     * @param monthReceive  本月接收字节数
     * @param monthSend     本月发送字节数
     * @return {@code {over: Boolean, reason: String}}，reason 为中文可读说明
     */
    Map<String, Object> evaluate(QuotaEntity quota, long monthReceive, long monthSend);

    /**
     * 遍历所有「已启用」的配额，按当月流量重算 {@code over_limit} 与 {@code over_reason}。
     * <p>由定时任务调用，也由后台 {@code /admin/quota/refresh} 手动触发。
     *
     * @return 被评估的配额条数
     */
    int refreshOverLimit();

    /**
     * 隧道配额校验（配置下发前的统一入口）。
     *
     * @param userId         账号 ID
     * @param requestedCount 本次准备下发的隧道条数（单条保存传 1）
     * @return 允许时返回 {@code null}；否则返回可直接展示给用户的中文拒绝原因
     */
    String checkTunnelQuota(String userId, int requestedCount);

    /**
     * 只校验「月度流量是否已超限」，用于 {@code /config/listDevice} 这种
     * 「不新建隧道、但要决定是否继续放行既有隧道」的场景。
     *
     * @return 允许时返回 {@code null}；否则返回中文拒绝原因
     */
    String checkOverLimit(String userId);

    /**
     * 超限账号名列表（供仪表盘 / 告警展示），最多返回内部上限条数。
     */
    List<String> overLimitUsers();
}

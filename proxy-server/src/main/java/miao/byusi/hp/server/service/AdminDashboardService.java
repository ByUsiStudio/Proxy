package miao.byusi.hp.server.service;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.core.ioc.annotation.Bean;
import miao.byusi.hp.server.domian.entity.ProxyServerEntity;
import miao.byusi.hp.server.domian.vo.UserVo;
import org.beetl.sql.core.page.PageResult;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台首页仪表盘的数据聚合。
 * <p>
 * 单独抽成一个 {@code @Bean} 而不是把逻辑写在控制器里，是为了让
 * {@code /admin}（旧入口）与 {@code /admin/dashboard}（新入口）共用同一份数据装配代码。
 * <p>
 * <b>为什么坚持「分页取 total + 采样聚合」而不是全表扫描</b>：
 * 仪表盘是落地页，每次打开都会执行，如果为了拿一个总数把整表读进内存，
 * 随着 sys_user / sys_statistics 增长，首页会先于业务功能崩掉。
 * 因此这里：
 * <ol>
 *   <li>数量类指标统一走分页接口的第 1 页，只取 {@code getTotalRow()}（由数据库 count 得出，
 *       内存里最多只驻留 1 行）；</li>
 *   <li>流量/审计的聚合复用各自 Service 已有的「取样上限」实现
 *       （StatisticsService 20000 行、AuditService 10000 行），返回的数值标注为样本口径，
 *       不会随数据量线性变慢；</li>
 *   <li>节点清单直接取内存缓存 {@link ProxyServerEntity#getAll()}，本来就不落库。</li>
 * </ol>
 */
@Bean
public class AdminDashboardService {

    /** 最近注册用户展示条数（同时用作 userCount 的分页大小，省一次 count 查询） */
    private static final int RECENT_USER_LIMIT = 6;
    /** 按端口流量排行条数 */
    private static final int TOP_PORT_LIMIT = 8;
    /** 仪表盘默认的审计统计天数 */
    private static final int AUDIT_DAY_RANGE = 14;
    /** 仪表盘默认的最近事件条数 */
    private static final int AUDIT_RECENT_LIMIT = 8;
    /** 图表最多展示的天数，避免 X 轴过密 */
    private static final int MAX_CHART_DAYS = 90;

    @Autowired
    private UserService userService;

    @Autowired
    private DomainService domainService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private StatisticsService statisticsService;

    @Autowired
    private AuditService auditService;

    /**
     * 服务端渲染用的模型：即使浏览器禁用 JS，页面上的关键数字依然正确。
     */
    public Map<String, Object> pageModel() {
        Map<String, Object> model = new LinkedHashMap<>(32);

        // ---- 数量类指标：分页第 1 页，只用 totalRow，不把整表读进内存 ----
        PageResult<UserVo> users = userService.list(1, RECENT_USER_LIMIT, "");
        model.put("userCount", users == null ? 0L : users.getTotalRow());
        model.put("recentUsers", users == null ? new ArrayList<UserVo>() : users.getList());

        PageResult<?> domains = domainService.list(1, 1, null);
        model.put("domainCount", domains == null ? 0L : domains.getTotalRow());

        PageResult<?> configs = configService.list(1, 1, null, null);
        model.put("configCount", configs == null ? 0L : configs.getTotalRow());

        PageResult<?> statistics = statisticsService.list(1, 1);
        model.put("statRowCount", statistics == null ? 0L : statistics.getTotalRow());

        // ---- 流量：复用 StatisticsService 的取样聚合（上限 20000 行） ----
        Map<String, Object> traffic = statisticsService.summary(null, null);
        Map<String, Object> trafficTotal = asMap(traffic.get("total"));
        long receive = asLong(trafficTotal.get("receive"));
        long send = asLong(trafficTotal.get("send"));
        model.put("trafficReceive", receive);
        model.put("trafficSend", send);
        model.put("trafficConnect", asLong(trafficTotal.get("connectNum")));
        model.put("trafficSample", asLong(trafficTotal.get("rowCount")));

        List<Map<String, Object>> trafficByDay = asList(traffic.get("byDay"));
        String today = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
        String month = today.substring(0, 7);
        long todayReceive = 0L;
        long todaySend = 0L;
        long monthReceive = 0L;
        long monthSend = 0L;
        for (Map<String, Object> day : trafficByDay) {
            String key = String.valueOf(day.get("day"));
            long dayReceive = asLong(day.get("receive"));
            long daySend = asLong(day.get("send"));
            if (today.equals(key)) {
                todayReceive = dayReceive;
                todaySend = daySend;
            }
            if (key.startsWith(month)) {
                monthReceive += dayReceive;
                monthSend += daySend;
            }
        }
        model.put("todayReceive", todayReceive);
        model.put("todaySend", todaySend);
        model.put("monthReceive", monthReceive);
        model.put("monthSend", monthSend);
        model.put("topPorts", topPorts(asList(traffic.get("byPort")), TOP_PORT_LIMIT));

        // ---- 节点：内存缓存，20 秒无访问即过期，因此缓存内的条目就是「近期活跃」 ----
        Collection<ProxyServerEntity> nodes = ProxyServerEntity.getAll();
        long connections = 0L;
        if (nodes != null) {
            for (ProxyServerEntity node : nodes) {
                if (node != null && node.getNum() != null) {
                    connections += node.getNum();
                }
            }
        } else {
            nodes = new ArrayList<>();
        }
        model.put("nodeCount", (long) nodes.size());
        model.put("nodeConnections", connections);

        // ---- 审计：同样走取样聚合 ----
        Map<String, Object> audit = auditService.summary(AUDIT_DAY_RANGE, AUDIT_RECENT_LIMIT);
        Map<String, Object> auditTotal = asMap(audit.get("total"));
        model.put("auditTotal", asLong(auditTotal.get("total")));
        model.put("auditOk", asLong(auditTotal.get("ok")));
        model.put("auditFail", asLong(auditTotal.get("fail")));
        model.put("auditRecentFail", asLong(auditTotal.get("recentFail")));
        model.put("auditDropped", asLong(auditTotal.get("dropped")));
        model.put("auditRecent", asList(audit.get("recent")));
        model.put("auditByAction", asList(audit.get("byAction")));
        return model;
    }

    /**
     * 图表与「最近事件」异步刷新用的 JSON 数据。
     *
     * @param days 审计按天聚合的天数范围
     */
    public Map<String, Object> chartData(int days) {
        int range = days <= 0 ? AUDIT_DAY_RANGE : Math.min(days, MAX_CHART_DAYS);

        Map<String, Object> traffic = statisticsService.summary(null, null);
        Map<String, Object> audit = auditService.summary(range, AUDIT_RECENT_LIMIT);

        Collection<ProxyServerEntity> nodes = ProxyServerEntity.getAll();

        Map<String, Object> out = new LinkedHashMap<>(16);
        out.put("days", range);
        // 流量按天只保留最近 range 天，避免折线图点过密
        out.put("trafficByDay", tail(asList(traffic.get("byDay")), range));
        out.put("trafficByPort", asList(traffic.get("byPort")));
        out.put("trafficTotal", asMap(traffic.get("total")));
        out.put("auditByDay", asList(audit.get("byDay")));
        out.put("auditByAction", asList(audit.get("byAction")));
        out.put("auditRecent", asList(audit.get("recent")));
        out.put("auditTotal", asMap(audit.get("total")));
        // 顶栏失败徽标的数据源：近 24 小时的失败事件数
        out.put("alertCount", asLong(asMap(audit.get("total")).get("recentFail")));
        out.put("nodeCount", nodes == null ? 0L : (long) nodes.size());
        out.put("serverTime", System.currentTimeMillis());
        return out;
    }

    // ------------------------------------------------------------------
    // 内部工具：所有强制转换都做空值兜底，仪表盘绝不能因为某张表为空而 500
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object value) {
        if (value instanceof List) {
            return (List<Map<String, Object>>) value;
        }
        return new ArrayList<>();
    }

    private static long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
        return 0L;
    }

    /** 只保留列表末尾的 limit 项（byDay 是按日期升序的）。 */
    private static List<Map<String, Object>> tail(List<Map<String, Object>> list, int limit) {
        if (list == null || list.size() <= limit) {
            return list == null ? new ArrayList<>() : list;
        }
        return new ArrayList<>(list.subList(list.size() - limit, list.size()));
    }

    /** 按入站流量取前 N 个端口，供条形占比列表展示。 */
    private static List<Map<String, Object>> topPorts(List<Map<String, Object>> byPort, int limit) {
        List<Map<String, Object>> copy = new ArrayList<>(byPort);
        copy.sort((a, b) -> Long.compare(asLong(b.get("receive")), asLong(a.get("receive"))));
        if (copy.size() > limit) {
            return new ArrayList<>(copy.subList(0, limit));
        }
        return copy;
    }
}

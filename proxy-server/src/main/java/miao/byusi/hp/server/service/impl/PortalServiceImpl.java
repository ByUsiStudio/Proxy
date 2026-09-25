package miao.byusi.hp.server.service.impl;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.core.ioc.annotation.Bean;
import miao.byusi.hp.server.dao.AuditLogDao;
import miao.byusi.hp.server.dao.DomainDao;
import miao.byusi.hp.server.dao.PortDao;
import miao.byusi.hp.server.dao.StatisticsDao;
import miao.byusi.hp.server.domian.entity.AuditLogEntity;
import miao.byusi.hp.server.domian.entity.DomainEntity;
import miao.byusi.hp.server.domian.entity.PortEntity;
import miao.byusi.hp.server.domian.entity.StatisticsEntity;
import miao.byusi.hp.server.domian.entity.UserEntity;
import miao.byusi.hp.server.domian.vo.PortalApplyResult;
import miao.byusi.hp.server.domian.vo.PortalQuotaVo;
import miao.byusi.hp.server.domian.vo.PortalSearchVo;
import miao.byusi.hp.server.domian.vo.PortalUsageVo;
import miao.byusi.hp.server.service.PortalService;
import miao.byusi.hp.server.service.StatisticsService;
import miao.byusi.hp.server.service.UserService;
import miao.byusi.hp.server.utils.DateUtil;
import miao.byusi.hp.server.utils.RateLimitUtil;
import miao.byusi.hp.server.utils.SafeInputUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 门户自助服务 / 全局搜索的实现。
 * <p>
 * 关键安全点（每一项都在方法上有对应注释）：
 * <ol>
 *   <li><b>身份只来自会话</b>：所有方法都以控制器传入的 username（AuthFilter 从 user_session
 *       解析并注入的请求头）为唯一身份来源，userId 由服务端反查得到；
 *       请求参数里的 username/userId 一律忽略，从根本上避免 IDOR（越权访问他人数据）。</li>
 *   <li><b>有界查询</b>：每个 LIKE 查询都带 limit，聚合取样上限与 StatisticsService 保持一致，
 *       绝不出现「不限量的整表扫描」。</li>
 *   <li><b>参数绑定</b>：全部使用 BeetlSQL LambdaQuery 的 andEq/andLike（占位符绑定），
 *       不存在 SQL 字符串拼接。</li>
 *   <li><b>写入校验</b>：自助申请先做格式白名单（SafeInputUtil）+ 频率限制 + 数量上限 +
 *       占用检查，主键由服务端生成 UUID，客户端无法指定任何 ID。</li>
 * </ol>
 */
@Bean
public class PortalServiceImpl implements PortalService {

    private static final Logger log = LoggerFactory.getLogger(PortalServiceImpl.class);

    /**
     * 自助申请的数量上限：每个账号最多 3 个域名 / 3 个固定端口。
     * 为什么单独定义而不直接用 ConstConfig.PROXY_SIZE：
     * PROXY_SIZE 约束的是「自动穿透配置」数量，运维可能单独调整；这里的上限用于自助申请入口，
     * 固定为 3 可以避免用户通过门户接口把端口/域名配额刷满（也避免与既有配置互相挤占）。
     */
    private static final int MAX_DOMAIN_APPLY = 3;
    private static final int MAX_PORT_APPLY = 3;

    /**
     * 自助申请固定端口的合法区间：10000~60000（与开放接口 {@code /server/portAdd} 完全一致）。
     * <p>
     * 为什么必须收紧（原实现允许 1~65535）：
     * <ol>
     *   <li><b>特权端口</b>：1~1023 是系统保留端口（80/443/22 等），普通用户申请后一旦被建监听，
     *       就会与系统服务抢占，或让运维误判「服务已经就绪」；</li>
     *   <li><b>节点自身监听</b>：节点进程的集群 / 中继 / 管理端口同样落在低位区间，
     *       用户申请的端口与节点自身监听撞车时，节点会启动失败或行为异常，
     *       而这种故障的表象是「节点莫名其妙起不来」，排查成本极高；</li>
     *   <li><b>口径一致</b>：自助门户与开放接口是同一件事的两个入口，
     *       两套区间会让用户误以为「门户申请到的端口，/server/portAdd 也能用」。</li>
     * </ol>
     * 区间只在这里定义一次，校验与提示文案都引用它，将来调整只需改这一处。
     * 注意：该限制只作用于<b>自助门户</b>；后台管理员的端口管理不经过这里，不受影响。
     */
    private static final int PORT_MIN = 10000;
    private static final int PORT_MAX = 60000;

    /**
     * 自助申请的频率限制：同一账号 60 秒内最多提交 1 次。
     * 目的：① 防止脚本刷写把库写爆；② 限制「探测某个域名/端口是否已被占用」的枚举行为。
     */
    private static final long APPLY_WINDOW_MS = 60_000L;
    private static final int APPLY_MAX_HITS = 1;

    /** 图表天数：默认 14 天，服务端硬上限 30 天（客户端传再大也会被夹紧，避免一次请求聚合过多） */
    private static final int DEFAULT_USAGE_DAYS = 14;
    private static final int MAX_USAGE_DAYS = 30;

    /** 单次「本月」聚合的取样上限，与 StatisticsServiceImpl.SAMPLE_LIMIT 保持一致 */
    private static final int MONTH_SAMPLE_LIMIT = 20000;

    /** 全局搜索每组结果上限（与 SearchController 展示上限一致） */
    private static final int MAX_SEARCH_ROWS = 20;

    /** LIKE 关键字长度上限：超长关键字会让 SQLite 做更多无用比较 */
    private static final int MAX_KEYWORD_LENGTH = 64;

    /** 按端口聚合的行数上限（正常账号端口很少，这里只是兜底） */
    private static final int MAX_PORT_ROWS = 100;

    @Autowired
    private UserService userService;

    @Autowired
    private StatisticsService statisticsService;

    @Autowired
    private DomainDao domainDao;

    @Autowired
    private PortDao portDao;

    @Autowired
    private StatisticsDao statisticsDao;

    @Autowired
    private AuditLogDao auditLogDao;

    // ------------------------------------------------------------------
    // 我的用量
    // ------------------------------------------------------------------

    @Override
    public PortalUsageVo usage(String username, int days) {
        PortalUsageVo vo = new PortalUsageVo();
        String name = SafeInputUtil.isBlank(username) ? null : username.trim();
        vo.setUsername(name);
        int range = days <= 0 ? DEFAULT_USAGE_DAYS : Math.min(days, MAX_USAGE_DAYS);
        vo.setDays(range);
        vo.setSampleLimit(MONTH_SAMPLE_LIMIT);
        if (name == null) {
            return vo;
        }

        // 1) 采样汇总（服务端内部按时间倒序取最多 20000 条，属于有界查询）
        Map<String, Object> summary = statisticsService.summary(name, null);
        Map<String, Object> total = mapOf(summary.get("total"));
        vo.setTotalReceive(longOf(total.get("receive")));
        vo.setTotalSend(longOf(total.get("send")));
        vo.setTotalConnect(longOf(total.get("connectNum")));
        vo.setTotalPack(longOf(total.get("packNum")));
        vo.setSampleCount(intOf(total.get("rowCount")));

        // 2) 按天趋势：summary 已给出 day/receive/send，这里按最近 range 天补齐缺失日期（补 0），
        //    让折线图 X 轴连续，用户不会误以为「没数据 = 断线」。
        Map<String, PortalUsageVo.DayPoint> indexed = new LinkedHashMap<>();
        for (Map<String, Object> row : listOfMaps(summary.get("byDay"))) {
            String day = strOf(row.get("day"));
            if (day == null || day.isEmpty()) {
                continue;
            }
            PortalUsageVo.DayPoint point = new PortalUsageVo.DayPoint(day);
            point.setReceive(longOf(row.get("receive")));
            point.setSend(longOf(row.get("send")));
            indexed.put(day, point);
        }
        SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd");
        Calendar now = Calendar.getInstance();
        List<PortalUsageVo.DayPoint> series = new ArrayList<>(range);
        for (int i = range - 1; i >= 0; i--) {
            Calendar cursor = (Calendar) now.clone();
            cursor.add(Calendar.DAY_OF_MONTH, -i);
            String label = dayFormat.format(cursor.getTime());
            PortalUsageVo.DayPoint point = indexed.get(label);
            series.add(point == null ? new PortalUsageVo.DayPoint(label) : point);
        }
        vo.setByDay(series);

        // 3) 按端口分布（summary 已按接收流量倒序），行数兜底截断
        List<PortalUsageVo.PortRow> ports = new ArrayList<>();
        for (Map<String, Object> row : listOfMaps(summary.get("byPort"))) {
            if (ports.size() >= MAX_PORT_ROWS) {
                break;
            }
            PortalUsageVo.PortRow item = new PortalUsageVo.PortRow();
            item.setPort(intOf(row.get("port")));
            item.setReceive(longOf(row.get("receive")));
            item.setSend(longOf(row.get("send")));
            item.setConnectNum(longOf(row.get("connectNum")));
            item.setPackNum(longOf(row.get("packNum")));
            ports.add(item);
        }
        vo.setByPort(ports);

        // 4) 本月合计：单独按「本月初的毫秒时间戳」做一次有界查询，
        //    因为 summary 的结构里没有按月的连接数/数据包数。
        //    注意 sys_statistics.create_time 存的是 13 位毫秒时间戳字符串，
        //    等长数字串的字典序与数值序一致，因此直接用 >= 比较（与 StatisticsServiceImpl.removeExpData 同口径）。
        SimpleDateFormat monthFormat = new SimpleDateFormat("yyyy-MM");
        vo.setMonthLabel(monthFormat.format(new Date()));
        Calendar monthStart = Calendar.getInstance();
        monthStart.set(Calendar.DAY_OF_MONTH, 1);
        monthStart.set(Calendar.HOUR_OF_DAY, 0);
        monthStart.set(Calendar.MINUTE, 0);
        monthStart.set(Calendar.SECOND, 0);
        monthStart.set(Calendar.MILLISECOND, 0);
        try {
            List<StatisticsEntity> monthRows = statisticsDao.createLambdaQuery()
                    .andEq(StatisticsEntity::getUsername, name)
                    .andGreatEq("create_time", String.valueOf(monthStart.getTimeInMillis()))
                    .orderBy("create_time desc")
                    .limit(0, MONTH_SAMPLE_LIMIT)
                    .select();
            long receive = 0L;
            long send = 0L;
            long connect = 0L;
            long pack = 0L;
            for (StatisticsEntity row : monthRows) {
                receive += parseLong(row.getReceive());
                send += parseLong(row.getSend());
                connect += parseLong(row.getConnectNum());
                pack += parseLong(row.getPackNum());
            }
            vo.setMonthReceive(receive);
            vo.setMonthSend(send);
            vo.setMonthConnect(connect);
            vo.setMonthPack(pack);
            vo.setMonthRowCount(monthRows.size());
        } catch (Exception e) {
            // 聚合失败不应该让整个页面打不开：退化为 0 并在服务端留痕
            log.warn("本月流量聚合失败：账号={}，原因={}", name, e.getMessage());
        }
        return vo;
    }

    /**
     * 导出用：只取该账号自己的流量记录（按账号<b>精确</b>匹配）。
     * <p>
     * 为什么不复用 {@code StatisticsService.listForExport}：它内部用的是
     * {@code andLike(username, "%" + username + "%")}，形如 {@code xa@qq.com} 的账号
     * 会匹配到 {@code a@qq.com} 的记录，导出即变成越权读取他人流量明细。
     * 这里改为 andEq 精确匹配 + 行数上限，所有权由会话账号唯一决定。
     */
    @Override
    public List<StatisticsEntity> myStatistics(String username, int limit) {
        List<StatisticsEntity> rows = new ArrayList<>();
        String name = SafeInputUtil.isBlank(username) ? null : username.trim();
        if (name == null) {
            return rows;
        }
        int max = limit <= 0 ? 1000 : Math.min(limit, MONTH_SAMPLE_LIMIT);
        try {
            List<StatisticsEntity> list = statisticsDao.createLambdaQuery()
                    .andEq(StatisticsEntity::getUsername, name)
                    .orderBy("create_time desc")
                    .limit(0, max)
                    .select();
            for (StatisticsEntity entity : list) {
                entity.setCreateTime(DateUtil.stampToDate(entity.getCreateTime()));
                rows.add(entity);
            }
        } catch (Exception e) {
            log.warn("导出个人流量记录失败：账号={}，原因={}", name, e.getMessage());
        }
        return rows;
    }

    // ------------------------------------------------------------------
    // 全局搜索（后台）
    // ------------------------------------------------------------------

    @Override
    public List<PortalSearchVo.DomainRow> searchDomains(String keyword, int limit) {
        List<PortalSearchVo.DomainRow> rows = new ArrayList<>();
        String key = normalizeKeyword(keyword);
        if (key == null) {
            return rows;
        }
        int max = clamp(limit, 1, MAX_SEARCH_ROWS);
        // 参数化模糊查询：domain / custom_domain 任一命中即返回，LIMIT 由数据库执行
        List<DomainEntity> list = domainDao.createLambdaQuery()
                .andLike(DomainEntity::getDomain, "%" + key + "%")
                .orLike(DomainEntity::getCustomDomain, "%" + key + "%")
                .orderBy("create_time desc")
                .limit(0, max)
                .select();
        for (DomainEntity entity : list) {
            PortalSearchVo.DomainRow row = new PortalSearchVo.DomainRow();
            row.setId(entity.getId());
            row.setDomain(entity.getDomain());
            row.setCustomDomain(entity.getCustomDomain());
            row.setOwnerId(entity.getUserId());
            // 归属账号只用于展示：按主键查（最多 max=20 次），属于有界查询
            UserEntity owner = SafeInputUtil.isBlank(entity.getUserId()) ? null : userService.getUserById(entity.getUserId());
            row.setUsername(owner == null ? "（账号不存在）" : owner.getUsername());
            row.setCreateTime(DateUtil.stampToDate(entity.getCreateTime()));
            rows.add(row);
        }
        return rows;
    }

    @Override
    public List<PortalSearchVo.AuditRow> searchAudit(String keyword, int limit) {
        List<PortalSearchVo.AuditRow> rows = new ArrayList<>();
        String key = normalizeKeyword(keyword);
        if (key == null) {
            return rows;
        }
        int max = clamp(limit, 1, MAX_SEARCH_ROWS);
        try {
            List<AuditLogEntity> list = auditLogDao.createLambdaQuery()
                    .andLike(AuditLogEntity::getActor, "%" + key + "%")
                    .orLike(AuditLogEntity::getAction, "%" + key + "%")
                    .orLike(AuditLogEntity::getTarget, "%" + key + "%")
                    .orderBy("create_time desc")
                    .limit(0, max)
                    .select();
            for (AuditLogEntity entity : list) {
                PortalSearchVo.AuditRow row = new PortalSearchVo.AuditRow();
                row.setId(entity.getId());
                row.setActor(entity.getActor());
                row.setActorType(entity.getActorType());
                row.setAction(entity.getAction());
                row.setTarget(entity.getTarget());
                row.setResult(entity.getResult());
                row.setIp(entity.getIp());
                row.setCreateTime(DateUtil.stampToDate(entity.getCreateTime()));
                // 【安全】刻意不复制 detail / userAgent：自由文本可能被上游误写入敏感内容
                rows.add(row);
            }
        } catch (Exception e) {
            // 旧库（未执行 db/migrate-v16.1.sql）可能没有 sys_audit_log 表：
            // 这里降级为空结果 + 服务端告警，绝不因为审计表缺失导致搜索页 500
            log.warn("审计日志检索不可用：{}", e.getMessage());
        }
        return rows;
    }

    @Override
    public boolean auditSearchAvailable() {
        try {
            // 只探测 1 条，代价可控；表不存在会抛异常
            auditLogDao.createLambdaQuery().limit(0, 1).select();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public PortalQuotaVo quota(String userId) {
        if (SafeInputUtil.isBlank(userId)) {
            return null;
        }
        try {
            List<PortalQuotaVo> list = domainDao.getSQLManager().lambdaQuery(PortalQuotaVo.class)
                    .andEq(PortalQuotaVo::getUserId, userId.trim())
                    .limit(0, 1)
                    .select();
            return list == null || list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            // 未设置配额 / 旧库缺 sys_quota 表：一律按「未设置配额」展示，不让页面报错
            log.debug("读取配额失败（视为未设置）：userId={}，原因={}", userId, e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 自助申请
    // ------------------------------------------------------------------

    /**
     * 自助申请域名。
     * <p>
     * 【设计说明（重要）】现有库表没有审批流表，本轮也无法新增表（db/** 属其它 worker）。
     * 因此这里选定的语义是：<b>通过校验即直接为该账号建立归属记录</b>
     * （sys_domain.custom_domain 留空，等待解析生效），UI 上明确提示「已提交，等待解析生效」。
     * 真正的「申请 → 管理员审批 → 生效」流程需要新增一张审批表
     * （字段至少包含：申请 id / user_id / 类型 / 值 / 状态 / 审批人 / 时间），
     * 属于下一轮工作，本轮不做。
     * <p>
     * 【越权（IDOR）规避】账号来自会话（AuthFilter 注入的 username），
     * userId 由服务端反查；请求里即使带 userId / username 也不会被读取。
     */
    @Override
    public PortalApplyResult applyDomain(String username, String rawDomain) {
        String name = SafeInputUtil.isBlank(username) ? null : username.trim();
        if (name == null) {
            // 正常流程下 AuthFilter 已拦截未登录请求，这里是纵深防御
            return PortalApplyResult.fail("会话已失效，请重新登录后再试");
        }
        // 1) 格式白名单：只允许字母/数字/点/横线，天然排除引号、尖括号、控制字符（杜绝存储型 XSS 与 SQL 注入载荷）
        String domain = SafeInputUtil.cleanDomain(rawDomain);
        if (domain == null) {
            return PortalApplyResult.fail("域名格式不正确：只允许字母、数字、点与横线");
        }
        if (domain.length() < 4 || domain.length() > 253) {
            return PortalApplyResult.fail("域名长度需在 4~253 个字符之间");
        }
        UserEntity user = userService.getUser(name);
        if (user == null) {
            return PortalApplyResult.fail("账号不存在或已被删除");
        }
        // 2) 频率限制（按服务端 userId 计数，客户端无法通过换参数绕过）
        if (!RateLimitUtil.allow("portal.apply.domain", user.getId(), APPLY_MAX_HITS, APPLY_WINDOW_MS)) {
            return PortalApplyResult.fail("提交过于频繁，请 1 分钟后再试");
        }
        // 3) 数量上限：只取上限+1 条做判断，避免把该账号全部记录读出来
        List<DomainEntity> owned = domainDao.createLambdaQuery()
                .andEq(DomainEntity::getUserId, user.getId())
                .limit(0, MAX_DOMAIN_APPLY + 1)
                .select();
        if (owned != null && owned.size() >= MAX_DOMAIN_APPLY) {
            return PortalApplyResult.fail("每人最多可申请 " + MAX_DOMAIN_APPLY + " 个域名，你已达到上限");
        }
        // 4) 全局唯一性：域名不能已被任何账号占用（含自己的旧记录）
        List<DomainEntity> taken = domainDao.createLambdaQuery()
                .andEq(DomainEntity::getDomain, domain)
                .limit(0, 1)
                .select();
        if (taken != null && !taken.isEmpty()) {
            return PortalApplyResult.fail("该域名已被占用，请换一个");
        }
        // 5) 写入：主键由服务端生成 UUID，createTime 由服务端生成，客户端无法指定任何 ID
        DomainEntity entity = new DomainEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(user.getId());
        entity.setDomain(domain);
        // custom_domain 留空表示「尚未绑定自定义域名」，管理员/用户后续可在后台补全
        entity.setCustomDomain("");
        entity.setCreateTime(String.valueOf(System.currentTimeMillis()));
        domainDao.insert(entity);
        // 【审计】写操作的审计统一由控制器负责（PortalController 调
        // AuditService.record("user", 会话账号, "portal.domain.apply", ...)）：
        // 控制器才拿得到 HttpRequest（真实来源 IP / UA），服务层这里只保留应用日志。
        log.info("门户自助申请域名成功：账号={}，域名={}，当前拥有 {} 个域名", name, domain,
                (owned == null ? 0 : owned.size()) + 1);
        return PortalApplyResult.ok("已提交，等待解析生效", domain);
    }

    /**
     * 自助申请固定端口。语义与 {@link #applyDomain(String, String)} 完全一致：
     * 校验通过即写入 sys_port 归属记录，UI 上标注「已提交，等待解析生效」。
     * <p>
     * 端口范围与开放接口 {@code /server/portAdd} 对齐为 {@link #PORT_MIN}~{@link #PORT_MAX}
     * （原先是 1~65535，会把 80/443 这类特权端口和节点自身的监听端口放给普通用户，
     * 具体原因见常量上的注释）。{@code SafeInputUtil.cleanPort} 只保证是 1~5 位数字，
     * 区间判断必须在这里做。
     */
    @Override
    public PortalApplyResult applyPort(String username, String rawPort) {
        String name = SafeInputUtil.isBlank(username) ? null : username.trim();
        if (name == null) {
            return PortalApplyResult.fail("会话已失效，请重新登录后再试");
        }
        String cleaned = SafeInputUtil.cleanPort(rawPort);
        if (cleaned == null) {
            return PortalApplyResult.fail("端口必须是数字");
        }
        int port;
        try {
            port = Integer.parseInt(cleaned);
        } catch (NumberFormatException e) {
            return PortalApplyResult.fail("端口必须是数字");
        }
        if (port < PORT_MIN || port > PORT_MAX) {
            // 提示里写明原因，避免用户反复试探 80/443 为什么不行
            return PortalApplyResult.fail("端口范围是 " + PORT_MIN + "~" + PORT_MAX
                    + "（10000 以下为系统与节点保留端口，不对自助申请开放）");
        }
        UserEntity user = userService.getUser(name);
        if (user == null) {
            return PortalApplyResult.fail("账号不存在或已被删除");
        }
        if (!RateLimitUtil.allow("portal.apply.port", user.getId(), APPLY_MAX_HITS, APPLY_WINDOW_MS)) {
            return PortalApplyResult.fail("提交过于频繁，请 1 分钟后再试");
        }
        List<PortEntity> owned = portDao.createLambdaQuery()
                .andEq(PortEntity::getUserId, user.getId())
                .limit(0, MAX_PORT_APPLY + 1)
                .select();
        if (owned != null && owned.size() >= MAX_PORT_APPLY) {
            return PortalApplyResult.fail("每人最多可申请 " + MAX_PORT_APPLY + " 个固定端口，你已达到上限");
        }
        List<PortEntity> taken = portDao.createLambdaQuery()
                .andEq(PortEntity::getPort, port)
                .limit(0, 1)
                .select();
        if (taken != null && !taken.isEmpty()) {
            return PortalApplyResult.fail("该端口已被占用，请换一个");
        }
        PortEntity entity = new PortEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(user.getId());
        entity.setPort(port);
        entity.setCreateTime(String.valueOf(System.currentTimeMillis()));
        portDao.insert(entity);
        log.info("门户自助申请固定端口成功：账号={}，端口={}", name, port);
        return PortalApplyResult.ok("已提交，等待解析生效", String.valueOf(port));
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /**
     * 关键字清洗：trim → 长度夹紧 → 拒绝危险字符。
     * <p>
     * 说明：LIKE 的 {@code %} / {@code _} 通配符不做转义——它们只会扩大匹配范围，
     * 而所有查询都带 LIMIT，最坏情况是多返回 20 条，不构成注入或性能风险。
     */
    private static String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String key = keyword.trim();
        if (key.isEmpty()) {
            return null;
        }
        if (key.length() > MAX_KEYWORD_LENGTH) {
            key = key.substring(0, MAX_KEYWORD_LENGTH);
        }
        // 含控制字符/HTML 元字符的关键字直接拒绝检索（模板侧还有 ?html 转义兜底）
        return SafeInputUtil.hasDangerousChars(key) ? null : key;
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }

    private static long parseLong(String value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List)) {
            return new ArrayList<>();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : (List<Object>) value) {
            if (item instanceof Map) {
                out.add((Map<String, Object>) item);
            }
        }
        return out;
    }

    private static String strOf(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long longOf(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return parseLong(strOf(value));
    }

    private static int intOf(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (Exception e) {
            return 0;
        }
    }
}

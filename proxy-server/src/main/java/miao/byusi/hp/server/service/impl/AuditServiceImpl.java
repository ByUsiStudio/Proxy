package miao.byusi.hp.server.service.impl;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.core.ioc.annotation.Bean;
import cn.hserver.plugin.web.interfaces.HttpRequest;
import miao.byusi.hp.server.dao.AuditLogDao;
import miao.byusi.hp.server.domian.entity.AuditLogEntity;
import miao.byusi.hp.server.service.AuditService;
import miao.byusi.hp.server.utils.DateUtil;
import miao.byusi.hp.server.utils.NetUtil;
import org.beetl.sql.core.SQLReady;
import org.beetl.sql.core.page.PageResult;
import org.beetl.sql.core.query.LambdaQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 审计日志实现。
 * <p>
 * 设计要点：
 * <ul>
 *   <li><b>非阻塞</b>：{@link #record} 只做清洗 + 入队，落库由单独的守护线程完成，
 *       因此审计写入永远不会拖慢后台请求；</li>
 *   <li><b>有界</b>：队列容量固定，写不过来时丢弃并计数（{@link #droppedCount()}），
 *       避免数据库故障时把服务打挂；</li>
 *   <li><b>失败安全</b>：任何异常都被吞掉并只记服务端日志，绝不影响业务主流程；</li>
 *   <li><b>脱敏兜底</b>：即使调用方不小心把整段查询串传进来，
 *       {@link #scrub} 也会抹掉 password/token/secret 之类的键值；</li>
 *   <li><b>真实来源</b>：IP 取真实 TCP 对端（见 {@link NetUtil}），
 *       不信任 X-Forwarded-For，避免审计记录被伪造。</li>
 * </ul>
 */
@Bean
public class AuditServiceImpl implements AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditServiceImpl.class);

    /** 队列容量：约可缓冲数千条事件，足够吸收突发写入 */
    private static final int QUEUE_CAPACITY = 4096;
    /** 单个字段的最大长度，避免被超长输入撑爆数据库 */
    private static final int MAX_ACTOR = 128;
    private static final int MAX_ACTION = 64;
    private static final int MAX_TARGET = 200;
    private static final int MAX_DETAIL = 500;
    private static final int MAX_UA = 200;
    /** 汇总取样上限：图表与徽标只需近期数据 */
    private static final int SUMMARY_SAMPLE = 10000;

    /**
     * 脱敏兜底：抹掉形如 password=xxx、token=xxx、secret=xxx、pwd=xxx 的片段。
     * 目的是「即使调用方写错也不会把凭据落库」。
     */
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|token|secret|session|sessionid|authorization)\\s*[=:]\\s*[^&\\s,;]*");

    /** 控制字符（含 CR/LF/Tab），用于防止审计内容被注入伪造行 */
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}]");

    @Autowired
    private AuditLogDao auditLogDao;

    private final BlockingQueue<AuditLogEntity> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean writerStarted = false;

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    @Override
    public void record(String actorType, String actor, String action, String target,
                       String detail, String result, HttpRequest request) {
        try {
            if (action == null || action.trim().isEmpty()) {
                // 没有动作标识的审计没有意义，直接忽略而不是写一条脏数据
                return;
            }
            AuditLogEntity entity = new AuditLogEntity();
            entity.setId(UUID.randomUUID().toString());
            entity.setActorType(clean(actorType, 32, "unknown"));
            entity.setActor(clean(actor, MAX_ACTOR, ""));
            entity.setAction(clean(action, MAX_ACTION, ""));
            entity.setTarget(clean(target, MAX_TARGET, ""));
            entity.setDetail(clean(detail, MAX_DETAIL, ""));
            entity.setResult("fail".equalsIgnoreCase(String.valueOf(result)) ? "fail" : "ok");
            entity.setCreateTime(String.valueOf(System.currentTimeMillis()));
            if (request != null) {
                entity.setIp(clean(NetUtil.socketIp(request), 64, NetUtil.UNKNOWN_IP));
                entity.setUserAgent(clean(request.getHeader("user-agent"), MAX_UA, ""));
            } else {
                entity.setIp("");
            }

            ensureWriter();
            if (!queue.offer(entity)) {
                // 队列满：丢弃并计数，绝不阻塞调用方
                dropped.incrementAndGet();
            }
        } catch (Throwable t) {
            // 审计失败绝不影响业务
            log.debug("审计事件入队失败: {}", t.getMessage());
        }
    }

    @Override
    public void record(String actorType, String actor, String action, String result, HttpRequest request) {
        record(actorType, actor, action, "", "", result, request);
    }

    /** 惰性启动唯一的写库线程（守护线程，不阻止 JVM 退出）。 */
    private void ensureWriter() {
        if (writerStarted) {
            return;
        }
        synchronized (this) {
            if (writerStarted) {
                return;
            }
            Thread thread = new Thread(this::drainLoop, "audit-writer");
            thread.setDaemon(true);
            thread.start();
            writerStarted = true;
        }
    }

    /**
     * 批量消费队列：攒批写入，降低数据库压力。
     */
    private void drainLoop() {
        List<AuditLogEntity> batch = new ArrayList<>(64);
        while (true) {
            try {
                AuditLogEntity first = queue.poll(1, TimeUnit.SECONDS);
                if (first == null) {
                    continue;
                }
                batch.clear();
                batch.add(first);
                // 最多再取 63 条，形成一批写入
                queue.drainTo(batch, 63);
                flush(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                log.warn("审计日志写入失败，本批丢弃：{}", t.getMessage());
            }
        }
    }

    private void flush(List<AuditLogEntity> batch) {
        for (AuditLogEntity entity : batch) {
            try {
                auditLogDao.insert(entity);
            } catch (Throwable t) {
                // 单条失败不影响同批其它记录；此处不重复打日志避免刷屏
                dropped.incrementAndGet();
            }
        }
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public PageResult<AuditLogEntity> list(int page, int pageSize, String actor, String action, String result) {
        LambdaQuery<AuditLogEntity> query = buildQuery(actor, action, result);
        PageResult<AuditLogEntity> pageResult = query.orderBy("create_time desc").page(page, pageSize);
        formatTime(pageResult.getList());
        return pageResult;
    }

    @Override
    public List<AuditLogEntity> listForExport(String actor, String action, String result, int limit) {
        LambdaQuery<AuditLogEntity> query = buildQuery(actor, action, result);
        int size = limit <= 0 ? 1000 : limit;
        List<AuditLogEntity> list = query.orderBy("create_time desc").limit(0, size).select();
        formatTime(list);
        return list;
    }

    private LambdaQuery<AuditLogEntity> buildQuery(String actor, String action, String result) {
        LambdaQuery<AuditLogEntity> query = auditLogDao.createLambdaQuery();
        if (actor != null && !actor.trim().isEmpty()) {
            query.andLike(AuditLogEntity::getActor, "%" + actor.trim() + "%");
        }
        if (action != null && !action.trim().isEmpty()) {
            query.andLike(AuditLogEntity::getAction, "%" + action.trim() + "%");
        }
        if ("ok".equalsIgnoreCase(String.valueOf(result)) || "fail".equalsIgnoreCase(String.valueOf(result))) {
            query.andEq(AuditLogEntity::getResult, String.valueOf(result).toLowerCase());
        }
        return query;
    }

    @Override
    public Map<String, Object> summary(int dayRange, int recentLimit) {
        int days = dayRange <= 0 ? 14 : dayRange;
        int recent = recentLimit <= 0 ? 8 : recentLimit;

        long now = System.currentTimeMillis();
        long since = now - (long) days * 24 * 3600 * 1000L;
        long since24h = now - 24 * 3600 * 1000L;

        // 取样上限固定，聚合在服务端内存完成（与统计页保持同一策略，
        // 避免依赖各数据库的日期函数方言）
        List<AuditLogEntity> rows = auditLogDao.createLambdaQuery()
                .orderBy("create_time desc")
                .limit(0, SUMMARY_SAMPLE)
                .select();

        int ok = 0;
        int fail = 0;
        int recentFail = 0;
        Map<String, Integer> byAction = new LinkedHashMap<>();
        // TreeMap 保证按日期升序，便于折线图直接使用
        Map<String, long[]> byDay = new TreeMap<>();

        for (AuditLogEntity row : rows) {
            if (row == null) {
                continue;
            }
            long ts = parseLong(row.getCreateTime());
            boolean isFail = "fail".equalsIgnoreCase(row.getResult());
            if (isFail) {
                fail++;
            } else {
                ok++;
            }
            if (isFail && ts >= since24h) {
                recentFail++;
            }
            if (ts < since) {
                continue;
            }
            String day = dayOf(ts);
            if (day != null) {
                long[] bucket = byDay.get(day);
                if (bucket == null) {
                    bucket = new long[2];
                    byDay.put(day, bucket);
                }
                if (isFail) {
                    bucket[1]++;
                } else {
                    bucket[0]++;
                }
            }
            String action = row.getAction() == null ? "" : row.getAction();
            byAction.merge(action, 1, Integer::sum);
        }

        List<Map<String, Object>> actionList = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : byAction.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("action", entry.getKey());
            item.put("count", entry.getValue());
            actionList.add(item);
        }
        actionList.sort((a, b) -> Integer.compare((Integer) b.get("count"), (Integer) a.get("count")));
        if (actionList.size() > 12) {
            actionList = new ArrayList<>(actionList.subList(0, 12));
        }

        List<Map<String, Object>> dayList = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : byDay.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("day", entry.getKey());
            item.put("ok", entry.getValue()[0]);
            item.put("fail", entry.getValue()[1]);
            dayList.add(item);
        }

        List<AuditLogEntity> recentRows = new ArrayList<>(Math.min(recent, rows.size()));
        for (int i = 0; i < rows.size() && i < recent; i++) {
            recentRows.add(rows.get(i));
        }
        // recent 是同一批对象，格式化会污染上面统计用的原始值，因此深拷贝一份再格式化
        List<AuditLogEntity> recentCopy = new ArrayList<>(recentRows.size());
        for (AuditLogEntity row : recentRows) {
            AuditLogEntity copy = new AuditLogEntity();
            copy.setId(row.getId());
            copy.setActor(row.getActor());
            copy.setActorType(row.getActorType());
            copy.setAction(row.getAction());
            copy.setTarget(row.getTarget());
            copy.setDetail(row.getDetail());
            copy.setResult(row.getResult());
            copy.setIp(row.getIp());
            copy.setCreateTime(row.getCreateTime());
            recentCopy.add(copy);
        }
        formatTime(recentCopy);

        Map<String, Object> total = new LinkedHashMap<>();
        total.put("total", rows.size());
        total.put("ok", ok);
        total.put("fail", fail);
        total.put("recentFail", recentFail);
        total.put("dropped", dropped.get());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("byAction", actionList);
        result.put("byDay", dayList);
        result.put("recent", recentCopy);
        return result;
    }

    @Override
    public int removeBatch(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (String id : ids) {
            removed += auditLogDao.deleteById(id);
        }
        return removed;
    }

    @Override
    public int removeExpired(int keepDays) {
        int days = keepDays <= 0 ? 90 : keepDays;
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_MONTH, -days);
        try {
            return auditLogDao.getSQLManager().executeUpdate(
                    new SQLReady("delete from sys_audit_log where create_time < ?",
                            String.valueOf(calendar.getTimeInMillis())));
        } catch (Throwable t) {
            log.warn("清理过期审计日志失败：{}", t.getMessage());
            return 0;
        }
    }

    @Override
    public long droppedCount() {
        return dropped.get();
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /**
     * 清洗待落库的文本：去控制字符（防止伪造日志行）、抹掉疑似凭据、按长度截断。
     */
    private static String clean(String value, int maxLength, String fallback) {
        if (value == null) {
            return fallback;
        }
        String v = scrub(CONTROL_CHARS.matcher(value).replaceAll(" ")).trim();
        if (v.length() > maxLength) {
            v = v.substring(0, maxLength) + "…";
        }
        return v.isEmpty() ? fallback : v;
    }

    /** 抹掉 password=/token=/secret= 之类的键值片段。 */
    private static String scrub(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return SECRET_PATTERN.matcher(value).replaceAll("$1=***");
    }

    private void formatTime(List<AuditLogEntity> list) {
        if (list == null) {
            return;
        }
        for (AuditLogEntity entity : list) {
            entity.setCreateTime(DateUtil.stampToDate(entity.getCreateTime()));
        }
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

    /** 把毫秒时间戳格式化为 yyyy-MM-dd（按服务器默认时区）。 */
    private static String dayOf(long timestamp) {
        if (timestamp <= 0) {
            return null;
        }
        return new java.text.SimpleDateFormat("yyyy-MM-dd").format(new java.util.Date(timestamp));
    }
}

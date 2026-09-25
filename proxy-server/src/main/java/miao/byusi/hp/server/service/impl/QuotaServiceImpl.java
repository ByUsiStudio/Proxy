package miao.byusi.hp.server.service.impl;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.core.ioc.annotation.Bean;
import miao.byusi.hp.server.dao.QuotaDao;
import miao.byusi.hp.server.domian.entity.ConfigEntity;
import miao.byusi.hp.server.domian.entity.QuotaEntity;
import miao.byusi.hp.server.domian.entity.StatisticsEntity;
import miao.byusi.hp.server.service.ConfigService;
import miao.byusi.hp.server.service.QuotaService;
import miao.byusi.hp.server.service.StatisticsService;
import miao.byusi.hp.server.utils.DateUtil;
import org.beetl.sql.core.page.PageResult;
import org.beetl.sql.core.query.LambdaQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 用量配额实现。
 * <p>
 * 设计要点（为什么这样做）：
 * <ol>
 *   <li><b>强制边界在「配置下发」</b>：云端看不到按账号的实时连接数，
 *       但完全掌控「是否给该账号新的隧道配置」以及「是否把既有隧道配置回给客户端」。
 *       因此超限的后果是：拒绝新建配置（{@code ConfigController.save} / 模板下发），
 *       并且 {@code ConfigController.listDevice} 不再返回隧道列表（客户端因此不会自动建隧道）。</li>
 *   <li><b>max_conns 不强制</b>：仅作为运营元数据保存与展示，见 QuotaService 的类注释。</li>
 *   <li><b>当月用量只做一次全表取样</b>：{@code refreshOverLimit} 只调用一次
 *       {@code StatisticsService.listForExport}（内部上限 20000 条，按时间倒序取最新的），
 *       在内存里按账号聚合当月流量，避免「每个账号跑一次聚合」把数据库打满。
 *       代价是：单月统计行数超过 20000 条的部署，当月用量会被低估，详见
 *       {@link #refreshOverLimit()} 的注释。</li>
 * </ol>
 */
@Bean
public class QuotaServiceImpl implements QuotaService {

    private static final Logger log = LoggerFactory.getLogger(QuotaServiceImpl.class);

    /**
     * 当月用量的取样上限，与 {@code StatisticsServiceImpl.SAMPLE_LIMIT} 保持一致。
     * 取最新的 N 条统计行，因此在正常规模的部署里覆盖完整当月数据。
     */
    private static final int STATS_SAMPLE_LIMIT = 20000;

    /** 单次刷新的最大配额条数：定时任务不允许无上限遍历，防止配额表被灌爆后拖垮服务 */
    private static final int MAX_REFRESH_QUOTAS = 2000;

    /** listAll / overLimitUsers 的最大返回条数 */
    private static final int MAX_LIST_ALL = 5000;

    @Autowired
    private QuotaDao quotaDao;

    @Autowired
    private StatisticsService statisticsService;

    /**
     * 隧道条数校验需要真实统计该账号已有的自动穿透配置条数，
     * 直接复用既有 ConfigService（不新增 DAO，也不改动它）。
     */
    @Autowired
    private ConfigService configService;

    @Override
    public QuotaEntity getByUserId(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return null;
        }
        List<QuotaEntity> list = quotaDao.createLambdaQuery()
                .andEq(QuotaEntity::getUserId, userId.trim())
                .limit(0, 1)
                .select();
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    @Override
    public QuotaEntity getByUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            return null;
        }
        List<QuotaEntity> list = quotaDao.createLambdaQuery()
                .andEq(QuotaEntity::getUsername, username.trim())
                .limit(0, 1)
                .select();
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    @Override
    public PageResult<QuotaEntity> list(Integer page, Integer pageSize, String username) {
        LambdaQuery<QuotaEntity> query = quotaDao.createLambdaQuery();
        if (username != null && !username.trim().isEmpty()) {
            // 参数化 LIKE，关键字由 BeetlSQL 绑定，不做 SQL 字符串拼接
            query.andLike(QuotaEntity::getUsername, "%" + username.trim() + "%");
        }
        PageResult<QuotaEntity> result = query.orderBy("update_time desc").page(page, pageSize);
        List<QuotaEntity> rows = result.getList();
        if (rows != null) {
            for (QuotaEntity row : rows) {
                row.setUpdateTime(DateUtil.stampToDate(row.getUpdateTime()));
                row.setCreateTime(DateUtil.stampToDate(row.getCreateTime()));
            }
        }
        return result;
    }

    @Override
    public List<QuotaEntity> listAll(int limit) {
        int size = limit <= 0 ? 1000 : Math.min(limit, MAX_LIST_ALL);
        List<QuotaEntity> list = quotaDao.createLambdaQuery().orderBy("update_time desc").limit(0, size).select();
        if (list != null) {
            for (QuotaEntity row : list) {
                row.setUpdateTime(DateUtil.stampToDate(row.getUpdateTime()));
                row.setCreateTime(DateUtil.stampToDate(row.getCreateTime()));
            }
        }
        return list;
    }

    @Override
    public boolean saveOrUpdate(QuotaEntity quota) {
        if (quota == null || quota.getUserId() == null || quota.getUserId().trim().isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        QuotaEntity existing = getByUserId(quota.getUserId());
        if (existing == null) {
            quota.setId(UUID.randomUUID().toString());
            quota.setCreateTime(String.valueOf(now));
            quota.setUpdateTime(String.valueOf(now));
            if (quota.getOverLimit() == null) {
                // 新配额在下次 refreshOverLimit 之前一律先按「未超限」展示
                quota.setOverLimit("false");
            }
            quotaDao.insert(quota);
            return true;
        }
        // 更新：以库中记录为基准补齐字段，避免 updateById 把未提交的列写成 null
        quota.setId(existing.getId());
        quota.setCreateTime(existing.getCreateTime());
        quota.setUpdateTime(String.valueOf(now));
        if (quota.getOverLimit() == null) {
            quota.setOverLimit(existing.getOverLimit());
        }
        if (quota.getOverReason() == null) {
            // 保留上一次的超限评估结果，直到 refreshOverLimit 重新计算
            quota.setOverReason(existing.getOverReason());
        }
        quota.setUserId(existing.getUserId());
        quotaDao.updateById(quota);
        return true;
    }

    @Override
    public boolean remove(String id) {
        if (id == null || id.trim().isEmpty()) {
            return false;
        }
        return quotaDao.deleteById(id.trim()) > 0;
    }

    @Override
    public int removeBatch(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int removed = 0;
        // 逐条主键删除：id 已在 ExportUtil.parseIds 做过字符白名单与条数上限校验
        for (String id : ids) {
            removed += quotaDao.deleteById(id);
        }
        return removed;
    }

    @Override
    public Map<String, Object> evaluate(QuotaEntity quota, long monthReceive, long monthSend) {
        Map<String, Object> result = new HashMap<>(4);
        if (quota == null || !quota.enabledFlag()) {
            result.put("over", Boolean.FALSE);
            result.put("reason", "配额未启用");
            return result;
        }
        long limitReceive = parseBytes(quota.getMonthlyReceive());
        long limitSend = parseBytes(quota.getMonthlySend());

        List<String> over = new ArrayList<>(2);
        if (limitReceive > 0 && monthReceive > limitReceive) {
            over.add("本月接收 " + fmtBytes(monthReceive) + " 超过上限 " + fmtBytes(limitReceive));
        }
        if (limitSend > 0 && monthSend > limitSend) {
            over.add("本月发送 " + fmtBytes(monthSend) + " 超过上限 " + fmtBytes(limitSend));
        }

        StringBuilder reason = new StringBuilder(96);
        if (!over.isEmpty()) {
            reason.append("已超限：").append(String.join("；", over));
            result.put("over", Boolean.TRUE);
        } else {
            // 未超限时也把用量摘要写进 over_reason：后台表格的悬浮提示据此展示用量，
            // 无需为每一行再跑一次统计聚合（配额列表页因此保持在一次查询内）。
            reason.append("未超限：本月接收 ").append(fmtBytes(monthReceive));
            reason.append(limitReceive > 0 ? (" / 上限 " + fmtBytes(limitReceive)) : " / 不限");
            reason.append("，发送 ").append(fmtBytes(monthSend));
            reason.append(limitSend > 0 ? (" / 上限 " + fmtBytes(limitSend)) : " / 不限");
            result.put("over", Boolean.FALSE);
        }
        result.put("reason", reason.toString());
        return result;
    }

    @Override
    public int refreshOverLimit() {
        String month = new SimpleDateFormat("yyyy-MM").format(new Date());

        // 1) 一次取样：StatisticsService 内部按 create_time 倒序取最新 20000 条。
        //    【已知边界】单月统计行数超过 20000 条的部署，当月用量会被低估（只统计到最新的样本），
        //    属于「宁可少算也不拖垮服务」的有意取舍；要精确统计需要在 sys_statistics 上做
        //    按账号 + 按月的 SQL 聚合，本轮不改动他人负责的统计模块。
        List<StatisticsEntity> sample;
        try {
            sample = statisticsService.listForExport(null, null, STATS_SAMPLE_LIMIT);
        } catch (Exception e) {
            log.error("配额刷新失败：读取统计样本异常 {}", e.getMessage());
            return 0;
        }
        Map<String, long[]> usage = new HashMap<>(64);
        if (sample != null) {
            for (StatisticsEntity row : sample) {
                if (row == null || row.getUsername() == null) {
                    continue;
                }
                String day = dayOf(row.getCreateTime());
                if (day == null || !day.startsWith(month)) {
                    continue;
                }
                long[] bucket = usage.get(row.getUsername());
                if (bucket == null) {
                    bucket = new long[2];
                    usage.put(row.getUsername(), bucket);
                }
                bucket[0] += parseBytes(row.getReceive());
                bucket[1] += parseBytes(row.getSend());
            }
        }

        // 2) 只遍历「已启用」的配额，且有条数上限（定时任务不允许无界遍历）
        List<QuotaEntity> quotas = quotaDao.createLambdaQuery()
                .andEq(QuotaEntity::getEnabled, 1)
                .limit(0, MAX_REFRESH_QUOTAS)
                .select();
        int evaluated = 0;
        int changed = 0;
        if (quotas != null) {
            for (QuotaEntity quota : quotas) {
                if (quota == null || quota.getUsername() == null) {
                    continue;
                }
                long[] bucket = usage.get(quota.getUsername());
                long receive = bucket == null ? 0L : bucket[0];
                long send = bucket == null ? 0L : bucket[1];
                Map<String, Object> evaluatedResult = evaluate(quota, receive, send);
                boolean over = Boolean.TRUE.equals(evaluatedResult.get("over"));
                String reason = String.valueOf(evaluatedResult.get("reason"));
                String flag = over ? "true" : "false";
                evaluated++;
                boolean same = flag.equalsIgnoreCase(quota.getOverLimit() == null ? "" : quota.getOverLimit())
                        && reason.equals(quota.getOverReason() == null ? "" : quota.getOverReason());
                if (same) {
                    continue;
                }
                quota.setOverLimit(flag);
                quota.setOverReason(reason);
                quota.setUpdateTime(String.valueOf(System.currentTimeMillis()));
                quotaDao.updateById(quota);
                changed++;
            }
        }
        log.info("配额超限刷新完成：评估 {} 个启用配额，更新 {} 条状态（当月 {}）", evaluated, changed, month);
        return evaluated;
    }

    @Override
    public String checkTunnelQuota(String userId, int requestedCount) {
        if (userId == null || userId.trim().isEmpty()) {
            return null;
        }
        QuotaEntity quota = getByUserId(userId);
        // 未配置配额或配额未启用 = 不限制；此时仍受 ConstConfig.PROXY_SIZE 的硬上限约束
        if (quota == null || !quota.enabledFlag()) {
            return null;
        }
        if (quota.overLimitFlag()) {
            String detail = quota.getOverReason() == null || quota.getOverReason().trim().isEmpty()
                    ? "本月用量已超过配额" : quota.getOverReason();
            return "账号已超出用量配额（" + detail + "），请联系管理员提升配额或等待次月重置";
        }
        Integer maxTunnels = quota.getMaxTunnels();
        if (maxTunnels != null && maxTunnels > 0) {
            int existing = countTunnels(userId);
            int wanted = Math.max(requestedCount, 0);
            if (existing + wanted > maxTunnels) {
                return "隧道数量已达配额上限（已有 " + existing + " 条，本次申请 " + wanted
                        + " 条，上限 " + maxTunnels + " 条）";
            }
        }
        return null;
    }

    @Override
    public String checkOverLimit(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return null;
        }
        QuotaEntity quota = getByUserId(userId);
        if (quota == null || !quota.enabledFlag() || !quota.overLimitFlag()) {
            return null;
        }
        String detail = quota.getOverReason() == null || quota.getOverReason().trim().isEmpty()
                ? "本月用量已超过配额" : quota.getOverReason();
        return "账号已超出用量配额（" + detail + "），客户端隧道下发已被暂停";
    }

    @Override
    public List<String> overLimitUsers() {
        List<QuotaEntity> list = quotaDao.createLambdaQuery()
                .andEq(QuotaEntity::getEnabled, 1)
                .andEq(QuotaEntity::getOverLimit, "true")
                .limit(0, MAX_LIST_ALL)
                .select();
        List<String> names = new ArrayList<>();
        if (list != null) {
            for (QuotaEntity quota : list) {
                if (quota != null && quota.getUsername() != null) {
                    names.add(quota.getUsername());
                }
            }
        }
        return names;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 统计该账号已有的自动穿透配置条数（配置数天然受 PROXY_SIZE 限制，不会很大）。 */
    private int countTunnels(String userId) {
        try {
            List<ConfigEntity> list = configService.list(userId);
            return list == null ? 0 : list.size();
        } catch (Exception e) {
            // 统计失败时按 0 处理（宁可少拦，也不要因为查询异常把所有下发都拒掉）
            log.warn("统计账号 {} 的隧道条数失败：{}", userId, e.getMessage());
            return 0;
        }
    }

    /**
     * 防御性解析「字节数」字符串：损坏/非数字一律按 0（= 不限制）处理，
     * 避免脏数据导致配额被误判为超限而阻断正常用户。
     */
    private static long parseBytes(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0L;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed < 0 ? 0L : parsed;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * 从 {@code yyyy-MM-dd HH:mm:ss} 取 {@code yyyy-MM}；
     * 若仍是毫秒时间戳则先转成日期串。
     */
    private static String dayOf(String createTime) {
        if (createTime == null || createTime.trim().isEmpty()) {
            return null;
        }
        String value = createTime.trim();
        if (value.length() >= 10 && value.charAt(4) == '-') {
            return value.length() >= 7 ? value.substring(0, 7) : null;
        }
        String formatted = DateUtil.stampToDate(value);
        if (formatted != null && formatted.length() >= 7 && formatted.charAt(4) == '-') {
            return formatted.substring(0, 7);
        }
        return null;
    }

    /** 人类可读的字节数（配额原因文案与报表共用同一套单位，避免口径不一致）。 */
    private static String fmtBytes(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        String[] units = {"B", "KB", "MB", "GB", "TB", "PB"};
        int index = 0;
        double value = bytes;
        while (value >= 1024 && index < units.length - 1) {
            value /= 1024;
            index++;
        }
        if (index == 0) {
            return bytes + " B";
        }
        return String.format("%.2f %s", value, units[index]);
    }
}

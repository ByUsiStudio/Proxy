package miao.byusi.hp.server.service.impl;

import miao.byusi.hp.server.dao.StatisticsDao;
import miao.byusi.hp.server.domian.bean.Statistics;
import miao.byusi.hp.server.domian.entity.StatisticsEntity;
import miao.byusi.hp.server.service.StatisticsService;
import miao.byusi.hp.server.utils.DateUtil;
import org.beetl.sql.core.SQLReady;
import org.beetl.sql.core.page.PageResult;
import org.beetl.sql.core.query.LambdaQuery;
import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.core.ioc.annotation.Bean;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * @author hxm
 */
@Bean
public class StatisticsServiceImpl implements StatisticsService {

    /**
     * 图表汇总的取样上限：避免一次性把整张表读进内存。
     * 取样按时间倒序，保证图表展示的是近期数据。
     */
    private static final int SAMPLE_LIMIT = 20000;

    @Autowired
    private StatisticsDao statisticsDao;

    @Override
    public void add(Statistics statistics) {
        StatisticsEntity statisticsEntity = new StatisticsEntity();
        statisticsEntity.setId(UUID.randomUUID().toString());
        statisticsEntity.setCreateTime(String.valueOf(System.currentTimeMillis()));
        statisticsEntity.setReceive(String.valueOf(statistics.getReceive()));
        statisticsEntity.setSend(String.valueOf(statistics.getSend()));
        statisticsEntity.setPackNum(String.valueOf(statistics.getPackNum()));
        statisticsEntity.setPort(statistics.getPort());
        statisticsEntity.setUsername(statistics.getUsername());
        statisticsEntity.setConnectNum(String.valueOf(statistics.getConnectNum()));
        statisticsDao.insert(statisticsEntity);
    }

    @Override
    public PageResult<StatisticsEntity> list(Integer page, Integer pageSize) {
        PageResult<StatisticsEntity> create_time_desc = statisticsDao.createLambdaQuery().orderBy("create_time desc").page(page, pageSize);
        formatTime(create_time_desc.getList());
        return create_time_desc;
    }

    @Override
    public PageResult<StatisticsEntity> list(Integer page, Integer pageSize, String username) {
        try {
            PageResult<StatisticsEntity> create_time_desc = statisticsDao.createLambdaQuery().andEq(StatisticsEntity::getUsername, username.trim()).orderBy("create_time desc").page(page, pageSize);
            formatTime(create_time_desc.getList());
            return create_time_desc;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public PageResult<StatisticsEntity> list(Integer page, Integer pageSize, String username, Integer port) {
        LambdaQuery<StatisticsEntity> query = statisticsDao.createLambdaQuery();
        if (username != null && !username.trim().isEmpty()) {
            // 参数化查询，关键字由 BeetlSQL 绑定，不做字符串拼接
            query.andLike(StatisticsEntity::getUsername, "%" + username.trim() + "%");
        }
        if (port != null) {
            query.andEq(StatisticsEntity::getPort, port);
        }
        PageResult<StatisticsEntity> result = query.orderBy("create_time desc").page(page, pageSize);
        formatTime(result.getList());
        return result;
    }

    @Override
    public List<StatisticsEntity> listForExport(String username, Integer port, int limit) {
        LambdaQuery<StatisticsEntity> query = statisticsDao.createLambdaQuery();
        if (username != null && !username.trim().isEmpty()) {
            query.andLike(StatisticsEntity::getUsername, "%" + username.trim() + "%");
        }
        if (port != null) {
            query.andEq(StatisticsEntity::getPort, port);
        }
        int size = limit <= 0 ? 1000 : limit;
        List<StatisticsEntity> list = query.orderBy("create_time desc").limit(0, size).select();
        formatTime(list);
        return list;
    }

    @Override
    public Map<String, Object> summary(String username, Integer port) {
        List<StatisticsEntity> sample = listForExport(username, port, SAMPLE_LIMIT);

        // 按端口聚合（LinkedHashMap 保持可读顺序，最终按入站流量倒序输出）
        Map<Integer, long[]> portAgg = new HashMap<>();
        // 按天聚合（TreeMap 保证日期升序）
        Map<String, long[]> dayAgg = new TreeMap<>();
        long totalReceive = 0L;
        long totalSend = 0L;
        long totalConnect = 0L;
        long totalPack = 0L;

        for (StatisticsEntity row : sample) {
            long receive = parseLong(row.getReceive());
            long send = parseLong(row.getSend());
            long connect = parseLong(row.getConnectNum());
            long pack = parseLong(row.getPackNum());

            totalReceive += receive;
            totalSend += send;
            totalConnect += connect;
            totalPack += pack;

            int key = row.getPort() == null ? 0 : row.getPort();
            long[] bucket = portAgg.get(key);
            if (bucket == null) {
                bucket = new long[4];
                portAgg.put(key, bucket);
            }
            bucket[0] += receive;
            bucket[1] += send;
            bucket[2] += connect;
            bucket[3] += pack;

            String day = dayOf(row.getCreateTime());
            if (day != null) {
                long[] dayBucket = dayAgg.get(day);
                if (dayBucket == null) {
                    dayBucket = new long[2];
                    dayAgg.put(day, dayBucket);
                }
                dayBucket[0] += receive;
                dayBucket[1] += send;
            }
        }

        List<Map<String, Object>> byPort = new ArrayList<>();
        for (Map.Entry<Integer, long[]> entry : portAgg.entrySet()) {
            long[] v = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("port", entry.getKey());
            item.put("receive", v[0]);
            item.put("send", v[1]);
            item.put("connectNum", v[2]);
            item.put("packNum", v[3]);
            byPort.add(item);
        }
        // 入站流量倒序，图表更直观
        byPort.sort((a, b) -> Long.compare((Long) b.get("receive"), (Long) a.get("receive")));

        List<Map<String, Object>> byDay = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : dayAgg.entrySet()) {
            long[] v = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("day", entry.getKey());
            item.put("receive", v[0]);
            item.put("send", v[1]);
            byDay.add(item);
        }

        Map<String, Object> total = new LinkedHashMap<>();
        total.put("receive", totalReceive);
        total.put("send", totalSend);
        total.put("connectNum", totalConnect);
        total.put("packNum", totalPack);
        total.put("rowCount", sample.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("byPort", byPort);
        result.put("byDay", byDay);
        result.put("total", total);
        return result;
    }

    @Override
    public void remove(String id) {
        statisticsDao.deleteById(id);
    }

    @Override
    public int removeBatch(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int removed = 0;
        // 逐条按主键删除：id 已在 ExportUtil.parseIds 做过字符白名单校验，
        // 这里再走 deleteById（参数绑定），不存在拼接注入。
        for (String id : ids) {
            removed += statisticsDao.deleteById(id);
        }
        return removed;
    }

    @Override
    public void removeExpData() {
        Calendar instance = Calendar.getInstance();
        instance.setTime(new Date());
        instance.add(Calendar.MONTH,-1);
        // 【安全修复】原实现把时间戳直接拼进 SQL，改为绑定参数
        statisticsDao.getSQLManager().executeUpdate(
                new SQLReady("delete from sys_statistics where create_time < ?", String.valueOf(instance.getTimeInMillis()))
        );
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private void formatTime(List<StatisticsEntity> list) {
        if (list == null) {
            return;
        }
        for (StatisticsEntity entity : list) {
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

    /**
     * 从已格式化的 {@code yyyy-MM-dd HH:mm:ss} 取日期部分；
     * 若该字段仍是原始毫秒时间戳（尚未格式化），则转换后再取。
     */
    private static String dayOf(String createTime) {
        if (createTime == null || createTime.trim().isEmpty()) {
            return null;
        }
        String value = createTime.trim();
        if (value.length() >= 10 && value.charAt(4) == '-') {
            return value.substring(0, 10);
        }
        if (value.length() >= 10) {
            String formatted = DateUtil.stampToDate(value);
            if (formatted != null && formatted.length() >= 10 && formatted.charAt(4) == '-') {
                return formatted.substring(0, 10);
            }
        }
        return null;
    }
}

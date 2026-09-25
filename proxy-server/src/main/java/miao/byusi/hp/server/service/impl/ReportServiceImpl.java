package miao.byusi.hp.server.service.impl;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.core.ioc.annotation.Bean;
import cn.hserver.core.ioc.annotation.Value;
import com.fasterxml.jackson.databind.ObjectMapper;
import miao.byusi.hp.server.domian.entity.StatisticsEntity;
import miao.byusi.hp.server.domian.vo.UserVo;
import miao.byusi.hp.server.service.ReportService;
import miao.byusi.hp.server.service.StatisticsService;
import miao.byusi.hp.server.service.UserService;
import miao.byusi.hp.server.utils.DateUtil;
import miao.byusi.hp.server.utils.ExportUtil;
import org.beetl.sql.core.page.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计报表实现。
 * <p>
 * 所有循环都有显式上限（统计取样 20000 条、用户取样 300 个、端口排行 20 行、
 * 报表天数 90 天），避免一个后台请求把整库读进内存。
 * <p>
 * 报表内容<b>不含任何口令/令牌</b>：它只由流量、连接数、数据包数与新增账号数组成。
 */
@Bean
public class ReportServiceImpl implements ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportServiceImpl.class);

    /** 报表覆盖天数的上限（同时保护内存与前端图表可读性） */
    private static final int MAX_DAYS = 90;
    /** 默认天数 */
    private static final int DEFAULT_DAYS = 14;
    /** 统计取样上限，与 StatisticsServiceImpl.SAMPLE_LIMIT 对齐 */
    private static final int STATS_SAMPLE_LIMIT = 20000;
    /** 「新增用户」取样的分页大小 */
    private static final int USER_PAGE_SIZE = 100;
    /** 「新增用户」取样的最大账号数（UserService.list 内部有按账号的额外查询，必须封顶） */
    private static final int USER_SAMPLE_LIMIT = 300;
    /** 按端口排行的最大行数 */
    private static final int MAX_PORT_ROWS = 20;
    /** 报表文件名的固定前缀 */
    private static final String REPORT_PREFIX = "report-";
    /** 配置缺省值（app.properties 未提供时使用） */
    private static final String DEFAULT_REPORT_DIR = "report";
    private static final int DEFAULT_INTERVAL_HOURS = 24;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private StatisticsService statisticsService;

    @Autowired
    private UserService userService;

    /**
     * 定时报表输出目录（相对进程工作目录）。
     * 由 app.properties 的 {@code admin.report.dir} 注入；缺失/null 时退回 "report"。
     */
    @Value("admin.report.dir")
    private String reportDir;

    /**
     * 定时报表生成周期（小时）。{@code <= 0} 表示关闭定时生成（手动生成仍可用）。
     * 由 app.properties 的 {@code admin.report.interval.hours} 注入。
     */
    @Value("admin.report.interval.hours")
    private String reportIntervalHours;

    @Override
    public Map<String, Object> summary(int days) {
        int window = clampDays(days);
        List<String> keys = dayKeys(window);
        String from = keys.isEmpty() ? "" : keys.get(0);

        // 按天桶：值为 [接收, 发送, 连接数, 数据包数]
        Map<String, long[]> dayAgg = new LinkedHashMap<>(window * 2);
        for (String key : keys) {
            dayAgg.put(key, new long[4]);
        }

        long totalReceive = 0L;
        long totalSend = 0L;
        long totalConnect = 0L;
        long totalPack = 0L;
        int sampledStats = 0;

        // 一次取样：StatisticsService 内部按 create_time 倒序取最新 20000 条
        List<StatisticsEntity> sample;
        try {
            sample = statisticsService.listForExport(null, null, STATS_SAMPLE_LIMIT);
        } catch (Exception e) {
            log.error("读取统计数据失败：{}", e.getMessage());
            sample = null;
        }
        // 按端口桶：值为 [接收, 发送, 连接数, 数据包数]
        Map<Integer, long[]> portAgg = new HashMap<>(32);
        if (sample != null) {
            sampledStats = sample.size();
            for (StatisticsEntity row : sample) {
                if (row == null) {
                    continue;
                }
                String day = dayOf(row.getCreateTime());
                if (day == null || day.compareTo(from) < 0) {
                    continue;
                }
                long[] bucket = dayAgg.get(day);
                if (bucket == null) {
                    // 落在窗口之外（例如取样里包含更新的数据）——忽略
                    continue;
                }
                long receive = parseLong(row.getReceive());
                long send = parseLong(row.getSend());
                long connect = parseLong(row.getConnectNum());
                long pack = parseLong(row.getPackNum());
                bucket[0] += receive;
                bucket[1] += send;
                bucket[2] += connect;
                bucket[3] += pack;
                totalReceive += receive;
                totalSend += send;
                totalConnect += connect;
                totalPack += pack;

                int portKey = row.getPort() == null ? 0 : row.getPort();
                long[] portBucket = portAgg.get(portKey);
                if (portBucket == null) {
                    portBucket = new long[4];
                    portAgg.put(portKey, portBucket);
                }
                portBucket[0] += receive;
                portBucket[1] += send;
                portBucket[2] += connect;
                portBucket[3] += pack;
            }
        }

        // 「新增用户」：UserService.list 每个账号还会查端口/域名，因此这里限制取样账号数
        Map<String, Integer> newUserAgg = new HashMap<>(window * 2);
        long userTotal = 0L;
        int sampledUsers = 0;
        try {
            int page = 1;
            while (sampledUsers < USER_SAMPLE_LIMIT) {
                PageResult<UserVo> result = userService.list(page, USER_PAGE_SIZE, null);
                if (result == null) {
                    break;
                }
                userTotal = result.getTotalRow();
                List<UserVo> users = result.getList();
                if (users == null || users.isEmpty()) {
                    break;
                }
                for (UserVo user : users) {
                    if (sampledUsers >= USER_SAMPLE_LIMIT) {
                        break;
                    }
                    sampledUsers++;
                    if (user == null) {
                        continue;
                    }
                    String day = dayOf(user.getCreateTime());
                    if (day != null && dayAgg.containsKey(day)) {
                        Integer count = newUserAgg.get(day);
                        newUserAgg.put(day, count == null ? 1 : count + 1);
                    }
                }
                long covered = (long) page * USER_PAGE_SIZE;
                if (covered >= userTotal || covered >= USER_SAMPLE_LIMIT) {
                    break;
                }
                page++;
            }
        } catch (Exception e) {
            log.warn("统计新增用户失败（不影响流量报表）：{}", e.getMessage());
        }

        List<Map<String, Object>> rows = new ArrayList<>(window);
        long totalNewUsers = 0L;
        for (String day : keys) {
            long[] bucket = dayAgg.get(day);
            int newUsers = newUserAgg.get(day) == null ? 0 : newUserAgg.get(day);
            totalNewUsers += newUsers;
            Map<String, Object> row = new LinkedHashMap<>(10);
            row.put("day", day);
            row.put("receive", bucket[0]);
            row.put("receiveText", fmtBytes(bucket[0]));
            row.put("send", bucket[1]);
            row.put("sendText", fmtBytes(bucket[1]));
            row.put("connectNum", bucket[2]);
            row.put("packNum", bucket[3]);
            row.put("newUsers", newUsers);
            rows.add(row);
        }

        List<Map<String, Object>> byPort = new ArrayList<>(portAgg.size());
        for (Map.Entry<Integer, long[]> entry : portAgg.entrySet()) {
            long[] value = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>(8);
            item.put("port", entry.getKey());
            item.put("receive", value[0]);
            item.put("receiveText", fmtBytes(value[0]));
            item.put("send", value[1]);
            item.put("sendText", fmtBytes(value[1]));
            item.put("connectNum", value[2]);
            item.put("packNum", value[3]);
            byPort.add(item);
        }
        byPort.sort((a, b) -> Long.compare((Long) b.get("receive"), (Long) a.get("receive")));
        if (byPort.size() > MAX_PORT_ROWS) {
            byPort = new ArrayList<>(byPort.subList(0, MAX_PORT_ROWS));
        }

        Map<String, Object> totals = new LinkedHashMap<>(16);
        totals.put("receive", totalReceive);
        totals.put("receiveText", fmtBytes(totalReceive));
        totals.put("send", totalSend);
        totals.put("sendText", fmtBytes(totalSend));
        totals.put("connectNum", totalConnect);
        totals.put("packNum", totalPack);
        totals.put("newUsers", totalNewUsers);
        totals.put("userTotal", userTotal);
        totals.put("sampledStats", sampledStats);
        totals.put("sampledUsers", sampledUsers);

        Map<String, Object> result = new LinkedHashMap<>(8);
        result.put("days", window);
        result.put("rows", rows);
        result.put("byPort", byPort);
        result.put("totals", totals);
        result.put("generatedAt", DateUtil.dateToStamp(new Date()));
        return result;
    }

    @Override
    public String exportCsv(int days) {
        Map<String, Object> summary = summary(days);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) summary.get("rows");
        List<String> headers = Arrays.asList("日期", "接收(字节)", "接收(可读)", "发送(字节)",
                "发送(可读)", "连接数", "数据包数", "新增用户");
        List<List<String>> cells = new ArrayList<>(rows == null ? 0 : rows.size());
        if (rows != null) {
            for (Map<String, Object> row : rows) {
                cells.add(Arrays.asList(
                        String.valueOf(row.get("day")),
                        String.valueOf(row.get("receive")),
                        String.valueOf(row.get("receiveText")),
                        String.valueOf(row.get("send")),
                        String.valueOf(row.get("sendText")),
                        String.valueOf(row.get("connectNum")),
                        String.valueOf(row.get("packNum")),
                        String.valueOf(row.get("newUsers"))
                ));
            }
        }
        return ExportUtil.csv(headers, cells);
    }

    @Override
    public String exportJson(int days) {
        try {
            // 用 Jackson 序列化：结构全部是 String/Long/List/Map，不含任何凭据
            return MAPPER.writeValueAsString(summary(days));
        } catch (Exception e) {
            log.error("报表 JSON 序列化失败：{}", e.getMessage());
            return "{}";
        }
    }

    @Override
    public String writeScheduledReport(int days, String dir) {
        File target = resolveDir(dir);
        if (!target.exists() && !target.mkdirs()) {
            log.error("报表输出目录不可创建：{}", target.getAbsolutePath());
            return null;
        }
        if (!target.isDirectory() || !target.canWrite()) {
            log.error("报表输出目录不可写：{}", target.getAbsolutePath());
            return null;
        }
        String name = REPORT_PREFIX + ExportUtil.timestamp() + ".csv";
        try {
            byte[] body = exportCsv(days).getBytes(StandardCharsets.UTF_8);
            Files.write(new File(target, name).toPath(), body);
        } catch (Exception e) {
            log.error("写入报表文件失败：{}", e.getMessage());
            return null;
        }
        return name;
    }

    @Override
    public String getReportDir() {
        String configured = reportDir == null ? "" : reportDir.trim();
        return configured.isEmpty() ? DEFAULT_REPORT_DIR : configured;
    }

    @Override
    public int getScheduledIntervalHours() {
        String raw = reportIntervalHours == null ? "" : reportIntervalHours.trim();
        if (raw.isEmpty()) {
            return DEFAULT_INTERVAL_HOURS;
        }
        try {
            // 允许 0 与负数：<= 0 表示关闭定时生成（手动生成不受影响）
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            log.warn("admin.report.interval.hours 配置值非法（{}），回退为默认 {} 小时", raw, DEFAULT_INTERVAL_HOURS);
            return DEFAULT_INTERVAL_HOURS;
        }
    }

    @Override
    public long lastReportTimeMillis(String dir) {
        File target = resolveDir(dir);
        if (!target.isDirectory()) {
            return -1L;
        }
        File[] files = target.listFiles();
        if (files == null || files.length == 0) {
            return -1L;
        }
        long latest = -1L;
        // 目录内容由我们自己生成，文件数量极小；仍然限制扫描数量以防目录被塞满
        int scanned = 0;
        for (File file : files) {
            if (file == null || !file.isFile() || scanned >= 1000) {
                continue;
            }
            String name = file.getName();
            if (!name.startsWith(REPORT_PREFIX) || !name.endsWith(".csv")) {
                continue;
            }
            scanned++;
            latest = Math.max(latest, file.lastModified());
        }
        return latest;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 天数收敛：非法/过大一律夹到 [1, MAX_DAYS]，默认 14 天。 */
    private static int clampDays(int days) {
        if (days <= 0) {
            return DEFAULT_DAYS;
        }
        return Math.min(days, MAX_DAYS);
    }

    /** 生成最近 window 天的日期键（含今天），按升序。 */
    private static List<String> dayKeys(int window) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_MONTH, -(window - 1));
        List<String> keys = new ArrayList<>(window);
        for (int i = 0; i < window; i++) {
            keys.add(format.format(calendar.getTime()));
            calendar.add(Calendar.DAY_OF_MONTH, 1);
        }
        return keys;
    }

    /**
     * 解析输出目录。
     * <p>
     * 安全约束：只接受「工作目录下的相对子目录」，任何绝对路径、盘符、{@code ..}
     * 都会被丢弃并退回默认目录，避免配置错误（或被篡改的配置）把报表写到系统目录。
     */
    private static File resolveDir(String dir) {
        String base = System.getProperty("user.dir");
        if (base == null || base.trim().isEmpty()) {
            base = ".";
        }
        String relative = dir == null ? "" : dir.trim();
        if (relative.isEmpty()
                || relative.contains("..")
                || relative.contains(":")
                || relative.startsWith("/")
                || relative.startsWith("\\")) {
            relative = DEFAULT_REPORT_DIR;
        }
        File candidate = new File(base, relative);
        String basePath = new File(base).getAbsolutePath();
        String targetPath = candidate.getAbsolutePath();
        if (!targetPath.equals(basePath) && !targetPath.startsWith(basePath + File.separator)) {
            // 归一化后越出工作目录 → 退回默认目录
            return new File(base, DEFAULT_REPORT_DIR);
        }
        return candidate;
    }

    /** 从 {@code yyyy-MM-dd HH:mm:ss} 取日期部分；若仍是毫秒时间戳则先转换。 */
    private static String dayOf(String createTime) {
        if (createTime == null || createTime.trim().isEmpty()) {
            return null;
        }
        String value = createTime.trim();
        if (value.length() >= 10 && value.charAt(4) == '-') {
            return value.substring(0, 10);
        }
        String formatted = DateUtil.stampToDate(value);
        if (formatted != null && formatted.length() >= 10 && formatted.charAt(4) == '-') {
            return formatted.substring(0, 10);
        }
        return null;
    }

    private static long parseLong(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 人类可读字节数（与配额模块同一套单位口径）。 */
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

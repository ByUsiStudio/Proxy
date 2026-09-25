package miao.byusi.hp.server.service;

import java.util.Map;

/**
 * 统计报表服务：按天汇总 + CSV / JSON 导出 + 定时落盘。
 * <p>
 * <b>为什么报表基于「有界取样」而不是精确 SQL 聚合：</b>
 * sys_statistics 的按天/按端口聚合由 {@code StatisticsService} 在服务端内存中完成
 * （内部取样上限 {@code SAMPLE_LIMIT = 20000}，按时间倒序取最新数据），
 * 这样既不引入各数据库方言差异，也不新增 SQL 注入面。报表沿用同一份取样口径，
 * 保证「仪表盘 / 用户日志页 / 报表页」三处数字一致；代价是统计行数超过取样上限的
 * 部署会出现低估，此时应改为在数据库侧做聚合（属于统计模块的后续工作）。
 * <p>
 * 配置文件里 {@code admin.report.dir} 是定时报表的输出目录（相对进程工作目录，
 * 默认 {@code report}）；{@code admin.report.interval.hours} 是生成周期（小时），
 * {@code <= 0} 表示不启用定时生成，手动生成不受影响。
 */
public interface ReportService {

    /**
     * 最近 N 天的日报表汇总。
     *
     * @param days 天数（内部会收敛到 1..90）
     * @return 结构：
     * <pre>
     * {
     *   "days": 14,
     *   "rows": [ {"day":"2024-05-01","receive":123,"receiveText":"123 B","send":45,
     *              "sendText":"45 B","connectNum":3,"packNum":9,"newUsers":1}, ... ],  // 按日期升序、缺日补零
     *   "byPort": [ {"port":8080,"receive":..,"send":..,"connectNum":..,"packNum":..} ], // 按接收流量倒序
     *   "totals": {"receive":..,"send":..,"receiveText":..,"sendText":..,"connectNum":..,
     *              "packNum":..,"newUsers":..,"userTotal":..,"sampledStats":..,"sampledUsers":..},
     *   "generatedAt": "yyyy-MM-dd HH:mm:ss"
     * }
     * </pre>
     */
    Map<String, Object> summary(int days);

    /** 导出 CSV 文本（带 UTF-8 BOM 与公式注入防护，由 {@code ExportUtil.csv} 生成）。 */
    String exportCsv(int days);

    /** 导出 JSON 文本（与 summary 同结构，可被前端图表直接消费）。 */
    String exportJson(int days);

    /**
     * 立即生成一份报表文件到指定目录。
     *
     * @param days 报表覆盖的天数
     * @param dir  目标目录（相对 {@code System.getProperty("user.dir")}）；为空/非法时退回默认目录
     * @return 生成的文件名（如 {@code report-20240501-120000.csv}）；失败返回 null
     */
    String writeScheduledReport(int days, String dir);

    /** 配置的报表输出目录（未配置时返回默认值 "report"）。 */
    String getReportDir();

    /** 配置的定时生成周期（小时）；{@code <= 0} 表示关闭定时生成。 */
    int getScheduledIntervalHours();

    /**
     * 目录中最新一份 {@code report-*.csv} 的修改时间（毫秒）；没有报表时返回 -1。
     * 定时任务据此判断「是否已到生成周期」，重启后依然有效。
     */
    long lastReportTimeMillis(String dir);
}

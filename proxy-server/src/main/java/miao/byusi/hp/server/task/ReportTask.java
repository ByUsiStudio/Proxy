package miao.byusi.hp.server.task;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.core.ioc.annotation.Bean;
import cn.hserver.core.ioc.annotation.Task;
import miao.byusi.hp.server.service.ReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 定时统计报表：按配置周期把最近 30 天的日报表写成 CSV 落盘。
 * <p>
 * 调度机制完全照搬 {@code FlowTask}（HServer 的 {@code @Task(name, time)}）。
 * <b>为什么固定每小时醒一次而不是直接用配置的周期：</b>
 * HServer 的 {@code @Task.time} 是编译期常量，无法注入 app.properties 的值，
 * 因此这里每小时醒来做一次「是否到期」判断，到期条件由
 * {@code admin.report.interval.hours} 决定（{@code <= 0} 表示关闭定时生成，
 * 手动「立即生成报表」不受影响）；是否到期以输出目录里最新一份
 * {@code report-*.csv} 的修改时间为准，因此重启不会丢掉周期信息。
 * <p>
 * 任务体内部整体捕获异常，绝不让异常抛出任务边界。
 */
@Bean
public class ReportTask {

    private static final Logger log = LoggerFactory.getLogger(ReportTask.class);

    /** 定时报表覆盖的天数 */
    private static final int REPORT_DAYS = 30;

    /** 一小时的毫秒数，同时用于「是否到期」换算 */
    private static final long HOUR_MILLIS = 3600000L;

    @Autowired
    private ReportService reportService;

    /**
     * 3600 * 1000 = 3600000 毫秒（每小时醒一次，内部按配置判断是否到期）。
     */
    @Task(name = "定时生成统计报表", time = "3600000")
    public void generateReport() {
        try {
            int intervalHours = reportService.getScheduledIntervalHours();
            if (intervalHours <= 0) {
                // 已关闭定时生成：静默返回（配置项说明见 app.properties）
                return;
            }
            String dir = reportService.getReportDir();
            long last = reportService.lastReportTimeMillis(dir);
            long intervalMillis = intervalHours * HOUR_MILLIS;
            if (last > 0 && System.currentTimeMillis() - last < intervalMillis) {
                return;
            }
            String name = reportService.writeScheduledReport(REPORT_DAYS, dir);
            if (name == null) {
                log.warn("定时统计报表生成失败：输出目录不可写 -> {}", dir);
            } else {
                log.info("定时统计报表已生成：{}/{}（覆盖最近 {} 天）", dir, name, REPORT_DAYS);
            }
        } catch (Exception e) {
            log.error("定时统计报表任务异常：{}", e.getMessage());
        }
    }
}

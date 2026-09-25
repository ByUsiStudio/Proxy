package miao.byusi.hp.server.controller.admin;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.plugin.web.annotation.Controller;
import cn.hserver.plugin.web.annotation.GET;
import cn.hserver.plugin.web.annotation.POST;
import cn.hserver.plugin.web.interfaces.HttpRequest;
import cn.hserver.plugin.web.interfaces.HttpResponse;
import miao.byusi.hp.server.service.AuditService;
import miao.byusi.hp.server.service.ReportService;
import miao.byusi.hp.server.utils.AdminAudit;
import miao.byusi.hp.server.utils.ExportUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 后台「统计报表」。
 * <p>
 * 路由与 AuthFilter 的约定（该文件由他人负责，不能改）：
 * <ul>
 *   <li>{@code /admin/report}、{@code /admin/report/download} 是只读 GET，已在白名单内；</li>
 *   <li>{@code POST /admin/report/generate} 是状态变更（写文件），必须 POST。</li>
 * </ul>
 * <b>关于 {@code GET /admin/report/data}：</b>该只读接口按需求实现，
 * 但 AuthFilter 的 {@code ADMIN_GET_PAGES} 白名单里<b>目前没有</b>这一条，
 * 因此直接以 GET 访问会拿到 405。页面因此不依赖它：
 * 「切换天数」走的是同样只读、且已在白名单内的
 * {@code /admin/report/download?format=json}（前端 fetch 读取 JSON 后重绘图表与表格），
 * 保证功能在当前白名单下即可用。把该路由补进白名单后，
 * {@code /admin/report/data} 即可作为对外的图表数据接口使用（详见交付报告）。
 */
@Controller
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    /** 默认统计天数 */
    private static final int DEFAULT_DAYS = 14;
    /** 最大统计天数（与 ReportServiceImpl.MAX_DAYS 一致） */
    private static final int MAX_DAYS = 90;

    @Autowired
    private ReportService reportService;

    /**
     * 审计服务：手动生成报表会在磁盘上写文件（action=report.generate）。
     * 只读页面 / 下载 / 图表数据不写审计——它们不改变服务端状态，
     * 每次刷新都写会淹没真正有价值的操作记录。
     */
    @Autowired
    private AuditService auditService;

    /**
     * 报表页：首屏由服务端渲染 KPI / 表格 / 图表数据（无需额外的 GET 接口即可显示）。
     */
    @GET("/admin/report")
    public void page(Integer days, HttpResponse response) {
        int window = clamp(days);
        Map<String, Object> summary = reportService.summary(window);
        Map<String, Object> data = new HashMap<>(16);
        data.put("days", window);
        data.put("rows", summary.get("rows"));
        data.put("totals", summary.get("totals"));
        data.put("byPort", summary.get("byPort"));
        data.put("generatedAt", summary.get("generatedAt"));
        // 图表数据以 JSON 字符串注入 data-* 属性（模板里用 ?html 转义，前端 JSON.parse 还原）
        data.put("reportJson", reportService.exportJson(window));
        data.put("reportDir", reportService.getReportDir());
        data.put("intervalHours", reportService.getScheduledIntervalHours());
        response.sendTemplate("/admin/report.ftl", data);
    }

    /**
     * 图表数据（JSON）。
     * 注意：需要把 {@code /admin/report/data} 加入 AuthFilter 的只读 GET 白名单才能访问（见类注释）。
     */
    @GET("/admin/report/data")
    public void data(Integer days, HttpResponse response) {
        response.sendJson(reportService.summary(clamp(days)));
    }

    /**
     * 下载报表。
     *
     * @param format csv（默认）或 json
     */
    @GET("/admin/report/download")
    public void download(Integer days, String format, HttpResponse response) {
        int window = clamp(days);
        boolean asJson = "json".equalsIgnoreCase(format == null ? "" : format.trim());
        String stamp = ExportUtil.timestamp();
        if (asJson) {
            ExportUtil.attachment(response, "proxy-report-" + stamp + ".json",
                    "application/json; charset=utf-8", reportService.exportJson(window));
            return;
        }
        ExportUtil.attachment(response, "proxy-report-" + stamp + ".csv",
                "text/csv; charset=utf-8", reportService.exportCsv(window));
    }

    /**
     * 立即生成一份报表文件（与定时任务写的是同一种文件），返回文件名。
     */
    @POST("/admin/report/generate")
    public void generate(Integer days, HttpResponse response, HttpRequest request) {
        int window = clamp(days);
        String dir = reportService.getReportDir();
        String filename;
        try {
            filename = reportService.writeScheduledReport(window, dir);
        } catch (Exception e) {
            log.error("生成报表失败：{}", e.getMessage());
            AdminAudit.record(auditService, request, "report.generate", "",
                    "生成失败（最近 " + window + " 天）：写入异常", false);
            response.sendJson(error("生成报表失败，请检查报表目录是否可写"));
            return;
        }
        if (filename == null) {
            AdminAudit.record(auditService, request, "report.generate", "",
                    "生成失败（最近 " + window + " 天）：目录不可写", false);
            response.sendJson(error("生成报表失败：目录不可写（" + dir + "）"));
            return;
        }
        log.info("后台手动生成统计报表：{}/{}（最近 {} 天）", dir, filename, window);
        // 摘要写「文件名 + 统计区间」，便于事后定位具体产出物
        AdminAudit.record(auditService, request, "report.generate", filename,
                "生成报表文件（最近 " + window + " 天）", true);
        Map<String, Object> result = ok("已生成报表 " + filename);
        result.put("filename", filename);
        result.put("dir", dir);
        response.sendJson(result);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 天数收敛：<=0 用默认 14 天，上限 90 天。 */
    private static int clamp(Integer days) {
        if (days == null || days <= 0) {
            return DEFAULT_DAYS;
        }
        return Math.min(days, MAX_DAYS);
    }

    private static Map<String, Object> ok(String message) {
        Map<String, Object> map = new HashMap<>(4);
        map.put("code", 200);
        map.put("msg", message);
        return map;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> map = new HashMap<>(4);
        map.put("code", -1);
        map.put("msg", message);
        return map;
    }
}

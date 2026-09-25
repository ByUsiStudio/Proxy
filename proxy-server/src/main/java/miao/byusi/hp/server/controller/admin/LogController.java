package miao.byusi.hp.server.controller.admin;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.plugin.web.annotation.Controller;
import cn.hserver.plugin.web.annotation.GET;
import cn.hserver.plugin.web.annotation.POST;
import cn.hserver.plugin.web.interfaces.HttpRequest;
import cn.hserver.plugin.web.interfaces.HttpResponse;
import miao.byusi.hp.server.domian.entity.StatisticsEntity;
import miao.byusi.hp.server.service.AuditService;
import miao.byusi.hp.server.service.StatisticsService;
import miao.byusi.hp.server.utils.AdminAudit;
import miao.byusi.hp.server.utils.ExportUtil;
import org.beetl.sql.core.page.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台「用户日志 / 流量统计」。
 * <p>
 * 本轮新增能力（对应前端需求）：
 * <ul>
 *   <li>搜索过滤：用户名模糊 + 端口精确，条件随分页链接保持；</li>
 *   <li>导出：{@code /admin/log/export} 支持 CSV / JSON，导出的是<b>当前过滤条件下的全量数据</b>而非当前页；</li>
 *   <li>流量统计图表：{@code /admin/log/stats} 返回按端口与按天聚合的 JSON，供前端 Canvas 绘图；</li>
 *   <li>批量操作：{@code /admin/log/batchRemove} 一次删除多条统计记录。</li>
 * </ul>
 * 安全约定：
 * <ul>
 *   <li>所有查询条件都通过 BeetlSQL 的 LambdaQuery 参数绑定，不做 SQL 字符串拼接；</li>
 *   <li>批量删除的 id 先经 {@link ExportUtil#parseIds} 做字符白名单与数量上限校验；</li>
 *   <li>导出文件名由服务端生成，导出体积有硬上限，避免被用作数据库导出 DoS；</li>
 *   <li>导出内容经过 CSV 公式注入防护（见 ExportUtil）。</li>
 * </ul>
 */
@Controller
public class LogController {

    private static final Logger log = LoggerFactory.getLogger(LogController.class);

    /** 单次导出的最大行数，防止一条请求把整库拖出来 */
    private static final int MAX_EXPORT_ROWS = 20000;
    /** 单次批量删除的最大条数 */
    private static final int MAX_BATCH_DELETE = 500;

    @Autowired
    private StatisticsService statisticsService;

    /**
     * 审计服务：统计记录的删除（含 GET 形式）会真删数据，必须留痕
     * （action=log.batchRemove / log.remove）。
     * 查询 / 导出 / 图表都是只读，不写审计。
     */
    @Autowired
    private AuditService auditService;

    @GET("/admin/log")
    public void log(Integer page, String username, Integer port, HttpResponse response) {
        if (page == null || page < 1) {
            page = 1;
        }
        PageResult<StatisticsEntity> list = statisticsService.list(page, 10, username, port);
        Map<String, Object> data = new HashMap<>(8);
        data.put("page", page);
        data.put("pageSize", 10);
        data.put("totalRow", list.getTotalRow());
        data.put("list", list.getList());
        data.put("totalPage", list.getTotalPage());
        data.put("username", username == null ? "" : username);
        data.put("port", port == null ? "" : String.valueOf(port));
        response.sendTemplate("/admin/log.ftl", data);
    }

    /**
     * 按当前过滤条件导出全部匹配记录。
     *
     * @param format csv（默认）或 json
     */
    @GET("/admin/log/export")
    public void export(String username, Integer port, String format, HttpResponse response) {
        boolean asJson = "json".equalsIgnoreCase(format == null ? "" : format.trim());
        List<StatisticsEntity> rows = statisticsService.listForExport(username, port, MAX_EXPORT_ROWS);
        String stamp = ExportUtil.timestamp();

        if (asJson) {
            String body = buildJson(rows);
            ExportUtil.attachment(response, "proxy-statistics-" + stamp + ".json",
                    "application/json; charset=utf-8", body);
            return;
        }

        List<String> headers = Arrays.asList("id", "用户名", "端口", "接收(字节)", "发送(字节)", "连接数", "数据包数", "时间");
        List<List<String>> cells = new ArrayList<>(rows.size());
        for (StatisticsEntity row : rows) {
            cells.add(Arrays.asList(
                    row.getId(),
                    row.getUsername(),
                    row.getPort() == null ? "" : String.valueOf(row.getPort()),
                    row.getReceive(),
                    row.getSend(),
                    row.getConnectNum(),
                    row.getPackNum(),
                    row.getCreateTime()
            ));
        }
        ExportUtil.attachment(response, "proxy-statistics-" + stamp + ".csv",
                "text/csv; charset=utf-8", ExportUtil.csv(headers, cells));
    }

    /**
     * 图表数据：按端口与按天聚合。
     */
    @GET("/admin/log/stats")
    public void stats(String username, Integer port, HttpResponse response) {
        Map<String, Object> summary = statisticsService.summary(username, port);
        response.sendJson(summary);
    }

    /**
     * 批量删除统计记录。
     *
     * @param ids 逗号分隔的记录 id
     */
    @POST("/admin/log/batchRemove")
    public void batchRemove(String ids, String username, Integer port, HttpResponse response, HttpRequest request) {
        List<String> list = ExportUtil.parseIds(ids, MAX_BATCH_DELETE);
        if (list.isEmpty()) {
            AdminAudit.record(auditService, request, "log.batchRemove", "", "未选择要删除的记录", false);
            response.sendJson(error("未选择要删除的记录"));
            return;
        }
        int removed;
        try {
            removed = statisticsService.removeBatch(list);
        } catch (Exception e) {
            log.error("批量删除统计记录失败：{}", e.getMessage());
            AdminAudit.record(auditService, request, "log.batchRemove", "",
                    "批量删除异常：请求 " + list.size() + " 条", false);
            response.sendJson(error("批量删除失败"));
            return;
        }
        log.info("后台批量删除统计记录 {} 条（请求 {} 条）", removed, list.size());
        AdminAudit.record(auditService, request, "log.batchRemove", "",
                "请求 " + list.size() + " 条，删除 " + removed + " 条", true);
        Map<String, Object> ok = new HashMap<>(4);
        ok.put("code", 200);
        ok.put("msg", "已删除 " + removed + " 条记录");
        ok.put("removed", removed);
        response.sendJson(ok);
    }

    @GET("/admin/log/remove")
    public void remove(Integer page, HttpResponse response, String id, HttpRequest request) {
        if (id != null) {
            // 该入口由后台页面的 GET 链接触发，但同样是「真删数据」的状态变更操作，必须留痕。
            // statisticsService.remove 无返回值（不区分 id 是否存在），
            // 因此成功与否以「是否抛异常」为准；异常照旧向上抛出，保持原有错误行为。
            try {
                statisticsService.remove(id);
                AdminAudit.record(auditService, request, "log.remove", id, "删除统计记录", true);
            } catch (RuntimeException e) {
                AdminAudit.record(auditService, request, "log.remove", id, "删除异常", false);
                throw e;
            }
        }
        log(page, null, null, response);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private static Map<String, Object> error(String message) {
        Map<String, Object> map = new HashMap<>(2);
        map.put("code", -1);
        map.put("msg", message);
        return map;
    }

    /**
     * 手工拼装 JSON：字段全部来自数据库且已知为字符串/数字，
     * 这里统一做 JSON 字符串转义，避免引号或控制字符破坏结构。
     */
    private static String buildJson(List<StatisticsEntity> rows) {
        StringBuilder sb = new StringBuilder(rows.size() * 160 + 64);
        sb.append("{\"exportedAt\":\"").append(jsonEscape(ExportUtil.timestamp())).append("\",\"rows\":[");
        for (int i = 0; i < rows.size(); i++) {
            StatisticsEntity row = rows.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{');
            sb.append("\"id\":\"").append(jsonEscape(row.getId())).append("\",");
            sb.append("\"username\":\"").append(jsonEscape(row.getUsername())).append("\",");
            sb.append("\"port\":").append(row.getPort() == null ? "null" : row.getPort()).append(',');
            sb.append("\"receive\":\"").append(jsonEscape(row.getReceive())).append("\",");
            sb.append("\"send\":\"").append(jsonEscape(row.getSend())).append("\",");
            sb.append("\"connectNum\":\"").append(jsonEscape(row.getConnectNum())).append("\",");
            sb.append("\"packNum\":\"").append(jsonEscape(row.getPackNum())).append("\",");
            sb.append("\"createTime\":\"").append(jsonEscape(row.getCreateTime())).append("\"");
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}

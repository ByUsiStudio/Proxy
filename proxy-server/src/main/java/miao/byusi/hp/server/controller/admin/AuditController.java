package miao.byusi.hp.server.controller.admin;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.plugin.web.annotation.Controller;
import cn.hserver.plugin.web.annotation.GET;
import cn.hserver.plugin.web.annotation.POST;
import cn.hserver.plugin.web.interfaces.HttpRequest;
import cn.hserver.plugin.web.interfaces.HttpResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import miao.byusi.hp.server.domian.entity.AuditLogEntity;
import miao.byusi.hp.server.service.AuditService;
import miao.byusi.hp.server.utils.AdminAudit;
import miao.byusi.hp.server.utils.ExportUtil;
import org.beetl.sql.core.page.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台「审计日志」。
 * <p>
 * 能力：
 * <ul>
 *   <li>{@code GET /admin/audit}：按操作者 / 动作 / 结果筛选，时间倒序分页（10 条/页）；</li>
 *   <li>{@code GET /admin/audit/export}：导出<b>当前筛选条件下的全量</b>记录（CSV / JSON，上限 2 万条）；</li>
 *   <li>{@code GET /admin/audit/stats}：按天成功/失败、高频动作、最近事件的聚合，供图表与失败徽标使用；</li>
 *   <li>{@code POST /admin/audit/batchRemove}：批量删除审计记录。</li>
 * </ul>
 * 安全约定：
 * <ul>
 *   <li>所有查询条件都走 {@link AuditService}，内部用 BeetlSQL 参数绑定，不做 SQL 字符串拼接；</li>
 *   <li><b>绝不直接读写 sys_audit_log 表</b>：写由 AuditService 的异步有界队列负责（保证审计写入
 *       不阻塞业务、写不过来时丢弃而不是把服务拖垮），删由 AuditService 做白名单后的按 id 删除；</li>
 *   <li>批量删除的 id 先经 {@link ExportUtil#parseIds} 做字符白名单与数量上限校验；</li>
 *   <li>导出文件名由服务端生成（固定前缀 + 时间戳），导出内容经过 CSV 公式注入防护；</li>
 *   <li>接口只返回审计表已有字段，不会额外带出任何凭据（审计表本身在写入时已脱敏）。</li>
 * </ul>
 * <p>
 * 注意 {@code POST /admin/audit/batchRemove} 是<b>状态变更</b>接口，故意不在 AuthFilter 的只读 GET
 * 白名单里：它必须由页面脚本以 POST + 同源校验的方式调用，跨站表单无法伪造同源头。
 */
@Controller
public class AuditController {

    private static final Logger log = LoggerFactory.getLogger(AuditController.class);

    /** 单页条数 */
    private static final int PAGE_SIZE = 10;
    /** 单次导出的最大行数，防止一条请求把整表拖出来 */
    private static final int MAX_EXPORT_ROWS = 20000;
    /** 单次批量删除的最大条数 */
    private static final int MAX_BATCH_DELETE = 500;
    /** 统计接口的天数/条数上限，避免前端传入极端值 */
    private static final int MAX_STAT_DAYS = 90;
    private static final int MAX_STAT_RECENT = 50;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private AuditService auditService;

    /**
     * 审计日志列表页。
     *
     * @param actor  操作者关键字（模糊），可为空
     * @param action 动作标识关键字（模糊），可为空
     * @param result 结果过滤：ok / fail，其它值按「全部」处理
     */
    @GET("/admin/audit")
    public void index(Integer page, String actor, String action, String result, HttpResponse response) {
        int current = (page == null || page < 1) ? 1 : page;
        PageResult<AuditLogEntity> list = auditService.list(current, PAGE_SIZE, actor, action, result);
        Map<String, Object> data = new HashMap<>(8);
        data.put("page", current);
        data.put("pageSize", PAGE_SIZE);
        data.put("totalRow", list.getTotalRow());
        data.put("list", list.getList());
        data.put("totalPage", list.getTotalPage());
        data.put("actor", actor == null ? "" : actor);
        data.put("action", action == null ? "" : action);
        // 归一化，模板里用同一个值渲染下拉框的选中项
        data.put("result", normalizeResult(result));
        response.sendTemplate("/admin/audit.ftl", data);
    }

    /**
     * 按当前筛选条件导出全量审计记录。
     *
     * @param format csv（默认）或 json
     */
    @GET("/admin/audit/export")
    public void export(String actor, String action, String result, String format,
                       HttpRequest request, HttpResponse response) {
        boolean asJson = "json".equalsIgnoreCase(format == null ? "" : format.trim());
        List<AuditLogEntity> rows = auditService.listForExport(actor, action, result, MAX_EXPORT_ROWS);
        String stamp = ExportUtil.timestamp();

        // 导出审计日志本身也是敏感操作（等于把操作留痕整批带走），因此同样留痕
        AdminAudit.record(auditService, request, "audit.export", stamp,
                "导出审计记录 " + rows.size() + " 条（" + (asJson ? "JSON" : "CSV") + "）", true);

        if (asJson) {
            ExportUtil.attachment(response, "proxy-audit-" + stamp + ".json",
                    "application/json; charset=utf-8", buildJson(rows, stamp));
            return;
        }

        List<String> headers = Arrays.asList(
                "id", "时间", "操作者类型", "操作者", "动作", "目标", "详情", "结果", "来源IP", "User-Agent");
        List<List<String>> cells = new ArrayList<>(rows.size());
        for (AuditLogEntity row : rows) {
            cells.add(Arrays.asList(
                    row.getId(),
                    row.getCreateTime(),
                    row.getActorType(),
                    row.getActor(),
                    row.getAction(),
                    row.getTarget(),
                    row.getDetail(),
                    "fail".equalsIgnoreCase(row.getResult()) ? "失败" : "成功",
                    row.getIp(),
                    row.getUserAgent()
            ));
        }
        ExportUtil.attachment(response, "proxy-audit-" + stamp + ".csv",
                "text/csv; charset=utf-8", ExportUtil.csv(headers, cells));
    }

    /**
     * 图表 / 徽标数据。直接返回 {@link AuditService#summary} 的 Map，由框架序列化成 JSON，
     * 不手写 JSON 字符串（避免转义遗漏）。
     */
    @GET("/admin/audit/stats")
    public void stats(Integer days, Integer recent, HttpResponse response) {
        int range = (days == null || days < 1) ? 14 : Math.min(days, MAX_STAT_DAYS);
        int limit = (recent == null || recent < 1) ? 8 : Math.min(recent, MAX_STAT_RECENT);
        response.sendJson(auditService.summary(range, limit));
    }

    /**
     * 批量删除审计记录。
     *
     * @param ids 逗号分隔的记录 id
     */
    @POST("/admin/audit/batchRemove")
    public void batchRemove(String ids, HttpRequest request, HttpResponse response) {
        List<String> list = ExportUtil.parseIds(ids, MAX_BATCH_DELETE);
        if (list.isEmpty()) {
            response.sendJson(error("未选择要删除的记录"));
            return;
        }
        int removed;
        try {
            removed = auditService.removeBatch(list);
        } catch (Exception e) {
            log.error("批量删除审计记录失败：{}", e.getMessage());
            AdminAudit.record(auditService, request, "audit.batchRemove", "",
                    "请求删除 " + list.size() + " 条审计记录，执行失败", false);
            response.sendJson(error("批量删除失败"));
            return;
        }
        log.info("后台批量删除审计记录 {} 条（请求 {} 条）", removed, list.size());
        AdminAudit.record(auditService, request, "audit.batchRemove", String.valueOf(removed),
                "删除审计记录 " + removed + " 条（请求 " + list.size() + " 条）", true);

        Map<String, Object> ok = new HashMap<>(4);
        ok.put("code", 200);
        ok.put("msg", "已删除 " + removed + " 条记录");
        ok.put("removed", removed);
        response.sendJson(ok);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 只接受 ok / fail，其它一律归一化成空串（= 全部）。 */
    private static String normalizeResult(String result) {
        if (result == null) {
            return "";
        }
        String value = result.trim().toLowerCase();
        return ("ok".equals(value) || "fail".equals(value)) ? value : "";
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> map = new HashMap<>(2);
        map.put("code", -1);
        map.put("msg", message);
        return map;
    }

    /**
     * 导出 JSON：交给 Jackson 序列化，字段名固定，绝不手拼字符串。
     */
    private static String buildJson(List<AuditLogEntity> rows, String exportedAt) {
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (AuditLogEntity row : rows) {
            Map<String, Object> item = new LinkedHashMap<>(10);
            item.put("id", row.getId());
            item.put("createTime", row.getCreateTime());
            item.put("actorType", row.getActorType());
            item.put("actor", row.getActor());
            item.put("action", row.getAction());
            item.put("target", row.getTarget());
            item.put("detail", row.getDetail());
            item.put("result", row.getResult());
            item.put("ip", row.getIp());
            item.put("userAgent", row.getUserAgent());
            items.add(item);
        }
        Map<String, Object> root = new LinkedHashMap<>(4);
        root.put("exportedAt", exportedAt);
        root.put("count", items.size());
        root.put("rows", items);
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            log.error("审计日志 JSON 序列化失败：{}", e.getMessage());
            return "{\"exportedAt\":\"" + exportedAt + "\",\"count\":0,\"rows\":[]}";
        }
    }
}

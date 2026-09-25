package miao.byusi.hp.server.controller.admin;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.plugin.web.annotation.Controller;
import cn.hserver.plugin.web.annotation.GET;
import cn.hserver.plugin.web.annotation.POST;
import cn.hserver.plugin.web.interfaces.HttpRequest;
import cn.hserver.plugin.web.interfaces.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import miao.byusi.hp.server.domian.entity.TemplateEntity;
import miao.byusi.hp.server.service.AuditService;
import miao.byusi.hp.server.service.TemplateService;
import miao.byusi.hp.server.utils.AdminAudit;
import miao.byusi.hp.server.utils.ExportUtil;
import miao.byusi.hp.server.utils.SafeInputUtil;
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
 * 后台「隧道模板」。
 * <p>
 * 路由约定（AuthFilter 由他人负责）：{@code /admin/template} 与
 * {@code /admin/template/export} 是只读 GET（已在白名单内），
 * 其余 save / import / remove / batchRemove / apply 一律 POST。
 * <p>
 * 安全与正确性约定：
 * <ul>
 *   <li>模板条目<b>不含口令</b>：下发时 password 留空，由客户端 bootstrap 用当前登录凭据补齐；</li>
 *   <li>条目校验统一走 {@link TemplateService#normalizeItems(String)}（Jackson +
 *       {@code SafeInputUtil} 白名单），任何一条不合法就整体拒绝保存，
 *       并把逐条失败原因回显给后台；</li>
 *   <li>导出 JSON 里的 items 是<b>真实 JSON 数组</b>（不是转义后的字符串），
 *       因此「导出 → 导入」可以闭环复现模板；</li>
 *   <li>导入/下发都有条数上限，避免一个请求写入海量数据。</li>
 * </ul>
 * <b>审计：</b>模板的保存 / 导入 / 删除 / 批量删除 / 下发全部留痕
 * （action=template.save / template.import / template.remove / template.batchRemove / template.apply）。
 * 其中 <b>template.apply 最关键</b>：它会为其他账号批量建立隧道配置，
 * 一旦被误用影响面是所有被下发账号，因此请求条数、实际成功/新增/跳过/失败数都要写进摘要。
 * 模板条目本身不含口令（下发时由客户端用当前登录凭据补齐），摘要里也不写条目内容。
 */
@Controller
public class TemplateController {

    private static final Logger log = LoggerFactory.getLogger(TemplateController.class);

    /** 每页条数 */
    private static final int PAGE_SIZE = 10;
    /** 单次导出的最大模板数 */
    private static final int MAX_EXPORT_ROWS = 1000;
    /** 单次批量删除的最大条数 */
    private static final int MAX_BATCH_DELETE = 200;
    /** 单次导入的最大模板数 */
    private static final int MAX_IMPORT_TEMPLATES = 200;
    /** 导入请求体的最大字符数（约 512KB） */
    private static final int MAX_IMPORT_CHARS = 512 * 1024;
    /** 模板名最大长度 */
    private static final int MAX_NAME_LEN = 64;
    /** 模板说明最大长度 */
    private static final int MAX_DESC_LEN = 200;
    /** 单次下发的最大账号数 */
    private static final int MAX_APPLY_USERS = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TemplateService templateService;

    /** 审计服务：模板保存/导入/下发都会影响其他账号的隧道，必须可追溯 */
    @Autowired
    private AuditService auditService;

    @GET("/admin/template")
    public void page(Integer page, String keyword, HttpResponse response) {
        if (page == null || page < 1) {
            page = 1;
        }
        PageResult<TemplateEntity> list = templateService.list(page, PAGE_SIZE, keyword);
        List<TemplateEntity> rows = list == null ? new ArrayList<TemplateEntity>() : list.getList();
        Map<String, Object> data = new HashMap<>(10);
        data.put("page", page);
        data.put("pageSize", PAGE_SIZE);
        data.put("totalRow", list == null ? 0L : list.getTotalRow());
        data.put("totalPage", list == null ? 1L : list.getTotalPage());
        data.put("list", rows);
        data.put("keyword", keyword == null ? "" : keyword);
        // 条目数在服务端解析后单独传递，避免模板里对 JSON 字符串做逻辑处理
        data.put("itemCounts", templateService.itemCounts(rows));
        response.sendTemplate("/admin/template.ftl", data);
    }

    /**
     * 导出模板。
     * <p>
     * {@code format=json}（默认）输出可直接被 {@code /admin/template/import} 重新导入的结构；
     * {@code format=csv} 供人工查阅（CSV 不是可回导格式，页面上有说明）。
     */
    @GET("/admin/template/export")
    public void export(String format, String keyword, HttpResponse response) {
        String fmt = format == null ? "json" : format.trim().toLowerCase();
        List<TemplateEntity> rows = templateService.listAll(MAX_EXPORT_ROWS);
        if (keyword != null && !keyword.trim().isEmpty()) {
            rows = filterByKeyword(rows, keyword.trim());
        }
        String stamp = ExportUtil.timestamp();
        if ("csv".equals(fmt)) {
            List<String> headers = Arrays.asList("id", "名称", "描述", "条目数", "下发次数", "创建人", "创建时间", "更新时间");
            Map<String, Integer> counts = templateService.itemCounts(rows);
            List<List<String>> cells = new ArrayList<>(rows.size());
            for (TemplateEntity row : rows) {
                Integer count = counts.get(row.getId());
                cells.add(Arrays.asList(
                        row.getId(),
                        row.getName(),
                        row.getDescription(),
                        count == null ? "0" : String.valueOf(count),
                        row.getApplyCount() == null ? "0" : String.valueOf(row.getApplyCount()),
                        row.getCreatedBy(),
                        row.getCreateTime(),
                        row.getUpdateTime()
                ));
            }
            ExportUtil.attachment(response, "proxy-template-" + stamp + ".csv",
                    "text/csv; charset=utf-8", ExportUtil.csv(headers, cells));
            return;
        }
        ExportUtil.attachment(response, "proxy-template-" + stamp + ".json",
                "application/json; charset=utf-8", buildExportJson(rows));
    }

    /**
     * 新增 / 更新模板。
     *
     * @param items 条目 JSON（数组，或 {"items":[...]} 形式）
     */
    @POST("/admin/template/save")
    public void save(String id, String name, String description, String items, HttpResponse response,
                     HttpRequest request) {
        List<String> failures = new ArrayList<>(4);
        String cleanName = validateName(name, failures);
        String cleanDesc = validateDescription(description, failures);
        Map<String, Object> normalized = templateService.normalizeItems(items);
        if (!Boolean.TRUE.equals(normalized.get("ok"))) {
            @SuppressWarnings("unchecked")
            List<String> itemFailures = (List<String>) normalized.get("failures");
            failures.addAll(itemFailures == null ? new ArrayList<String>() : itemFailures);
        }
        if (!failures.isEmpty()) {
            // 名称本身不合法时 cleanName 为 null，此时不把原始名称写进审计（可能含危险字符/超长）
            AdminAudit.record(auditService, request, "template.save", cleanName == null ? "" : cleanName,
                    "校验失败：" + String.join("；", failures), false);
            Map<String, Object> result = error("保存失败：" + String.join("；", failures));
            result.put("failures", failures);
            response.sendJson(result);
            return;
        }

        TemplateEntity template = new TemplateEntity();
        if (id != null && !id.trim().isEmpty()) {
            template.setId(id.trim());
        }
        template.setName(cleanName);
        template.setDescription(cleanDesc);
        template.setItems((String) normalized.get("items"));
        // 后台只有一个共享入口密码，没有独立的操作者账号，这里统一记为 admin
        template.setCreatedBy("admin");

        TemplateEntity saved;
        try {
            saved = templateService.save(template);
        } catch (Exception e) {
            log.error("保存隧道模板失败：{}", e.getMessage());
            AdminAudit.record(auditService, request, "template.save", cleanName, "保存异常", false);
            response.sendJson(error("保存失败，请稍后重试"));
            return;
        }
        if (saved == null) {
            AdminAudit.record(auditService, request, "template.save", cleanName, "模板不存在或已被删除", false);
            response.sendJson(error("保存失败：模板不存在或已被删除"));
            return;
        }
        log.info("后台保存隧道模板：{}（{} 条条目）", cleanName, normalized.get("count"));
        AdminAudit.record(auditService, request, "template.save", cleanName,
                "条目数 " + normalized.get("count"), true);
        Map<String, Object> result = ok("已保存模板「" + cleanName + "」，共 " + normalized.get("count") + " 条条目");
        result.put("id", saved.getId());
        response.sendJson(result);
    }

    /**
     * 导入模板（接受与「导出 JSON」一致的结构，也接受裸数组）。
     */
    @POST("/admin/template/import")
    public void importTemplates(String payload, HttpResponse response, HttpRequest request) {
        if (payload == null || payload.trim().isEmpty()) {
            AdminAudit.record(auditService, request, "template.import", "", "导入内容为空", false);
            response.sendJson(error("请粘贴或选择要导入的 JSON 内容"));
            return;
        }
        if (payload.length() > MAX_IMPORT_CHARS) {
            AdminAudit.record(auditService, request, "template.import", "",
                    "导入内容过大（" + payload.length() + " 字符）", false);
            response.sendJson(error("导入内容过大，单次最多 " + (MAX_IMPORT_CHARS / 1024) + " KB"));
            return;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(payload);
        } catch (Exception e) {
            // 只记「不是合法 JSON」，绝不把 payload 写进审计（可能含任意内容）
            AdminAudit.record(auditService, request, "template.import", "", "导入内容不是合法 JSON", false);
            response.sendJson(error("导入内容不是合法的 JSON"));
            return;
        }
        JsonNode array = root;
        if (root != null && root.isObject()) {
            array = root.get("templates");
            if (array == null) {
                array = root.get("list");
            }
        }
        if (array == null || !array.isArray()) {
            AdminAudit.record(auditService, request, "template.import", "", "缺少 templates 数组", false);
            response.sendJson(error("导入内容缺少 templates 数组"));
            return;
        }
        if (array.size() == 0) {
            AdminAudit.record(auditService, request, "template.import", "", "导入内容里没有模板", false);
            response.sendJson(error("导入内容里没有模板"));
            return;
        }
        if (array.size() > MAX_IMPORT_TEMPLATES) {
            AdminAudit.record(auditService, request, "template.import", "",
                    "超过单次导入上限（" + array.size() + " > " + MAX_IMPORT_TEMPLATES + "）", false);
            response.sendJson(error("单次最多导入 " + MAX_IMPORT_TEMPLATES + " 个模板"));
            return;
        }

        List<String> failures = new ArrayList<>();
        int created = 0;
        int index = 0;
        for (JsonNode node : array) {
            index++;
            if (node == null || !node.isObject()) {
                failures.add("第 " + index + " 个：不是合法的对象");
                continue;
            }
            List<String> itemFailures = new ArrayList<>(2);
            String name = validateName(text(node, "name"), itemFailures);
            String description = validateDescription(text(node, "description"), itemFailures);
            JsonNode itemsNode = node.get("items");
            if (itemsNode == null || itemsNode.isNull()) {
                itemFailures.add("缺少 items 条目数组");
            }
            Map<String, Object> normalized = itemsNode == null
                    ? null : templateService.normalizeItems(itemsNode.toString());
            if (normalized != null && !Boolean.TRUE.equals(normalized.get("ok"))) {
                @SuppressWarnings("unchecked")
                List<String> list = (List<String>) normalized.get("failures");
                if (list != null) {
                    itemFailures.addAll(list);
                }
            }
            if (!itemFailures.isEmpty()) {
                failures.add("第 " + index + " 个（" + (name == null ? "未命名" : name) + "）："
                        + String.join("；", itemFailures));
                continue;
            }
            TemplateEntity template = new TemplateEntity();
            template.setName(name);
            template.setDescription(description);
            template.setItems((String) normalized.get("items"));
            String createdBy = text(node, "createdBy");
            template.setCreatedBy(SafeInputUtil.isBlank(createdBy) ? "admin" : createdBy);
            try {
                if (templateService.save(template) == null) {
                    failures.add("第 " + index + " 个（" + name + "）：写入失败");
                    continue;
                }
                created++;
            } catch (Exception e) {
                log.error("导入隧道模板失败（第 {} 个）：{}", index, e.getMessage());
                failures.add("第 " + index + " 个（" + name + "）：写入异常");
            }
        }
        log.info("后台导入隧道模板：成功 {} 个，失败 {} 个", created, failures.size());
        // 摘要只写计数，不写模板名/条目内容（模板条目可能被上游误写入敏感内容）。
        // 注意：模板导入没有「跳过」语义（写入失败会直接计入失败），故不虚报跳过数。
        AdminAudit.record(auditService, request, "template.import", "",
                "新增 " + created + "，失败 " + failures.size(), created > 0);
        Map<String, Object> result;
        if (created == 0) {
            result = error("没有模板被导入");
        } else {
            result = ok("导入完成：新增 " + created + " 个模板，失败 " + failures.size() + " 个");
        }
        result.put("created", created);
        result.put("failures", failures);
        response.sendJson(result);
    }

    /** 删除单个模板。 */
    @POST("/admin/template/remove")
    public void remove(String id, HttpResponse response, HttpRequest request) {
        if (SafeInputUtil.isBlank(id)) {
            AdminAudit.record(auditService, request, "template.remove", "", "参数不合法", false);
            response.sendJson(error("参数不合法"));
            return;
        }
        boolean removed;
        try {
            removed = templateService.remove(id.trim());
        } catch (Exception e) {
            log.error("删除隧道模板失败：{}", e.getMessage());
            AdminAudit.record(auditService, request, "template.remove", id.trim(), "删除异常", false);
            response.sendJson(error("删除失败"));
            return;
        }
        if (!removed) {
            AdminAudit.record(auditService, request, "template.remove", id.trim(), "模板不存在或已被删除", false);
            response.sendJson(error("模板不存在或已被删除"));
            return;
        }
        AdminAudit.record(auditService, request, "template.remove", id.trim(), "删除模板", true);
        response.sendJson(ok("已删除该模板"));
    }

    /** 批量删除模板。 */
    @POST("/admin/template/batchRemove")
    public void batchRemove(String ids, HttpResponse response, HttpRequest request) {
        List<String> list = ExportUtil.parseIds(ids, MAX_BATCH_DELETE);
        if (list.isEmpty()) {
            AdminAudit.record(auditService, request, "template.batchRemove", "", "未选择要删除的模板", false);
            response.sendJson(error("未选择要删除的模板"));
            return;
        }
        int removed;
        try {
            removed = templateService.removeBatch(list);
        } catch (Exception e) {
            log.error("批量删除隧道模板失败：{}", e.getMessage());
            AdminAudit.record(auditService, request, "template.batchRemove", "",
                    "批量删除异常：请求 " + list.size() + " 个", false);
            response.sendJson(error("批量删除失败"));
            return;
        }
        log.info("后台批量删除隧道模板 {} 个（请求 {} 个）", removed, list.size());
        AdminAudit.record(auditService, request, "template.batchRemove", "",
                "请求 " + list.size() + " 条，删除 " + removed + " 条", true);
        Map<String, Object> result = ok("已删除 " + removed + " 个模板");
        result.put("removed", removed);
        response.sendJson(result);
    }

    /**
     * 一键下发：把模板条目写入指定账号的自动穿透配置。
     *
     * @param usernames 逗号/换行分隔的账号名
     * @param deviceId  可选；留空时使用占位值 template（见 TemplateService#applyTo）
     */
    @POST("/admin/template/apply")
    public void apply(String id, String usernames, String deviceId, HttpResponse response, HttpRequest request) {
        if (SafeInputUtil.isBlank(id)) {
            AdminAudit.record(auditService, request, "template.apply", "", "参数不合法", false);
            response.sendJson(error("参数不合法"));
            return;
        }
        List<String> targets = splitUsernames(usernames);
        if (targets.isEmpty()) {
            AdminAudit.record(auditService, request, "template.apply", id.trim(), "未填写下发账号", false);
            response.sendJson(error("请填写要下发的账号（逗号或换行分隔）"));
            return;
        }
        if (targets.size() > MAX_APPLY_USERS) {
            AdminAudit.record(auditService, request, "template.apply", id.trim(),
                    "下发账号数超过上限（" + targets.size() + " > " + MAX_APPLY_USERS + "）", false);
            response.sendJson(error("单次最多下发 " + MAX_APPLY_USERS + " 个账号，当前 " + targets.size() + " 个"));
            return;
        }
        Map<String, Object> result;
        try {
            result = templateService.applyTo(id.trim(), targets, deviceId);
        } catch (Exception e) {
            log.error("模板下发失败：{}", e.getMessage());
            AdminAudit.record(auditService, request, "template.apply", id.trim(),
                    "下发账号 " + targets.size() + " 个：执行异常", false);
            response.sendJson(error("下发失败，请稍后重试"));
            return;
        }
        int applied = num(result.get("applied"));
        int created = num(result.get("created"));
        int skipped = num(result.get("skipped"));
        @SuppressWarnings("unchecked")
        List<String> failures = (List<String>) result.get("failures");
        int failureCount = failures == null ? 0 : failures.size();

        // 【审计】这是全控制器影响面最大的动作：模板条目会被写进其他账号的自动穿透配置。
        // 因此不论成功还是「一个都没成功」，都按同一口径记录请求账号数与实际结果；
        // 摘要里不写账号名单本身（可能很长），只写计数。
        String detail = "下发账号 " + targets.size() + " 个，新增 " + created
                + "，跳过 " + skipped + "，失败 " + failureCount;
        AdminAudit.record(auditService, request, "template.apply", id.trim(), detail, applied > 0);

        Map<String, Object> payload;
        if (applied == 0) {
            payload = error("下发失败：没有任何账号被成功下发");
        } else {
            payload = ok("下发完成：成功 " + applied + " 个账号，新增配置 " + created
                    + " 条，跳过 " + skipped + " 条，异常 " + failureCount + " 条");
        }
        payload.put("applied", applied);
        payload.put("created", created);
        payload.put("skipped", skipped);
        payload.put("failures", failures == null ? new ArrayList<String>() : failures);
        response.sendJson(payload);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 账号名列表解析：逗号/空白/换行分隔，去重并保持顺序。 */
    private static List<String> splitUsernames(String raw) {
        List<String> list = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return list;
        }
        for (String part : raw.split("[,\\s]+")) {
            String name = part.trim();
            if (name.isEmpty() || list.contains(name)) {
                continue;
            }
            list.add(name);
            if (list.size() >= MAX_APPLY_USERS + 1) {
                // 多解析一个用于触发「超出上限」提示，不必继续解析
                break;
            }
        }
        return list;
    }

    private static List<TemplateEntity> filterByKeyword(List<TemplateEntity> rows, String keyword) {
        String lower = keyword.toLowerCase();
        List<TemplateEntity> filtered = new ArrayList<>(rows.size());
        for (TemplateEntity row : rows) {
            if (row == null) {
                continue;
            }
            String name = row.getName() == null ? "" : row.getName().toLowerCase();
            String description = row.getDescription() == null ? "" : row.getDescription().toLowerCase();
            if (name.contains(lower) || description.contains(lower)) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    /** 模板名校验：非空、长度限制、无危险字符（模板名会渲染到后台页面）。 */
    private static String validateName(String raw, List<String> failures) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) {
            failures.add("模板名称不能为空");
            return null;
        }
        if (name.length() > MAX_NAME_LEN) {
            failures.add("模板名称最多 " + MAX_NAME_LEN + " 个字符");
            return null;
        }
        if (SafeInputUtil.hasDangerousChars(name)) {
            failures.add("模板名称不能包含控制字符或 < > \" ' & \\ 等字符");
            return null;
        }
        return name;
    }

    /** 说明校验：可空，长度限制 + 危险字符拦截。 */
    private static String validateDescription(String raw, List<String> failures) {
        String description = raw == null ? "" : raw.trim();
        if (description.length() > MAX_DESC_LEN) {
            failures.add("模板说明最多 " + MAX_DESC_LEN + " 个字符");
            return null;
        }
        if (!description.isEmpty() && SafeInputUtil.hasDangerousChars(description)) {
            failures.add("模板说明不能包含控制字符或 < > \" ' & \\ 等字符");
            return null;
        }
        return description;
    }

    /**
     * 导出 JSON：items 以真实 JSON 数组输出（不转义成字符串），保证可被导入接口回读。
     * 使用 Jackson 序列化，不做手工拼串。
     */
    private static String buildExportJson(List<TemplateEntity> rows) {
        Map<String, Object> root = new LinkedHashMap<>(8);
        root.put("version", "16.1");
        root.put("type", "proxy-template");
        root.put("exportedAt", ExportUtil.timestamp());
        root.put("withSecret", Boolean.FALSE);
        List<Map<String, Object>> templates = new ArrayList<>(rows.size());
        for (TemplateEntity row : rows) {
            Map<String, Object> item = new LinkedHashMap<>(10);
            item.put("name", row.getName());
            item.put("description", row.getDescription());
            item.put("items", parseItemsNode(row.getItems()));
            item.put("createdBy", row.getCreatedBy());
            item.put("applyCount", row.getApplyCount() == null ? 0 : row.getApplyCount());
            item.put("createTime", row.getCreateTime());
            item.put("updateTime", row.getUpdateTime());
            templates.add(item);
        }
        root.put("templates", templates);
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            log.error("模板导出序列化失败：{}", e.getMessage());
            return "{\"version\":\"16.1\",\"templates\":[]}";
        }
    }

    /** 把库里的 items 文本转成 JsonNode；损坏时回退为空数组（导出不应因脏数据整体失败）。 */
    private static JsonNode parseItemsNode(String items) {
        try {
            if (items != null && !items.trim().isEmpty()) {
                JsonNode node = MAPPER.readTree(items);
                if (node != null && node.isArray()) {
                    return node;
                }
                if (node != null && node.isObject() && node.get("items") != null && node.get("items").isArray()) {
                    return node.get("items");
                }
            }
        } catch (Exception e) {
            log.warn("模板条目解析失败，导出时按空数组处理：{}", e.getMessage());
        }
        ArrayNode empty = MAPPER.createArrayNode();
        return empty;
    }

    private static String text(JsonNode node, String name) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.get(name);
        if (value != null && !value.isNull()) {
            String s = value.asText();
            if (s != null) {
                return s;
            }
        }
        return "";
    }

    private static int num(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
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

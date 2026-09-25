package miao.byusi.hp.server.controller.admin;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.plugin.web.annotation.Controller;
import cn.hserver.plugin.web.annotation.GET;
import cn.hserver.plugin.web.annotation.POST;
import cn.hserver.plugin.web.interfaces.HttpRequest;
import cn.hserver.plugin.web.interfaces.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import miao.byusi.hp.server.domian.entity.ConfigEntity;
import miao.byusi.hp.server.domian.entity.UserEntity;
import miao.byusi.hp.server.service.AuditService;
import miao.byusi.hp.server.service.ConfigService;
import miao.byusi.hp.server.service.UserService;
import miao.byusi.hp.server.utils.AdminAudit;
import miao.byusi.hp.server.utils.ExportUtil;
import miao.byusi.hp.server.utils.SafeInputUtil;
import org.beetl.sql.core.page.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台「自动穿透（sys_config）」。
 * <p>
 * 本轮新增能力：
 * <ul>
 *   <li>搜索过滤：用户名 / 设备ID 模糊匹配；</li>
 *   <li>导出：{@code /admin/config/export} 输出 JSON 或 CSV；</li>
 *   <li>导入：{@code /admin/config/import} 接受与导出格式一致的 JSON，逐条做白名单校验；</li>
 *   <li>二维码分享：{@code /admin/config/export?format=qr} 返回一段<b>不含口令</b>的精简 JSON，
 *       由前端在浏览器内绘制成二维码（服务端不引入二维码依赖，也避免把密文渲染成图片）；</li>
 *   <li>批量操作：{@code /admin/config/batchRemove} 一次删除多条配置。</li>
 * </ul>
 * 安全约定：
 * <ul>
 *   <li><b>导出内容永不包含 password 字段</b>。sys_config.password 是客户端隧道凭据，
 *       一旦进入导出文件 / 二维码即可被随手转发，属于凭据外泄；</li>
 *   <li>导入字段全部经 {@link SafeInputUtil} 白名单校验（用户名、host:port、域名、设备ID、类型、端口），
 *       不合法的条目被逐条拒绝并回报原因，不会写入数据库；</li>
 *   <li>导入内容有体积与条数上限，防止超大请求打满内存；</li>
 *   <li>批量删除的 id 经 {@link ExportUtil#parseIds} 校验。</li>
 * </ul>
 */
@Controller
public class AutoConfigController {

    private static final Logger log = LoggerFactory.getLogger(AutoConfigController.class);

    /** 导入/导出的最大条数 */
    private static final int MAX_ROWS = 5000;
    /** 单次批量删除的最大条数 */
    private static final int MAX_BATCH_DELETE = 500;
    /** 导入请求体的最大字符数（约 512KB） */
    private static final int MAX_IMPORT_CHARS = 512 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private ConfigService configService;

    @Autowired
    private UserService userService;

    /**
     * 审计服务：导入 / 批量删除 / 单条删除都写审计
     * （action=config.import / config.batchRemove / config.remove）。
     * <b>摘要里绝不回显导入请求体</b>——payload 是调用方提交的任意文本，
     * 可能夹带凭据或超长内容，写进审计等于把风险复制一份。
     */
    @Autowired
    private AuditService auditService;

    @GET("/admin/config")
    public void log(Integer page, String username, String deviceId, HttpResponse response) {
        if (page == null || page < 1) {
            page = 1;
        }
        PageResult<ConfigEntity> list = configService.list(page, 10, username, deviceId);
        Map<String, Object> data = new HashMap<>(8);
        data.put("page", page);
        data.put("pageSize", 10);
        data.put("totalRow", list.getTotalRow());
        data.put("list", list.getList());
        data.put("totalPage", list.getTotalPage());
        data.put("username", username == null ? "" : username);
        data.put("deviceId", deviceId == null ? "" : deviceId);
        response.sendTemplate("/admin/config.ftl", data);
    }

    /**
     * 导出当前过滤条件下的配置。
     *
     * @param format json（默认）或 csv
     */
    @GET("/admin/config/export")
    public void export(String username, String deviceId, String format, HttpResponse response) {
        String fmt = format == null ? "json" : format.trim().toLowerCase();
        List<ConfigEntity> rows = configService.listForExport(username, deviceId, MAX_ROWS);
        String stamp = ExportUtil.timestamp();

        if ("csv".equals(fmt)) {
            List<String> headers = Arrays.asList("id", "用户名", "设备ID", "内网服务", "外网服务", "类型", "域名", "端口", "时间");
            List<List<String>> cells = new ArrayList<>(rows.size());
            for (ConfigEntity row : rows) {
                cells.add(Arrays.asList(
                        row.getId(),
                        row.getUsername(),
                        row.getDeviceId(),
                        row.getUserHost(),
                        row.getServerHost(),
                        row.getType(),
                        row.getDomain(),
                        row.getPort(),
                        row.getCreateTime()
                ));
            }
            ExportUtil.attachment(response, "proxy-autoconfig-" + stamp + ".csv",
                    "text/csv; charset=utf-8", ExportUtil.csv(headers, cells));
            return;
        }
        ExportUtil.attachment(response, "proxy-autoconfig-" + stamp + ".json",
                "application/json; charset=utf-8", buildExportJson(rows));
    }

    /**
     * 导入配置。请求体为 JSON，支持两种形态：
     * <ul>
     *   <li>{@code {"tunnels":[ ... ]}}（与导出文件一致）</li>
     *   <li>{@code [ ... ]}（裸数组）</li>
     * </ul>
     * 每条记录字段：username / deviceId / userHost / serverHost / type / domain / port。
     */
    @POST("/admin/config/import")
    public void importConfig(String payload, HttpResponse response, HttpRequest request) {
        if (payload == null || payload.trim().isEmpty()) {
            AdminAudit.record(auditService, request, "config.import", "", "导入内容为空", false);
            response.sendJson(error("请粘贴或选择要导入的 JSON 配置"));
            return;
        }
        if (payload.length() > MAX_IMPORT_CHARS) {
            AdminAudit.record(auditService, request, "config.import", "",
                    "导入内容过大（" + payload.length() + " 字符）", false);
            response.sendJson(error("导入内容过大，单次最多 " + (MAX_IMPORT_CHARS / 1024) + " KB"));
            return;
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(payload);
        } catch (Exception e) {
            // 只记「不是合法 JSON」，不把 payload 写进审计
            AdminAudit.record(auditService, request, "config.import", "", "导入内容不是合法 JSON", false);
            response.sendJson(error("配置内容不是合法的 JSON"));
            return;
        }

        JsonNode array = root;
        if (root != null && root.isObject()) {
            array = root.get("tunnels");
            if (array == null) {
                array = root.get("list");
            }
        }
        if (array == null || !array.isArray()) {
            AdminAudit.record(auditService, request, "config.import", "", "缺少 tunnels 数组", false);
            response.sendJson(error("配置内容缺少 tunnels 数组"));
            return;
        }
        if (array.size() == 0) {
            AdminAudit.record(auditService, request, "config.import", "", "配置中没有可导入的条目", false);
            response.sendJson(error("配置中没有可导入的条目"));
            return;
        }
        if (array.size() > MAX_ROWS) {
            AdminAudit.record(auditService, request, "config.import", "",
                    "超过单次导入上限（" + array.size() + " > " + MAX_ROWS + "）", false);
            response.sendJson(error("单次最多导入 " + MAX_ROWS + " 条配置"));
            return;
        }

        List<ConfigEntity> parsed = new ArrayList<>(array.size());
        List<String> failures = new ArrayList<>();
        int index = 0;
        for (JsonNode node : array) {
            index++;
            ConfigEntity entity = parseOne(node, index, failures);
            if (entity != null) {
                parsed.add(entity);
            }
        }
        if (parsed.isEmpty()) {
            AdminAudit.record(auditService, request, "config.import", "",
                    "没有合法条目可导入，非法 " + failures.size() + " 条", false);
            Map<String, Object> result = error("没有合法条目可导入");
            result.put("failures", failures);
            response.sendJson(result);
            return;
        }

        int[] stat;
        try {
            stat = configService.importBatch(parsed);
        } catch (Exception e) {
            log.error("导入自动穿透配置失败：{}", e.getMessage());
            AdminAudit.record(auditService, request, "config.import", "",
                    "导入异常：提交 " + parsed.size() + " 条合法条目", false);
            response.sendJson(error("导入失败，请稍后重试"));
            return;
        }
        log.info("后台导入自动穿透配置：新增 {} 条，跳过 {} 条，非法 {} 条", stat[0], stat[1], failures.size());
        // 只写计数，不回显请求体（铁律：payload 可能夹带凭据）
        AdminAudit.record(auditService, request, "config.import", "",
                "新增 " + stat[0] + " 条，跳过 " + stat[1] + " 条，失败 " + failures.size() + " 条", true);

        Map<String, Object> ok = new HashMap<>(6);
        ok.put("code", 200);
        ok.put("msg", "导入完成：新增 " + stat[0] + " 条，跳过 " + stat[1] + " 条，非法 " + failures.size() + " 条");
        ok.put("created", stat[0]);
        ok.put("skipped", stat[1]);
        ok.put("failures", failures);
        response.sendJson(ok);
    }

    /**
     * 批量删除配置。
     */
    @POST("/admin/config/batchRemove")
    public void batchRemove(String ids, HttpResponse response, HttpRequest request) {
        List<String> list = ExportUtil.parseIds(ids, MAX_BATCH_DELETE);
        if (list.isEmpty()) {
            AdminAudit.record(auditService, request, "config.batchRemove", "", "未选择要删除的配置", false);
            response.sendJson(error("未选择要删除的配置"));
            return;
        }
        int removed;
        try {
            removed = configService.removeBatch(list);
        } catch (Exception e) {
            log.error("批量删除自动穿透配置失败：{}", e.getMessage());
            AdminAudit.record(auditService, request, "config.batchRemove", "",
                    "批量删除异常：请求 " + list.size() + " 条", false);
            response.sendJson(error("批量删除失败"));
            return;
        }
        log.info("后台批量删除自动穿透配置 {} 条（请求 {} 条）", removed, list.size());
        AdminAudit.record(auditService, request, "config.batchRemove", "",
                "请求 " + list.size() + " 条，删除 " + removed + " 条", true);
        Map<String, Object> ok = new HashMap<>(4);
        ok.put("code", 200);
        ok.put("msg", "已删除 " + removed + " 条配置");
        ok.put("removed", removed);
        response.sendJson(ok);
    }

    @GET("/admin/config/remove")
    public void remove(Integer page, HttpResponse response, String id, HttpRequest request) {
        if (id != null) {
            // 与 /admin/log/remove 同理：GET 链接触发的状态变更同样必须留痕
            boolean removed;
            try {
                removed = configService.remove(id);
            } catch (RuntimeException e) {
                AdminAudit.record(auditService, request, "config.remove", id, "删除异常", false);
                throw e;
            }
            AdminAudit.record(auditService, request, "config.remove", id,
                    removed ? "删除自动穿透配置" : "配置不存在或已被删除", removed);
        }
        log(page, null, null, response);
    }

    // ------------------------------------------------------------------
    // 内部工具：解析与序列化
    // ------------------------------------------------------------------

    /**
     * 解析并校验单条导入记录；不合法时把原因写入 failures 并返回 null。
     */
    private ConfigEntity parseOne(JsonNode node, int index, List<String> failures) {
        if (node == null || !node.isObject()) {
            failures.add("第 " + index + " 条：不是合法的对象");
            return null;
        }
        String username = text(node, "username");
        String deviceId = text(node, "deviceId", "device_id");
        String userHost = text(node, "userHost", "user_host");
        String serverHost = text(node, "serverHost", "server_host");
        String type = text(node, "type");
        String domain = text(node, "domain");
        String port = text(node, "port");

        // 用户名沿用「必须真实存在」的判定：这里只做字符层面的危险字符拦截，
        // 不强制邮箱格式 —— 历史账号（如 admin / heixiaoma）不是邮箱，
        // 但同样需要能够导入配置。真正的安全边界是「账号必须存在于 sys_user」。
        String cleanUser = username == null ? "" : username.trim();
        if (cleanUser.isEmpty() || cleanUser.length() > 64 || SafeInputUtil.hasDangerousChars(cleanUser)) {
            failures.add("第 " + index + " 条：用户名不合法");
            return null;
        }
        UserEntity user = userService.getUser(cleanUser);
        if (user == null) {
            failures.add("第 " + index + " 条：用户不存在 " + cleanUser);
            return null;
        }
        String cleanDevice = SafeInputUtil.cleanDeviceId(deviceId);
        if (cleanDevice == null) {
            failures.add("第 " + index + " 条：设备ID不合法");
            return null;
        }
        String cleanUserHost = SafeInputUtil.cleanHostPort(userHost);
        if (cleanUserHost == null) {
            failures.add("第 " + index + " 条：内网服务地址不合法");
            return null;
        }
        String cleanServerHost = SafeInputUtil.cleanHostPort(serverHost);
        if (cleanServerHost == null) {
            failures.add("第 " + index + " 条：外网服务地址不合法");
            return null;
        }
        String cleanType = SafeInputUtil.cleanType(type);
        if (cleanType == null) {
            failures.add("第 " + index + " 条：穿透类型不合法");
            return null;
        }
        String cleanPort = SafeInputUtil.cleanPort(port);
        // 端口允许留空（由服务端随机分配）
        if (port != null && !port.trim().isEmpty() && cleanPort == null) {
            failures.add("第 " + index + " 条：端口不合法");
            return null;
        }
        if (cleanPort != null) {
            int p = Integer.parseInt(cleanPort);
            if (p < 1 || p > 65535) {
                failures.add("第 " + index + " 条：端口超出 1-65535");
                return null;
            }
        }
        String cleanDomain = "";
        if (domain != null && !domain.trim().isEmpty()) {
            cleanDomain = SafeInputUtil.cleanDomain(domain);
            if (cleanDomain == null) {
                failures.add("第 " + index + " 条：域名不合法");
                return null;
            }
        } else if (!"UDP".equals(cleanType)) {
            failures.add("第 " + index + " 条：非 UDP 隧道必须填写域名");
            return null;
        }

        ConfigEntity entity = new ConfigEntity();
        entity.setUserId(user.getId());
        entity.setUsername(cleanUser);
        entity.setDeviceId(cleanDevice);
        entity.setUserHost(cleanUserHost);
        entity.setServerHost(cleanServerHost);
        entity.setType(cleanType);
        entity.setDomain(cleanDomain);
        entity.setPort(cleanPort == null ? "" : cleanPort);
        // 导入配置不携带口令：password 字段留空，客户端首次使用时会用当前登录凭据补齐
        entity.setPassword("");
        return entity;
    }

    /**
     * 导出 JSON（含 id、创建时间等元数据，<b>不含 password</b>）。
     */
    private static String buildExportJson(List<ConfigEntity> rows) {
        StringBuilder sb = new StringBuilder(rows.size() * 200 + 96);
        sb.append("{\"version\":\"16.0\",\"exportedAt\":\"").append(esc(ExportUtil.timestamp()));
        sb.append("\",\"withSecret\":false,\"tunnels\":[");
        for (int i = 0; i < rows.size(); i++) {
            ConfigEntity row = rows.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{')
                    .append("\"id\":\"").append(esc(row.getId())).append("\",")
                    .append("\"username\":\"").append(esc(row.getUsername())).append("\",")
                    .append("\"deviceId\":\"").append(esc(row.getDeviceId())).append("\",")
                    .append("\"userHost\":\"").append(esc(row.getUserHost())).append("\",")
                    .append("\"serverHost\":\"").append(esc(row.getServerHost())).append("\",")
                    .append("\"type\":\"").append(esc(row.getType())).append("\",")
                    .append("\"domain\":\"").append(esc(row.getDomain())).append("\",")
                    .append("\"port\":\"").append(esc(row.getPort())).append("\",")
                    .append("\"createTime\":\"").append(esc(row.getCreateTime())).append("\"")
                    .append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                String s = value.asText();
                if (s != null && !s.trim().isEmpty()) {
                    return s;
                }
            }
        }
        return "";
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> map = new HashMap<>(2);
        map.put("code", -1);
        map.put("msg", message);
        return map;
    }

    private static String esc(String value) {
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

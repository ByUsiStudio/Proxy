package miao.byusi.hp.server.controller.admin;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.plugin.web.annotation.Controller;
import cn.hserver.plugin.web.annotation.GET;
import cn.hserver.plugin.web.annotation.POST;
import cn.hserver.plugin.web.interfaces.HttpRequest;
import cn.hserver.plugin.web.interfaces.HttpResponse;
import miao.byusi.hp.server.domian.entity.QuotaEntity;
import miao.byusi.hp.server.domian.entity.UserEntity;
import miao.byusi.hp.server.service.AuditService;
import miao.byusi.hp.server.service.QuotaService;
import miao.byusi.hp.server.service.UserService;
import miao.byusi.hp.server.utils.AdminAudit;
import miao.byusi.hp.server.utils.ExportUtil;
import miao.byusi.hp.server.utils.SafeInputUtil;
import org.beetl.sql.core.page.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台「用量配额」。
 * <p>
 * 路由与 AuthFilter 的约定（该文件由他人负责，不能改）：
 * <ul>
 *   <li>{@code /admin/quota}、{@code /admin/quota/export} 是只读 GET，已在白名单内；</li>
 *   <li>{@code save / remove / batchRemove / refresh} 一律 POST，由
 *       {@code Admin.post(...)} 发起（自带 {@code X-Requested-With} 头，可通过同源校验）。</li>
 * </ul>
 * 安全约定：
 * <ul>
 *   <li>配额只按「真实存在的账号」建立：username 必须先解析到 sys_user，userId 一律取自库里；
 *       客户端提交的 userId 不被信任。</li>
 *   <li>数值型字段有明确上下界，流量按「数值 + 单位(MB/GB)」在服务端换算成字节数，
 *       换算与上限都在服务端完成，前端只负责展示。</li>
 *   <li>备注等文本字段统一拒绝控制字符/HTML 元字符（存储型 XSS 防护）。</li>
 *   <li>批量删除的 id 先经 {@link ExportUtil#parseIds} 做白名单与条数上限校验。</li>
 * </ul>
 * <b>审计：</b>配额的保存 / 删除 / 批量删除 / 刷新全部留痕
 * （action=quota.save / quota.remove / quota.batchRemove / quota.refresh）。
 * 校验失败等早退分支同样写一条 fail —— 「谁在反复提交非法配额」本身就是安全信号。
 * 摘要里只写数值与单位，绝不写任何凭据（本就无需口令，这里只是重申铁律）。
 */
@Controller
public class QuotaController {

    private static final Logger log = LoggerFactory.getLogger(QuotaController.class);

    /** 每页条数 */
    private static final int PAGE_SIZE = 10;
    /** 单次导出的最大行数 */
    private static final int MAX_EXPORT_ROWS = 5000;
    /** 单次批量删除的最大条数 */
    private static final int MAX_BATCH_DELETE = 500;
    /** 隧道数上限（0 = 不限制） */
    private static final int MAX_TUNNELS_LIMIT = 10000;
    /** 端口数上限（0 = 不限制） */
    private static final int MAX_PORTS_LIMIT = 10000;
    /** 并发连接数上限（0 = 不限制；该值目前仅作参考元数据，见 QuotaService 类注释） */
    private static final int MAX_CONNS_LIMIT = 1000000;
    /** 月度流量上限：1 PiB（超过这个量级一定是配置错误） */
    private static final long MAX_TRAFFIC_BYTES = 1L << 50;
    /** 备注最大长度 */
    private static final int MAX_NOTE_LEN = 200;

    /** MB / GB 的换算基数（与前端下拉框的单位取值一致） */
    private static final long UNIT_MB = 1024L * 1024L;
    private static final long UNIT_GB = 1024L * 1024L * 1024L;

    @Autowired
    private QuotaService quotaService;

    @Autowired
    private UserService userService;

    /** 审计服务：配额变更必须能回答「谁在什么时候把谁的配额改成了什么」 */
    @Autowired
    private AuditService auditService;

    @GET("/admin/quota")
    public void page(Integer page, String username, HttpResponse response) {
        if (page == null || page < 1) {
            page = 1;
        }
        PageResult<QuotaEntity> list = quotaService.list(page, PAGE_SIZE, username);
        Map<String, Object> data = new HashMap<>(10);
        data.put("page", page);
        data.put("pageSize", PAGE_SIZE);
        data.put("totalRow", list == null ? 0L : list.getTotalRow());
        data.put("totalPage", list == null ? 1L : list.getTotalPage());
        data.put("list", list == null ? new ArrayList<QuotaEntity>() : list.getList());
        data.put("username", username == null ? "" : username);
        response.sendTemplate("/admin/quota.ftl", data);
    }

    /**
     * 按当前过滤条件导出全部配额。
     *
     * @param format csv（默认）或 json
     */
    @GET("/admin/quota/export")
    public void export(String format, String username, HttpResponse response) {
        boolean asJson = "json".equalsIgnoreCase(format == null ? "" : format.trim());
        List<QuotaEntity> rows = quotaService.listAll(MAX_EXPORT_ROWS);
        // listAll 不支持按用户名过滤，这里在内存中按同一关键字过滤，保证与页面筛选一致
        if (username != null && !username.trim().isEmpty()) {
            String keyword = username.trim().toLowerCase();
            List<QuotaEntity> filtered = new ArrayList<>(rows.size());
            for (QuotaEntity row : rows) {
                if (row != null && row.getUsername() != null
                        && row.getUsername().toLowerCase().contains(keyword)) {
                    filtered.add(row);
                }
            }
            rows = filtered;
        }
        String stamp = ExportUtil.timestamp();
        if (asJson) {
            ExportUtil.attachment(response, "proxy-quota-" + stamp + ".json",
                    "application/json; charset=utf-8", buildJson(rows));
            return;
        }
        List<String> headers = Arrays.asList("id", "用户名", "隧道上限", "端口上限", "并发上限",
                "月度接收上限(字节)", "月度发送上限(字节)", "启用", "是否超限", "超限/用量说明", "备注", "更新时间");
        List<List<String>> cells = new ArrayList<>(rows.size());
        for (QuotaEntity row : rows) {
            cells.add(Arrays.asList(
                    row.getId(),
                    row.getUsername(),
                    num(row.getMaxTunnels()),
                    num(row.getMaxPorts()),
                    num(row.getMaxConns()),
                    zeroIfBlank(row.getMonthlyReceive()),
                    zeroIfBlank(row.getMonthlySend()),
                    row.enabledFlag() ? "是" : "否",
                    row.overLimitFlag() ? "是" : "否",
                    row.getOverReason(),
                    row.getNote(),
                    row.getUpdateTime()
            ));
        }
        ExportUtil.attachment(response, "proxy-quota-" + stamp + ".csv",
                "text/csv; charset=utf-8", ExportUtil.csv(headers, cells));
    }

    /**
     * 新增 / 更新配额（同一账号只保留一条，按 userId 判重）。
     */
    @POST("/admin/quota/save")
    public void save(String id, String username, String maxTunnels, String maxPorts, String maxConns,
                     String monthlyReceive, String monthlySend, String receiveUnit, String sendUnit,
                     String enabled, String note, HttpResponse response, HttpRequest request) {
        List<String> failures = new ArrayList<>(4);
        // 账号必须先解析到真实用户：配额以 userId 为唯一业务键，不接受客户端提交的 userId
        String name = username == null ? "" : username.trim();
        if (name.isEmpty() || name.length() > 64 || SafeInputUtil.hasDangerousChars(name)) {
            // 审计目标留空：这条输入已被判定含危险字符或超长，原样写入只会把风险搬进审计表
            AdminAudit.record(auditService, request, "quota.save", "", "用户名不合法", false);
            response.sendJson(error("用户名不合法"));
            return;
        }
        UserEntity user = userService.getUser(name);
        if (user == null) {
            AdminAudit.record(auditService, request, "quota.save", name, "用户名不存在", false);
            response.sendJson(error("用户不存在：" + name));
            return;
        }

        Integer tunnels = parseIntLimit(maxTunnels, MAX_TUNNELS_LIMIT, "隧道上限", failures);
        Integer ports = parseIntLimit(maxPorts, MAX_PORTS_LIMIT, "端口上限", failures);
        Integer conns = parseIntLimit(maxConns, MAX_CONNS_LIMIT, "并发上限", failures);
        String receiveBytes = parseTraffic(monthlyReceive, receiveUnit, "月度接收上限", failures);
        String sendBytes = parseTraffic(monthlySend, sendUnit, "月度发送上限", failures);

        String cleanNote = note == null ? "" : note.trim();
        if (cleanNote.length() > MAX_NOTE_LEN) {
            failures.add("备注最多 " + MAX_NOTE_LEN + " 个字符");
        }
        if (!cleanNote.isEmpty() && SafeInputUtil.hasDangerousChars(cleanNote)) {
            failures.add("备注不能包含控制字符或 < > \" ' & \\ 等字符");
        }
        if (!failures.isEmpty()) {
            AdminAudit.record(auditService, request, "quota.save", name,
                    "数值不合法：" + String.join("；", failures), false);
            response.sendJson(error("保存失败：" + String.join("；", failures)));
            return;
        }

        QuotaEntity quota = new QuotaEntity();
        quota.setUserId(user.getId());
        quota.setUsername(user.getUsername());
        quota.setMaxTunnels(tunnels);
        quota.setMaxPorts(ports);
        quota.setMaxConns(conns);
        quota.setMonthlyReceive(receiveBytes);
        quota.setMonthlySend(sendBytes);
        quota.setEnabled("0".equals(enabled == null ? "" : enabled.trim()) ? 0 : 1);
        quota.setNote(cleanNote);

        QuotaEntity existing = quotaService.getByUserId(user.getId());
        if (existing != null) {
            // 仅当表单确实提交了 id 且与库中记录一致时才走更新，避免出现「改名复制出第二条配额」
            quota.setId(existing.getId());
        } else if (id != null && !id.trim().isEmpty()) {
            // 页面停留在旧数据上、配额已被他人删除：提示刷新而不是静默新增
            AdminAudit.record(auditService, request, "quota.save", user.getUsername(),
                    "配额已被删除，请刷新页面后重试", false);
            response.sendJson(error("该配额已被删除，请刷新页面后重试"));
            return;
        }

        try {
            if (!quotaService.saveOrUpdate(quota)) {
                AdminAudit.record(auditService, request, "quota.save", user.getUsername(), "写入未生效", false);
                response.sendJson(error("保存失败，请稍后重试"));
                return;
            }
        } catch (Exception e) {
            log.error("保存配额失败：{}", e.getMessage());
            AdminAudit.record(auditService, request, "quota.save", user.getUsername(), "保存异常", false);
            response.sendJson(error("保存失败，请稍后重试"));
            return;
        }
        log.info("后台保存用量配额：账号 {}，隧道 {}，端口 {}，并发 {}，月度接收 {}B，月度发送 {}B",
                user.getUsername(), tunnels, ports, conns, receiveBytes, sendBytes);
        // 摘要沿用表单的「数值+单位」写法，便于人工核对；不含任何凭据
        AdminAudit.record(auditService, request, "quota.save", user.getUsername(),
                "maxTunnels=" + tunnels + ", maxPorts=" + ports + ", maxConns=" + conns
                        + ", monthlyReceive=" + trafficLabel(monthlyReceive, receiveUnit)
                        + ", monthlySend=" + trafficLabel(monthlySend, sendUnit)
                        + ", enabled=" + quota.getEnabled(), true);
        response.sendJson(ok("已保存 " + user.getUsername() + " 的用量配额"));
    }

    /** 删除单条配额。 */
    @POST("/admin/quota/remove")
    public void remove(String id, HttpResponse response, HttpRequest request) {
        if (SafeInputUtil.isBlank(id)) {
            AdminAudit.record(auditService, request, "quota.remove", "", "参数不合法", false);
            response.sendJson(error("参数不合法"));
            return;
        }
        boolean removed;
        try {
            removed = quotaService.remove(id.trim());
        } catch (Exception e) {
            log.error("删除配额失败：{}", e.getMessage());
            AdminAudit.record(auditService, request, "quota.remove", id.trim(), "删除异常", false);
            response.sendJson(error("删除失败"));
            return;
        }
        if (!removed) {
            AdminAudit.record(auditService, request, "quota.remove", id.trim(), "配额不存在或已被删除", false);
            response.sendJson(error("配额不存在或已被删除"));
            return;
        }
        AdminAudit.record(auditService, request, "quota.remove", id.trim(), "删除配额", true);
        response.sendJson(ok("已删除该配额"));
    }

    /** 批量删除配额。 */
    @POST("/admin/quota/batchRemove")
    public void batchRemove(String ids, HttpResponse response, HttpRequest request) {
        List<String> list = ExportUtil.parseIds(ids, MAX_BATCH_DELETE);
        if (list.isEmpty()) {
            AdminAudit.record(auditService, request, "quota.batchRemove", "", "未选择要删除的配额", false);
            response.sendJson(error("未选择要删除的配额"));
            return;
        }
        int removed;
        try {
            removed = quotaService.removeBatch(list);
        } catch (Exception e) {
            log.error("批量删除配额失败：{}", e.getMessage());
            AdminAudit.record(auditService, request, "quota.batchRemove", "",
                    "批量删除异常：请求 " + list.size() + " 条", false);
            response.sendJson(error("批量删除失败"));
            return;
        }
        log.info("后台批量删除用量配额 {} 条（请求 {} 条）", removed, list.size());
        AdminAudit.record(auditService, request, "quota.batchRemove", "",
                "请求 " + list.size() + " 条，删除 " + removed + " 条", true);
        Map<String, Object> result = ok("已删除 " + removed + " 条配额");
        result.put("removed", removed);
        response.sendJson(result);
    }

    /** 手动刷新所有账号的超限状态（定时任务之外的即时入口）。 */
    @POST("/admin/quota/refresh")
    public void refresh(HttpResponse response, HttpRequest request) {
        int evaluated;
        try {
            evaluated = quotaService.refreshOverLimit();
        } catch (Exception e) {
            log.error("刷新超限状态失败：{}", e.getMessage());
            AdminAudit.record(auditService, request, "quota.refresh", "", "刷新异常", false);
            response.sendJson(error("刷新失败，请稍后重试"));
            return;
        }
        // 摘要里附带「当前超限账号数」，事后可与刷新结果对照。
        // 该计数纯属审计摘要：查询失败时降级为「未知」，绝不因为写摘要把刷新本身变成 500。
        String overLimit;
        try {
            List<String> over = quotaService.overLimitUsers();
            overLimit = String.valueOf(over == null ? 0 : over.size());
        } catch (Exception e) {
            log.warn("读取超限账号数失败（仅影响审计摘要）：{}", e.getMessage());
            overLimit = "未知";
        }
        AdminAudit.record(auditService, request, "quota.refresh", "",
                "已刷新 " + evaluated + " 个账号，当前超限 " + overLimit + " 个", true);
        response.sendJson(ok("已刷新 " + evaluated + " 个账号的超限状态"));
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /**
     * 解析 0..max 的整数上限；空值按 0（= 不限制）处理。
     * 非法时把中文原因写入 failures 并返回 null。
     */
    private static Integer parseIntLimit(String raw, int max, String label, List<String> failures) {
        if (raw == null || raw.trim().isEmpty()) {
            return 0;
        }
        String value = raw.trim();
        if (!value.matches("[0-9]{1,10}")) {
            failures.add(label + " 必须是 0-" + max + " 之间的整数");
            return null;
        }
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException e) {
            failures.add(label + " 数值非法");
            return null;
        }
        if (parsed < 0 || parsed > max) {
            failures.add(label + " 超出范围 0-" + max + "（0 表示不限制）");
            return null;
        }
        return (int) parsed;
    }

    /**
     * 把「数值 + 单位」换算为字节数字符串。
     * <p>
     * 换算基数：MB = 1024*1024，GB = 1024*1024*1024（与前端下拉框一致，页面上有说明）。
     * 空值或 0 表示不限制，统一存 "0"；数值参与运算的是 {@link BigDecimal}，
     * 避免 double 舍入把 1.5 GB 算成 1610612735 之类的零头。
     *
     * @return 字节数字符串；非法时返回 null 并写入 failures
     */
    private static String parseTraffic(String raw, String unit, String label, List<String> failures) {
        if (raw == null || raw.trim().isEmpty()) {
            return "0";
        }
        String value = raw.trim();
        if (!value.matches("[0-9]{1,12}(\\.[0-9]{1,4})?")) {
            failures.add(label + " 必须是非负数字");
            return null;
        }
        long factor;
        String normalizedUnit = unit == null ? "" : unit.trim().toUpperCase();
        if ("MB".equals(normalizedUnit)) {
            factor = UNIT_MB;
        } else if ("GB".equals(normalizedUnit) || normalizedUnit.isEmpty()) {
            // 默认按 GB 处理（前端默认选中 GB），避免单位缺失时把上限算小 1024 倍
            factor = UNIT_GB;
        } else {
            failures.add(label + " 的单位只支持 MB 或 GB");
            return null;
        }
        try {
            BigDecimal bytes = new BigDecimal(value).multiply(BigDecimal.valueOf(factor));
            if (bytes.compareTo(BigDecimal.valueOf(MAX_TRAFFIC_BYTES)) > 0) {
                failures.add(label + " 过大（上限 1024 TB）");
                return null;
            }
            return bytes.setScale(0, RoundingMode.HALF_UP).toPlainString();
        } catch (NumberFormatException e) {
            failures.add(label + " 数值非法");
            return null;
        }
    }

    private static String num(Integer value) {
        return value == null ? "0" : String.valueOf(value);
    }

    /**
     * 审计摘要里的流量写法：沿用表单提交的「数值 + 单位」形式（如 10GB），
     * 而不是换算后的 10737418240 —— 摘要给人看，字节数反而不好核对。
     * 空值按保存逻辑的语义展示为 0（= 不限制）。
     */
    private static String trafficLabel(String raw, String unit) {
        if (raw == null || raw.trim().isEmpty()) {
            return "0";
        }
        String normalizedUnit = unit == null || unit.trim().isEmpty() ? "GB" : unit.trim().toUpperCase();
        return raw.trim() + normalizedUnit;
    }

    private static String zeroIfBlank(String value) {
        return value == null || value.trim().isEmpty() ? "0" : value.trim();
    }

    /**
     * 手工拼装导出 JSON：字段全部来自数据库且类型已知，
     * 这里统一做 JSON 转义，避免引号或控制字符破坏结构（与既有导出口径一致）。
     */
    private static String buildJson(List<QuotaEntity> rows) {
        StringBuilder sb = new StringBuilder(rows.size() * 220 + 96);
        sb.append("{\"version\":\"16.1\",\"exportedAt\":\"").append(esc(ExportUtil.timestamp()));
        sb.append("\",\"withSecret\":false,\"quotas\":[");
        for (int i = 0; i < rows.size(); i++) {
            QuotaEntity row = rows.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{')
                    .append("\"id\":\"").append(esc(row.getId())).append("\",")
                    .append("\"username\":\"").append(esc(row.getUsername())).append("\",")
                    .append("\"maxTunnels\":").append(row.getMaxTunnels() == null ? "0" : row.getMaxTunnels()).append(',')
                    .append("\"maxPorts\":").append(row.getMaxPorts() == null ? "0" : row.getMaxPorts()).append(',')
                    .append("\"maxConns\":").append(row.getMaxConns() == null ? "0" : row.getMaxConns()).append(',')
                    .append("\"monthlyReceive\":\"").append(esc(zeroIfBlank(row.getMonthlyReceive()))).append("\",")
                    .append("\"monthlySend\":\"").append(esc(zeroIfBlank(row.getMonthlySend()))).append("\",")
                    .append("\"enabled\":").append(row.enabledFlag() ? 1 : 0).append(',')
                    .append("\"overLimit\":\"").append(row.overLimitFlag() ? "true" : "false").append("\",")
                    .append("\"overReason\":\"").append(esc(row.getOverReason())).append("\",")
                    .append("\"note\":\"").append(esc(row.getNote())).append("\",")
                    .append("\"updateTime\":\"").append(esc(row.getUpdateTime())).append("\"")
                    .append('}');
        }
        sb.append("]}");
        return sb.toString();
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

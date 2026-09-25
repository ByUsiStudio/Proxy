package miao.byusi.hp.server.controller.index;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.core.server.util.JsonResult;
import cn.hserver.plugin.web.annotation.Controller;
import cn.hserver.plugin.web.annotation.GET;
import cn.hserver.plugin.web.annotation.POST;
import cn.hserver.plugin.web.interfaces.HttpRequest;
import cn.hserver.plugin.web.interfaces.HttpResponse;
import miao.byusi.hp.server.domian.entity.ConfigEntity;
import miao.byusi.hp.server.domian.entity.DomainEntity;
import miao.byusi.hp.server.domian.entity.PortEntity;
import miao.byusi.hp.server.domian.entity.StatisticsEntity;
import miao.byusi.hp.server.domian.entity.UserEntity;
import miao.byusi.hp.server.domian.vo.PortalApplyResult;
import miao.byusi.hp.server.domian.vo.PortalQuotaVo;
import miao.byusi.hp.server.domian.vo.PortalUsageVo;
import miao.byusi.hp.server.service.AuditService;
import miao.byusi.hp.server.service.ConfigService;
import miao.byusi.hp.server.service.PortalService;
import miao.byusi.hp.server.service.UserService;
import miao.byusi.hp.server.utils.DateUtil;
import miao.byusi.hp.server.utils.ExportUtil;
import miao.byusi.hp.server.utils.NetUtil;
import miao.byusi.hp.server.utils.SafeInputUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 用户自助门户（{@code /index/**}）。
 * <p>
 * <b>身份来源（最重要的一条）</b>：本控制器的所有接口都只从
 * {@link miao.byusi.hp.server.AuthFilter} 注入到请求头里的 {@code username}
 * 取当前登录账号——那是 AuthFilter 从 {@code user_session} Cookie 解析会话后
 * 用 {@code getHeaders().put("username", ...)} <b>覆盖写入</b>的值，
 * 客户端即使自己发一个同名请求头也会被服务端覆盖。
 * 请求参数里的 {@code username} / {@code userId} 一律不读取，
 * 因此不存在 IDOR（越权读写他人数据）的可能。
 * <p>
 * 路由说明：{@code /index/**} 不受后台只读白名单约束，登录态由 AuthFilter 的
 * {@code uri.contains("index")} 分支统一校验（未登录会被渲染成 /index/default.ftl），
 * 控制器里再保留一次防御性判空。
 * <p>
 * 写操作（{@code /index/apply/*}）额外要求严格同源或 XHR 头，
 * 用于抵御「跨站表单伪造提交」（会话 Cookie 本身是 SameSite=Lax，属于第二道防线）。
 * <p>
 * <b>审计：</b>自助申请（{@code /index/apply/domain}、{@code /index/apply/port}）
 * 成功与失败都写审计，且 <b>actorType 固定为 {@code user}</b>（站点用户，不是后台管理员，
 * 因此不走 {@link miao.byusi.hp.server.utils.AdminAudit}）；actor 只取会话身份，
 * 绝不取请求参数里的 username（见 {@link #sessionUsername}）。
 * 只读的用量查询与 {@code /index/export} 导出不写审计。
 * </p>
 */
@Controller
public class PortalController {

    private static final Logger log = LoggerFactory.getLogger(PortalController.class);

    /** 图表天数上限：客户端可传 days，服务端必须夹紧（1..30） */
    private static final int DEFAULT_USAGE_DAYS = 14;
    private static final int MAX_USAGE_DAYS = 30;

    /** 单次导出的最大行数：避免一条请求把整库拖出来（自助导出同样需要上限） */
    private static final int MAX_EXPORT_ROWS = 5000;

    @Autowired
    private PortalService portalService;

    @Autowired
    private UserService userService;

    @Autowired
    private ConfigService configService;

    /**
     * 审计服务：门户自助申请是站点用户发起的写操作，actorType 固定 "user"。
     * 失败（同源校验拒绝 / 会话失效 / 格式不合法 / 频率限制 / 数量上限 / 已被占用）
     * 同样要留痕——这些正是「有人在探测或刷接口」的信号。
     */
    @Autowired
    private AuditService auditService;

    // ------------------------------------------------------------------
    // 我的用量
    // ------------------------------------------------------------------

    /**
     * 「我的用量」页面：本月与累计流量、按天趋势、按端口分布、配额、我的端口/域名与自助申请入口。
     */
    @GET("/index/usage")
    public void usage(Integer days, HttpRequest request, HttpResponse response) {
        String username = sessionUsername(request);
        if (username == null) {
            // 正常流程下 AuthFilter 已拦截，这里是纵深防御
            response.sendTemplate("/index/default.ftl");
            return;
        }
        int range = clampDays(days);
        PortalUsageVo usage = portalService.usage(username, range);

        Map<String, Object> data = new HashMap<>(24);
        data.put("username", username);
        data.put("days", range);
        data.put("usage", usage);

        UserEntity user = userService.getUser(username);
        if (user != null) {
            // 【安全】只把展示需要的标量放进模型，绝不把带 password 的 UserEntity 交给模板
            data.put("userLevel", user.getLevel() == null ? 0 : user.getLevel());
            data.put("userType", user.getType());
            data.put("userCreateTime", DateUtil.stampToDate(user.getCreateTime()));
            PortalQuotaVo quota = portalService.quota(user.getId());
            data.put("quota", quota);
            data.put("hasQuota", quota != null);
            List<DomainEntity> domains = userService.getDomain(user.getId());
            List<PortEntity> ports = userService.getPort(user.getId());
            // createTime 在库里是毫秒时间戳，模板不做数值格式化，这里统一转成可读时间
            if (domains != null) {
                for (DomainEntity domain : domains) {
                    domain.setCreateTime(DateUtil.stampToDate(domain.getCreateTime()));
                }
            }
            if (ports != null) {
                for (PortEntity port : ports) {
                    port.setCreateTime(DateUtil.stampToDate(port.getCreateTime()));
                }
            }
            data.put("myDomains", domains == null ? new ArrayList<DomainEntity>() : domains);
            data.put("myPorts", ports == null ? new ArrayList<PortEntity>() : ports);
        } else {
            data.put("hasQuota", false);
            data.put("myDomains", new ArrayList<DomainEntity>());
            data.put("myPorts", new ArrayList<PortEntity>());
        }
        response.sendTemplate("/index/usage.ftl", data);
    }

    /**
     * 图表数据（会话维度）：返回最近 N 天的按天流量与按端口分布。
     * <p>
     * 聚合账号取自会话，接口不接受任何账号参数，因此不可能读到别人的数据。
     */
    @GET("/index/usage/data")
    public void usageData(Integer days, HttpRequest request, HttpResponse response) {
        String username = sessionUsername(request);
        if (username == null) {
            response.sendJson(error("会话已失效，请重新登录"));
            return;
        }
        int range = clampDays(days);
        PortalUsageVo usage = portalService.usage(username, range);

        Map<String, Object> out = new LinkedHashMap<>(16);
        out.put("code", 200);
        out.put("username", usage.getUsername());
        out.put("days", usage.getDays());
        out.put("monthLabel", usage.getMonthLabel());
        out.put("sampleLimit", usage.getSampleLimit());
        out.put("sampleCount", usage.getSampleCount());

        Map<String, Object> month = new LinkedHashMap<>(6);
        month.put("receive", usage.getMonthReceive());
        month.put("send", usage.getMonthSend());
        month.put("connectNum", usage.getMonthConnect());
        month.put("packNum", usage.getMonthPack());
        month.put("rowCount", usage.getMonthRowCount());
        out.put("month", month);

        Map<String, Object> total = new LinkedHashMap<>(6);
        total.put("receive", usage.getTotalReceive());
        total.put("send", usage.getTotalSend());
        total.put("connectNum", usage.getTotalConnect());
        total.put("packNum", usage.getTotalPack());
        out.put("total", total);

        out.put("byDay", usage.getByDay());
        out.put("byPort", usage.getByPort());
        response.sendJson(out);
    }

    // ------------------------------------------------------------------
    // 自助导出
    // ------------------------------------------------------------------

    /**
     * 自助导出：{@code type=statistics|config}，{@code format=csv|json}。
     * <p>
     * <b>所有权完全由会话身份决定</b>：
     * <ul>
     *   <li>statistics：按会话账号<b>精确</b>匹配（见 {@link PortalService#myStatistics}），
     *       不使用 LIKE，避免形如 {@code xa@qq.com} 命中 {@code a@qq.com} 的记录；</li>
     *   <li>config：按会话账号反查出的 userId 精确匹配；</li>
     *   <li><b>导出内容不含任何口令字段</b>（sys_config.password 是隧道注册凭据）。</li>
     * </ul>
     */
    @GET("/index/export")
    public void export(String type, String format, HttpRequest request, HttpResponse response) {
        String username = sessionUsername(request);
        if (username == null) {
            response.sendTemplate("/index/default.ftl");
            return;
        }
        String kind = type == null ? "statistics" : type.trim().toLowerCase(Locale.ROOT);
        boolean asJson = "json".equalsIgnoreCase(format == null ? "" : format.trim());
        String stamp = ExportUtil.timestamp();

        if ("config".equals(kind)) {
            UserEntity user = userService.getUser(username);
            if (user == null) {
                response.sendJson(error("账号不存在或已被删除"));
                return;
            }
            List<ConfigEntity> configs = configService.list(user.getId());
            if (configs == null) {
                configs = new ArrayList<>();
            }
            if (asJson) {
                ExportUtil.attachment(response, "my-tunnels-" + stamp + ".json",
                        "application/json; charset=utf-8", configJson(configs));
                return;
            }
            List<String> headers = Arrays.asList("id", "设备ID", "内网服务", "外网服务", "类型", "域名", "端口", "创建时间");
            List<List<String>> cells = new ArrayList<>(configs.size());
            for (ConfigEntity row : configs) {
                // 【安全】只写非口令列：绝不导出 ConfigEntity.password
                cells.add(Arrays.asList(
                        row.getId(),
                        row.getDeviceId(),
                        row.getUserHost(),
                        row.getServerHost(),
                        row.getType(),
                        row.getDomain(),
                        row.getPort(),
                        row.getCreateTime()
                ));
            }
            ExportUtil.attachment(response, "my-tunnels-" + stamp + ".csv",
                    "text/csv; charset=utf-8", ExportUtil.csv(headers, cells));
            return;
        }

        // 默认导出流量统计
        List<StatisticsEntity> rows = portalService.myStatistics(username, MAX_EXPORT_ROWS);
        if (asJson) {
            ExportUtil.attachment(response, "my-statistics-" + stamp + ".json",
                    "application/json; charset=utf-8", statisticsJson(rows));
            return;
        }
        List<String> headers = Arrays.asList("id", "端口", "接收(字节)", "发送(字节)", "连接数", "数据包数", "时间");
        List<List<String>> cells = new ArrayList<>(rows.size());
        for (StatisticsEntity row : rows) {
            cells.add(Arrays.asList(
                    row.getId(),
                    row.getPort() == null ? "" : String.valueOf(row.getPort()),
                    row.getReceive(),
                    row.getSend(),
                    row.getConnectNum(),
                    row.getPackNum(),
                    row.getCreateTime()
            ));
        }
        ExportUtil.attachment(response, "my-statistics-" + stamp + ".csv",
                "text/csv; charset=utf-8", ExportUtil.csv(headers, cells));
    }

    // ------------------------------------------------------------------
    // 自助申请（写操作）
    // ------------------------------------------------------------------

    /**
     * 自助申请域名。写入语义见 {@link PortalService#applyDomain}（校验通过即建立归属记录，
     * UI 标注「已提交，等待解析生效」）。
     */
    @POST("/index/apply/domain")
    public JsonResult applyDomain(String domain, HttpRequest request, HttpResponse response) {
        if (!sameOriginPost(request)) {
            log.warn("门户申请域名被来源校验拒绝：ip={}", NetUtil.clientIp(request));
            // 身份存疑的拒绝也要留痕：actor 取会话账号（可能为空），目标留空——
            // 此时域名尚未经过任何校验，不能把原始输入写进审计。
            auditService.record("user", sessionUsername(request), "portal.domain.apply", "",
                    "请求来源校验失败", "fail", request);
            return JsonResult.error("请求来源校验失败，请刷新页面后重试");
        }
        String username = sessionUsername(request);
        if (username == null) {
            auditService.record("user", null, "portal.domain.apply", "", "会话已失效", "fail", request);
            return JsonResult.error("会话已失效，请重新登录");
        }
        // 【越权规避】即使请求体里带了 username/userId，也一律忽略：
        // 归属账号只取服务端会话身份，userId 由服务层按账号反查
        PortalApplyResult result = portalService.applyDomain(username, domain);
        // 【审计】成功与失败共用一处记录：失败原因直接取服务层返回的可读文案
        // （格式不合法 / 提交过于频繁 / 达到数量上限 / 域名已被占用）。
        // target 用清洗后的域名：被拒的原始输入可能含 HTML 元字符，不能原样落库。
        String auditTarget = SafeInputUtil.cleanDomain(domain);
        auditService.record("user", username, "portal.domain.apply",
                auditTarget == null ? "" : auditTarget,
                result.getMessage(), result.isOk() ? "ok" : "fail", request);
        if (!result.isOk()) {
            return JsonResult.error(result.getMessage());
        }
        return JsonResult.ok(result.getMessage()).put("value", result.getValue());
    }

    /**
     * 自助申请固定端口。
     */
    @POST("/index/apply/port")
    public JsonResult applyPort(String port, HttpRequest request, HttpResponse response) {
        if (!sameOriginPost(request)) {
            log.warn("门户申请端口被来源校验拒绝：ip={}", NetUtil.clientIp(request));
            auditService.record("user", sessionUsername(request), "portal.port.apply", "",
                    "请求来源校验失败", "fail", request);
            return JsonResult.error("请求来源校验失败，请刷新页面后重试");
        }
        String username = sessionUsername(request);
        if (username == null) {
            auditService.record("user", null, "portal.port.apply", "", "会话已失效", "fail", request);
            return JsonResult.error("会话已失效，请重新登录");
        }
        PortalApplyResult result = portalService.applyPort(username, port);
        // 【审计】口径与申请域名一致；target 用清洗后的端口串（纯数字，非法时留空）
        String auditTarget = SafeInputUtil.cleanPort(port);
        auditService.record("user", username, "portal.port.apply",
                auditTarget == null ? "" : auditTarget,
                result.getMessage(), result.isOk() ? "ok" : "fail", request);
        if (!result.isOk()) {
            return JsonResult.error(result.getMessage());
        }
        return JsonResult.ok(result.getMessage()).put("value", result.getValue());
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /**
     * 当前登录账号：只认 AuthFilter 写入的请求头（服务端会话身份）。
     * <p>
     * 这里再次强调：<b>绝不</b>读取请求参数里的 username。
     */
    private static String sessionUsername(HttpRequest request) {
        String name = request == null ? null : request.getHeader("username");
        if (SafeInputUtil.isBlank(name)) {
            return null;
        }
        return name.trim();
    }

    private static int clampDays(Integer days) {
        if (days == null || days <= 0) {
            return DEFAULT_USAGE_DAYS;
        }
        return Math.min(days, MAX_USAGE_DAYS);
    }

    /**
     * 写操作的来源校验（与 AuthFilter 的后台策略一致）：
     * 有 Origin/Referer 时必须严格同源；两者都缺失时要求 {@code X-Requested-With: XMLHttpRequest}
     * （跨站表单无法设置自定义请求头）。jQuery 的 {@code $.post} 天然携带该头。
     */
    private static boolean sameOriginPost(HttpRequest request) {
        if (request == null) {
            return false;
        }
        String ajax = request.getHeader("x-requested-with");
        boolean isAjax = ajax != null && "XMLHttpRequest".equalsIgnoreCase(ajax.trim());
        String expected = NetUtil.expectedOrigin(request);
        if (expected == null) {
            return false;
        }
        String source = request.getHeader("origin");
        if (source == null || source.trim().isEmpty()) {
            source = request.getHeader("referer");
        }
        if (source == null || source.trim().isEmpty() || "null".equalsIgnoreCase(source.trim())) {
            return isAjax;
        }
        String normalized = NetUtil.normalizeOrigin(source);
        return normalized != null && normalized.equals(expected);
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> map = new HashMap<>(2);
        map.put("code", -1);
        map.put("msg", message);
        return map;
    }

    /** 流量统计 JSON 导出（字段与 CSV 对齐，全部做 JSON 转义）。 */
    private static String statisticsJson(List<StatisticsEntity> rows) {
        StringBuilder sb = new StringBuilder(rows.size() * 160 + 64);
        sb.append("{\"exportedAt\":\"").append(jsonEscape(ExportUtil.timestamp())).append("\",\"type\":\"statistics\",\"rows\":[");
        for (int i = 0; i < rows.size(); i++) {
            StatisticsEntity row = rows.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{');
            sb.append("\"id\":\"").append(jsonEscape(row.getId())).append("\",");
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

    /** 隧道配置 JSON 导出：<b>不含 password</b>（凭据绝不进入导出文件）。 */
    private static String configJson(List<ConfigEntity> rows) {
        StringBuilder sb = new StringBuilder(rows.size() * 200 + 64);
        sb.append("{\"exportedAt\":\"").append(jsonEscape(ExportUtil.timestamp())).append("\",\"type\":\"config\",\"rows\":[");
        for (int i = 0; i < rows.size(); i++) {
            ConfigEntity row = rows.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append('{');
            sb.append("\"id\":\"").append(jsonEscape(row.getId())).append("\",");
            sb.append("\"deviceId\":\"").append(jsonEscape(row.getDeviceId())).append("\",");
            sb.append("\"userHost\":\"").append(jsonEscape(row.getUserHost())).append("\",");
            sb.append("\"serverHost\":\"").append(jsonEscape(row.getServerHost())).append("\",");
            sb.append("\"type\":\"").append(jsonEscape(row.getType())).append("\",");
            sb.append("\"domain\":\"").append(jsonEscape(row.getDomain())).append("\",");
            sb.append("\"port\":\"").append(jsonEscape(row.getPort())).append("\",");
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

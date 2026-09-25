package miao.byusi.hp.server.service.impl;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.core.ioc.annotation.Bean;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import miao.byusi.hp.server.config.ConstConfig;
import miao.byusi.hp.server.dao.TemplateDao;
import miao.byusi.hp.server.domian.entity.ConfigEntity;
import miao.byusi.hp.server.domian.entity.TemplateEntity;
import miao.byusi.hp.server.domian.entity.UserEntity;
import miao.byusi.hp.server.service.ConfigService;
import miao.byusi.hp.server.service.QuotaService;
import miao.byusi.hp.server.service.TemplateService;
import miao.byusi.hp.server.service.UserService;
import miao.byusi.hp.server.utils.DateUtil;
import miao.byusi.hp.server.utils.SafeInputUtil;
import org.beetl.sql.core.page.PageResult;
import org.beetl.sql.core.query.LambdaQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 隧道模板实现：条目校验（Jackson + SafeInputUtil 白名单）与一键下发。
 * <p>
 * 与 {@code AutoConfigController} 的导入校验保持同一套规则与措辞，避免
 * 「后台导入能过、模板下发被拒」这类不一致。
 */
@Bean
public class TemplateServiceImpl implements TemplateService {

    private static final Logger log = LoggerFactory.getLogger(TemplateServiceImpl.class);

    /** 单个模板最多条目数（同时是校验/保存/导入的上限） */
    public static final int MAX_ITEMS = 50;

    /** 条目 JSON 的最大字符数（约 64KB），防止超大请求打满内存 */
    private static final int MAX_ITEMS_CHARS = 64 * 1024;

    /** 单次下发的最大账号数（控制器同样会截断，这里是服务层的兜底） */
    private static final int MAX_APPLY_USERS = 200;

    /**
     * 模板下发时的占位设备 ID（见 TemplateService#applyTo 的文档）。
     * 取值必须满足 {@code SafeInputUtil.cleanDeviceId} 的规则（6-64 位字母数字/-/_），
     * 且要能一眼看出是模板下发而非真实设备。
     */
    public static final String DEVICE_PLACEHOLDER = "template";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TemplateDao templateDao;

    @Autowired
    private ConfigService configService;

    @Autowired
    private UserService userService;

    @Autowired
    private QuotaService quotaService;

    @Override
    public PageResult<TemplateEntity> list(Integer page, Integer pageSize, String keyword) {
        LambdaQuery<TemplateEntity> query = templateDao.createLambdaQuery();
        if (keyword != null && !keyword.trim().isEmpty()) {
            // 参数化 LIKE；名称或描述命中即可（OR 由 BeetlSQL 生成，关键字仍然是绑定参数）
            String like = "%" + keyword.trim() + "%";
            query.andLike(TemplateEntity::getName, like).orLike(TemplateEntity::getDescription, like);
        }
        PageResult<TemplateEntity> result = query.orderBy("update_time desc").page(page, pageSize);
        formatTime(result.getList());
        return result;
    }

    @Override
    public List<TemplateEntity> listAll(int limit) {
        int size = limit <= 0 ? 1000 : limit;
        List<TemplateEntity> list = templateDao.createLambdaQuery()
                .orderBy("update_time desc")
                .limit(0, size)
                .select();
        formatTime(list);
        return list;
    }

    @Override
    public TemplateEntity getById(String id) {
        if (id == null || id.trim().isEmpty()) {
            return null;
        }
        try {
            return templateDao.single(id.trim());
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public TemplateEntity save(TemplateEntity template) {
        if (template == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (template.getId() == null || template.getId().trim().isEmpty()) {
            template.setId(UUID.randomUUID().toString());
            template.setCreateTime(String.valueOf(now));
            template.setUpdateTime(String.valueOf(now));
            if (template.getApplyCount() == null) {
                template.setApplyCount(0);
            }
            templateDao.insert(template);
            return template;
        }
        TemplateEntity existing = getById(template.getId());
        if (existing == null) {
            return null;
        }
        // 以库中记录为基准补齐，避免 updateById 把未提交列写成 null
        template.setCreateTime(existing.getCreateTime());
        template.setUpdateTime(String.valueOf(now));
        template.setApplyCount(existing.getApplyCount() == null ? 0 : existing.getApplyCount());
        if (template.getCreatedBy() == null) {
            template.setCreatedBy(existing.getCreatedBy());
        }
        templateDao.updateById(template);
        return template;
    }

    @Override
    public boolean remove(String id) {
        if (id == null || id.trim().isEmpty()) {
            return false;
        }
        return templateDao.deleteById(id.trim()) > 0;
    }

    @Override
    public int removeBatch(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int removed = 0;
        // 逐条主键删除：id 已在 ExportUtil.parseIds 做过字符白名单与条数上限校验
        for (String id : ids) {
            removed += templateDao.deleteById(id);
        }
        return removed;
    }

    @Override
    public Map<String, Object> normalizeItems(String itemsJson) {
        Map<String, Object> result = new LinkedHashMap<>(6);
        List<String> failures = new ArrayList<>();
        List<Map<String, Object>> normalized = new ArrayList<>();

        if (itemsJson == null || itemsJson.trim().isEmpty()) {
            failures.add("条目不能为空，至少需要 1 条隧道");
            return result(result, false, null, 0, failures);
        }
        if (itemsJson.length() > MAX_ITEMS_CHARS) {
            failures.add("条目内容过大，单次最多 " + (MAX_ITEMS_CHARS / 1024) + " KB");
            return result(result, false, null, 0, failures);
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(itemsJson);
        } catch (Exception e) {
            failures.add("条目不是合法的 JSON");
            return result(result, false, null, 0, failures);
        }
        JsonNode array = root;
        if (root != null && root.isObject()) {
            array = root.get("items");
        }
        if (array == null || !array.isArray()) {
            failures.add("条目必须是 JSON 数组（或 {\"items\":[...]} 形式）");
            return result(result, false, null, 0, failures);
        }
        if (array.size() == 0) {
            failures.add("条目不能为空，至少需要 1 条隧道");
            return result(result, false, null, 0, failures);
        }
        if (array.size() > MAX_ITEMS) {
            failures.add("单个模板最多 " + MAX_ITEMS + " 条，当前 " + array.size() + " 条");
            return result(result, false, null, 0, failures);
        }

        int index = 0;
        for (JsonNode node : array) {
            index++;
            Map<String, Object> item = parseOne(node, index, failures);
            if (item != null) {
                normalized.add(item);
            }
        }
        if (!failures.isEmpty()) {
            // 有任何一条不合法就整体拒绝：半截模板下发出去比直接报错更危险（可能下发到错误域名/端口）
            return result(result, false, null, normalized.size(), failures);
        }

        String json;
        try {
            json = MAPPER.writeValueAsString(normalized);
        } catch (Exception e) {
            failures.add("条目序列化失败：" + e.getMessage());
            return result(result, false, null, 0, failures);
        }
        return result(result, true, json, normalized.size(), failures);
    }

    @Override
    public Map<String, Integer> itemCounts(List<TemplateEntity> templates) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (templates == null) {
            return counts;
        }
        for (TemplateEntity template : templates) {
            if (template == null || template.getId() == null) {
                continue;
            }
            int count = 0;
            try {
                JsonNode root = MAPPER.readTree(template.getItems() == null ? "[]" : template.getItems());
                if (root != null && root.isArray()) {
                    count = root.size();
                } else if (root != null && root.isObject() && root.get("items") != null && root.get("items").isArray()) {
                    count = root.get("items").size();
                }
            } catch (Exception e) {
                count = 0;
            }
            counts.put(template.getId(), count);
        }
        return counts;
    }

    @Override
    public Map<String, Object> applyTo(String templateId, List<String> usernames, String deviceId) {
        List<String> failures = new ArrayList<>();
        int applied = 0;
        int created = 0;
        int skipped = 0;
        int itemTotal = 0;

        TemplateEntity template = getById(templateId);
        if (template == null) {
            failures.add("模板不存在或已被删除");
            return applyResult(applied, created, skipped, itemTotal, failures);
        }

        // 模板条目在保存时已经校验过，这里再校验一次：模板可能由历史数据或直连数据库写入
        Map<String, Object> normalized = normalizeItems(template.getItems());
        if (!Boolean.TRUE.equals(normalized.get("ok"))) {
            failures.add("模板条目不合法：" + normalized.get("failures"));
            return applyResult(applied, created, skipped, itemTotal, failures);
        }
        List<Map<String, Object>> items = parseNormalized((String) normalized.get("items"));
        if (items.isEmpty()) {
            failures.add("模板不含可下发的条目");
            return applyResult(applied, created, skipped, itemTotal, failures);
        }
        itemTotal = items.size();

        String device = SafeInputUtil.cleanDeviceId(deviceId);
        if (device == null) {
            device = DEVICE_PLACEHOLDER;
        }

        List<String> targets = usernames == null ? new ArrayList<>() : usernames;
        int processed = 0;
        for (String raw : targets) {
            if (processed >= MAX_APPLY_USERS) {
                failures.add("单次最多下发 " + MAX_APPLY_USERS + " 个账号，其余已忽略");
                break;
            }
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            processed++;
            String username = raw.trim();
            // 账号沿用「必须真实存在」的判定：历史账号不是邮箱格式，因此只做危险字符与长度拦截
            if (username.length() > 64 || SafeInputUtil.hasDangerousChars(username)) {
                failures.add(username + "：账号格式不合法");
                continue;
            }
            UserEntity user = userService.getUser(username);
            if (user == null) {
                failures.add(username + "：账号不存在");
                continue;
            }
            // 配额：超限或隧道条数超配额时直接拒绝该账号（原因原样回显给后台）
            String refuse = quotaService.checkTunnelQuota(user.getId(), items.size());
            if (refuse != null) {
                failures.add(username + "：" + refuse);
                continue;
            }

            List<ConfigEntity> configs = new ArrayList<>(items.size());
            for (Map<String, Object> item : items) {
                configs.add(toConfig(user, item, device));
            }
            int[] stat;
            try {
                stat = configService.importBatch(configs);
            } catch (Exception e) {
                log.error("模板下发失败（账号 {}）：{}", username, e.getMessage());
                failures.add(username + "：写入配置异常，请稍后重试");
                continue;
            }
            created += stat[0];
            skipped += stat[1];
            applied++;
            if (stat[1] > 0) {
                failures.add(username + "：跳过 " + stat[1] + " 条（已达单账号自动穿透上限 "
                        + ConstConfig.PROXY_SIZE + " 条）");
            }
        }

        // 下发次数按「成功下发的账号数」累加：一次下发 3 个账号记 3 次，比只记 1 次更能反映运营量
        if (applied > 0) {
            TemplateEntity fresh = getById(templateId);
            if (fresh != null) {
                int count = fresh.getApplyCount() == null ? 0 : fresh.getApplyCount();
                fresh.setApplyCount(count + applied);
                fresh.setUpdateTime(String.valueOf(System.currentTimeMillis()));
                templateDao.updateById(fresh);
            }
        }
        log.info("模板下发完成：模板 {}，账号 {} 个成功，新增配置 {} 条，跳过 {} 条，异常 {} 条",
                templateId, applied, created, skipped, failures.size());
        return applyResult(applied, created, skipped, itemTotal, failures);
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /**
     * 校验并规范化单条条目；不合法时把原因写进 failures 并返回 null。
     * 与 {@code AutoConfigController#parseOne} 使用同一套规则。
     */
    private Map<String, Object> parseOne(JsonNode node, int index, List<String> failures) {
        if (node == null || !node.isObject()) {
            failures.add("第 " + index + " 条：不是合法的对象");
            return null;
        }
        String type = SafeInputUtil.cleanType(text(node, "type"));
        if (type == null) {
            failures.add("第 " + index + " 条：穿透类型不合法（只允许 2-10 位字母，如 TCP/UDP）");
            return null;
        }
        String userHost = SafeInputUtil.cleanHostPort(text(node, "userHost", "user_host"));
        if (userHost == null) {
            failures.add("第 " + index + " 条：内网服务地址不合法（host 或 host:port）");
            return null;
        }
        String serverHost = SafeInputUtil.cleanHostPort(text(node, "serverHost", "server_host"));
        if (serverHost == null) {
            failures.add("第 " + index + " 条：外网服务地址不合法（host 或 host:port）");
            return null;
        }
        String portRaw = text(node, "port");
        String port = "";
        if (portRaw != null && !portRaw.trim().isEmpty()) {
            port = SafeInputUtil.cleanPort(portRaw);
            if (port == null) {
                failures.add("第 " + index + " 条：端口不合法（只允许 1-5 位数字）");
                return null;
            }
            int parsedPort = Integer.parseInt(port);
            if (parsedPort < 1 || parsedPort > 65535) {
                failures.add("第 " + index + " 条：端口超出 1-65535");
                return null;
            }
        }
        String domainRaw = text(node, "domain");
        String domain = "";
        if (domainRaw != null && !domainRaw.trim().isEmpty()) {
            domain = SafeInputUtil.cleanDomain(domainRaw);
            if (domain == null) {
                failures.add("第 " + index + " 条：域名不合法");
                return null;
            }
        } else if (!"UDP".equals(type)) {
            // 与自动穿透配置导入保持一致：UDP 的「域名」由客户端用内网地址推导，可以留空
            failures.add("第 " + index + " 条：非 UDP 隧道必须填写域名");
            return null;
        }

        Map<String, Object> item = new LinkedHashMap<>(6);
        item.put("type", type);
        item.put("userHost", userHost);
        item.put("serverHost", serverHost);
        item.put("domain", domain);
        item.put("port", port);
        return item;
    }

    /** 把规范化后的条目 JSON 解析回列表（只读取自己刚写出的结构）。 */
    private List<Map<String, Object>> parseNormalized(String json) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) {
            return items;
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || !root.isArray()) {
                return items;
            }
            for (JsonNode node : root) {
                Map<String, Object> item = new LinkedHashMap<>(6);
                item.put("type", text(node, "type"));
                item.put("userHost", text(node, "userHost"));
                item.put("serverHost", text(node, "serverHost"));
                item.put("domain", text(node, "domain"));
                item.put("port", text(node, "port"));
                items.add(item);
            }
        } catch (Exception e) {
            log.warn("解析模板条目失败：{}", e.getMessage());
        }
        return items;
    }

    /**
     * 由模板条目构造自动穿透配置。
     * <p>
     * password 一律留空：模板不含口令，客户端在 bootstrap 时用当前登录凭据补齐
     * （见 ConfigController 的既有注释），这里绝不写入任何凭据。
     */
    private ConfigEntity toConfig(UserEntity user, Map<String, Object> item, String deviceId) {
        ConfigEntity config = new ConfigEntity();
        config.setUserId(user.getId());
        config.setUsername(user.getUsername());
        config.setPassword("");
        config.setDeviceId(deviceId);
        config.setUserHost(String.valueOf(item.get("userHost")));
        config.setServerHost(String.valueOf(item.get("serverHost")));
        config.setType(String.valueOf(item.get("type")));
        config.setDomain(String.valueOf(item.get("domain") == null ? "" : item.get("domain")));
        config.setPort(String.valueOf(item.get("port") == null ? "" : item.get("port")));
        return config;
    }

    private static Map<String, Object> result(Map<String, Object> target, boolean ok, String items,
                                              int count, List<String> failures) {
        target.put("ok", ok);
        target.put("items", items);
        target.put("count", count);
        target.put("failures", failures);
        return target;
    }

    private static Map<String, Object> applyResult(int applied, int created, int skipped,
                                                   int items, List<String> failures) {
        Map<String, Object> map = new LinkedHashMap<>(8);
        map.put("applied", applied);
        map.put("created", created);
        map.put("skipped", skipped);
        map.put("items", items);
        map.put("failures", failures);
        return map;
    }

    private static String text(JsonNode node, String... names) {
        if (node == null) {
            return "";
        }
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

    private static void formatTime(List<TemplateEntity> list) {
        if (list == null) {
            return;
        }
        for (TemplateEntity entity : list) {
            entity.setCreateTime(DateUtil.stampToDate(entity.getCreateTime()));
            entity.setUpdateTime(DateUtil.stampToDate(entity.getUpdateTime()));
        }
    }
}

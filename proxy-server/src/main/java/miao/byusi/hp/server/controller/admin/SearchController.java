package miao.byusi.hp.server.controller.admin;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.plugin.web.annotation.Controller;
import cn.hserver.plugin.web.annotation.GET;
import cn.hserver.plugin.web.interfaces.HttpResponse;
import miao.byusi.hp.server.domian.entity.ConfigEntity;
import miao.byusi.hp.server.domian.entity.PortEntity;
import miao.byusi.hp.server.domian.entity.StatisticsEntity;
import miao.byusi.hp.server.domian.entity.UserEntity;
import miao.byusi.hp.server.domian.vo.PortalSearchVo;
import miao.byusi.hp.server.domian.vo.UserVo;
import miao.byusi.hp.server.service.ConfigService;
import miao.byusi.hp.server.service.PortalService;
import miao.byusi.hp.server.service.StatisticsService;
import miao.byusi.hp.server.service.UserService;
import miao.byusi.hp.server.utils.DateUtil;
import miao.byusi.hp.server.utils.SafeInputUtil;
import org.beetl.sql.core.page.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 后台「全局搜索」。
 * <p>
 * 路由只使用 {@code GET /admin/search}：
 * {@link miao.byusi.hp.server.AuthFilter} 的白名单里只登记了这一个只读地址，
 * 任何新造的 admin GET 路由都会被 405 拒绝，因此所有检索维度都通过
 * {@code type} 参数在这一个地址上完成（写操作一律走 POST）。
 * <p>
 * 安全与性能约定：
 * <ul>
 *   <li>关键字 trim 后长度夹紧到 1..64，含控制字符/HTML 元字符时直接不检索（模板侧另有 ?html 转义）；</li>
 *   <li>每组结果上限 {@value #GROUP_LIMIT} 条，底层查询用 LIMIT/分页限制，绝不整表扫描；</li>
 *   <li>所有查询都走服务层的参数绑定（BeetlSQL LambdaQuery），没有字符串拼接 SQL；</li>
 *   <li><b>绝不返回口令字段</b>：用户组用 {@link #toSafeVo} 重建 VO（不复制 password），
 *       配置组在模板里只渲染非口令列。</li>
 * </ul>
 */
@Controller
public class SearchController {

    private static final Logger log = LoggerFactory.getLogger(SearchController.class);

    /** 关键字长度上限（超出部分直接截断，避免超长 LIKE） */
    private static final int MAX_KEYWORD = 64;

    /** 每组结果的展示与查询上限 */
    private static final int GROUP_LIMIT = 20;

    /** 允许的检索类型；未知值一律退回 all */
    private static final Set<String> TYPES = new HashSet<>(Arrays.asList(
            "all", "user", "domain", "config", "statistics", "audit"));

    @Autowired
    private UserService userService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private StatisticsService statisticsService;

    @Autowired
    private PortalService portalService;

    @GET("/admin/search")
    public void search(String q, String type, HttpResponse response) {
        // 1) 关键字清洗：trim + 长度夹紧（越界截断而不是报错，保证搜索框里回显的值与检索值一致）
        String keyword = q == null ? "" : q.trim();
        if (keyword.length() > MAX_KEYWORD) {
            keyword = keyword.substring(0, MAX_KEYWORD);
        }
        String typeFilter = type == null ? "all" : type.trim().toLowerCase(Locale.ROOT);
        if (!TYPES.contains(typeFilter)) {
            typeFilter = "all";
        }
        // 含危险字符的关键字不进入任何查询（既防注入面，也避免把 XSS 载荷带进页面）
        boolean searchable = !keyword.isEmpty() && !SafeInputUtil.hasDangerousChars(keyword);

        Map<String, Object> data = new HashMap<>(32);
        // header.ftl 的全局搜索框读取 keyword 并回显
        data.put("keyword", keyword);
        data.put("type", typeFilter);
        data.put("groupLimit", GROUP_LIMIT);
        data.put("searchable", searchable);

        int total = 0;
        if (searchable) {
            try {
                if (wants(typeFilter, "user")) {
                    List<UserVo> users = searchUsers(keyword);
                    data.put("users", users);
                    data.put("userCount", users.size());
                    total += users.size();
                }
                if (wants(typeFilter, "domain")) {
                    List<PortalSearchVo.DomainRow> domains = portalService.searchDomains(keyword, GROUP_LIMIT);
                    data.put("domains", domains);
                    data.put("domainCount", domains.size());
                    total += domains.size();
                }
                if (wants(typeFilter, "config")) {
                    List<ConfigEntity> configs = searchConfigs(keyword);
                    data.put("configs", configs);
                    data.put("configCount", configs.size());
                    total += configs.size();
                }
                if (wants(typeFilter, "statistics")) {
                    List<StatisticsEntity> stats = searchStatistics(keyword);
                    data.put("statistics", stats);
                    data.put("statisticsCount", stats.size());
                    total += stats.size();
                }
                if (wants(typeFilter, "audit")) {
                    boolean auditAvailable = portalService.auditSearchAvailable();
                    List<PortalSearchVo.AuditRow> audits = auditAvailable
                            ? portalService.searchAudit(keyword, GROUP_LIMIT)
                            : new ArrayList<>();
                    data.put("auditAvailable", auditAvailable);
                    data.put("audits", audits);
                    data.put("auditCount", audits.size());
                    total += audits.size();
                }
            } catch (Exception e) {
                // 单个维度异常不应该让整个搜索页打不开
                log.error("全局搜索失败：keyword={}，type={}，原因={}", keyword, typeFilter, e.getMessage());
                data.put("searchError", "部分数据源检索失败，请稍后重试");
            }
        }
        data.put("totalCount", total);
        response.sendTemplate("/admin/search.ftl", data);
    }

    // ------------------------------------------------------------------
    // 各维度查询（全部有界 + 参数绑定）
    // ------------------------------------------------------------------

    /**
     * 用户：用户名 LIKE 分页查询；当关键字本身是一个合法账号（邮箱格式）时，
     * 再补一次精确查询并置顶，保证「输入完整账号」立刻命中（getUser 内部 andEq 绑定参数）。
     */
    private List<UserVo> searchUsers(String keyword) {
        List<UserVo> rows = new ArrayList<>();
        PageResult<UserVo> pageResult = userService.list(1, GROUP_LIMIT, keyword);
        if (pageResult != null && pageResult.getList() != null) {
            rows.addAll(pageResult.getList());
        }
        if (SafeInputUtil.cleanUsername(keyword) != null) {
            UserEntity exact = userService.getUser(keyword);
            if (exact != null && !containsUser(rows, exact.getId())) {
                rows.add(0, toSafeVo(exact));
                // 保持在 GROUP_LIMIT 条以内
                while (rows.size() > GROUP_LIMIT) {
                    rows.remove(rows.size() - 1);
                }
            }
        }
        return rows;
    }

    private List<ConfigEntity> searchConfigs(String keyword) {
        PageResult<ConfigEntity> pageResult = configService.list(1, GROUP_LIMIT, keyword, null);
        if (pageResult == null || pageResult.getList() == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(pageResult.getList());
    }

    private List<StatisticsEntity> searchStatistics(String keyword) {
        PageResult<StatisticsEntity> pageResult = statisticsService.list(1, GROUP_LIMIT, keyword, null);
        if (pageResult == null || pageResult.getList() == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(pageResult.getList());
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private static boolean wants(String typeFilter, String group) {
        return "all".equals(typeFilter) || group.equals(typeFilter);
    }

    private static boolean containsUser(List<UserVo> rows, String userId) {
        if (userId == null) {
            return false;
        }
        for (UserVo row : rows) {
            if (userId.equals(row.getId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 由实体重建用于渲染的 VO。
     * <p>
     * 【安全】<b>刻意不复制 password 字段</b>：实体里的口令不会进入模板模型，
     * 后续即使模板改动也不可能把它渲染出来。
     */
    private UserVo toSafeVo(UserEntity user) {
        UserVo vo = new UserVo();
        vo.setId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setType(user.getType());
        vo.setLevel(user.getLevel());
        vo.setCreateTime(DateUtil.stampToDate(user.getCreateTime()));
        vo.setLoginTime(DateUtil.stampToDate(user.getLoginTime()));
        // UserEntity 没有 login_ip 字段（只在 sys_user 表里），VO 上留空由模板兜底显示「-」
        vo.setLoginIp("");
        List<Integer> ports = new ArrayList<>();
        List<PortEntity> owned = userService.getPort(user.getId());
        if (owned != null) {
            for (PortEntity port : owned) {
                if (port != null && port.getPort() != null) {
                    ports.add(port.getPort());
                }
            }
        }
        vo.setPorts(ports);
        return vo;
    }
}

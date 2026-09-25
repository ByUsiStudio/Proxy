package miao.byusi.hp.server.controller.open;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.core.queue.HServerQueue;
import cn.hserver.core.server.util.JsonResult;
import cn.hserver.plugin.web.annotation.Controller;
import cn.hserver.plugin.web.annotation.GET;
import cn.hserver.plugin.web.annotation.POST;
import cn.hserver.plugin.web.context.WebConstConfig;
import cn.hserver.plugin.web.interfaces.HttpRequest;
import cn.hserver.plugin.web.interfaces.HttpResponse;
import cn.hserver.plugin.web.interfaces.ProgressStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import miao.byusi.hp.server.config.ConstConfig;
import miao.byusi.hp.server.dao.DomainDao;
import miao.byusi.hp.server.dao.PortDao;
import miao.byusi.hp.server.domian.bean.Statistics;
import miao.byusi.hp.server.domian.entity.*;
import miao.byusi.hp.server.domian.vo.UserVo;
import miao.byusi.hp.server.service.*;
import miao.byusi.hp.server.utils.DateUtil;
import miao.byusi.hp.server.utils.LoginFailureLimiter;
import miao.byusi.hp.server.utils.NetUtil;
import miao.byusi.hp.server.utils.RateLimitUtil;
import miao.byusi.hp.server.utils.SafeInputUtil;
import miao.byusi.hp.server.utils.UserAuthUtil;
import miao.byusi.hp.server.utils.UserSessionStore;
import miao.byusi.hp.server.utils.UserCheckUtil;
import org.beetl.sql.core.page.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 开放大盘的数据
 */
@Controller
public class OpenApiController {
    private static final Logger log = LoggerFactory.getLogger(OpenApiController.class);

    @Autowired
    private AppService appService;

    @Autowired
    private CoreService coreService;

    @Autowired
    private StatisticsService statisticsService;

    @Autowired
    private UserService userService;

    @Autowired
    private PortDao portDao;

    @Autowired
    private DomainDao domainDao;

    @Autowired
    private PayService payService;

    /**
     * 开放给用得接口
     *
     * @param page
     * @param username
     * @return
     */
    @GET("/statistics/getMyInfo")
    public JsonResult getMyInfo(Integer page, String username, String password) {
        if (page == null) {
            page = 1;
        }
        // 【安全修复】原实现只凭调用方传入的 username 就返回该账号的流量记录（未授权越权/IDOR）。
        // 现在要求同时提供该账号的密码，校验失败返回原有 {code,msg} 结构。
        if (!UserAuthUtil.checkOwnership(userService, null, username, password)) {
            return JsonResult.error("账号或密码错误");
        }
        username = username.trim();
        PageResult<StatisticsEntity> list = statisticsService.list(page, 10, username);
        return list == null ? JsonResult.error() : JsonResult.ok().put("data", list);
    }

    /**
     * 代理节点注册。
     * 【安全修复】原实现匿名可调用并把 ConstConfig.REG_TOKEN（所有节点 @CheckApi 接口的通行证）
     * 直接放在响应 msg 字段返回，等于公开了整个集群的管理凭据。
     * 现在要求携带与 proxy.regSecret 一致的共享密钥（常量时间比较），未配置则直接拒绝。
     * 响应结构保持不变：code=200 时 msg 仍然是 token，proxy-proxy 的 HttpService.reg() 依赖该字段。
     */
    @POST("/proxy/reg")
    public JsonResult regServer(ProxyServerEntity proxyServerEntity, String secret) {
        if (SafeInputUtil.isBlank(ConstConfig.REG_SECRET)) {
            log.warn("/proxy/reg 被调用，但 proxy.regSecret 未配置，已按 fail-closed 拒绝。");
            return JsonResult.error("注册中心未配置注册密钥");
        }
        if (SafeInputUtil.isBlank(secret) || !SafeInputUtil.safeEquals(ConstConfig.REG_SECRET, secret.trim())) {
            log.warn("/proxy/reg 注册密钥校验失败，来源：{}", proxyServerEntity == null ? "" : proxyServerEntity.getIp());
            return JsonResult.error("注册密钥校验失败");
        }
        ProxyServerEntity.add(proxyServerEntity);
        return JsonResult.ok(ConstConfig.REG_TOKEN);
    }

    @GET("/user/email")
    public JsonResult sendEmail(HttpRequest request, String username) {
        return doSendEmail(request, username);
    }

    /**
     * 【安全修复 J11】{@code /user/email} 原为「GET + 产生副作用」（发邮件）。
     * 为兼容既有客户端保留 GET，同时新增等价 POST 入口；两者共用同一套限流。
     * 状态变更类调用方（新版控制台）应优先使用 POST。
     */
    @POST("/user/email")
    public JsonResult sendEmailPost(HttpRequest request, String username) {
        return doSendEmail(request, username);
    }

    private JsonResult doSendEmail(HttpRequest request, String username) {
        if (!UserCheckUtil.checkUsername(username)) {
            return JsonResult.error("注册只能使用qq邮箱");
        }
        username = username.trim();
        // 【安全修复 J1】限流必须按**真实 TCP 对端 IP**，不能用 request.getIpAddress()：
        // 后者优先返回 X-Forwarded-For，攻击者轮换该请求头即可无限发信（邮件轰炸）。
        String ip = NetUtil.clientIp(request);
        if (ConstConfig.EMAIL_IP_LIMIT.getIfPresent(ip) != null) {
            return JsonResult.error("已经发了邮件，请检查下垃圾邮箱里是否存在。");
        }
        if (ConstConfig.EMAIL_IP.getIfPresent(username) != null) {
            return JsonResult.error("已经发了邮件，请检查下垃圾邮箱里是否存在。");
        }
        ConstConfig.EMAIL_IP_LIMIT.put(ip, ip);
        ConstConfig.EMAIL_IP.put(username, username);
        // 重新发码时清空错误计数
        ConstConfig.EMAIL_CODE_FAIL.invalidate(username);
        HServerQueue.sendQueue("EMAIL", username);
        return JsonResult.ok();
    }

    @POST("/user/reg")
    public JsonResult reg(HttpRequest request, HttpResponse response, String username, String password, String code) {
        int time = ConstConfig.TIME;
        if (time == -1) {
            return JsonResult.error("注册功能已经关闭。如有疑问联系管理员");
        } else if (time > 0) {
            int hour = LocalDateTime.now().getHour();
            if (hour != time) {
                return JsonResult.error("注册功能已经关闭,请在每天的" + time + "点时注册，开放注册时间为一小时");
            }
        }
        if (username != null && password != null) {
            username = username.trim();
            if (!UserCheckUtil.checkUsername(username)) {
                return JsonResult.error("注册只能使用qq邮箱");
            }

            // 【安全修复】账号已存在时，本次请求本质是“重置密码”，绝不能静默覆盖：
            // 必须走该账号的邮箱验证码校验（不接受通用注册码），且验证码一次性使用。
            UserEntity existing = userService.getUser(username);
            boolean resetFlow = existing != null;

            boolean byEmailCode;
            if (resetFlow) {
                byEmailCode = true;
            } else {
                // 新注册仍保留原逻辑：通用注册码 或 邮箱验证码
                byEmailCode = code == null || !SafeInputUtil.safeEquals(ConstConfig.REG_CODE, code);
            }

            if (byEmailCode) {
                Integer failObj = ConstConfig.EMAIL_CODE_FAIL.getIfPresent(username);
                int failCount = failObj == null ? 0 : failObj;
                // 【安全修复】同一账号验证码错误次数上限，超过即作废验证码，防止验证码被在线爆破
                if (failCount >= ConstConfig.EMAIL_CODE_MAX_FAIL) {
                    ConstConfig.EMAIL_CODE.invalidate(username);
                    ConstConfig.EMAIL_CODE_FAIL.invalidate(username);
                    return JsonResult.ok("验证码错误次数过多，请重新获取验证码");
                }
                String email_code = ConstConfig.EMAIL_CODE.getIfPresent(username);
                if (email_code == null || code == null || !SafeInputUtil.safeEquals(email_code, code.trim())) {
                    ConstConfig.EMAIL_CODE_FAIL.put(username, failCount + 1);
                    if (failCount + 1 >= ConstConfig.EMAIL_CODE_MAX_FAIL) {
                        ConstConfig.EMAIL_CODE.invalidate(username);
                    }
                    // 保持原有响应结构与成功语义不变，避免影响 Go/Android 客户端
                    return JsonResult.ok("验证码错误，请检查邮箱验证码");
                }
                // 【安全修复】验证码一次性使用，校验成功立即作废
                ConstConfig.EMAIL_CODE.invalidate(username);
                ConstConfig.EMAIL_CODE_FAIL.invalidate(username);
            }

            if (userService.addUser(username, password.trim(), null, null, 0, "false")) {
                if (resetFlow) {
                    // 审计日志：只记录账号，绝不记录密码/验证码
                    log.info("用户：{} 通过邮箱验证码完成了密码重置", username);
                }
                // 【安全修复】注册成功后同样下发不透明会话 ID（新增字段，向后兼容）
                String session = UserSessionStore.create(username);
                // 【安全修复 J9】Cookie 由服务端下发（HttpOnly），JS 不再写 document.cookie
                setSessionCookie(response, request, session);
                return JsonResult.ok("注册成功").put("session", session);
            } else {
                return JsonResult.error("用户名已经存在请换一个");
            }
        }
        return JsonResult.error("注册失败");
    }


    @POST("/user/domainLogin")
    public JsonResult domainLogin(HttpRequest request, HttpResponse response, String username, String password, String domain, String address) throws JsonProcessingException {
        if (domain != null && password != null && username != null) {
            // 【安全修复 J1】来源地址使用真实 TCP 对端，避免伪造 X-Forwarded-For 污染审计与限流
            String clientIp = NetUtil.clientIp(request);
            if (address == null) {
                address = clientIp;
            }
            // 【安全修复 J4】按「账号+IP」限流登录失败，防止明文口令被在线爆破
            if (LoginFailureLimiter.isBlocked(username, clientIp)) {
                log.warn("登录已被临时限流：账号={}，来源IP={}", username, clientIp);
                return JsonResult.error("登录失败.请尝试重新注册");
            }
            // 【安全修复】原日志把明文密码打进了日志文件，现只保留账号与来源地址
            log.info("登录信息：账号={}，域名={}，address={}，来源IP={}", username, domain, address, clientIp);

            UserVo login = userService.domainLogin(username, password, domain, address);
            if (login != null) {
                LoginFailureLimiter.clear(username, clientIp);
                login.setTips(ConstConfig.TIPS);
                // 【安全修复】不再把整个登录结果（含 password 字段）序列化进日志
                log.info("登录成功：账号={}，请求ID={}", username, request.getRequestId());
                String session = UserSessionStore.create(username.trim());
                setSessionCookie(response, request, session);
                return JsonResult.ok("登录成功.").put("data", login).put("session", session);
            }
            LoginFailureLimiter.recordFailure(username, clientIp);
        }
        return JsonResult.error("登录失败.请尝试重新注册");
    }

    /**
     * 【残留架构债】本接口按历史客户端契约**必须**在响应中返回 password
     * （见 doc/SECURITY.md 第五节第1条：隧道 REGISTER 使用 username+password，
     * 需要云端/Go/安卓/节点端同步改造才能去掉）。本轮只新增 session 字段与失败限流，
     * 不改变原有响应结构。
     */
    @POST("/user/login")
    public JsonResult login(HttpRequest request, HttpResponse response, String username, String password, String address) {
        if (username != null && password != null) {
            String name = username.trim();
            String clientIp = NetUtil.clientIp(request);
            if (address == null) {
                address = clientIp;
            }
            // 【安全修复 J4】登录失败限流（账号+IP，含IP维度阈值）
            if (LoginFailureLimiter.isBlocked(name, clientIp)) {
                log.warn("登录已被临时限流：账号={}，来源IP={}", name, clientIp);
                return JsonResult.error("登录失败.请尝试重新注册");
            }
            UserVo login = userService.login(name, password.trim(), address);
            if (login != null) {
                LoginFailureLimiter.clear(name, clientIp);
                login.setTips(ConstConfig.TIPS);
                // 【安全修复】不再输出登录结果，避免把返回给客户端的 password 字段写进日志
                log.info("登录成功：账号={}，请求ID={}", name, request.getRequestId());
                // 【安全修复】为站点页面下发不透明会话 ID，取代前端保存「账号|密码」的明文 cookie。
                // 该字段是新增的，不影响既有 Go/Android 客户端解析。
                String session = UserSessionStore.create(name);
                // 【安全修复 J9】会话 Cookie 改由服务端下发，才能带 HttpOnly
                // （浏览器端通过 document.cookie 写入的 Cookie 永远不可能是 HttpOnly）。
                setSessionCookie(response, request, session);
                return JsonResult.ok("登录成功.").put("data", login).put("session", session);
            }
            LoginFailureLimiter.recordFailure(name, clientIp);
        }
        return JsonResult.error("登录失败.请尝试重新注册");
    }

    /**
     * 【安全修复 J9】站点用户登出。
     * <p>
     * 原实现只在浏览器 JS 里删 Cookie，服务端 {@link UserSessionStore} 中的会话
     * 仍可继续使用 12 小时（被窃取的 Cookie 依旧有效）。现在由服务端作废会话并清除 Cookie。
     * 契约：POST /user/logout，幂等，始终返回 {code:200}。
     */
    @POST("/user/logout")
    public JsonResult logout(HttpRequest request, HttpResponse response) {
        String sessionId = UserSessionStore.cookieValue(request.getHeader("cookie"), "user_session");
        UserSessionStore.invalidate(sessionId);
        clearSessionCookie(response, request);
        return JsonResult.ok("已退出登录");
    }

    /**
     * 【安全修复 J9】组装用户会话 Cookie：Path=/; HttpOnly; SameSite=Lax（HTTPS 时追加 Secure）。
     * 与 AdminController#buildCookie 的属性约定保持一致；HttpOnly 使 XSS 无法读取会话 ID。
     */
    private void setSessionCookie(HttpResponse response, HttpRequest request, String sessionId) {
        if (response == null || sessionId == null || sessionId.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder(96);
        sb.append("user_session=").append(sessionId).append("; Path=/; HttpOnly; SameSite=Lax");
        if (NetUtil.isHttps(request)) {
            sb.append("; Secure");
        }
        response.setHeader("Set-Cookie", sb.toString());
    }

    private void clearSessionCookie(HttpResponse response, HttpRequest request) {
        if (response == null) {
            return;
        }
        StringBuilder sb = new StringBuilder(96);
        sb.append("user_session=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
        if (NetUtil.isHttps(request)) {
            sb.append("; Secure");
        }
        response.setHeader("Set-Cookie", sb.toString());
    }

    /**
     * 【安全修复 J11】节点上报流量统计。
     * 原实现匿名可写，任何人（或任意被入侵的第三方）都能伪造统计数据污染后台报表。
     * 现在要求携带集群凭据，两种之一：
     * <ul>
     *   <li>{@code token}：与 {@link ConstConfig#REG_TOKEN} 一致的集群令牌
     *       （节点通过 {@code /proxy/reg} 获取，见 proxy-proxy {@code HttpService.updateStatistics}）；</li>
     *   <li>{@code secret}：与 {@code proxy.regSecret} 一致的注册共享密钥（未注册节点的兜底）。</li>
     * </ul>
     * 常量时间比较，未配置任何密钥时 fail-closed 拒绝。
     */
    @POST("/statistics/add")
    public JsonResult statisticsAdd(Statistics statistics, HttpRequest request) {
        String token = request == null ? null : request.query("token");
        String secret = request == null ? null : request.query("secret");
        boolean tokenOk = !SafeInputUtil.isBlank(ConstConfig.REG_TOKEN) && !SafeInputUtil.isBlank(token)
                && SafeInputUtil.safeEquals(ConstConfig.REG_TOKEN, token.trim());
        boolean secretOk = !SafeInputUtil.isBlank(ConstConfig.REG_SECRET) && !SafeInputUtil.isBlank(secret)
                && SafeInputUtil.safeEquals(ConstConfig.REG_SECRET, secret.trim());
        if (!tokenOk && !secretOk) {
            log.warn("/statistics/add 凭据校验失败，来源：{}", NetUtil.clientIp(request));
            return JsonResult.error("token 校验失败");
        }
        // 【安全修复】username 会未转义渲染到后台 log.ftl，需先做白名单校验，防止存储型XSS
        String username = SafeInputUtil.cleanUsername(statistics == null ? null : statistics.getUsername());
        if (username == null) {
            return JsonResult.error("参数不合法");
        }
        statistics.setUsername(username);
        log.debug("统计数据: {}", statistics);
        statisticsService.add(statistics);
        return JsonResult.ok();
    }

    /**
     * 【安全修复 J11】节点列表（控制台选择穿透服务器用）。
     * <p>
     * 该接口被 Go 控制台 {@code PX.api.get('/hp/load/data')} 调用，
     * 而控制台的反向代理**不会**为它注入账号口令（见 proxy-client-golang/web/common/js/app.js
     * 的 CLOUD_GET_ONLY_CREDENTIAL_PATHS），因此本轮不能改成强制鉴权（会直接打断控制台）。
     * 采取的最低限度缓解：
     * <ol>
     *   <li>只返回控制台实际消费的字段（name/ip/port/level/num），
     *       不再序列化 connectInfo / stat 等内部运行细节；</li>
     *   <li>按真实 TCP 对端 IP 限流。</li>
     * </ol>
     * 【残留风险】节点清单（内网穿透服务器地址）仍是匿名可读的。
     * 彻底修复需要 Go 控制台把 {@code /hp/load/data} 加入需要凭据的路径集合后，
     * 服务端再改为强制 {@link UserAuthUtil} 校验（已在交付报告中列出）。
     */
    @GET("/load/data")
    public JsonResult data(HttpRequest request) {
        String ip = NetUtil.clientIp(request);
        if (!RateLimitUtil.allow("load-data", ip, 60, 60 * 1000L)) {
            return JsonResult.error("请求过于频繁，请稍后再试");
        }
        Collection<ProxyServerEntity> all = ProxyServerEntity.getAll();
        List<Map<String, Object>> sort = new ArrayList<>();
        for (ProxyServerEntity entity : all) {
            if (entity == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>(6);
            item.put("name", entity.getName());
            item.put("ip", entity.getIp());
            item.put("port", entity.getPort());
            item.put("level", entity.getLevel());
            item.put("num", entity.getNum());
            sort.add(item);
        }
        sort.sort(Comparator.comparing(o -> {
            Object num = o.get("num");
            return num instanceof Number ? ((Number) num).intValue() : 0;
        }));
        if (sort.isEmpty()) {
            return JsonResult.error();
        }
        return JsonResult.ok().put("data", sort);
    }


    @POST("/server/portAdd")
    public JsonResult portAdd(String userId, Integer port, String username, String password) {
        // 【安全修复】原实现匿名调用 + 任意 userId，属于未授权越权（IDOR）
        if (!UserAuthUtil.checkOwnership(userService, userId, username, password)) {
            return JsonResult.error("账号或密码错误");
        }
        if (port == null || port < 10000 || port > 60000) {
            return JsonResult.error("10000-60000以内为开放端口。请重新申请");
        }
        List<PortEntity> ports = portDao.createLambdaQuery().andEq(PortEntity::getUserId, userId).select();
        if (ports.size() > 1) {
            return JsonResult.error("端口过多，暂时不能过多申请");
        }
        List<PortEntity> select = portDao.createLambdaQuery().andEq(PortEntity::getPort, port).select();
        if (select.isEmpty()) {
            PortEntity portEntity = new PortEntity();
            portEntity.setUserId(userId);
            portEntity.setId(UUID.randomUUID().toString());
            portEntity.setCreateTime(String.valueOf(System.currentTimeMillis()));
            portEntity.setPort(port);
            portDao.insert(portEntity);
            return JsonResult.ok();
        } else {
            return JsonResult.error("端口已经存在");
        }
    }


    @GET("/server/portList")
    public JsonResult portList(String userId, String username, String password) {
        // 【安全修复】未授权越权（IDOR）：必须校验凭据属于目标用户
        if (!UserAuthUtil.checkOwnership(userService, userId, username, password)) {
            return JsonResult.error("账号或密码错误");
        }
        List<PortEntity> ports = portDao.createLambdaQuery().andEq(PortEntity::getUserId, userId).select();
        return JsonResult.ok().put("data", ports);
    }

    @POST("/server/portRemove")
    public JsonResult portRemove(String userId, Integer port, String username, String password) {
        // 【安全修复】未授权越权（IDOR）
        if (!UserAuthUtil.checkOwnership(userService, userId, username, password)) {
            return JsonResult.error("账号或密码错误");
        }
        List<PortEntity> select = portDao.createLambdaQuery().andEq(PortEntity::getUserId, userId).andEq(PortEntity::getPort, port).select();
        if (!select.isEmpty()) {
            portDao.deleteById(select.get(0).getId());
            return JsonResult.ok();
        }
        return JsonResult.error();
    }


    @POST("/server/domainAdd")
    public JsonResult domainAdd(String userId, String domain, String customDomain, String username, String password) {
        // 【安全修复】未授权越权（IDOR）
        if (!UserAuthUtil.checkOwnership(userService, userId, username, password)) {
            return JsonResult.error("账号或密码错误");
        }
        if (domain == null) {
            return JsonResult.error("域名不能为空");
        }
        domain = domain.trim();
        if (domain.length() <= 3) {
            return JsonResult.error("域名的长度太短，大于等于4位");
        }
        if (!UserCheckUtil.checkDomain(domain)) {
            return JsonResult.error("域名只能小写字母和数字");
        }
        // 【安全修复】customDomain 会未转义渲染到后台 domain.ftl，入库前做主机名白名单校验
        if (customDomain != null && customDomain.trim().length() > 0) {
            customDomain = SafeInputUtil.cleanDomain(customDomain);
            if (customDomain == null) {
                return JsonResult.error("自定义域名格式不正确");
            }
        } else {
            customDomain = null;
        }
        List<DomainEntity> ports = domainDao.createLambdaQuery().andEq(DomainEntity::getUserId, userId).select();
        if (ports.size() > ConstConfig.PROXY_SIZE - 1) {
            return JsonResult.error("限定每人" + ConstConfig.PROXY_SIZE + "个域名，域名过多，暂时不能过多申请");
        }
        List<DomainEntity> select = domainDao.createLambdaQuery().andEq(DomainEntity::getDomain, domain).select();
        if (select.isEmpty()) {
            DomainEntity portEntity = new DomainEntity();
            portEntity.setUserId(userId);
            portEntity.setId(UUID.randomUUID().toString());
            portEntity.setCreateTime(String.valueOf(System.currentTimeMillis()));
            portEntity.setDomain(domain);
            portEntity.setCustomDomain(customDomain);
            domainDao.insert(portEntity);
            return JsonResult.ok();
        } else {
            return JsonResult.error("域名已经存在");
        }
    }


    @GET("/server/domainList")
    public JsonResult domainList(String userId, String username, String password) {
        // 【安全修复】未授权越权（IDOR）
        if (!UserAuthUtil.checkOwnership(userService, userId, username, password)) {
            return JsonResult.error("账号或密码错误");
        }
        List<DomainEntity> ports = domainDao.createLambdaQuery().andEq(DomainEntity::getUserId, userId).select();
        return JsonResult.ok().put("data", ports);
    }

    @POST("/server/domainRemove")
    public JsonResult domainRemove(String userId, String domain, String username, String password) {
        // 【安全修复】未授权越权（IDOR）
        if (!UserAuthUtil.checkOwnership(userService, userId, username, password)) {
            return JsonResult.error("账号或密码错误");
        }
        List<DomainEntity> select = domainDao.createLambdaQuery().andEq(DomainEntity::getUserId, userId).andEq(DomainEntity::getDomain, domain).select();
        if (!select.isEmpty()) {
            domainDao.deleteById(select.get(0).getId());
            return JsonResult.ok();
        }
        return JsonResult.error();
    }

    @GET("/server/pay")
    public JsonResult pay() {
        return JsonResult.ok().put("data", payService.getTop52());
    }


    @GET("/app/getVersion")
    public JsonResult getVersion() {
        return JsonResult.ok().put("data", appService.getAppVersion());
    }

    @GET("/app/getCoreVersion")
    public JsonResult getCoreVersion() {
        CoreEntity appVersion = coreService.getAppVersion();
        String createTime = appVersion.getCreateTime();
        String s = DateUtil.stampToDate(createTime);
        appVersion.setCreateTime(s);
        return JsonResult.ok().put("data",appVersion );
    }

    /*
    @GET("/app/download")
    public void download(HttpResponse response, HttpRequest request) throws Exception {

        File file = new File("./hp-client.apk");
        response.setDownloadBigFile(file, new ProgressStatus() {

            @Override
            public void operationComplete(String s) {
                log.info("下载完成");
            }

            @Override
            public void downloading(long progress, long total) {
                if (total < 0) {
                    log.warn("file {} transfer progress: {}", file.getName(), progress);
                } else {
                    log.debug("file {} transfer progress: {}/{}", file.getName(), progress, total);
                }
            }
        }, request);
    }
    */
}

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
        if (!UserCheckUtil.checkUsername(username)) {
            return JsonResult.error("注册只能使用qq邮箱");
        }
        username = username.trim();
        // 【安全修复】按调用者IP限流（原实现只按收件账号限流，可换账号继续轰炸）+
        // 按账号5分钟一次的旧逻辑，双重限流。
        String ip = request.getIpAddress();
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
    public JsonResult reg(String username, String password, String code) {
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
                return JsonResult.ok("注册成功").put("session", session);
            } else {
                return JsonResult.error("用户名已经存在请换一个");
            }
        }
        return JsonResult.error("注册失败");
    }


    @POST("/user/domainLogin")
    public JsonResult domainLogin(HttpRequest request, String username, String password, String domain, String address) throws JsonProcessingException {
        if (domain != null && password != null && username != null) {
            if (address == null) {
                address = request.getIpAddress();
            }
            // 【安全修复】原日志把明文密码打进了日志文件，现只保留账号与来源地址
            log.info("登录信息：账号={}，域名={}，address={}，来源IP={}", username, domain, address, request.getIpAddress());

            UserVo login = userService.domainLogin(username, password, domain, address);
            if (login != null) {
                login.setTips(ConstConfig.TIPS);
                // 【安全修复】不再把整个登录结果（含 password 字段）序列化进日志
                log.info("登录成功：账号={}，请求ID={}", username, request.getRequestId());
                return JsonResult.ok("登录成功.").put("data", login);
            }
        }
        return JsonResult.error("登录失败.请尝试重新注册");
    }

    @POST("/user/login")
    public JsonResult login(HttpRequest request, String username, String password, String address) {
        if (username != null && password != null) {
            if (address == null) {
                address = request.getIpAddress();
            }
            UserVo login = userService.login(username.trim(), password.trim(), address);
            if (login != null) {
                login.setTips(ConstConfig.TIPS);
                // 【安全修复】不再输出登录结果，避免把返回给客户端的 password 字段写进日志
                log.info("登录成功：账号={}，请求ID={}", username.trim(), request.getRequestId());
                // 【安全修复】为站点页面下发不透明会话 ID，取代前端保存「账号|密码」的明文 cookie。
                // 该字段是新增的，不影响既有 Go/Android 客户端解析。
                String session = UserSessionStore.create(username.trim());
                return JsonResult.ok("登录成功.").put("data", login).put("session", session);
            }
        }
        return JsonResult.error("登录失败.请尝试重新注册");
    }

    @POST("/statistics/add")
    public JsonResult statisticsAdd(Statistics statistics) {
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

    @GET("/load/data")
    public JsonResult data() {
        Collection<ProxyServerEntity> all = ProxyServerEntity.getAll();
        List<ProxyServerEntity> sort = all.stream().sorted(Comparator.comparing(ProxyServerEntity::getNum)).collect(Collectors.toList());
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

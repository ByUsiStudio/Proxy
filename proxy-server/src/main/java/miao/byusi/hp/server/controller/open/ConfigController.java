package miao.byusi.hp.server.controller.open;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.core.server.util.JsonResult;
import cn.hserver.plugin.web.annotation.Controller;
import cn.hserver.plugin.web.annotation.GET;
import cn.hserver.plugin.web.annotation.POST;
import cn.hserver.plugin.web.interfaces.HttpRequest;
import miao.byusi.hp.server.config.ConstConfig;
import miao.byusi.hp.server.domian.entity.ConfigEntity;
import miao.byusi.hp.server.domian.entity.UserEntity;
import miao.byusi.hp.server.service.ConfigService;
import miao.byusi.hp.server.service.UserService;
import miao.byusi.hp.server.utils.NetUtil;
import miao.byusi.hp.server.utils.RateLimitUtil;
import miao.byusi.hp.server.utils.SafeInputUtil;
import miao.byusi.hp.server.utils.UserAuthUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户的配置中心
 */
@Controller("/config/")
public class ConfigController {

    @Autowired
    private ConfigService configService;

    @Autowired
    private UserService userService;

    @POST("save")
    public JsonResult save(ConfigEntity config){
        if (config.getUserId()==null){
            return JsonResult.error("用户ID不能为空");
        }
        if (config.getServerHost()==null){
            return JsonResult.error("穿透服务不能为空");
        }
        if (config.getType()==null){
            return JsonResult.error("穿透类型不能为空");
        }
        if (config.getType().contains("TCP")&&config.getDomain()==null){
            return JsonResult.error("域名不能为空");
        }
        if (config.getDeviceId()==null||config.getDeviceId().equals("NO_ID")){
            return JsonResult.error("设备ID不能为空");
        }
        if (config.getUserHost()==null){
            return JsonResult.error("内网服务不能为空");
        }

        // 【安全修复】原实现只凭 userId 就写入配置，且把 username/password 原样存库。
        // 现在要求调用方提供与目标用户一致的账号密码（控制台提交自动穿透配置时本来就带）。
        if (!UserAuthUtil.checkOwnership(userService, config.getUserId(), config.getUsername(), config.getPassword())) {
            return JsonResult.error("账号或密码错误");
        }

        UserEntity userById = userService.getUserById(config.getUserId());
        if (userById==null){
            return JsonResult.error("用户不存在");
        }

        // 【安全修复】以下字段都会被后台 config.ftl 未转义渲染，入库前统一做白名单校验/规范化，
        // 阻断存储型 XSS。
        String userHost = SafeInputUtil.cleanHostPort(config.getUserHost());
        String serverHost = SafeInputUtil.cleanHostPort(config.getServerHost());
        String type = SafeInputUtil.cleanType(config.getType());
        String deviceId = SafeInputUtil.cleanDeviceId(config.getDeviceId());
        String port = SafeInputUtil.cleanPort(config.getPort());
        if (userHost == null || serverHost == null || type == null || deviceId == null) {
            return JsonResult.error("参数不合法");
        }
        String domain = null;
        if (config.getDomain() != null && config.getDomain().trim().length() > 0) {
            domain = SafeInputUtil.cleanDomain(config.getDomain());
            if (domain == null) {
                return JsonResult.error("参数不合法");
            }
        }
        config.setUserHost(userHost);
        config.setServerHost(serverHost);
        config.setType(type);
        config.setDeviceId(deviceId);
        config.setDomain(domain);
        config.setPort(port);

        // 账号信息一律以数据库为准，不信任客户端提交值
        config.setUsername(userById.getUsername());
        config.setPassword(userById.getPassword());

        if (configService.save(config)){
            return JsonResult.ok();
        }else {
            return JsonResult.error("一个账号只能配置"+ ConstConfig.PROXY_SIZE+"个自动穿透");
        }
    }

    @GET("list")
    public JsonResult list(String userId, String username, String password){
        // 【安全修复】原实现匿名可读，会返回该用户的明文密码。现在必须校验凭据归属。
        // 【残留架构债】本接口仍会把 ConfigEntity.password（明文）返回给**已通过口令校验的本账号**，
        // 与 doc/SECURITY.md 第五节第1条记录的 /user/login 明文回传属同一问题；
        // 控制台 settings/autoproxy 页面依赖该字段回填，故本轮不改变响应结构，
        // 待迁移到「令牌 + 数据库存 bcrypt」方案时一并去除（需要云端/Go/安卓同步改造）。
        if (!UserAuthUtil.checkOwnership(userService, userId, username, password)) {
            return JsonResult.error("账号或密码错误");
        }
        return JsonResult.ok().put("data",configService.list(userId));
    }

    /**
     * 【安全修复 J2】设备配置读取接口（客户端启动引导用）。
     * <p>
     * 原实现**完全匿名**：任何人只要提交一个 deviceId（用户自选、形如 IMEI，
     * 规则 {@code ^[0-9a-zA-Z]{10,36}$}，且没有任何限流）就能拿到该账号的
     * 明文 password、username、config id 与内部 host:port，属于未认证的凭据泄露。
     * <p>
     * 现契约（Go 客户端 bootstrap 流程需要同步调整，详见交付报告）：
     * <pre>
     * GET /config/listDevice?deviceId=xxx&amp;username=账号&amp;password=口令
     * </pre>
     * <ul>
     *   <li>先按真实 TCP 对端 IP 限流（不信任 X-Forwarded-For）；</li>
     *   <li>再用 {@link UserAuthUtil#authenticate} 校验账号口令；</li>
     *   <li>最后只返回 **userId 属于该账号** 的设备配置，
     *       避免用别人的 deviceId 读取他人配置（IDOR）。</li>
     * </ul>
     * 响应结构保持不变：{@code {code, data:[ConfigEntity...]}}，
     * 客户端读取的 username/password/userHost/serverHost/type/domain/port 字段名不变。
     */
    @GET("listDevice")
    public JsonResult listDevice(String deviceId, String username, String password, HttpRequest request) {
        // 该接口仍需按设备ID读取自身配置，deviceId 是随机设备标识（非账号ID），
        // 但必须先做格式校验，避免异常输入被回显到模板。
        String clean = SafeInputUtil.cleanDeviceId(deviceId);
        if (clean == null) {
            return JsonResult.error("参数不合法");
        }
        // 未认证接口，先限流，防止被用来批量枚举 deviceId
        String ip = NetUtil.clientIp(request);
        if (!RateLimitUtil.allow("config-listDevice", ip, 30, 60 * 1000L)) {
            return JsonResult.error("请求过于频繁，请稍后再试");
        }
        UserEntity user = UserAuthUtil.authenticate(userService, null, username, password);
        if (user == null) {
            return JsonResult.error("账号或密码错误");
        }
        List<ConfigEntity> all = configService.listDevice(clean);
        List<ConfigEntity> owned = new ArrayList<>();
        if (all != null) {
            for (ConfigEntity config : all) {
                if (config != null && user.getId() != null && user.getId().equals(config.getUserId())) {
                    owned.add(config);
                }
            }
        }
        return JsonResult.ok().put("data", owned);
    }

    /**
     * 【安全修复】删除配置：原 GET 无鉴权即可删除任意 id。
     * 现在要求凭据归属校验；同时提供 POST 形式（保留 GET 以兼容旧控制台，但同样先校验凭据）。
     * 【安全修复 J3】校验凭据之后还必须校验**目标配置属于该账号**，
     * 否则任何已注册用户都能用合法凭据删除别人的配置（IDOR）。
     */
    @POST("remove")
    public JsonResult remove(String id, String userId, String username, String password){
        return doRemove(id, userId, username, password);
    }

    @GET("remove")
    public JsonResult removeByGet(String id, String userId, String username, String password){
        return doRemove(id, userId, username, password);
    }

    private JsonResult doRemove(String id, String userId, String username, String password) {
        if (SafeInputUtil.isBlank(id)) {
            return JsonResult.error("参数不合法");
        }
        UserEntity user = UserAuthUtil.authenticate(userService, userId, username, password);
        if (user == null) {
            return JsonResult.error("账号或密码错误");
        }
        ConfigEntity target = configService.getById(id.trim());
        if (target == null) {
            return JsonResult.error("配置不存在");
        }
        // 归属校验：配置的 userId 必须与已认证用户一致
        if (target.getUserId() == null || user.getId() == null || !user.getId().equals(target.getUserId())) {
            return JsonResult.error("无权操作该配置");
        }
        return JsonResult.ok().put("data",configService.remove(target.getId()));
    }



}

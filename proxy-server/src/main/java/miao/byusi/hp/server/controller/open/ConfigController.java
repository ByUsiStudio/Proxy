package miao.byusi.hp.server.controller.open;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.core.server.util.JsonResult;
import cn.hserver.plugin.web.annotation.Controller;
import cn.hserver.plugin.web.annotation.GET;
import cn.hserver.plugin.web.annotation.POST;
import miao.byusi.hp.server.config.ConstConfig;
import miao.byusi.hp.server.domian.entity.ConfigEntity;
import miao.byusi.hp.server.domian.entity.UserEntity;
import miao.byusi.hp.server.service.ConfigService;
import miao.byusi.hp.server.service.UserService;
import miao.byusi.hp.server.utils.SafeInputUtil;
import miao.byusi.hp.server.utils.UserAuthUtil;

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
        if (!UserAuthUtil.checkOwnership(userService, userId, username, password)) {
            return JsonResult.error("账号或密码错误");
        }
        return JsonResult.ok().put("data",configService.list(userId));
    }

    @GET("listDevice")
    public JsonResult listDevice(String deviceId){
        // 该接口供客户端按设备ID读取自身配置，deviceId 是随机设备标识（非账号ID），
        // 但仍需校验格式，避免异常输入被回显到模板。
        String clean = SafeInputUtil.cleanDeviceId(deviceId);
        if (clean == null) {
            return JsonResult.error("参数不合法");
        }
        return JsonResult.ok().put("data",configService.listDevice(clean));
    }

    /**
     * 【安全修复】删除配置：原 GET 无鉴权即可删除任意 id。
     * 现在要求凭据归属校验；同时提供 POST 形式（保留 GET 以兼容旧控制台，但同样先校验凭据）。
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
        if (!UserAuthUtil.checkOwnership(userService, userId, username, password)) {
            return JsonResult.error("账号或密码错误");
        }
        return JsonResult.ok().put("data",configService.remove(id));
    }



}

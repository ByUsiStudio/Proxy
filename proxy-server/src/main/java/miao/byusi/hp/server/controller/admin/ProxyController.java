package miao.byusi.hp.server.controller.admin;

import cn.hserver.core.server.util.JsonResult;
import cn.hserver.plugin.web.annotation.Controller;
import cn.hserver.plugin.web.annotation.GET;
import cn.hserver.plugin.web.annotation.POST;
import cn.hserver.plugin.web.interfaces.HttpResponse;
import miao.byusi.hp.server.config.ConstConfig;
import miao.byusi.hp.server.domian.bean.GlobalStat;
import miao.byusi.hp.server.domian.entity.ProxyServerEntity;

import java.util.HashMap;

@Controller
public class ProxyController {

    /**
     * 【安全修复 J10 / 残留风险】后台集群页会把 REG_TOKEN 与 REG_CODE 渲染进模板
     * （模板 admin/proxy.ftl 由另一位维护者负责，本轮不修改其内容，
     * 该文件已自行加了 referrerpolicy="no-referrer" 与风险提示）。
     * <p>
     * 风险：REG_TOKEN 是静态 UUID，进程存活期间不轮换，且是所有节点 @CheckApi 接口的通行证；
     * 一旦通过 Referer、浏览器历史、截图或代理日志泄露，攻击者即可调用全部节点管理接口
     * （下线用户、删除图片等）。REG_CODE 同理属于可重复使用的注册码。
     * <p>
     * 为什么本轮不改为「轮换/带过期」令牌：节点端契约是
     * {@code /proxy/reg → 响应 msg 即 token → 节点把它当 VER_TOKEN 缓存并用于全部 @CheckApi 调用}，
     * 服务端单方面轮换会让所有已注册节点的令牌立即失效（节点每 10s 重新注册才可能恢复），
     * 属于必须与 proxy-proxy/Go 客户端同步发布的破坏性变更。
     * 规划中的修复方向：把 token 改为「短期有效 + 注册时下发 + 支持多令牌宽限期」，
     * 或在节点管理接口上改用带 HMAC 的请求签名。
     */
    @GET("/admin/proxy")
    public void tips(HttpResponse response) {
        HashMap<String, Object> tips = new HashMap<>();
        tips.put("list", ProxyServerEntity.getAll());
        tips.put("token", ConstConfig.REG_TOKEN);
        tips.put("reg_code", ConstConfig.REG_CODE);
        response.sendTemplate("/admin/proxy.ftl", tips);
    }


}

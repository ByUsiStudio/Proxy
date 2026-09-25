package miao.byusi.hp.server.controller.open;

import cn.hserver.core.queue.HServerQueue;
import cn.hserver.core.server.util.JsonResult;
import cn.hserver.plugin.web.annotation.Controller;
import cn.hserver.plugin.web.annotation.POST;
import miao.byusi.hp.server.config.ConstConfig;
import miao.byusi.hp.server.utils.SafeInputUtil;

import java.util.Map;

@Controller
public class NoticeController {

    /**
     * 【安全修复】原接口完全无鉴权，且 MailPushQueue 收到通知就会把对应用户 type 置为 -1，
     * 相当于一个匿名封号接口。现在只接受持有集群 token（由 /proxy/reg 下发）的内部调用方。
     * 响应结构保持 {code,msg} 不变。
     */
    @POST("/notice/push")
    public JsonResult push(Map<String, String> data) {
        String token = data == null ? null : data.get("token");
        if (SafeInputUtil.isBlank(ConstConfig.REG_TOKEN) || SafeInputUtil.isBlank(token)
                || !SafeInputUtil.safeEquals(ConstConfig.REG_TOKEN, token.trim())) {
            return JsonResult.error("token 校验失败");
        }
        String title = data.get("title");
        String message = data.get("message");
        // 防止 CRLF 注入邮件头
        if (title != null && (title.indexOf('\r') >= 0 || title.indexOf('\n') >= 0)) {
            return JsonResult.error("参数不合法");
        }
        HServerQueue.sendQueue("EMAIL_PUSH", data.get("username"), title, message);
        return JsonResult.ok();
    }

}

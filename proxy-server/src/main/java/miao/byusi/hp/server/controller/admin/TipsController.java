package miao.byusi.hp.server.controller.admin;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.plugin.web.annotation.Controller;
import cn.hserver.plugin.web.annotation.GET;
import cn.hserver.plugin.web.annotation.POST;
import cn.hserver.plugin.web.interfaces.HttpRequest;
import cn.hserver.plugin.web.interfaces.HttpResponse;
import miao.byusi.hp.server.config.ConstConfig;
import miao.byusi.hp.server.service.AuditService;
import miao.byusi.hp.server.utils.AdminAudit;

import java.util.HashMap;

/**
 * 公告（tips）管理。
 * <p>
 * 【审计】公告是对所有客户端可见的文案，改动写审计（tips.update）。
 * detail 只记录长度与是否清空，不搬运全文，避免审计表被长文本挤占。
 */
@Controller
public class TipsController {

    @Autowired
    private AuditService auditService;

    @GET("/admin/tips")
    public void tips(HttpResponse response) {
        HashMap<String, Object> tips = new HashMap<>();
        tips.put("tips", ConstConfig.TIPS);
        response.sendTemplate("/admin/tips.ftl", tips);
    }

    @POST("/admin/setTips")
    public void setTips(String tips, HttpRequest request, HttpResponse response) {
        if (tips != null) {
            ConstConfig.TIPS = tips.trim();
            AdminAudit.record(auditService, request, "tips.update", "",
                    ConstConfig.TIPS.isEmpty() ? "公告已清空" : "公告长度=" + ConstConfig.TIPS.length(), true);
        }
        tips(response);
    }
}

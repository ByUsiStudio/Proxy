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
 * 注册开关管理。
 * <p>
 * 【审计】注册开放时间是运营开关（-1 关闭注册 / 0 全天开放 / &gt;0 每天某小时开放），
 * 改动直接影响新用户能否注册，必须留痕（reg.setTime）。
 */
@Controller
public class RegController {

    @Autowired
    private AuditService auditService;

    @GET("/admin/reg")
    public void reg(HttpResponse response) {
        HashMap<String, Object> reg = new HashMap<>();
        reg.put("time", ConstConfig.TIME);
        response.sendTemplate("/admin/reg.ftl", reg);
    }

    @POST("/admin/setTime")
    public void setTime(Integer time, HttpRequest request, HttpResponse response) {
        if (time != null) {
            ConstConfig.TIME = time;
            String detail;
            if (time == -1) {
                detail = "关闭注册";
            } else if (time == 0) {
                detail = "全天开放注册";
            } else {
                detail = "每日 " + time + " 点开放注册";
            }
            AdminAudit.record(auditService, request, "reg.setTime", String.valueOf(time), detail, true);
        }
        reg(response);
    }
}

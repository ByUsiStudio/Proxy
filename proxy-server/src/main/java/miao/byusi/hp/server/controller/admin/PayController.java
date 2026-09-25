package miao.byusi.hp.server.controller.admin;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.plugin.web.annotation.Controller;
import cn.hserver.plugin.web.annotation.GET;
import cn.hserver.plugin.web.annotation.POST;
import cn.hserver.plugin.web.interfaces.HttpRequest;
import cn.hserver.plugin.web.interfaces.HttpResponse;
import miao.byusi.hp.server.domian.entity.PayEntity;
import miao.byusi.hp.server.service.AuditService;
import miao.byusi.hp.server.service.PayService;
import miao.byusi.hp.server.utils.AdminAudit;
import org.beetl.sql.core.page.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * @author hxm
 * <p>
 * 【审计】打赏记录的新增 / 删除写审计（pay.add / pay.remove）。
 * 打赏金额属于业务数据而非凭据，可以进 detail。
 */
@Controller
public class PayController {
    private static final Logger log = LoggerFactory.getLogger(PayController.class);

    @Autowired
    private PayService payService;

    @Autowired
    private AuditService auditService;

    @GET("/admin/pay")
    public void index(Integer page, HttpResponse response) {
        if (page == null) {
            page = 1;
        }
        PageResult<PayEntity> list = payService.list(page, 10);
        Map<String, Object> data = new HashMap<>(5);
        data.put("page", page);
        data.put("pageSize", 10);
        data.put("totalRow", list.getTotalRow());
        data.put("list", list.getList());
        data.put("totalPage", list.getTotalPage());
        data.put("totalPrice", payService.countPrice());
        response.sendTemplate("/admin/pay.ftl", data);
    }

    @POST("/admin/pay/add")
    public void add(Integer page, HttpResponse response, PayEntity payEntity, HttpRequest request) {
        if (payEntity != null) {
            String target = payEntity.getUsername() == null ? "" : payEntity.getUsername().trim();
            String detail = "打赏金额=" + payEntity.getPrice();
            if (target.length() > 0 && payEntity.getPrice() != null && payEntity.getPrice().trim().length() > 0) {
                try {
                    payService.add(payEntity);
                    AdminAudit.record(auditService, request, "pay.add", target, detail, true);
                } catch (Exception e) {
                    // 原有实现静默失败，这里补一条失败审计并保留原行为
                    AdminAudit.record(auditService, request, "pay.add", target, detail, false);
                    log.warn("新增打赏记录失败：{}", e.getMessage());
                }
            } else {
                AdminAudit.record(auditService, request, "pay.add", target, "参数不完整，未写入：" + detail, false);
            }
        }
        index(page, response);
    }


    @GET("/admin/pay/remove")
    public void remove(Integer page, HttpResponse response, String id, HttpRequest request) {
        if (id != null) {
            try {
                payService.remove(id);
                AdminAudit.record(auditService, request, "pay.remove", id, "删除打赏记录", true);
            } catch (RuntimeException e) {
                AdminAudit.record(auditService, request, "pay.remove", id, "删除打赏记录", false);
                throw e;
            }
        }
        index(page, response);
    }
}

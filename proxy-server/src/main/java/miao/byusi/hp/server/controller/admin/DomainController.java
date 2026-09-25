package miao.byusi.hp.server.controller.admin;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.plugin.web.annotation.Controller;
import cn.hserver.plugin.web.annotation.GET;
import cn.hserver.plugin.web.annotation.POST;
import cn.hserver.plugin.web.interfaces.HttpRequest;
import cn.hserver.plugin.web.interfaces.HttpResponse;
import miao.byusi.hp.server.domian.bean.DomainVo;
import miao.byusi.hp.server.domian.entity.PayEntity;
import miao.byusi.hp.server.service.AuditService;
import miao.byusi.hp.server.service.DomainService;
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
 * 【审计】域名的新增 / 删除写审计（domain.add / domain.remove）。
 */
@Controller
public class DomainController {
    private static final Logger log = LoggerFactory.getLogger(DomainController.class);

    @Autowired
    private DomainService domainService;

    @Autowired
    private AuditService auditService;

    @GET("/admin/domain")
    public void index(Integer page,String usernameSearch, HttpResponse response) {
        if (page == null) {
            page = 1;
        }
        PageResult<DomainVo> list = domainService.list(page, 10,usernameSearch);
        Map<String, Object> data = new HashMap<>(5);
        data.put("page", page);
        data.put("pageSize", 10);
        if (usernameSearch==null){
            data.put("usernameSearch", "");
        }else {
            data.put("usernameSearch", usernameSearch);
        }
        data.put("totalRow", list.getTotalRow());
        data.put("list", list.getList());
        data.put("totalPage", list.getTotalPage());
        response.sendTemplate("/admin/domain.ftl", data);
    }

    @POST("/admin/domain/add")
    public void add(Integer page,String usernameSearch, HttpResponse response, DomainVo domainVo, HttpRequest request) {
        // 目标与详情只取域名/账号，不含任何凭据
        String target = domainVo == null ? "" : domainVo.getDomain();
        String detail = domainVo == null ? "新增域名"
                : "账号=" + domainVo.getUsername() + "，自定义域名=" + domainVo.getCustomDomain();
        try {
            domainService.add(domainVo);
            AdminAudit.record(auditService, request, "domain.add", target, detail, true);
        } catch (Exception e) {
            // 保持原有「失败不阻断页面渲染」的行为，同时补一条失败审计
            AdminAudit.record(auditService, request, "domain.add", target, detail, false);
            log.warn("新增域名失败：{}", e.getMessage());
        }
        index(page, usernameSearch,response);
    }


    @GET("/admin/domain/remove")
    public void remove(Integer page,String usernameSearch, HttpResponse response, String id, HttpRequest request) {
        if (id != null) {
            try {
                domainService.remove(id);
                AdminAudit.record(auditService, request, "domain.remove", id, "删除域名记录", true);
            } catch (RuntimeException e) {
                AdminAudit.record(auditService, request, "domain.remove", id, "删除域名记录", false);
                throw e;
            }
        }
        index(page,usernameSearch, response);
    }
}

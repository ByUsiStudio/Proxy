package miao.byusi.hp.server.controller.admin;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.plugin.web.annotation.Controller;
import cn.hserver.plugin.web.annotation.GET;
import cn.hserver.plugin.web.annotation.POST;
import cn.hserver.plugin.web.interfaces.HttpRequest;
import cn.hserver.plugin.web.interfaces.HttpResponse;
import miao.byusi.hp.server.domian.entity.CoreEntity;
import miao.byusi.hp.server.service.AuditService;
import miao.byusi.hp.server.service.CoreService;
import miao.byusi.hp.server.utils.AdminAudit;
import org.beetl.sql.core.page.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * @author hxm
 * <p>
 * 【审计】内核版本的新增 / 删除写审计（core.add / core.remove）。
 * 目标是版本号，detail 只补充更新说明长度，不整段搬运更新内容（避免审计表被大文本撑爆）。
 */
@Controller
public class CoreController {
    private static final Logger log = LoggerFactory.getLogger(CoreController.class);

    @Autowired
    private CoreService coreService;

    @Autowired
    private AuditService auditService;

    @GET("/admin/core")
    public void index(Integer page, HttpResponse response) {
        if (page == null) {
            page = 1;
        }
        PageResult<CoreEntity> list = coreService.list(page, 10);
        Map<String, Object> data = new HashMap<>(5);
        data.put("page", page);
        data.put("pageSize", 10);
        data.put("totalRow", list.getTotalRow());
        data.put("list", list.getList());
        data.put("totalPage", list.getTotalPage());
        response.sendTemplate("/admin/core.ftl", data);
    }

    @POST("/admin/core/add")
    public void add(Integer page, HttpResponse response, CoreEntity coreEntity, HttpRequest request) {
        String target = coreEntity == null || coreEntity.getVersionCode() == null ? "" : coreEntity.getVersionCode().trim();
        String detail = coreEntity == null ? "新增内核版本"
                : "更新说明长度=" + (coreEntity.getUpdateContent() == null ? 0 : coreEntity.getUpdateContent().length());
        if (coreEntity != null && target.length() > 0 && coreEntity.getUpdateContent() != null
                && coreEntity.getUpdateContent().trim().length() > 0) {
            try {
                coreService.add(coreEntity);
                AdminAudit.record(auditService, request, "core.add", target, detail, true);
            } catch (Exception e) {
                // 保留原有静默失败行为，同时补一条失败审计
                AdminAudit.record(auditService, request, "core.add", target, detail, false);
                log.warn("新增内核版本失败：{}", e.getMessage());
            }
        } else {
            AdminAudit.record(auditService, request, "core.add", target, "参数不完整，未写入", false);
        }
        index(page, response);
    }

    @GET("/admin/core/remove")
    public void remove(Integer page, HttpResponse response, String id, HttpRequest request) {
        if (id != null) {
            try {
                coreService.remove(id);
                AdminAudit.record(auditService, request, "core.remove", id, "删除内核版本记录", true);
            } catch (RuntimeException e) {
                AdminAudit.record(auditService, request, "core.remove", id, "删除内核版本记录", false);
                throw e;
            }
        }
        index(page, response);
    }
}

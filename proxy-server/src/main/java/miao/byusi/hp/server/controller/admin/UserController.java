package miao.byusi.hp.server.controller.admin;

import cn.hserver.plugin.web.annotation.Controller;
import cn.hserver.plugin.web.annotation.GET;
import cn.hserver.plugin.web.annotation.POST;
import cn.hserver.plugin.web.interfaces.HttpRequest;
import cn.hserver.plugin.web.interfaces.HttpResponse;
import miao.byusi.hp.server.domian.vo.UserVo;
import miao.byusi.hp.server.service.AuditService;
import miao.byusi.hp.server.service.UserService;
import miao.byusi.hp.server.utils.AdminAudit;
import miao.byusi.hp.server.utils.UserCheckUtil;
import org.beetl.sql.core.page.PageResult;
import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.core.server.util.JsonResult;

import java.util.HashMap;
import java.util.Map;

/**
 * @author hxm
 * <p>
 * 【审计】用户的新增 / 修改 / 删除都会写审计（action=user.add / user.edit / user.remove）。
 * <b>口令永不进入审计</b>：detail 里只放端口、域名、类型、级别等业务字段，
 * 连「口令长度」都不记录（长度同样是口令信息）；
 * 因此需要新增字段时，务必确认它不是凭据类字段。
 */
@Controller
public class UserController {

    @Autowired
    private UserService userService;

    @Autowired
    private AuditService auditService;

    @GET("/admin/user")
    public void index(Integer page, HttpResponse response, String username) {
        if (page == null) {
            page = 1;
        }
        if (username == null) {
            username = "";
        }

        PageResult<UserVo> list = userService.list(page, 10, username);
        Map<String, Object> data = new HashMap<>(5);
        data.put("page", page);
        data.put("pageSize", 10);
        data.put("totalRow", list.getTotalRow());
        data.put("list", list.getList());
        data.put("totalPage", list.getTotalPage());
        data.put("username", username);
        response.sendTemplate("/admin/user.ftl", data);
    }

    @POST("/admin/user/edit")
    public void edit(Integer page, HttpResponse response, String username, String password, String ports,
                     Integer type, Integer level, String domains, String hasCloseCheckPhoto, HttpRequest request) {
        if (username != null) {
            // 审计摘要：只写业务字段，password 不进 detail
            String detail = "类型=" + type + "，级别=" + level + "，端口=" + ports + "，域名=" + domains;
            try {
                userService.editUser(username, password, ports, type, level, domains, hasCloseCheckPhoto);
                AdminAudit.record(auditService, request, "user.edit", username, detail, true);
            } catch (RuntimeException e) {
                // 审计失败路径后原样抛出，保持与改造前一致的错误行为（不静默吞异常）
                AdminAudit.record(auditService, request, "user.edit", username, detail, false);
                throw e;
            }
        }
        index(page, response, null);
    }

    @POST("/admin/user/add")
    public void add(Integer page, HttpResponse response, String username, String password, String ports,
                    String domains, Integer level, HttpRequest request) {
        if (username != null) {
            String detail = "级别=" + level + "，端口=" + ports + "，域名=" + domains;
            boolean created;
            try {
                created = userService.addUser(username, password, ports, domains, level, "false");
            } catch (RuntimeException e) {
                AdminAudit.record(auditService, request, "user.add", username, detail, false);
                throw e;
            }
            // 返回值即「是否真的新增成功」（账号已存在会返回 false），审计结果以它为准
            AdminAudit.record(auditService, request, "user.add", username, detail, created);
        }
        index(page, response, null);
    }

    @GET("/admin/user/remove")
    public void remove(Integer page, HttpResponse response, String username, HttpRequest request) {
        if (username != null) {
            try {
                userService.remove(username);
                AdminAudit.record(auditService, request, "user.remove", username, "删除用户及其端口/域名", true);
            } catch (RuntimeException e) {
                AdminAudit.record(auditService, request, "user.remove", username, "删除用户", false);
                throw e;
            }
        }
        index(page, response, null);
    }
}

package miao.byusi.hp.server.controller.admin;

import cn.hserver.plugin.web.annotation.Controller;
import cn.hserver.plugin.web.annotation.GET;
import cn.hserver.plugin.web.annotation.POST;
import cn.hserver.plugin.web.context.PartFile;
import cn.hserver.plugin.web.interfaces.HttpRequest;
import cn.hserver.plugin.web.interfaces.HttpResponse;
import miao.byusi.hp.server.domian.entity.AppEntity;
import miao.byusi.hp.server.service.AppService;
import miao.byusi.hp.server.service.AuditService;
import miao.byusi.hp.server.utils.AdminAudit;
import org.beetl.sql.core.page.PageResult;
import cn.hserver.core.ioc.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * @author hxm
 * <p>
 * 【审计】app 版本的新增 / 安装包上传 / 删除写审计
 * （app.add / app.upload / app.remove）。
 * 上传失败（大小、后缀、魔数不合规）同样留痕：安装包替换是只读接口之外最敏感的操作之一，
 * 「谁尝试上传了一个不合规的文件」本身就是需要排查的信号。detail 只记录大小与原因，不记录文件内容。
 */
@Controller
public class AppController {
    private static final Logger log = LoggerFactory.getLogger(AppController.class);

    /**
     * 【安全修复】APK 上传限制：固定目录 + 固定文件名 + 后缀/大小/魔数校验，彻底丢弃客户端文件名。
     */
    private static final long MAX_APK_SIZE = 200L * 1024 * 1024;
    private static final String APK_DIR = "data" + File.separator + "apk";
    private static final String APK_NAME = "hp-client.apk";

    @Autowired
    private AppService appService;

    @Autowired
    private AuditService auditService;

    @GET("/admin/app")
    public void index(Integer page, HttpResponse response) {
        if (page == null) {
            page = 1;
        }
        PageResult<AppEntity> list = appService.list(page, 10);
        Map<String, Object> data = new HashMap<>(5);
        data.put("page", page);
        data.put("pageSize", 10);
        data.put("totalRow", list.getTotalRow());
        data.put("list", list.getList());
        data.put("totalPage", list.getTotalPage());
        response.sendTemplate("/admin/app.ftl", data);
    }

    @POST("/admin/app/add")
    public void add(Integer page, HttpResponse response, AppEntity appEntity, HttpRequest request) {
        String target = appEntity == null || appEntity.getVersionCode() == null ? "" : appEntity.getVersionCode().trim();
        String detail = appEntity == null ? "新增 app 版本"
                : "更新说明长度=" + (appEntity.getUpdateContent() == null ? 0 : appEntity.getUpdateContent().length());
        if (appEntity != null && target.length() > 0 && appEntity.getUpdateContent() != null
                && appEntity.getUpdateContent().trim().length() > 0) {
            try {
                appService.add(appEntity);
                AdminAudit.record(auditService, request, "app.add", target, detail, true);
            } catch (Exception e) {
                AdminAudit.record(auditService, request, "app.add", target, detail, false);
                log.warn("新增 app 版本失败：{}", e.getMessage());
            }
        } else {
            AdminAudit.record(auditService, request, "app.add", target, "参数不完整，未写入", false);
        }
        index(page, response);
    }

    @POST("/admin/app/upload")
    public void add(Integer page, HttpResponse response, HttpRequest request) throws IOException {
        PartFile apk = request.queryFile("apk");
        // 【安全修复】原实现 apk 为 null 时直接 NPE，且把客户端文件名交给 moveTo(new File("./hp-client.apk"))，
        // 相对当前工作目录写入，配合 Multipart 文件名可造成任意路径写入。
        if (apk == null) {
            AdminAudit.record(auditService, request, "app.upload", APK_NAME, "上传失败：请求中没有 apk 文件字段", false);
            index(page, response);
            return;
        }
        if (apk.getLength() <= 0 || apk.getLength() > MAX_APK_SIZE) {
            log.warn("APK 上传被拒绝：大小不合法({} bytes)", apk.getLength());
            AdminAudit.record(auditService, request, "app.upload", APK_NAME,
                    "上传失败：大小不合法（" + apk.getLength() + " 字节）", false);
            index(page, response);
            return;
        }
        String formName = apk.getFileName();
        if (formName == null || !formName.toLowerCase().endsWith(".apk")) {
            log.warn("APK 上传被拒绝：后缀不是 .apk");
            AdminAudit.record(auditService, request, "app.upload", APK_NAME,
                    "上传失败：后缀不是 .apk", false);
            index(page, response);
            return;
        }
        if (!isZipPackage(apk)) {
            log.warn("APK 上传被拒绝：文件头不是 ZIP/APK 格式");
            AdminAudit.record(auditService, request, "app.upload", APK_NAME,
                    "上传失败：文件头不是 ZIP/APK 格式", false);
            index(page, response);
            return;
        }

        File dir = new File(System.getProperty("user.dir"), APK_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            log.error("APK 上传失败：无法创建目录 {}", dir.getAbsolutePath());
            AdminAudit.record(auditService, request, "app.upload", APK_NAME,
                    "上传失败：无法创建安装包目录", false);
            index(page, response);
            return;
        }
        // 文件名完全由服务端指定，客户端输入不参与路径拼接；再做一次规范化校验兜底。
        File target = new File(dir, APK_NAME);
        String base = dir.getCanonicalPath() + File.separator;
        if (!target.getCanonicalPath().startsWith(base)) {
            log.error("APK 上传失败：目标路径越界");
            AdminAudit.record(auditService, request, "app.upload", APK_NAME,
                    "上传失败：目标路径越界", false);
            index(page, response);
            return;
        }
        apk.moveTo(target);
        // 上传成功：只记录落盘文件的大小，不记录内容
        AdminAudit.record(auditService, request, "app.upload", APK_NAME,
                "安装包已替换，大小=" + apk.getLength() + " 字节", true);
        index(page, response);
    }

    /**
     * 校验文件头是否为 ZIP（APK 本质是 ZIP，魔数 PK\x03\x04 / PK\x05\x06）。
     */
    private boolean isZipPackage(PartFile apk) {
        byte[] head = new byte[4];
        try {
            File f = apk.getFile();
            if (f != null && f.isFile()) {
                try (InputStream in = new FileInputStream(f)) {
                    if (in.read(head) < 4) {
                        return false;
                    }
                }
            } else {
                byte[] data = apk.getData();
                if (data == null || data.length < 4) {
                    return false;
                }
                System.arraycopy(data, 0, head, 0, 4);
            }
        } catch (Exception e) {
            log.warn("APK 上传校验读取失败: {}", e.getMessage());
            return false;
        }
        return head[0] == 'P' && head[1] == 'K'
                && ((head[2] == 3 && head[3] == 4) || (head[2] == 5 && head[3] == 6));
    }

    @GET("/admin/app/remove")
    public void remove(Integer page, HttpResponse response, String id, HttpRequest request) {
        if (id != null) {
            try {
                appService.remove(id);
                AdminAudit.record(auditService, request, "app.remove", id, "删除 app 版本记录", true);
            } catch (RuntimeException e) {
                AdminAudit.record(auditService, request, "app.remove", id, "删除 app 版本记录", false);
                throw e;
            }
        }
        index(page, response);
    }
}

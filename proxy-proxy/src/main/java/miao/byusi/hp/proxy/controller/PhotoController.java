package miao.byusi.hp.proxy.controller;

import cn.hserver.core.server.context.ConstConfig;
import cn.hserver.plugin.web.annotation.Controller;
import cn.hserver.plugin.web.annotation.GET;
import cn.hserver.plugin.web.interfaces.HttpRequest;
import cn.hserver.plugin.web.interfaces.HttpResponse;
import miao.byusi.hp.proxy.annotation.CheckApi;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author hxm
 */
@Controller
public class PhotoController {
    private static final Logger log = LoggerFactory.getLogger(PhotoController.class);

    private static final String PHOTO_PATH = ConstConfig.PATH + "photo";

    /**
     * 【安全修复 J14】合法的「时间桶」目录名白名单。
     * 图片目录由节点按 {@code yyyy-MM-dd} 命名（见 PhotoQueue），因此只接受
     * 该格式或纯数字（≤32位），其余一律拒绝——绝不能把 URL 解码后的任意路径拼进 Location。
     */
    private static final java.util.regex.Pattern TIME_BUCKET =
            java.util.regex.Pattern.compile("^[0-9]{1,32}$|^[0-9]{4}-[0-9]{2}-[0-9]{2}$");

    /**
     * 【安全修复】把调用方提供的相对路径安全地解析到图片根目录内。
     * 拒绝：空值、NUL、绝对路径、盘符、协议前缀、"." / ".." / 空路径段，
     * 并在规范化后再次确认结果仍位于根目录之下（防目录穿越与符号链接逃逸）。
     *
     * @return 合法则返回 File，否则返回 null
     */
    private static File resolveInPhotoDir(String relative) {
        if (relative == null) {
            return null;
        }
        String raw = relative.trim();
        if (raw.isEmpty() || raw.indexOf('\0') >= 0) {
            return null;
        }
        String normalized = raw.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.startsWith("~")) {
            return null;
        }
        if (normalized.contains("://") || normalized.matches("^[A-Za-z]:.*")) {
            return null;
        }
        for (String seg : normalized.split("/")) {
            if (seg.isEmpty() || ".".equals(seg) || "..".equals(seg)) {
                return null;
            }
        }
        try {
            Path base = Paths.get(PHOTO_PATH).toAbsolutePath().normalize();
            Path target = base.resolve(normalized).normalize();
            if (!target.startsWith(base)) {
                return null;
            }
            // 若路径已存在，再用 toRealPath 解析符号链接，确保没有跳出根目录
            try {
                Path real = target.toRealPath();
                Path realBase = base.toRealPath();
                if (!real.startsWith(realBase)) {
                    return null;
                }
            } catch (Exception ignored) {
                // 路径不存在时无法 toRealPath，此时 normalize + startsWith 已足够
            }
            return target.toFile();
        } catch (Exception e) {
            log.warn("图片路径解析失败: {}", e.getMessage());
            return null;
        }
    }

    private static List<String> getPhotoDirectory() {
        List<String> data = new ArrayList<>();
        File file = new File(PHOTO_PATH);
        File[] files = file.listFiles();
        if (files == null) {
            return data;
        }
        for (File listFile : files) {
            if (listFile.isDirectory()) {
                data.add(listFile.getName());
            }
        }
        return data;
    }

    private static List<String> getPhotoFile(String time) {
        List<String> data = new ArrayList<>();
        // 【安全修复】/photo/{time} 的 time 同样可以包含 ../，这里做同样的目录约束
        File file = resolveInPhotoDir(time);
        if (file == null || !file.isDirectory()) {
            return data;
        }
        File[] files = file.listFiles();
        if (files == null) {
            return data;
        }
        for (File listFile : files) {
            if (listFile.isFile()) {
                data.add(listFile.getName());
            }
        }
        return data;
    }

    /**
     * 【安全修复 J6】原实现缺少 @CheckApi：任何人可匿名枚举图片目录清单。
     * token 走查询串（节点端既有契约，见 HookCheckApi），模板链接已同步带上 token。
     */
    @CheckApi
    @GET("/photoList")
    public void photoList(HttpRequest request, HttpResponse response) {
        try {
            Map<String, Object> data = new HashMap<>(2);
            data.put("token", request.query("token"));
            List<String> photoDirectory = getPhotoDirectory();
            data.put("dataSize", photoDirectory.size());
            data.put("data", photoDirectory);
            response.sendTemplate("/photoList.ftl", data);
        } catch (Exception e) {
        }
    }

    /**
     * 【安全修复 J6】原实现缺少 @CheckApi：任何人可匿名枚举某个时间桶下的图片文件名。
     */
    @CheckApi
    @GET("/photo/{time}")
    public void photo(String time, HttpRequest request, HttpResponse response) {
        try {
            Map<String, Object> data = new HashMap<>(2);
            data.put("token", request.query("token"));
            data.put("time", time);
            List<String> photoFile = getPhotoFile(time);
            data.put("dataSize", photoFile.size());
            data.put("data", photoFile);
            response.sendTemplate("/photoDetailList.ftl", data);
        } catch (Exception e) {
        }
    }

    /**
     * 【安全修复】破坏性接口：要求 @CheckApi 令牌 + 目标目录必须位于图片根目录内。
     */
    @CheckApi
    @GET("/photoRemoveAll/{time}")
    public void photoRemoveAll(String time, HttpRequest request, HttpResponse response) {
        File dir = resolveInPhotoDir(time);
        if (dir == null) {
            log.warn("photoRemoveAll 路径非法，已拒绝");
            response.sendStatusCode(io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST);
            response.sendText("");
            return;
        }
        try {
            FileUtils.deleteDirectory(dir);
        } catch (Exception e) {
            log.error("删除图片目录失败: {}", e.getMessage());
        }
        // 【安全修复 J6】/photoList 现在需要 @CheckApi，跳转必须继续携带 token
        response.redirect(redirectTarget("/photoList", request));
    }


    /**
     * 【安全修复】原实现直接把 {path} 拼到图片目录后交给 setDownloadFile，
     * 形如 ../../etc/passwd 的路径可读取任意文件；同时缺乏任何鉴权。
     * 现在路径被严格限制在图片根目录内。
     * 【安全修复 J6】补上 @CheckApi：该接口能下载图片根目录内的任意文件，不能匿名开放。
     * 由于 photoDetailList.ftl 以 &lt;img src&gt; 引用它（浏览器无法附加自定义请求头），
     * 因此按节点端既有契约允许 token 走查询参数（HookCheckApi 读取 request.query("token")），
     * 模板已同步在 src 中带上 token；节点响应统一带 Referrer-Policy: no-referrer，避免 Referer 泄露 token。
     */
    @CheckApi
    @GET("/photoDetail/{path}")
    public void photoDetail(String path, HttpRequest request, HttpResponse response) {
        File file = resolveInPhotoDir(path);
        if (file == null || !file.isFile()) {
            response.sendStatusCode(io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND);
            response.sendText("");
            return;
        }
        response.setDownloadFile(file);
    }

    /**
     * 【安全修复】破坏性接口：@CheckApi 令牌 + 目标文件必须位于图片根目录内。
     */
    @CheckApi
    @GET("/photoRemove/{path}")
    public void photoRemove(String path, HttpRequest request, HttpResponse response) {
        File file = resolveInPhotoDir(path);
        if (file == null) {
            log.warn("photoRemove 路径非法，已拒绝");
            response.sendStatusCode(io.netty.handler.codec.http.HttpResponseStatus.BAD_REQUEST);
            response.sendText("");
            return;
        }
        try {
            FileUtils.delete(file);
        } catch (Exception e) {
            log.error("删除图片失败: {}", e.getMessage());
        }
        String normalized = path == null ? "" : path.replace('\\', '/');
        String first = "";
        int slash = normalized.indexOf('/');
        if (slash >= 0) {
            first = normalized.substring(0, slash);
        } else {
            first = normalized;
        }
        if (TIME_BUCKET.matcher(first).matches()) {
            response.redirect(redirectTarget("/photo/" + first, request));
        } else {
            log.warn("photoRemove 的跳转目标不在时间桶白名单内，已回落到 /photoList");
            response.redirect(redirectTarget("/photoList", request));
        }
    }

    /**
     * 【安全修复 J14 / J6】跳转目标同样要带上 token。
     * <p>
     * J14：原实现把 URL 解码后的 {path} 第一段直接拼进 Location，
     * 形如 %0d%0a 的输入可以污染 Location 响应头（响应拆分/重定向伪造）。
     * 现在只允许跳转到「时间桶」白名单里的目录，其余一律回到图片根列表。
     * <p>
     * J6：/photo/* 与 /photoList 现在都需要 @CheckApi，因此重定向目标必须继续携带 token，
     * 否则删除后的跳转会被鉴权拦截。
     */
    private static String redirectTarget(String path, HttpRequest request) {
        String token = request == null ? null : request.query("token");
        if (token == null || token.trim().isEmpty()) {
            return path;
        }
        return path + "?token=" + URLEncoder.encode(token.trim(), StandardCharsets.UTF_8);
    }

}

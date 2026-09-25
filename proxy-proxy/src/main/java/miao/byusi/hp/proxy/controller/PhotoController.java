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
        response.redirect("/photoList");
    }


    /**
     * 【安全修复】原实现直接把 {path} 拼到图片目录后交给 setDownloadFile，
     * 形如 ../../etc/passwd 的路径可读取任意文件；同时缺乏任何鉴权。
     * 现在路径被严格限制在图片根目录内。
     * 说明：该接口未加 @CheckApi，因为 photoDetailList.ftl 以 &lt;img src&gt; 引用它，
     * 浏览器不会带 token；模板侧如需鉴权请改造成带 token 的下载入口。
     */
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
        String first = path.replace('\\', '/').split("/")[0];
        response.redirect("/photo/" + first);
    }

}

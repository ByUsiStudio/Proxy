package miao.byusi.hp.server.controller.admin;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.plugin.web.annotation.Controller;
import cn.hserver.plugin.web.annotation.GET;
import cn.hserver.plugin.web.interfaces.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import miao.byusi.hp.server.config.SysLogConfig;
import miao.byusi.hp.server.utils.ExportUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 后台「系统日志」：日志文件的在线查看与下载。
 * <p>
 * 日志文件由 logback-proxy.xml 产出（相对进程工作目录）：
 * <pre>
 *   log/proxy-server.log                  当前全量日志
 *   log/proxy-server-error.log            仅 ERROR
 *   log/proxy-server-YYYY-MM-DD.N.log     按天 + 按大小归档
 * </pre>
 * 目录与行数上限来自 app.properties 的 {@code admin.syslog.dir} / {@code admin.syslog.max.lines}
 * （见 {@link SysLogConfig}）。
 * <p>
 * <b>为什么查看要从文件尾部读</b>：日志文件最大 20MB 且持续增长，若每次查看都整文件读入并切行，
 * 一个管理员的刷新动作就能吃掉几十 MB 堆内存。因此这里只读文件末尾最多
 * {@link #MAX_TAIL_BYTES} 字节，再从后往前挑出需要的行。
 * <p>
 * <b>为什么下载要限制大小</b>：下载走的是「整个文件读成字符串」的路径，
 * 必须有一个硬上限，否则被归档文件撑爆。
 */
@Controller
public class SysLogController {

    private static final Logger log = LoggerFactory.getLogger(SysLogController.class);

    /** 文件名白名单：只允许字母数字点下划线横线（配合后面的后缀与目录包含校验） */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");
    /** 归档文件名形如 proxy-server-2026-09-25.1.log；不匹配的即为「正在写入」的当前文件 */
    private static final Pattern ARCHIVE_NAME = Pattern.compile(".*-\\d{4}-\\d{2}-\\d{2}\\.\\d+\\.log$");
    /** 单次最多从文件尾部读取的字节数（4MB 足够覆盖数千行日志） */
    private static final int MAX_TAIL_BYTES = 4 * 1024 * 1024;
    /** 下载的硬上限（logback 单文件上限 20MB，留出余量） */
    private static final long MAX_DOWNLOAD_BYTES = 32L * 1024 * 1024;
    /** 默认返回行数 */
    private static final int DEFAULT_LINES = 500;
    /** 文件名长度上限 */
    private static final int MAX_NAME_LENGTH = 128;

    private static final String LEVEL_ALL = "all";

    @Autowired
    private SysLogConfig sysLogConfig;

    // ------------------------------------------------------------------
    // 页面与接口
    // ------------------------------------------------------------------

    /**
     * 日志页：服务端先把文件清单渲染出来，JS 再异步加载内容。
     */
    @GET("/admin/syslog")
    public void index(HttpResponse response) {
        List<Map<String, Object>> files = listFiles();
        Map<String, Object> data = new HashMap<>(8);
        data.put("dir", displayDir());
        data.put("files", files);
        data.put("activeFile", defaultFile(files));
        data.put("maxLines", sysLogConfig.getMaxLines());
        response.sendTemplate("/admin/syslog.ftl", data);
    }

    /**
     * 文件清单 JSON（按修改时间倒序）。
     */
    @GET("/admin/syslog/list")
    public void list(HttpResponse response) {
        Map<String, Object> data = new LinkedHashMap<>(4);
        data.put("code", 200);
        data.put("dir", displayDir());
        data.put("files", listFiles());
        response.sendJson(data);
    }

    /**
     * 读取日志尾部若干行。
     *
     * @param file    文件名（只允许日志目录下的 .log 文件，见 {@link #resolveLogFile}）
     * @param lines   返回行数，上限为 admin.syslog.max.lines
     * @param level   all / debug / info / warn / error
     * @param keyword 关键字（不区分大小写），可为空
     */
    @GET("/admin/syslog/view")
    public void view(String file, Integer lines, String level, String keyword, HttpResponse response) {
        Path target = resolveLogFile(file);
        if (target == null) {
            response.sendJson(error("日志文件不存在或名称不合法"));
            return;
        }
        int max = sysLogConfig.getMaxLines();
        int want = (lines == null || lines < 1) ? DEFAULT_LINES : Math.min(lines, max);
        String normalizedLevel = normalizeLevel(level);

        TailResult tail = readTail(target, want, normalizedLevel, keyword);
        if (tail == null) {
            // 文件正在被 logback 轮转/截断时的兜底：返回可读的错误，而不是 500
            response.sendJson(error("日志文件暂时无法读取，请稍后重试"));
            return;
        }

        Map<String, Object> data = new LinkedHashMap<>(12);
        data.put("code", 200);
        data.put("file", target.getFileName().toString());
        data.put("level", normalizedLevel);
        data.put("keyword", keyword == null ? "" : keyword);
        data.put("size", tail.size);
        // 读取窗口内的总行数与「匹配过滤条件的行数」，让前端能提示「已截断」
        data.put("window", tail.window);
        data.put("matched", tail.matched);
        data.put("returned", tail.picked.size());
        data.put("truncated", tail.truncated);
        data.put("lines", tail.picked);
        response.sendJson(data);
    }

    /**
     * 下载日志文件原文。
     */
    @GET("/admin/syslog/download")
    public void download(String file, HttpResponse response) {
        Path target = resolveLogFile(file);
        if (target == null) {
            response.sendStatusCode(HttpResponseStatus.NOT_FOUND);
            response.sendText("日志文件不存在或名称不合法");
            return;
        }
        try {
            long size = Files.size(target);
            if (size > MAX_DOWNLOAD_BYTES) {
                log.warn("拒绝下载过大的日志文件：{}（{} 字节）", target.getFileName(), size);
                // 用数值而不是常量名：Netty 各版本对 413 的常量命名不一致
                response.sendStatusCode(HttpResponseStatus.valueOf(413));
                response.sendText("日志文件过大，请直接在服务器上查看");
                return;
            }
            byte[] bytes = Files.readAllBytes(target);
            // 复用统一的附件写出：文件名会被 safeFilename 再过滤一次，
            // 并带 X-Content-Type-Options: nosniff / Cache-Control: no-store
            ExportUtil.attachment(response, target.getFileName().toString(),
                    "text/plain; charset=utf-8", new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("下载日志文件失败：{} -> {}", target.getFileName(), e.getMessage());
            response.sendStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR);
            response.sendText("日志文件读取失败");
        }
    }

    // ------------------------------------------------------------------
    // 路径安全
    // ------------------------------------------------------------------

    /**
     * 【安全】把请求参数里的文件名解析成「一定位于日志目录内」的真实文件。
     * <p>
     * 这是典型的<b>任意文件读取漏洞</b>（CWE-22 / OWASP A01）的防线：
     * 只要把 {@code file=../../app.properties}（或 {@code ..%5C..%5C}、绝对路径、
     * 带 NUL 截断的名字）交给 {@code new File(dir, name)}，攻击者就能读到进程可读的任意文件——
     * 本项目的 app.properties 里就有后台密码与邮箱授权码。
     * <p>
     * 因此这里做四层校验，任何一层不通过都直接拒绝（fail-closed）：
     * <ol>
     *   <li>显式拒绝 {@code / \\ .. NUL} 与绝对路径；</li>
     *   <li>只允许 {@code [A-Za-z0-9._-]+} 且必须以 {@code .log} 结尾；</li>
     *   <li>{@code Paths.get(name).getFileName()} 必须与原始串完全一致
     *       （Windows 上 {@code a/../b} 会被规范化，靠这一步兜住）；</li>
     *   <li>解析并规范化后必须仍以「日志目录的真实路径」为前缀，
     *       再用 {@code toRealPath()} 解析符号链接后复核一次。</li>
     * </ol>
     */
    private Path resolveLogFile(String rawName) {
        if (rawName == null) {
            return null;
        }
        String name = rawName.trim();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            return null;
        }
        // 1) 目录成分 / 上跳 / 空字节 / 绝对路径
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.indexOf('\0') >= 0 || name.contains("..")) {
            return null;
        }
        // 2) 字符白名单 + 固定后缀
        if (!SAFE_NAME.matcher(name).matches()
                || !name.toLowerCase(Locale.ROOT).endsWith(".log")) {
            return null;
        }
        Path pure = Paths.get(name);
        if (pure.isAbsolute()) {
            return null;
        }
        // 3) getFileName 必须与原名相同（只有一个纯文件名，没有任何目录语义）
        Path fileName = pure.getFileName();
        if (fileName == null || !name.equals(fileName.toString())) {
            return null;
        }

        Path realDir = realLogDir();
        if (realDir == null) {
            return null;
        }
        // 4) 目录包含校验：规范化后仍必须在日志目录内
        Path candidate = realDir.resolve(fileName.toString()).normalize();
        if (!candidate.startsWith(realDir) || !Files.isRegularFile(candidate)) {
            return null;
        }
        try {
            Path real = candidate.toRealPath();
            if (!real.startsWith(realDir) || !Files.isRegularFile(real)) {
                return null;
            }
            return real;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 解析配置里的日志目录。
     * <p>
     * 配置可以是相对路径（相对进程工作目录 {@code user.dir}，与 logback 的相对路径语义一致），
     * 也可以是绝对路径。目录不存在时返回 null：上层据此返回空列表，而不是报错 ——
     * 首次启动、尚未产生任何日志时，页面应当是「空」而不是「坏掉」。
     */
    private Path resolveDir() {
        String configured = sysLogConfig.getDir();
        try {
            Path path = Paths.get(configured);
            if (!path.isAbsolute()) {
                String base = System.getProperty("user.dir");
                path = Paths.get(base == null || base.isEmpty() ? "." : base).resolve(path);
            }
            return path.normalize();
        } catch (Exception e) {
            log.warn("日志目录配置不合法：{} -> {}", configured, e.getMessage());
            return null;
        }
    }

    /** 日志目录的真实路径（解析符号链接），目录不存在返回 null。 */
    private Path realLogDir() {
        Path dir = resolveDir();
        if (dir == null) {
            return null;
        }
        try {
            Path real = dir.toRealPath();
            return Files.isDirectory(real) ? real : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** 页面上展示的目录（配置值 + 绝对路径兜底）。 */
    private String displayDir() {
        Path dir = resolveDir();
        return dir == null ? sysLogConfig.getDir() : dir.toString();
    }

    // ------------------------------------------------------------------
    // 文件清单
    // ------------------------------------------------------------------

    private List<Map<String, Object>> listFiles() {
        List<Map<String, Object>> out = new ArrayList<>();
        Path dir = realLogDir();
        if (dir == null) {
            // 目录不存在：返回空列表，不报错
            return out;
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.log")) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (!SAFE_NAME.matcher(name).matches() || !Files.isRegularFile(path)) {
                    continue;
                }
                long size = 0L;
                long modTime = 0L;
                try {
                    size = Files.size(path);
                    modTime = Files.getLastModifiedTime(path).toMillis();
                } catch (IOException e) {
                    // 文件恰好被 logback 轮转掉：属性读不到时按 0 处理，不影响其它文件
                }
                Map<String, Object> item = new LinkedHashMap<>(6);
                item.put("name", name);
                item.put("size", size);
                item.put("sizeText", humanSize(size));
                item.put("modTime", modTime);
                item.put("modTimeText", modTime <= 0 ? "—" : format.format(new Date(modTime)));
                // 不带日期序号的（proxy-server.log / proxy-server-error.log）就是正在写入的当前文件
                item.put("active", !ARCHIVE_NAME.matcher(name).matches());
                out.add(item);
            }
        } catch (Exception e) {
            // 目录遍历本身也可能抛 DirectoryIteratorException（非受检），一并兜住
            log.warn("读取日志目录失败：{} -> {}", dir, e.getMessage());
        }
        // 最近修改的排在前面
        out.sort((a, b) -> Long.compare((Long) b.get("modTime"), (Long) a.get("modTime")));
        return out;
    }

    /** 默认选中的文件：优先当前正在写入的，否则取最新的一个。 */
    private static String defaultFile(List<Map<String, Object>> files) {
        for (Map<String, Object> file : files) {
            if (Boolean.TRUE.equals(file.get("active"))) {
                return String.valueOf(file.get("name"));
            }
        }
        return files.isEmpty() ? "" : String.valueOf(files.get(0).get("name"));
    }

    // ------------------------------------------------------------------
    // 尾部读取
    // ------------------------------------------------------------------

    /**
     * 从文件尾部读取并筛选日志行。
     * <p>
     * 并发容忍：logback 可能正在写入同一文件（甚至刚刚触发轮转把文件改名），
     * 因此整体重试一次；仍失败则返回 null，由调用方给出可读错误，绝不抛到框架。
     */
    private TailResult readTail(Path file, int want, String level, String keyword) {
        IOException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return readTailOnce(file, want, level, keyword);
            } catch (IOException e) {
                last = e;
            }
        }
        log.warn("读取日志文件失败：{} -> {}", file.getFileName(), last == null ? "" : last.getMessage());
        return null;
    }

    private TailResult readTailOnce(Path file, int want, String level, String keyword) throws IOException {
        long size = Files.size(file);
        long start = Math.max(0L, size - MAX_TAIL_BYTES);
        int capacity = (int) Math.min((long) MAX_TAIL_BYTES, size);
        byte[] buffer = new byte[capacity];
        int read = 0;
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(start);
            while (read < capacity) {
                int n = raf.read(buffer, read, capacity - read);
                if (n < 0) {
                    // 文件在读取过程中被截断：读到多少算多少
                    break;
                }
                read += n;
            }
        }

        String text = new String(buffer, 0, read, StandardCharsets.UTF_8);
        String[] raw = text.split("\r?\n", -1);
        // 从文件中间开始读时，第一行很可能是被截断的半行，丢弃它以免展示残缺内容
        int from = (start > 0 && raw.length > 0) ? 1 : 0;

        String key = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        Deque<Map<String, Object>> picked = new ArrayDeque<>();
        int window = 0;
        int matched = 0;
        for (int i = raw.length - 1; i >= from; i--) {
            String line = raw[i];
            if (line.isEmpty()) {
                continue;
            }
            window++;
            String lineLevel = levelOf(line);
            if (!LEVEL_ALL.equals(level) && !level.equals(lineLevel)) {
                continue;
            }
            if (!key.isEmpty() && line.toLowerCase(Locale.ROOT).indexOf(key) < 0) {
                continue;
            }
            matched++;
            if (picked.size() >= want) {
                // 已经凑够要返回的行数，继续只是为了统计「还有多少行匹配」
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>(3);
            // 行号是「读取窗口内的序号」：日志文件可能被轮转，无法在不全量扫描的前提下
            // 得到绝对行号，前端也只把它当展示用的序号。
            item.put("n", i - from + 1);
            item.put("level", lineLevel);
            item.put("text", line);
            picked.addFirst(item);
        }

        TailResult result = new TailResult();
        result.size = size;
        result.window = window;
        result.matched = matched;
        result.picked = new ArrayList<>(picked);
        // 截断两种情形：文件更大、更早的部分没读（start > 0），或匹配行数超过请求行数
        result.truncated = start > 0 || matched > result.picked.size();
        return result;
    }

    /**
     * 从行文本推断级别。日志里的级别是渲染后的字符串，直接按关键字判定，
     * 顺序与 admin.js 的 Admin.logLines 保持一致，保证前后端着色一致。
     */
    private static String levelOf(String line) {
        if (line.contains("ERROR") || line.contains("FATAL")) {
            return "error";
        }
        if (line.contains("WARN")) {
            return "warn";
        }
        if (line.contains("DEBUG") || line.contains("TRACE")) {
            return "debug";
        }
        return "info";
    }

    private static String normalizeLevel(String level) {
        if (level == null) {
            return LEVEL_ALL;
        }
        String value = level.trim().toLowerCase(Locale.ROOT);
        if ("debug".equals(value) || "info".equals(value) || "warn".equals(value) || "error".equals(value)) {
            return value;
        }
        return LEVEL_ALL;
    }

    private static String humanSize(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int index = 0;
        double value = bytes;
        while (value >= 1024 && index < units.length - 1) {
            value /= 1024;
            index++;
        }
        return String.format(Locale.ROOT, index == 0 ? "%.0f %s" : "%.2f %s", value, units[index]);
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> map = new LinkedHashMap<>(2);
        map.put("code", -1);
        map.put("msg", message);
        return map;
    }

    /** 尾部读取结果 */
    private static final class TailResult {
        long size;
        int window;
        int matched;
        boolean truncated;
        List<Map<String, Object>> picked = new ArrayList<>();
    }
}

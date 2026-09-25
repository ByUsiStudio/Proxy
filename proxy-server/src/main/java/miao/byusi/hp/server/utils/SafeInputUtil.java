package miao.byusi.hp.server.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

/**
 * 安全输入校验/清洗工具。
 * <p>
 * 修复点：
 * 1) 账号、域名、host、设备ID 等字段会被原样写入数据库并渲染到后台 FreeMarker 模板，
 * 未转义时构成存储型 XSS（可导致后台被接管），因此在入库前做严格白名单校验；
 * 2) 口令/token 比较统一走 {@link #safeEquals(String, String)}，避免 == / equals 造成的时序侧信道。
 */
public final class SafeInputUtil {

    /**
     * 账号：邮箱格式（注册只允许 qq 邮箱，格式之外的字符一律拒绝）
     */
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,100}\\.[A-Za-z]{2,10}$");
    /**
     * host 或 host:port（只允许字母数字、点、横线、下划线和可选端口）
     */
    private static final Pattern HOST_PORT = Pattern.compile("^[A-Za-z0-9._-]{1,120}(:[0-9]{1,5})?$");
    /**
     * 纯主机名（域名）
     */
    private static final Pattern DOMAIN = Pattern.compile("^[A-Za-z0-9]([A-Za-z0-9.-]{0,250}[A-Za-z0-9])?$");
    /**
     * 设备ID：只允许字母数字和 - _ ，长度 6-64
     */
    private static final Pattern DEVICE_ID = Pattern.compile("^[A-Za-z0-9_-]{6,64}$");
    /**
     * 穿透类型：只允许纯字母（TCP/UDP 等）
     */
    private static final Pattern TYPE = Pattern.compile("^[A-Za-z]{2,10}$");
    /**
     * 端口字符串
     */
    private static final Pattern PORT = Pattern.compile("^[0-9]{1,5}$");

    private SafeInputUtil() {
    }

    public static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * 常量时间比较，用于口令、验证码、密钥等敏感值，防止通过响应时间推断内容。
     */
    public static boolean safeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 是否包含控制字符或 HTML 元字符（渲染到模板时可能形成 XSS）。
     */
    public static boolean hasDangerousChars(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F || c == '<' || c == '>' || c == '"' || c == '\'' || c == '&' || c == '\\') {
                return true;
            }
        }
        return false;
    }

    /**
     * 校验并返回安全的账号名；不合法返回 null。
     */
    public static String cleanUsername(String s) {
        if (isBlank(s)) {
            return null;
        }
        String v = s.trim();
        if (!USERNAME.matcher(v).matches()) {
            return null;
        }
        // 额外兜底：禁止任何控制字符/HTML 元字符
        return hasDangerousChars(v) ? null : v;
    }

    /**
     * 校验并返回安全的 host 或 host:port；不合法返回 null。
     */
    public static String cleanHostPort(String s) {
        if (isBlank(s)) {
            return null;
        }
        String v = s.trim();
        return HOST_PORT.matcher(v).matches() ? v : null;
    }

    /**
     * 校验并返回安全的主机名/域名；不合法返回 null。
     */
    public static String cleanDomain(String s) {
        if (isBlank(s)) {
            return null;
        }
        String v = s.trim();
        return DOMAIN.matcher(v).matches() ? v : null;
    }

    /**
     * 校验并返回安全的设备ID；不合法返回 null。
     */
    public static String cleanDeviceId(String s) {
        if (isBlank(s)) {
            return null;
        }
        String v = s.trim();
        if ("NO_ID".equals(v)) {
            return null;
        }
        return DEVICE_ID.matcher(v).matches() ? v : null;
    }

    /**
     * 校验并返回安全的穿透类型；不合法返回 null。
     */
    public static String cleanType(String s) {
        if (isBlank(s)) {
            return null;
        }
        String v = s.trim();
        return TYPE.matcher(v).matches() ? v.toUpperCase() : null;
    }

    /**
     * 校验并返回安全的端口字符串；不合法返回 null。
     */
    public static String cleanPort(String s) {
        if (isBlank(s)) {
            return null;
        }
        String v = s.trim();
        return PORT.matcher(v).matches() ? v : null;
    }

    /**
     * 校验并返回一段只含字母数字与 - _ 的标识（用于日志、节点名等）；不合法返回 null。
     */
    public static String cleanToken(String s, int maxLen) {
        if (isBlank(s)) {
            return null;
        }
        String v = s.trim();
        if (v.length() > maxLen) {
            return null;
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
            if (!ok) {
                return null;
            }
        }
        return v;
    }
}

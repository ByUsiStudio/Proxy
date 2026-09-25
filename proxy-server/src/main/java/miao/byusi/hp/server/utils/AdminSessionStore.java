package miao.byusi.hp.server.utils;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【安全修复】后台管理会话。
 * <p>
 * 原实现把管理员密码明文写进非 HttpOnly 的 cookie（auth=密码），由前端 JS 设置，
 * 且校验使用 String.contains，既无会话概念也无法登出。现改为：
 * 服务端用 SecureRandom 生成 32 字节会话ID，保存在内存 map 中，30 分钟无操作过期。
 */
public final class AdminSessionStore {

    private static final long IDLE_TIMEOUT_MS = 30 * 60 * 1000L;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Long> SESSIONS = new ConcurrentHashMap<>();

    private AdminSessionStore() {
    }

    /**
     * 生成并登记一个新的管理会话ID（64位十六进制）。
     */
    public static String create() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        String id = sb.toString();
        SESSIONS.put(id, System.currentTimeMillis());
        return id;
    }

    /**
     * 精确匹配（map key 直接查找，绝不做 contains 子串判断）并刷新空闲时间。
     */
    public static boolean valid(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return false;
        }
        Long last = SESSIONS.get(sessionId);
        if (last == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - last > IDLE_TIMEOUT_MS) {
            SESSIONS.remove(sessionId);
            return false;
        }
        SESSIONS.put(sessionId, now);
        return true;
    }

    public static void invalidate(String sessionId) {
        if (sessionId != null) {
            SESSIONS.remove(sessionId);
        }
    }

    /**
     * 清理过期会话，避免 map 无限增长。
     */
    public static void cleanExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Long>> it = SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue() > IDLE_TIMEOUT_MS) {
                it.remove();
            }
        }
    }

    /**
     * 从 cookie 头中取出指定 cookie 的值（精确名称匹配）。
     */
    public static String cookieValue(String cookieHeader, String name) {
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            return null;
        }
        for (String part : cookieHeader.split(";")) {
            String s = part.trim();
            int idx = s.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            if (s.substring(0, idx).trim().equals(name)) {
                return s.substring(idx + 1).trim();
            }
        }
        return null;
    }
}

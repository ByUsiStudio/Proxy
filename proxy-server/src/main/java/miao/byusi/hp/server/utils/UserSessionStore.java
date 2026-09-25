package miao.byusi.hp.server.utils;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【安全修复】站点（/index/*）用户会话。
 * <p>
 * 原实现由前端 JS 把「用户名|密码」写入 cookie（authUser=账号|明文密码），
 * 该 cookie 非 HttpOnly、无有效期、随每个请求发送，任何 XSS 或抓包都会直接泄露密码。
 * 现改为：登录成功后由服务端下发不透明会话 ID，前端只保存这个 ID，
 * 服务端在内存中维护会话并设置空闲过期时间。
 */
public final class UserSessionStore {

    /** 会话空闲有效期：12 小时 */
    private static final long IDLE_TIMEOUT_MS = 12 * 60 * 60 * 1000L;
    /** 会话数量上限，避免被刷爆内存 */
    private static final int MAX_SESSIONS = 20000;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Entry> SESSIONS = new ConcurrentHashMap<>();

    private UserSessionStore() {
    }

    private static final class Entry {
        private final String username;
        private volatile long lastActive;

        private Entry(String username, long lastActive) {
            this.username = username;
            this.lastActive = lastActive;
        }
    }

    /**
     * 创建会话，返回不透明会话 ID（64 位十六进制）。
     */
    public static String create(String username) {
        if (username == null || username.trim().isEmpty()) {
            return null;
        }
        cleanExpired();
        if (SESSIONS.size() >= MAX_SESSIONS) {
            // 【安全修复 J9】原实现 SESSIONS.clear() 是一次「全站登出」原语：
            // 任何调用方只要把会话表刷到上限，就能把所有在线用户踢下线。
            // 现在改为按空闲时间淘汰最旧的部分会话，仅淘汰到刚好低于上限。
            evictOldest(SESSIONS.size() - MAX_SESSIONS + 1);
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        String id = sb.toString();
        SESSIONS.put(id, new Entry(username.trim(), System.currentTimeMillis()));
        return id;
    }

    /**
     * 【安全修复 J9】淘汰最久未活动的 {@code count} 个会话（不再整体清空）。
     */
    private static void evictOldest(int count) {
        if (count <= 0 || SESSIONS.isEmpty()) {
            return;
        }
        SESSIONS.entrySet().stream()
                .sorted((a, b) -> Long.compare(a.getValue().lastActive, b.getValue().lastActive))
                .limit(count)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toList())
                .forEach(SESSIONS::remove);
    }

    /**
     * 校验会话并刷新空闲时间。
     *
     * @return 会话对应的账号；无效或过期返回 null
     */
    public static String username(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return null;
        }
        Entry entry = SESSIONS.get(sessionId);
        if (entry == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (now - entry.lastActive > IDLE_TIMEOUT_MS) {
            SESSIONS.remove(sessionId);
            return null;
        }
        entry.lastActive = now;
        return entry.username;
    }

    public static void invalidate(String sessionId) {
        if (sessionId != null) {
            SESSIONS.remove(sessionId);
        }
    }

    /**
     * 清理过期会话。
     */
    public static void cleanExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Entry>> it = SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().lastActive > IDLE_TIMEOUT_MS) {
                it.remove();
            }
        }
    }

    /**
     * 读取 cookie 值（复用管理会话的解析逻辑，避免重复实现）。
     */
    public static String cookieValue(String cookieHeader, String name) {
        return AdminSessionStore.cookieValue(cookieHeader, name);
    }
}

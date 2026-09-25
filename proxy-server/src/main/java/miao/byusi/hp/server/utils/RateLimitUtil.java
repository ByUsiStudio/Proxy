package miao.byusi.hp.server.utils;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【安全修复 J11】极简的内存固定窗口限流器。
 * <p>
 * 用于「无需账号即可调用」的接口（例如 {@code /config/listDevice}、{@code /load/data}），
 * 按 调用方真实IP + 桶名 计数，超过阈值在窗口内直接拒绝。
 * <ul>
 *   <li>key 使用 {@link NetUtil#clientIp} 得到的真实 TCP 对端地址，不采用可伪造的请求头；</li>
 *   <li>map 大小有硬上限，超限先清理过期窗口，避免被刷爆内存。</li>
 * </ul>
 */
public final class RateLimitUtil {

    /** 计数器条目上限（超过即触发过期清理） */
    private static final int MAX_ENTRIES = 50000;

    private static final Map<String, Window> WINDOWS = new ConcurrentHashMap<>();

    private RateLimitUtil() {
    }

    private static final class Window {
        private final long start;
        private int count;

        private Window(long start, int count) {
            this.start = start;
            this.count = count;
        }
    }

    /**
     * @param bucket   限流桶名（不同接口用不同名字，避免互相影响）
     * @param key      调用方标识（一般是真实IP）
     * @param maxHits  窗口内允许的最大次数
     * @param windowMs 窗口长度（毫秒）
     * @return true 表示本次允许，false 表示已被限流
     */
    public static boolean allow(String bucket, String key, int maxHits, long windowMs) {
        if (maxHits <= 0 || windowMs <= 0) {
            return true;
        }
        String id = (key == null || key.trim().isEmpty() ? NetUtil.UNKNOWN_IP : key.trim());
        String mapKey = bucket + '|' + id;
        long now = System.currentTimeMillis();
        Window window = WINDOWS.compute(mapKey, (k, current) -> {
            if (current == null || now - current.start >= windowMs) {
                return new Window(now, 1);
            }
            current.count++;
            return current;
        });
        if (WINDOWS.size() > MAX_ENTRIES) {
            evictExpired(now, windowMs);
        }
        return window.count <= maxHits;
    }

    private static void evictExpired(long now, long windowMs) {
        Iterator<Map.Entry<String, Window>> it = WINDOWS.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().start >= windowMs) {
                it.remove();
            }
        }
    }
}

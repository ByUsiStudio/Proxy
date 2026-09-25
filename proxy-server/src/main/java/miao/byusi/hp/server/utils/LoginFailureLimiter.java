package miao.byusi.hp.server.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 【安全修复 J4】口令登录失败限流（防在线爆破）。
 * <p>
 * 原实现 {@code /user/login}、{@code /user/domainLogin}、{@code /admin/login}
 * 都可以无限次尝试明文口令。这里用与 {@link AdminSessionStore} 相同风格的内存计数器：
 * <ul>
 *   <li>按「账号 + 来源IP」计数：连续失败 {@value #MAX_FAILS} 次后封禁 {@value #BLOCK_MINUTES} 分钟；</li>
 *   <li>按「来源IP」计数：同一IP对任意账号累计失败 {@value #MAX_IP_FAILS} 次后同样封禁，
 *       用于阻断撞库/喷洒（阈值更高，尽量避免被用来恶意锁定他人账号）；</li>
 *   <li>登录成功立即清零「账号+IP」计数；</li>
 *   <li>map 有硬上限，超限先清理过期条目，避免被刷爆内存；</li>
 *   <li>对外只返回统一的“账号或密码错误”文案，不做账号枚举。</li>
 * </ul>
 * 绝不记录口令明文（调用方只传账号/来源IP）。
 */
public final class LoginFailureLimiter {
    private static final Logger log = LoggerFactory.getLogger(LoginFailureLimiter.class);

    /** 账号+IP 连续失败上限 */
    private static final int MAX_FAILS = 5;
    /** 同一IP 对任意账号的累计失败上限 */
    private static final int MAX_IP_FAILS = 20;
    /** 失败计数窗口 */
    private static final long WINDOW_MS = 15 * 60 * 1000L;
    /** 触发上限后的封禁时长 */
    private static final long BLOCK_MS = 15 * 60 * 1000L;
    /** 条目硬上限 */
    private static final int MAX_ENTRIES = 20000;

    private static final Map<String, Fail> FAILS = new ConcurrentHashMap<>();

    private LoginFailureLimiter() {
    }

    private static final class Fail {
        private volatile long first;
        private volatile long last;
        private volatile long blockedUntil;
        private int count;

        private Fail(long now) {
            this.first = now;
            this.last = now;
        }
    }

    /** 登录前调用：命中封禁则直接拒绝，返回统一文案。 */
    public static boolean isBlocked(String account, String ip) {
        long now = System.currentTimeMillis();
        Fail byAccount = FAILS.get(accountKey(account, ip));
        if (byAccount != null && byAccount.blockedUntil > now) {
            return true;
        }
        Fail byIp = FAILS.get(ipKey(ip));
        return byIp != null && byIp.blockedUntil > now;
    }

    /** 登录失败时调用。 */
    public static void recordFailure(String account, String ip) {
        long now = System.currentTimeMillis();
        bump(accountKey(account, ip), now, MAX_FAILS);
        bump(ipKey(ip), now, MAX_IP_FAILS);
        if (FAILS.size() > MAX_ENTRIES) {
            evictExpired(now);
        }
    }

    /**
     * 登录成功时调用：只清零「账号+IP」计数。
     * 保留 IP 维度的计数，避免攻击者用自己合法账号的成功登录来重置 IP 上的撞库计数。
     */
    public static void clear(String account, String ip) {
        FAILS.remove(accountKey(account, ip));
    }

    private static void bump(String key, long now, int limit) {
        FAILS.compute(key, (k, current) -> {
            Fail fail = current;
            if (fail == null || now - fail.first > WINDOW_MS) {
                fail = new Fail(now);
            }
            fail.last = now;
            fail.count++;
            if (fail.count >= limit) {
                fail.blockedUntil = now + BLOCK_MS;
                if (fail.count == limit) {
                    log.warn("登录失败次数达到上限，已临时封禁 {} 分钟（key 已脱敏，不含口令）", BLOCK_MS / 60000);
                }
            }
            return fail;
        });
    }

    private static void evictExpired(long now) {
        Iterator<Map.Entry<String, Fail>> it = FAILS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Fail> entry = it.next();
            Fail fail = entry.getValue();
            if (now - fail.last > WINDOW_MS && fail.blockedUntil <= now) {
                it.remove();
            }
        }
        if (FAILS.size() > MAX_ENTRIES) {
            // 仍然超限：删除最久未活动的条目（极端情况下的兜底，保证内存有界）
            FAILS.entrySet().stream()
                    .min((a, b) -> Long.compare(a.getValue().last, b.getValue().last))
                    .map(Map.Entry::getKey)
                    .ifPresent(FAILS::remove);
        }
    }

    private static String accountKey(String account, String ip) {
        return "acc:" + normalize(account) + '|' + normalize(ip);
    }

    private static String ipKey(String ip) {
        return "ip:" + normalize(ip);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "-";
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.isEmpty() ? "-" : v;
    }
}

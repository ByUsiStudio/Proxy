package miao.byusi.hp.server.utils;

import cn.hserver.core.server.util.PropUtil;
import cn.hserver.plugin.web.interfaces.HttpRequest;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.ssl.SslHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.util.Locale;
import java.util.Map;

/**
 * 网络工具类。
 * <p>
 * 【安全修复 J1】HServer 的 {@code request.getIpAddress()} 会优先返回
 * {@code X-Forwarded-For / Proxy-Client-IP / WL-Proxy-Client-IP / X-Real-IP}
 * 等**请求头**的值，任何远程调用方只要伪造 {@code X-Forwarded-For: 127.0.0.1}
 * 就能冒充回环地址，从而绕过节点端 {@code @CheckApi} 的 localOnly 放行、
 * 绕过云端按 IP 的邮件限流。因此这里改为从 Netty {@code Channel} 读取
 * **真实 TCP 对端地址**，绝不把请求头当作身份来源。
 * <p>
 * 只有在运维显式开启 {@code trusted.proxy=true} 时才会参考
 * {@code X-Forwarded-For}（并且取最后一个不可信跳，而不是第一个值）。
 *
 * @author hxm
 */
public class NetUtil {
    private static final Logger log = LoggerFactory.getLogger(NetUtil.class);

    /** 无法确定对端地址时的占位值（比复用某个 IP 更保守：全部未知来源共用一个限流桶）。 */
    public static final String UNKNOWN_IP = "unknown";

    /** 是否信任前置反向代理转发头（X-Forwarded-For / X-Forwarded-Proto 等）。默认关闭。 */
    private static final boolean TRUSTED_PROXY = readTrustedProxy();

    private NetUtil() {
    }

    private static boolean readTrustedProxy() {
        try {
            String value = PropUtil.getInstance().get("trusted.proxy", "false");
            return value != null && "true".equalsIgnoreCase(value.trim());
        } catch (Throwable e) {
            return false;
        }
    }

    /** 是否已显式声明部署在受信任的反向代理之后（trusted.proxy=true）。 */
    public static boolean trustedProxy() {
        return TRUSTED_PROXY;
    }

    /**
     * 获取一个可用的端口
     *
     * @return
     */
    public static int getAvailablePort() {
        ServerSocket serverSocket=null;
        try {
            serverSocket = new ServerSocket(0);
            return serverSocket.getLocalPort();
        } catch (Throwable ignored) {
        }finally {
            if (serverSocket!=null){
                try {
                    serverSocket.close();
                } catch (IOException e) {
                    log.error("关闭端口检测Socket失败", e);
                }
            }
        }
        return -1;
    }

    /**
     * 【安全修复 J1】真实 TCP 对端地址：只读 Netty Channel 的 remoteAddress，
     * 完全不参考请求头。无法确定时返回 {@code null}（调用方必须按“不可信”处理）。
     */
    public static String socketIp(HttpRequest request) {
        if (request == null) {
            return null;
        }
        try {
            ChannelHandlerContext ctx = request.getCtx();
            if (ctx == null || ctx.channel() == null) {
                return null;
            }
            SocketAddress address = ctx.channel().remoteAddress();
            if (!(address instanceof InetSocketAddress)) {
                return null;
            }
            InetSocketAddress inet = (InetSocketAddress) address;
            InetAddress ip = inet.getAddress();
            if (ip != null) {
                return ip.getHostAddress();
            }
            String host = inet.getHostString();
            return host == null || host.isEmpty() ? null : host;
        } catch (Throwable e) {
            log.debug("读取 TCP 对端地址失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 【安全修复 J1】请求是否真的来自回环地址（依据 TCP 对端，而非请求头）。
     * 判定失败一律返回 false（fail-closed），避免伪造头带来的鉴权绕过。
     */
    public static boolean isLoopbackPeer(HttpRequest request) {
        InetAddress address = socketAddress(request);
        if (address != null) {
            return address.isLoopbackAddress();
        }
        return isLoopbackLiteral(socketIp(request));
    }

    /**
     * 用于限流/日志的调用方 IP：
     * <ul>
     *   <li>默认（trusted.proxy=false）：真实 TCP 对端地址；</li>
     *   <li>trusted.proxy=true：取 X-Forwarded-For 中**最后一个不可信跳**（从右往左跳过回环地址）。</li>
     * </ul>
     * 绝不直接采用 X-Forwarded-For 的第一个值。
     */
    public static String clientIp(HttpRequest request) {
        String socket = socketIp(request);
        if (!TRUSTED_PROXY) {
            return socket == null ? UNKNOWN_IP : socket;
        }
        String forwarded = request == null ? null : request.getHeader("x-forwarded-for");
        if (forwarded != null && !forwarded.trim().isEmpty()) {
            String[] parts = forwarded.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String candidate = normalizeIp(parts[i]);
                if (candidate == null || "unknown".equalsIgnoreCase(candidate)) {
                    continue;
                }
                // 跳过受信任代理自身（回环地址），返回第一个不可信跳
                if (isLoopbackLiteral(candidate)) {
                    continue;
                }
                return candidate;
            }
        }
        return socket == null ? UNKNOWN_IP : socket;
    }

    /**
     * 【安全修复 J13】判断当前请求是否为 HTTPS。
     * <p>
     * 原实现直接相信 {@code x-forwarded-proto}（任意调用方都能伪造），
     * 并且在无法判断时比较 {@code request.getPort() == 443}——而 HServer 的
     * {@code getPort()} 返回的是**客户端源端口**，该判断恒为 false。
     * <p>
     * 现在：
     * <ol>
     *   <li>本机直接终止 TLS 时以 pipeline 中的 SslHandler 为准；</li>
     *   <li>仅当 trusted.proxy=true 时才参考 X-Forwarded-Proto；</li>
     *   <li>无法判断时按明文 HTTP 处理（不追加 Secure），
     *       以免在纯 HTTP 部署下把 Cookie 变得不可用。</li>
     * </ol>
     * 残留限制：在**未开启** trusted.proxy 的 TLS 卸载部署下，服务端无法感知外部协议，
     * Cookie 不会带 Secure，需要运维显式配置 trusted.proxy=true。
     */
    public static boolean isHttps(HttpRequest request) {
        try {
            ChannelHandlerContext ctx = request == null ? null : request.getCtx();
            if (ctx != null && ctx.pipeline() != null) {
                Map<String, ChannelHandler> handlers = ctx.pipeline().toMap();
                for (ChannelHandler handler : handlers.values()) {
                    if (handler instanceof SslHandler) {
                        return true;
                    }
                }
            }
        } catch (Throwable e) {
            log.debug("判断本机 TLS 状态失败: {}", e.getMessage());
        }
        if (TRUSTED_PROXY && request != null) {
            String proto = firstHeaderValue(request.getHeader("x-forwarded-proto"));
            if (proto != null && "https".equalsIgnoreCase(proto)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 【安全修复 J12】期望的请求来源（scheme://host[:port]），用于 CSRF 的同源比较。
     * 只有 trusted.proxy=true 时才采用 X-Forwarded-Proto / X-Forwarded-Host。
     */
    public static String expectedOrigin(HttpRequest request) {
        if (request == null) {
            return null;
        }
        String host = null;
        String proto = null;
        if (TRUSTED_PROXY) {
            host = firstHeaderValue(request.getHeader("x-forwarded-host"));
            proto = firstHeaderValue(request.getHeader("x-forwarded-proto"));
        }
        if (host == null || host.isEmpty()) {
            host = firstHeaderValue(request.getHeader("host"));
        }
        if (host == null || host.isEmpty()) {
            return null;
        }
        if (proto == null || proto.isEmpty()) {
            proto = isHttps(request) ? "https" : "http";
        }
        return normalizeOrigin(proto.toLowerCase(Locale.ROOT) + "://" + host);
    }

    /**
     * 把 {@code scheme://host[:port][/path]} 规范化为 {@code scheme://host:port}，
     * 端口缺省时按协议补 80/443，便于严格比较（含协议与端口）。
     */
    public static String normalizeOrigin(String url) {
        if (url == null) {
            return null;
        }
        String value = url.trim();
        if (value.isEmpty() || "null".equalsIgnoreCase(value)) {
            return null;
        }
        int schemeIdx = value.indexOf("://");
        if (schemeIdx <= 0) {
            return null;
        }
        String scheme = value.substring(0, schemeIdx).toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return null;
        }
        String rest = value.substring(schemeIdx + 3);
        int cut = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                cut = i;
                break;
            }
        }
        rest = rest.substring(0, cut);
        String host;
        String port = null;
        if (rest.startsWith("[")) {
            int end = rest.indexOf(']');
            if (end < 0) {
                return null;
            }
            host = rest.substring(1, end);
            int colon = rest.indexOf(':', end);
            if (colon > 0) {
                port = rest.substring(colon + 1);
            }
        } else {
            int colon = rest.lastIndexOf(':');
            if (colon >= 0 && rest.indexOf(':') == colon) {
                host = rest.substring(0, colon);
                port = rest.substring(colon + 1);
            } else {
                host = rest;
            }
        }
        host = host.trim().toLowerCase(Locale.ROOT);
        if (host.isEmpty() || host.indexOf(' ') >= 0) {
            return null;
        }
        if (port == null || port.isEmpty()) {
            port = "https".equals(scheme) ? "443" : "80";
        } else if (!port.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return scheme + "://" + host + ":" + port;
    }

    private static InetAddress socketAddress(HttpRequest request) {
        if (request == null) {
            return null;
        }
        try {
            ChannelHandlerContext ctx = request.getCtx();
            if (ctx == null || ctx.channel() == null) {
                return null;
            }
            SocketAddress address = ctx.channel().remoteAddress();
            if (address instanceof InetSocketAddress) {
                return ((InetSocketAddress) address).getAddress();
            }
        } catch (Throwable e) {
            log.debug("读取对端 InetAddress 失败: {}", e.getMessage());
        }
        return null;
    }

    /** 逗号分隔的头只取第一段（X-Forwarded-Host / Proto 可能被多级代理追加）。 */
    private static String firstHeaderValue(String value) {
        if (value == null) {
            return null;
        }
        int comma = value.indexOf(',');
        String first = comma >= 0 ? value.substring(0, comma) : value;
        first = first.trim();
        return first.isEmpty() ? null : first;
    }

    /** 规范化 IP 字面量：去掉 [ ]、端口、IPv6 zone（%eth0）以及 IPv4-mapped 前缀。 */
    private static String normalizeIp(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim();
        if (v.isEmpty()) {
            return null;
        }
        if (v.startsWith("[")) {
            int end = v.indexOf(']');
            if (end > 0) {
                v = v.substring(1, end);
            } else {
                v = v.substring(1);
            }
        } else if (v.indexOf(':') == v.lastIndexOf(':')) {
            // 仅一个冒号：host:port 形式
            int colon = v.indexOf(':');
            if (colon > 0) {
                v = v.substring(0, colon);
            }
        }
        int zone = v.indexOf('%');
        if (zone > 0) {
            v = v.substring(0, zone);
        }
        String lower = v.toLowerCase(Locale.ROOT);
        if (lower.startsWith("::ffff:")) {
            lower = lower.substring(7);
        }
        return lower.isEmpty() ? null : lower;
    }

    /** 字面量回环判断（仅用于无法取得 InetAddress 时的兜底）。 */
    private static boolean isLoopbackLiteral(String ip) {
        String v = normalizeIp(ip);
        if (v == null) {
            return false;
        }
        if ("::1".equals(v) || "0:0:0:0:0:0:0:1".equals(v) || "localhost".equals(v)) {
            return true;
        }
        if (v.startsWith("127.")) {
            return true;
        }
        try {
            return InetAddress.getByName(v).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }
}

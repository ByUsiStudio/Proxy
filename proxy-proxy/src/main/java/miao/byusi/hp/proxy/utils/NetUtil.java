package miao.byusi.hp.proxy.utils;

import cn.hserver.core.server.util.PropUtil;
import cn.hserver.plugin.web.interfaces.HttpRequest;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.util.Locale;

/**
 * 网络工具类。
 * <p>
 * 【安全修复 J1】HServer 的 {@code request.getIpAddress()} 会优先返回
 * {@code X-Forwarded-For / Proxy-Client-IP / WL-Proxy-Client-IP / X-Real-IP}
 * 等**请求头**的值。节点端 {@code @CheckApi} 曾据此判断「是否来自本机」，
 * 于是任何远程调用方只要发送 {@code X-Forwarded-For: 127.0.0.1}
 * 即可在 localOnly 部署下绕过全部 @CheckApi 鉴权。
 * 现在统一改为读取 Netty Channel 上**真实的 TCP 对端地址**。
 *
 * @author hxm
 */
public class NetUtil {
    private static final Logger log = LoggerFactory.getLogger(NetUtil.class);

    /** 是否信任前置反向代理转发头（X-Forwarded-For）。默认关闭。 */
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
     * 【安全修复 J1】真实 TCP 对端地址：只读 Netty Channel 的 remoteAddress，不参考任何请求头。
     * 无法确定时返回 null。
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
     * 判定失败一律返回 false（fail-closed）。
     */
    public static boolean isLoopbackPeer(HttpRequest request) {
        if (request == null) {
            return false;
        }
        try {
            ChannelHandlerContext ctx = request.getCtx();
            if (ctx != null && ctx.channel() != null) {
                SocketAddress address = ctx.channel().remoteAddress();
                if (address instanceof InetSocketAddress) {
                    InetAddress ip = ((InetSocketAddress) address).getAddress();
                    if (ip != null) {
                        return ip.isLoopbackAddress();
                    }
                }
            }
        } catch (Throwable e) {
            log.debug("读取对端 InetAddress 失败: {}", e.getMessage());
        }
        return isLoopbackLiteral(socketIp(request));
    }

    /**
     * 用于限流/日志的调用方 IP：默认取真实 TCP 对端地址；
     * 仅当 trusted.proxy=true 时取 X-Forwarded-For 中最后一个不可信跳（绝不取第一个值）。
     */
    public static String clientIp(HttpRequest request) {
        String socket = socketIp(request);
        if (!TRUSTED_PROXY) {
            return socket == null ? "unknown" : socket;
        }
        String forwarded = request == null ? null : request.getHeader("x-forwarded-for");
        if (forwarded != null && !forwarded.trim().isEmpty()) {
            String[] parts = forwarded.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String candidate = normalizeIp(parts[i]);
                if (candidate == null || "unknown".equalsIgnoreCase(candidate)) {
                    continue;
                }
                if (isLoopbackLiteral(candidate)) {
                    continue;
                }
                return candidate;
            }
        }
        return socket == null ? "unknown" : socket;
    }

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
            v = end > 0 ? v.substring(1, end) : v.substring(1);
        } else if (v.indexOf(':') == v.lastIndexOf(':')) {
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

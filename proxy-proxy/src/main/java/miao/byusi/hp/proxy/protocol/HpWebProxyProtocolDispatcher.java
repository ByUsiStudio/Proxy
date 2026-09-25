package miao.byusi.hp.proxy.protocol;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.core.ioc.annotation.Bean;
import cn.hserver.core.ioc.annotation.Order;
import cn.hserver.core.server.util.protocol.HostUtil;
import cn.hserver.core.server.util.protocol.SSLUtils;
import cn.hserver.plugin.web.context.WebConstConfig;
import cn.hserver.plugin.web.protocol.DispatchHttp;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpServerCodec;
import miao.byusi.hp.proxy.config.WebConfig;
import miao.byusi.hp.proxy.domian.bean.ConnectInfo;
import miao.byusi.hp.proxy.handler.HpServerHandler;
import miao.byusi.hp.proxy.handler.proxy.FrontendHandler;
import miao.byusi.hp.proxy.handler.proxy.RouterHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

/**
 * 优先级要调整到自己的管理后台http协议的之上，都是http协议，所以这里需要判断是否是80
 */
@Order(0)
@Bean
public class HpWebProxyProtocolDispatcher extends DispatchHttp {

    @Autowired
    private WebConfig webConfig;

    private static final Logger log = LoggerFactory.getLogger(HpWebProxyProtocolDispatcher.class);

    //判断HP头
    @Override
    public boolean dispatcher(ChannelHandlerContext ctx, ChannelPipeline channelPipeline, byte[] headers) {
        InetSocketAddress socketAddress = (InetSocketAddress) ctx.channel().localAddress();
        if (socketAddress.getPort() == 80 || socketAddress.getPort() == 443) {
            try {
                String host = HostUtil.getHost(ByteBuffer.wrap(headers));
                log.debug("version:{},host:{}", SSLUtils.verifyPacket(ByteBuffer.wrap(headers)), host);
                if (host != null) {
                    // 检查是否是否官方主域名？如果是主域名走内部接口
                    // 【安全修复】原实现使用 host.startsWith(webConfig.getHost()) 做前缀匹配，
                    // 攻击者只要构造 Host: <官方域名>.evil.com 就能被路由到内部管理后台。
                    // 现在改为忽略端口/大小写后的精确匹配（或子域名精确匹配）。
                    if (isInternalHost(host)) {
                        return super.dispatcher(ctx, channelPipeline, headers);
                    }
                    String[] split = host.split("\\.");
                    String domain = split[0];

                    ConnectInfo connectInfo = HpServerHandler.CURRENT_STATUS.stream().filter(v -> domain != null && v != null && v.getDomain() != null && domain.equals(v.getDomain())).findFirst().orElse(null);
                    if (connectInfo == null) {
                        connectInfo = HpServerHandler.CURRENT_STATUS.stream().filter(v -> v != null && v.getCustomDomain() != null && host.equals(v.getCustomDomain())).findFirst().orElse(null);
                    }

                    if (connectInfo == null) {
                        addErrorHandler(channelPipeline, RouterHandler.ERROR.OFF_LINE);
                    } else {
                        addProxyHandler(host, channelPipeline, connectInfo.getPort());
                    }
                    return true;
                }
            } catch (Exception e) {
                log.error(e.getMessage(), e);
                return false;
            }
        }
        return false;
    }

    public void addErrorHandler(ChannelPipeline pipeline, RouterHandler.ERROR error) {
        pipeline.addLast(WebConstConfig.BUSINESS_EVENT, new HttpServerCodec());
        pipeline.addLast(WebConstConfig.BUSINESS_EVENT, new RouterHandler(error));
    }

    /**
     * 【安全修复】Host 精确匹配内部管理域名。
     * 只允许 host 与配置的 host 完全一致（忽略大小写、去掉端口），
     * 不再接受 "官方域名.attacker.com" 这类前缀命中。
     * 如需放行子域名，请把完整域名逐个加入 web.internalHosts 白名单。
     */
    private boolean isInternalHost(String host) {
        String candidate = stripPort(host);
        if (candidate == null) {
            return false;
        }
        String configuredHost = stripPort(webConfig.getHost());
        if (configuredHost != null && configuredHost.equalsIgnoreCase(candidate)) {
            return true;
        }
        String extra = cn.hserver.core.server.util.PropUtil.getInstance().get("web.internalHosts", "");
        if (extra != null) {
            for (String item : extra.split(",")) {
                String allow = stripPort(item);
                if (allow != null && allow.equalsIgnoreCase(candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 去掉端口并统一小写；IPv6 只处理 [::1]:8080 形式。
     */
    private static String stripPort(String host) {
        if (host == null) {
            return null;
        }
        String v = host.trim().toLowerCase();
        if (v.isEmpty()) {
            return null;
        }
        if (v.startsWith("[")) {
            int end = v.indexOf(']');
            if (end < 0) {
                return null;
            }
            return v.substring(1, end);
        }
        int colon = v.lastIndexOf(':');
        if (colon > 0) {
            v = v.substring(0, colon);
        }
        return v.isEmpty() ? null : v;
    }

    public void addProxyHandler(String host, ChannelPipeline pipeline, Integer port) {
        pipeline.addLast(WebConstConfig.BUSINESS_EVENT, new FrontendHandler(host,port));
    }

}

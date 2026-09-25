package miao.byusi.hp.server.exception;

import cn.hserver.HServerApplication;
import cn.hserver.core.ioc.annotation.Bean;
import cn.hserver.plugin.web.context.Webkit;
import cn.hserver.plugin.web.exception.NotFoundException;
import cn.hserver.plugin.web.interfaces.GlobalException;
import miao.byusi.hp.server.utils.NetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Bean

public class Ex implements GlobalException {
    private static final Logger log = LoggerFactory.getLogger(HServerApplication.class);

    @Override
    public void handler(Throwable throwable, int i, String s, Webkit webkit) {
        // 【安全修复 J1】原实现把 webkit.httpRequest.getIpAddress() 的值原样回显到响应体，
        // 而该值优先来自 X-Forwarded-For 等请求头 —— 等于把攻击者可控内容反射回页面
        // （响应拆分/内容伪造/日志污染）。现在只记录服务端解析出的真实 TCP 对端地址，
        // 响应体不再包含任何调用方可控数据。
        String peer = NetUtil.clientIp(webkit == null ? null : webkit.httpRequest);
        if (throwable instanceof NotFoundException) {
            log.info("未知地址，来源IP：{}", peer);
            webkit.httpResponse.sendText("未知地址");
        }else {
            log.error("异常：{},Ip:{},{}", throwable.getMessage(), peer,
                    webkit == null || webkit.httpRequest == null ? null : webkit.httpRequest.getNettyUri());
            webkit.httpResponse.sendText("未知地址");
        }
    }
}

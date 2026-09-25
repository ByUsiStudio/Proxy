package miao.byusi.hp.proxy.except;

import cn.hserver.core.ioc.annotation.Bean;
import cn.hserver.plugin.web.context.Webkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Bean
public class GlobalException implements cn.hserver.plugin.web.interfaces.GlobalException {
    private static final Logger log = LoggerFactory.getLogger(GlobalException.class);

    @Override
    public void handler(Throwable throwable, int i, String s, Webkit webkit) {
        // 【安全修复】原实现把 throwable.getMessage() 直接返回给客户端，
        // 会泄露内部路径/SQL/依赖等实现细节。改为服务端记录详细日志，客户端只看到通用提示。
        log.error("请求处理异常，请求ID={}", s, throwable);
        webkit.httpResponse.sendText("系统繁忙，请稍后再试");
    }
}

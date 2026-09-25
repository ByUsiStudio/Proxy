package miao.byusi.hp.proxy.annotation;

import cn.hserver.core.interfaces.HookAdapter;
import cn.hserver.core.ioc.IocUtil;
import cn.hserver.core.ioc.annotation.Hook;
import cn.hserver.plugin.web.context.HServerContextHolder;
import cn.hserver.plugin.web.interfaces.HttpRequest;
import cn.hserver.plugin.web.interfaces.HttpResponse;
import miao.byusi.hp.proxy.config.CostConfig;
import miao.byusi.hp.proxy.config.WebConfig;

import java.lang.reflect.Method;

@Hook(CheckApi.class)
public class HookCheckApi implements HookAdapter {

    @Override
    public void before(Class aClass, Method method, Object[] objects) throws Throwable {
        // 【安全修复】原实现只要 notReg=true（一个仅表示“是否注册到注册中心”的展示开关）
        // 就 return，等于一键关闭全部接口鉴权。现在改为：
        // 1) 绝不再依据 notReg 跳过鉴权；
        // 2) 只有在显式开启 localOnly 且请求确实来自回环地址时，才允许跳过。
        WebConfig webConfig = IocUtil.getBean(WebConfig.class);
        HttpRequest httpRequest = HServerContextHolder.getWebKit().httpRequest;
        if (Boolean.TRUE.equals(webConfig.getLocalOnly()) && isLoopback(httpRequest.getIpAddress())) {
            return;
        }
        String token = httpRequest.query("token");
        // 【安全修复】VER_TOKEN 为空（未成功注册/未配置注册密钥）时必须拒绝，
        // 否则客户端传 token="" 即可绕过校验。
        if (CostConfig.VER_TOKEN == null || CostConfig.VER_TOKEN.trim().isEmpty()
                || token == null || !token.equals(CostConfig.VER_TOKEN)) {
            throw new Exception("token 校验失败");
        }
    }

    private boolean isLoopback(String ip) {
        if (ip == null) {
            return false;
        }
        String v = ip.trim();
        int slash = v.indexOf('/');
        if (slash >= 0) {
            v = v.substring(slash + 1);
        }
        if (v.startsWith("[")) {
            int end = v.indexOf(']');
            if (end > 0) {
                v = v.substring(1, end);
            }
        } else {
            int colon = v.lastIndexOf(':');
            if (colon > 0) {
                v = v.substring(0, colon);
            }
        }
        return "127.0.0.1".equals(v) || "::1".equals(v) || "0:0:0:0:0:0:0:1".equals(v)
                || "localhost".equalsIgnoreCase(v);
    }

    @Override
    public Object after(Class aClass, Method method, Object o) {
        return o;
    }

    @Override
    public void throwable(Class aClass, Method method, Throwable throwable) {

    }
}

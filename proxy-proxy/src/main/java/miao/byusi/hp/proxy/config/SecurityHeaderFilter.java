package miao.byusi.hp.proxy.config;

import cn.hserver.core.ioc.annotation.Bean;
import cn.hserver.plugin.web.context.Webkit;
import cn.hserver.plugin.web.interfaces.FilterAdapter;

/**
 * 【安全修复 J10】节点端安全响应头。
 * <p>
 * 节点页面（index.ftl / backList.ftl / photoList.ftl ...）按既有契约把集群 token
 * 作为查询参数放进 href 与 img src（否则节点管理接口无法从浏览器调用）。
 * token 出现在 URL 中时，最危险的泄露途径是浏览器把完整 URL 放进 {@code Referer}
 * 发给第三方站点（例如页面里的外部链接）。
 * <p>
 * 模板本身无法设置响应头，因此在全局过滤器里统一追加
 * {@code Referrer-Policy: no-referrer}，让浏览器完全不再发送 Referer。
 * 同时补一个 {@code X-Content-Type-Options: nosniff}，避免图片/文本被嗅探成脚本。
 */
@Bean
public class SecurityHeaderFilter implements FilterAdapter {

    @Override
    public void doFilter(Webkit webkit) throws Exception {
        if (webkit == null || webkit.httpResponse == null) {
            return;
        }
        webkit.httpResponse.setHeader("Referrer-Policy", "no-referrer");
        webkit.httpResponse.setHeader("X-Content-Type-Options", "nosniff");
    }
}

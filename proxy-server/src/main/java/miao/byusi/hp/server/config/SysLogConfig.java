package miao.byusi.hp.server.config;

import cn.hserver.core.ioc.annotation.Bean;
import cn.hserver.core.ioc.annotation.Value;
import cn.hserver.core.server.util.PropUtil;

/**
 * 后台「系统日志」页面所需的配置项。
 * <p>
 * 与 {@link WebConfig} 保持同一套写法（{@code @Bean} + {@code @Value} 字段注入，
 * HServer 的 IOC 在启动时把 app.properties 的值填进来），这样运维改配置不需要动代码。
 * <p>
 * 为什么要再兜一层 {@link PropUtil}：{@code @Value} 注入依赖启动期配置齐全，
 * 一旦某个键缺失或为空，这里仍能退回一个安全的默认值，避免整个页面 500。
 */
@Bean
public class SysLogConfig {

    /** 日志目录：可以是相对路径（相对进程工作目录），也可以是绝对路径 */
    private static final String DEFAULT_DIR = "log";

    /** 单次查看返回的最大行数上限（防御性上限，防止前端传一个极大的 lines 把页面拖死） */
    private static final int DEFAULT_MAX_LINES = 2000;
    private static final int MIN_MAX_LINES = 50;
    private static final int MAX_MAX_LINES = 20000;

    @Value("admin.syslog.dir")
    private String dir;

    @Value("admin.syslog.max.lines")
    private String maxLines;

    /**
     * 配置的日志目录（可能是相对路径，由调用方决定相对谁解析）。
     */
    public String getDir() {
        String value = dir;
        if (value == null || value.trim().isEmpty()) {
            value = PropUtil.getInstance().get("admin.syslog.dir", DEFAULT_DIR);
        }
        if (value == null || value.trim().isEmpty()) {
            value = DEFAULT_DIR;
        }
        return value.trim();
    }

    /**
     * 单次查看允许返回的最大行数，做区间收敛，避免配置写错导致内存/渲染问题。
     */
    public int getMaxLines() {
        String value = maxLines;
        if (value == null || value.trim().isEmpty()) {
            value = PropUtil.getInstance().get("admin.syslog.max.lines", String.valueOf(DEFAULT_MAX_LINES));
        }
        int parsed = DEFAULT_MAX_LINES;
        try {
            parsed = Integer.parseInt(value.trim());
        } catch (Exception e) {
            // 配置不是数字：保持默认值而不是让页面挂掉
            parsed = DEFAULT_MAX_LINES;
        }
        if (parsed < MIN_MAX_LINES) {
            return MIN_MAX_LINES;
        }
        if (parsed > MAX_MAX_LINES) {
            return MAX_MAX_LINES;
        }
        return parsed;
    }
}

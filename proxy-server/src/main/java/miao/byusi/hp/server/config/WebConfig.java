package miao.byusi.hp.server.config;

import cn.hserver.core.ioc.annotation.Bean;
import cn.hserver.core.ioc.annotation.Value;

@Bean
public class WebConfig {

    @Value("host")
    private String host;

    @Value("password")
    private String password;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    @Override
    public String toString() {
        // 【安全修复】toString 可能被框架日志打印，绝不能带出后台密码明文
        return "WebConfig{" +
                "host='" + host + '\'' +
                ", password='" + (password == null || password.isEmpty() ? "" : "******") + '\'' +
                '}';
    }
}

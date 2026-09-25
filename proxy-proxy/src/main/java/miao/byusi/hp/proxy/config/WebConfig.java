package miao.byusi.hp.proxy.config;

import cn.hserver.core.ioc.annotation.Bean;
import cn.hserver.core.ioc.annotation.Value;

@Bean
public class WebConfig {

    @Value("adminAddress")
    private String adminAddress;

    @Value("host")
    private String host;

    @Value("userHost")
    private String userHost;

    @Value("notReg")
    private Boolean notReg;

    /**
     * 【安全修复】显式开启后才允许“仅本机访问”跳过 @CheckApi 校验（且必须来自回环地址）。
     * 与 notReg 完全解耦，避免一个展示开关顺带关掉全部鉴权。
     */
    @Value("localOnly")
    private Boolean localOnly;

    /**
     * 【安全修复】注册中心共享密钥，必须与 proxy-server 的 proxy.regSecret 一致。
     */
    @Value("regSecret")
    private String regSecret;

    @Value("level")
    private Integer level;

    @Value("name")
    private String name;

    public String getRegSecret() {
        return regSecret;
    }

    public void setRegSecret(String regSecret) {
        this.regSecret = regSecret;
    }

    public Boolean getLocalOnly() {
        return localOnly;
    }

    public void setLocalOnly(Boolean localOnly) {
        this.localOnly = localOnly;
    }

    public String getName() {
        return name;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAdminAddress() {
        return adminAddress;
    }

    public void setAdminAddress(String adminAddress) {
        this.adminAddress = adminAddress;
    }

    public String getHost() {
        return host;
    }

    public Boolean getNotReg() {
        return notReg;
    }

    public String getUserHost() {
        return userHost;
    }

    public void setUserHost(String userHost) {
        this.userHost = userHost;
    }

    public void setNotReg(Boolean notReg) {
        this.notReg = notReg;
    }

    public void setHost(String host) {
        this.host = host;
    }

    @Override
    public String toString() {
        return "WebConfig{" +
                "adminAddress='" + adminAddress + '\'' +
                ", host='" + host + '\'' +
                '}';
    }
}

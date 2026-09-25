package miao.byusi.hp.server.config;

import cn.hserver.core.server.util.PropUtil;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class ConstConfig {
    //注册模式：-1免费关闭注册 0 免费注册 >0 每天24小时时间内注册(小时数)
    public static int TIME = 0;
    public static int PROXY_SIZE = Integer.parseInt(PropUtil.getInstance().get("proxy.size", "3"));
    public static String TIPS = "禁止穿透违法程序，免费不易 请大家谅解，ByUsi改编发行";
    public static String REG_TOKEN = UUID.randomUUID().toString();
    /**
     * 【安全修复】/proxy/reg 的共享注册密钥，来自配置 proxy.regSecret。
     * 为空表示运维尚未配置，此时一律拒绝节点注册，避免匿名调用者拿到 REG_TOKEN
     * （该 token 是所有代理节点 @CheckApi 接口的通行证）。
     */
    public static final String REG_SECRET = PropUtil.getInstance().get("proxy.regSecret", "");
    /**
     * 通用注册码
     */
    public static String REG_CODE = UUID.randomUUID().toString();
    /**
     * 邮箱验证码（key=账号）
     */
    public static final Cache<String, String> EMAIL_CODE = CacheBuilder.newBuilder().expireAfterAccess(30, TimeUnit.MINUTES).build();
    /**
     * 调用者IP 5分钟一次（key=账号，兼容旧逻辑）
     */
    public static final Cache<String, String> EMAIL_IP = CacheBuilder.newBuilder().expireAfterAccess(5, TimeUnit.MINUTES).build();
    /**
     * 【安全修复】验证码发送冷却（key=调用者真实IP），与按账号的 EMAIL_IP 双重限流，
     * 防止 /user/email 被用来对任意邮箱轰炸，也防止同一IP换账号刷邮件。
     */
    public static final Cache<String, String> EMAIL_IP_LIMIT = CacheBuilder.newBuilder().expireAfterAccess(5, TimeUnit.MINUTES).build();
    /**
     * 【安全修复】验证码错误次数（key=账号），超过 EMAIL_CODE_MAX_FAIL 立刻作废验证码，防止4/6位验证码被暴力枚举。
     */
    public static final Cache<String, Integer> EMAIL_CODE_FAIL = CacheBuilder.newBuilder().expireAfterWrite(30, TimeUnit.MINUTES).build();
    /**
     * 【安全修复】验证码最大错误次数
     */
    public static final int EMAIL_CODE_MAX_FAIL = 5;

}

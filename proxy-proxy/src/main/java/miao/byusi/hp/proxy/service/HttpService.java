package miao.byusi.hp.proxy.service;

import cn.hserver.core.ioc.IocUtil;
import cn.hserver.core.server.util.ExceptionUtil;
import cn.hserver.core.server.util.PropUtil;
import cn.hserver.plugin.web.context.WebConstConfig;
import com.fasterxml.jackson.databind.JsonNode;
import miao.byusi.hp.proxy.config.CostConfig;
import miao.byusi.hp.proxy.config.WebConfig;
import miao.byusi.hp.proxy.domian.bean.Statistics;
import miao.byusi.hp.proxy.domian.vo.UserVo;
import miao.byusi.hp.proxy.handler.HpServerHandler;
import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class HttpService {
    private static final Logger log = LoggerFactory.getLogger(HttpService.class);

    private static final OkHttpClient okHttpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .pingInterval(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build();


    public static UserVo login(String username, String password, String domain,String address) {
        // 【安全修复】原日志输出明文密码，现只保留账号/域名/地址
        log.info("登录信息：账号={}，域名={}，地址={}",username,domain,address);
        WebConfig bean = IocUtil.getBean(WebConfig.class);
        String adminAddress = bean.getAdminAddress();
        try {
            RequestBody body = new FormBody.Builder()
                    .add("username", username)
                    .add("password", password)
                    .add("domain", domain)
                    .add("address", address)
                    .build();
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(adminAddress + "/user/domainLogin")
                    .post(body)
                    .build();
            String string = okHttpClient.newCall(request).execute().body().string();
            // 【安全修复】不再打印登录响应体（其中含 password 字段）
            log.info("login 请求完成，账号={}", username);
            JsonNode jsonNode = WebConstConfig.JSON.readTree(string);
            int code = jsonNode.get("code").asInt();
            if (code == 200) {
                return WebConstConfig.JSON.readValue(WebConstConfig.JSON.writeValueAsString(jsonNode.get("data")), UserVo.class);
            }
        } catch (Exception e) {
            log.info("login 失败：{}", ExceptionUtil.getMessage(e));
        }
        return null;
    }

    public static void noticePush(String username,String title,String message) {
        WebConfig bean = IocUtil.getBean(WebConfig.class);
        String adminAddress = bean.getAdminAddress();
        try {
            Map<String,String> data=new HashMap<>();
            data.put("title",title);
            data.put("message",message);
            data.put("username",username);
            // 【安全修复】/notice/push 现在要求内部集群 token，否则任何人都能推送通知（并触发封号）
            data.put("token", CostConfig.VER_TOKEN);
            RequestBody requestBody = RequestBody.create(
                    MediaType.parse("application/json; charset=utf-8"),
                    WebConstConfig.JSON.writeValueAsBytes(data)
            );
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(adminAddress + "/notice/push")
                    .post(requestBody)
                    .build();
            String string = okHttpClient.newCall(request).execute().body().string();
            log.info("noticePush：{}", string);
        } catch (Exception e) {
            log.info("noticePush：{}", ExceptionUtil.getMessage(e));
        }
    }

    public static void updateStatistics(Statistics statistics) {
        if (statistics==null){
            return;
        }
        WebConfig bean = IocUtil.getBean(WebConfig.class);
        String adminAddress = bean.getAdminAddress();
        try {
            RequestBody requestBody = RequestBody.create(
                    MediaType.parse("application/json; charset=utf-8"),
                    WebConstConfig.JSON.writeValueAsBytes(statistics)
            );
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(adminAddress + "/statistics/add")
                    .post(requestBody)
                    .build();
            String string = okHttpClient.newCall(request).execute().body().string();
            log.info("statistics：{}", string);
        } catch (Exception e) {
            log.info("statistics：{}", ExceptionUtil.getMessage(e));
        }
    }


    public static void reg() {
        WebConfig bean = IocUtil.getBean(WebConfig.class);
        if (bean.getNotReg() != null && bean.getNotReg()) {
            return;
        }
        String adminAddress = bean.getAdminAddress();
        try {
            RequestBody body = new FormBody.Builder()
                    .add("name", bean.getName())
                    .add("ip", bean.getHost())
                    .add("level", String.valueOf(bean.getLevel()))
                    .add("port", PropUtil.getInstance().get("port"))
                    .add("num", String.valueOf(HpServerHandler.CURRENT_STATUS.size()))
                    // 【安全修复】/proxy/reg 需要共享注册密钥，否则任何人都能拿到 REG_TOKEN
                    .add("secret", bean.getRegSecret() == null ? "" : bean.getRegSecret())
                    .build();

            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(adminAddress + "/proxy/reg")
                    .post(body)
                    .build();
            String string = okHttpClient.newCall(request).execute().body().string();
            JsonNode jsonNode = WebConstConfig.JSON.readTree(string);
            if (jsonNode.get("code").asInt() == 200) {
                String token = jsonNode.get("msg") == null ? null : jsonNode.get("msg").asText();
                if (token != null && !token.trim().isEmpty() && !"null".equals(token)) {
                    CostConfig.VER_TOKEN = token;
                    // 【安全修复】不再把 token 明文写进日志
                    log.info("reg：注册中心校验通过，已更新集群 token");
                } else {
                    log.warn("reg：注册中心返回的 token 为空，节点管理接口将按 fail-closed 拒绝访问");
                }
            } else {
                log.warn("reg：注册失败，code={}，请检查 proxy.regSecret 配置是否与服务端一致",
                        jsonNode.get("code").asInt());
            }
        } catch (Exception e) {
            log.info("reg：{}", ExceptionUtil.getMessage(e));
        }
    }
}

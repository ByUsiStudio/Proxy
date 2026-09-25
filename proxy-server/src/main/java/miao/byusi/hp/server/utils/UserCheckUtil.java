package miao.byusi.hp.server.utils;

public class UserCheckUtil {
    public static boolean checkUsername(String str) {
        if (str == null) {
            return false;
        }
        String v = str.trim();
        if (!v.endsWith("@qq.com")) {
            return false;
        }
        // 【安全修复】原实现只判断 @qq.com 后缀，形如 `"><script>@qq.com` 的账号可以注册成功，
        // 并被后台 user.ftl 未转义渲染，形成存储型 XSS。这里追加严格的邮箱白名单校验。
        return SafeInputUtil.cleanUsername(v) != null;
    }

    public static boolean checkDomain(String str) {
        String regex = "^[a-z0-9]+$";
        return str != null && str.matches(regex);
    }
}

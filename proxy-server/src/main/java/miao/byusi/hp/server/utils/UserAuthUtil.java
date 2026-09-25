package miao.byusi.hp.server.utils;

import miao.byusi.hp.server.domian.entity.UserEntity;
import miao.byusi.hp.server.service.UserService;

/**
 * 【安全修复】/server/port*、/server/domain*、/statistics/getMyInfo、/config/* 等接口
 * 只凭调用方传入的 userId/username 就执行读写，属于典型的未授权越权（IDOR）。
 * <p>
 * 这里要求调用方同时提供目标账号的 username + password（控制台本来就持有这两个值），
 * 校验通过才允许操作，失败时由各控制器返回原有 {code,msg} 错误结构。
 */
public final class UserAuthUtil {

    private UserAuthUtil() {
    }

    /**
     * 校验凭据是否属于目标用户。
     *
     * @param userService 用户服务
     * @param userId      调用方声明的用户ID（可为空，为空时用 username 反查）
     * @param username    调用方声明的账号
     * @param password    调用方声明的密码
     * @return true 表示凭据与该用户一致，允许操作
     */
    public static boolean checkOwnership(UserService userService, String userId, String username, String password) {
        if (userService == null || SafeInputUtil.isBlank(username) || SafeInputUtil.isBlank(password)) {
            return false;
        }
        String name = username.trim();
        UserEntity user = SafeInputUtil.isBlank(userId) ? userService.getUser(name) : userService.getUserById(userId);
        if (user == null) {
            return false;
        }
        // userId 与 username 必须指向同一个用户，防止拿自己的凭据去操作别人的ID
        if (!SafeInputUtil.isBlank(userId) && !userId.trim().equals(user.getId())) {
            return false;
        }
        if (!name.equals(user.getUsername())) {
            return false;
        }
        // 被封禁账号、以及未初始化密码（init.sql 不再内置默认口令）的账号一律拒绝
        if (user.getType() == -1 || SafeInputUtil.isBlank(user.getPassword())) {
            return false;
        }
        return SafeInputUtil.safeEquals(user.getPassword(), password.trim());
    }
}

package miao.byusi.hp.server.queue;

import cn.hserver.core.ioc.annotation.queue.QueueHandler;
import cn.hserver.core.ioc.annotation.queue.QueueListener;
import miao.byusi.hp.server.config.ConstConfig;
import miao.byusi.hp.server.utils.MailUtils;

import java.security.SecureRandom;

@QueueListener(queueName = "EMAIL")
public class MailQueue {
    /**
     * 【安全修复】验证码必须使用密码学安全随机数，原来的 java.util.Random 可被预测。
     */
    private static final SecureRandom RANDOM = new SecureRandom();

    @QueueHandler
    public void sendVerificationCode(String username) {
        String code = generateCode();
        String htmlContent = MailUtils.buildVerificationEmail(code);

        if (MailUtils.sendHtmlMail(username, "ByUsi Proxy 安全验证码", htmlContent)) {
            ConstConfig.EMAIL_CODE.put(username, code);
        } else {
            ConstConfig.EMAIL_IP.invalidate(username);
        }
        // 注意：日志中绝不输出验证码本身
    }

    /**
     * 【安全修复】验证码长度从 4 位提升到 6 位（10^4 组合太容易被在线爆破）。
     */
    private String generateCode() {
        return String.format("%06d", RANDOM.nextInt(1000000));
    }
}

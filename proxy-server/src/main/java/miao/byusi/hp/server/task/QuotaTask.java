package miao.byusi.hp.server.task;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.core.ioc.annotation.Bean;
import cn.hserver.core.ioc.annotation.Task;
import miao.byusi.hp.server.service.QuotaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用量配额的定时刷新：重算每个启用配额的「月度流量是否超限」。
 * <p>
 * 调度机制完全照搬 {@code FlowTask}（HServer 的 {@code @Task(name, time)}，
 * time 为毫秒周期）。每 30 分钟醒一次：月度流量是慢变量，没必要更频繁，
 * 也不能更稀疏，否则超限账号会长时间继续拿到隧道配置。
 * <p>
 * 任务体内部整体捕获异常：定时任务抛异常会打断调度器，导致后续所有周期都不再执行。
 */
@Bean
public class QuotaTask {

    private static final Logger log = LoggerFactory.getLogger(QuotaTask.class);

    @Autowired
    private QuotaService quotaService;

    /**
     * 30 * 60 * 1000 = 1800000 毫秒。
     */
    @Task(name = "刷新用量配额超限状态", time = "1800000")
    public void refreshQuota() {
        try {
            int evaluated = quotaService.refreshOverLimit();
            log.info("用量配额定时刷新完成：本次评估 {} 个启用配额", evaluated);
        } catch (Exception e) {
            log.error("用量配额定时刷新异常：{}", e.getMessage());
        }
    }
}

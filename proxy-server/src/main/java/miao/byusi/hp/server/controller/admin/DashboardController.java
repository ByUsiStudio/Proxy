package miao.byusi.hp.server.controller.admin;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.plugin.web.annotation.Controller;
import cn.hserver.plugin.web.annotation.GET;
import cn.hserver.plugin.web.interfaces.HttpResponse;
import miao.byusi.hp.server.service.AdminDashboardService;

/**
 * 后台首页仪表盘。
 * <p>
 * 两个入口共用同一份数据装配（{@link AdminDashboardService}）：
 * <ul>
 *   <li>{@code GET /admin}：旧入口，现在直接渲染仪表盘（此前是 302 到 /admin/proxy）；</li>
 *   <li>{@code GET /admin/dashboard}：新入口，导航栏「仪表盘」指向它。</li>
 * </ul>
 * {@code /admin/dashboard/data} 提供图表与「最近事件」的异步数据；
 * 页面上的失败徽标由前端调用 {@code /admin/audit/stats} 获取（与本接口解耦，
 * 这样徽标的「近 24 小时失败数」语义不会被图表的天数参数影响）。
 */
@Controller
public class DashboardController {

    /** 默认统计天数 */
    private static final int DEFAULT_DAYS = 14;
    /** 图表天数上限，避免前端传入极端值导致聚合过慢 */
    private static final int MAX_DAYS = 90;

    @Autowired
    private AdminDashboardService adminDashboardService;

    @GET("/admin/dashboard")
    public void dashboard(HttpResponse response) {
        response.sendTemplate("/admin/dashboard.ftl", adminDashboardService.pageModel());
    }

    /**
     * 图表与事件流数据。
     *
     * @param days 按天聚合的天数范围
     */
    @GET("/admin/dashboard/data")
    public void data(Integer days, HttpResponse response) {
        int range = (days == null || days < 1) ? DEFAULT_DAYS : Math.min(days, MAX_DAYS);
        response.sendJson(adminDashboardService.chartData(range));
    }
}

package miao.byusi.hp.server.controller.index;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.plugin.web.annotation.Controller;
import cn.hserver.plugin.web.annotation.GET;
import cn.hserver.plugin.web.interfaces.HttpRequest;
import cn.hserver.plugin.web.interfaces.HttpResponse;
import miao.byusi.hp.server.dao.StatisticsDao;
import miao.byusi.hp.server.domian.entity.StatisticsEntity;
import miao.byusi.hp.server.utils.DateUtil;
import miao.byusi.hp.server.utils.SafeInputUtil;
import org.beetl.sql.core.page.PageResult;
import org.beetl.sql.core.query.LambdaQuery;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * 门户「穿透日志」（该账号自己的流量明细）。
 * <p>
 * 本轮增强：新增按端口过滤（可选参数，1~65535）。
 * <p>
 * 【身份与越权】账号始终取自 {@code AuthFilter} 注入的请求头（会话身份），
 * 端口过滤只是「在自己数据里的进一步筛选」，无法用它读到别人的记录。
 * <p>
 * 【为什么不直接用 {@code StatisticsService.list(page,size,username,port)}】
 * 该重载内部对账号用的是 {@code andLike(username, "%"+username+"%")}：
 * 会话账号为 {@code a@qq.com} 时会匹配到 {@code xa@qq.com} 的记录，
 * 门户页面就会把别人的流量明细展示给当前用户（越权读取）。
 * 因此这里改用 DAO 的 {@code andEq} 精确匹配 + 端口可选条件，全部参数绑定。
 */
@Controller
public class LogController {

    /** 单页条数（固定值，不接受客户端传入，避免超大分页拖垮数据库） */
    private static final int PAGE_SIZE = 10;

    @Autowired
    private StatisticsDao statisticsDao;

    @GET("/index/log")
    public void log(Integer page, Integer port, HttpRequest request, HttpResponse response) {
        if (page == null || page < 1) {
            page = 1;
        }
        // 端口过滤：只接受合法范围内的整数，越界/空值一律视为「不过滤」
        Integer portFilter = (port == null || port < 1 || port > 65535) ? null : port;
        String username = request.getHeader("username");
        if (SafeInputUtil.isBlank(username)) {
            // 正常流程下 AuthFilter 已拦截未登录请求，这里是纵深防御
            username = "";
        } else {
            username = username.trim();
        }

        Map<String, Object> data = new HashMap<>(7);
        data.put("page", page);
        data.put("pageSize", PAGE_SIZE);
        data.put("port", portFilter == null ? "" : String.valueOf(portFilter));
        data.put("username", username);

        if (username.isEmpty()) {
            data.put("totalRow", 0L);
            data.put("list", new ArrayList<StatisticsEntity>());
            data.put("totalPage", 0L);
            response.sendTemplate("/index/log.ftl", data);
            return;
        }

        LambdaQuery<StatisticsEntity> query = statisticsDao.createLambdaQuery()
                .andEq(StatisticsEntity::getUsername, username);
        if (portFilter != null) {
            query.andEq(StatisticsEntity::getPort, portFilter);
        }
        PageResult<StatisticsEntity> result = query.orderBy("create_time desc").page(page, PAGE_SIZE);
        if (result != null && result.getList() != null) {
            for (StatisticsEntity entity : result.getList()) {
                entity.setCreateTime(DateUtil.stampToDate(entity.getCreateTime()));
            }
        }
        data.put("totalRow", result == null ? 0L : result.getTotalRow());
        data.put("list", result == null || result.getList() == null ? new ArrayList<StatisticsEntity>() : result.getList());
        data.put("totalPage", result == null ? 0L : result.getTotalPage());
        response.sendTemplate("/index/log.ftl", data);
    }
}

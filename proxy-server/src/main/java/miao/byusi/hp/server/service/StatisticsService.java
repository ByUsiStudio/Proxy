package miao.byusi.hp.server.service;

import miao.byusi.hp.server.domian.bean.Statistics;
import miao.byusi.hp.server.domian.entity.StatisticsEntity;
import org.beetl.sql.core.page.PageResult;

import java.util.List;
import java.util.Map;

/**
 * @author hxm
 */
public interface StatisticsService {

    /**
     * 添加
     *
     * @param statistics
     */
    void add(Statistics statistics);


    /**
     * 列表
     *
     * @param page
     * @param pageSize
     * @return
     */
    PageResult<StatisticsEntity> list(Integer page, Integer pageSize);

    /**
     * 按用户名查询
     *
     * @param page
     * @param pageSize
     * @param username
     * @return
     */
    PageResult<StatisticsEntity> list(Integer page, Integer pageSize, String username);

    /**
     * 后台带条件的列表查询（用户名模糊 / 端口精确）。
     *
     * @param page     页码，从 1 开始
     * @param pageSize 每页条数
     * @param username 用户名关键字，可为空
     * @param port     端口，可为空
     */
    PageResult<StatisticsEntity> list(Integer page, Integer pageSize, String username, Integer port);

    /**
     * 按条件取全量（受 limit 限制），用于导出。
     *
     * @param username 用户名关键字，可为空
     * @param port     端口，可为空
     * @param limit    最大返回条数
     */
    List<StatisticsEntity> listForExport(String username, Integer port, int limit);

    /**
     * 汇总统计，用于后台图表。
     * <p>
     * 返回结构：
     * <pre>
     * {
     *   "byPort": [{"port":8080,"receive":123,"send":45,"connectNum":3,"packNum":9}, ...],
     *   "byDay":  [{"day":"2024-05-01","receive":123,"send":45}, ...],
     *   "total":  {"receive":..., "send":..., "connectNum":..., "packNum":..., "rowCount":...}
     * }
     * </pre>
     * 为避免不同数据库的 SQL 方言差异与注入面，聚合在服务端内存中完成，
     * 取样上限由 {@code SAMPLE_LIMIT} 控制。
     *
     * @param username 用户名关键字，可为空
     * @param port     端口，可为空
     */
    Map<String, Object> summary(String username, Integer port);

    /**
     * 删除
     *
     * @param id
     */
    void remove(String id);

    /**
     * 批量删除。
     *
     * @param ids 记录 id 列表（已由调用方做白名单校验）
     * @return 实际删除条数
     */
    int removeBatch(List<String> ids);

    /**
     * 删除超过1个月的日志
     */
    void removeExpData();

}

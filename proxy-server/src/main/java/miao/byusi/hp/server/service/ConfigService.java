package miao.byusi.hp.server.service;

import miao.byusi.hp.server.domian.entity.ConfigEntity;
import org.beetl.sql.core.page.PageResult;

import java.util.List;

public interface ConfigService {

    boolean save(ConfigEntity config);

    List<ConfigEntity> list(String userId);
    List<ConfigEntity> listDevice(String deviceId);

    PageResult<ConfigEntity> list(Integer page, Integer pageSize);

    /**
     * 后台带条件的列表查询（用户名 / 设备ID 关键字）。
     *
     * @param page     页码，从 1 开始
     * @param pageSize 每页条数
     * @param username 用户名关键字，可为空
     * @param deviceId 设备ID关键字，可为空
     */
    PageResult<ConfigEntity> list(Integer page, Integer pageSize, String username, String deviceId);

    /**
     * 按条件取全量（受 limit 限制），用于导出与二维码分享。
     *
     * @param username 用户名关键字，可为空
     * @param deviceId 设备ID关键字，可为空
     * @param limit    最大返回条数
     */
    List<ConfigEntity> listForExport(String username, String deviceId, int limit);

    /**
     * 按主键查询，用于导入时的去重判断。
     */
    ConfigEntity getById(String id);

    /**
     * 批量导入。
     *
     * @param configs 待导入配置（字段已由调用方做白名单校验）
     * @return [成功条数, 跳过条数]
     */
    int[] importBatch(List<ConfigEntity> configs);

    boolean remove(String id);

    /**
     * 批量删除。
     *
     * @param ids 记录 id 列表（已由调用方做白名单校验）
     * @return 实际删除条数
     */
    int removeBatch(List<String> ids);
}

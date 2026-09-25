package miao.byusi.hp.server.dao;

import cn.hserver.plugin.beetlsql.annotation.BeetlSQL;
import miao.byusi.hp.server.domian.entity.QuotaEntity;
import org.beetl.sql.mapper.BaseMapper;

/**
 * 用量配额（sys_quota）数据访问。
 * 所有查询都通过 BeetlSQL 的 LambdaQuery / 主键方法完成，不出现 SQL 字符串拼接。
 */
@BeetlSQL
public interface QuotaDao extends BaseMapper<QuotaEntity> {
}

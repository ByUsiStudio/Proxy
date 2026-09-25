package miao.byusi.hp.server.dao;

import cn.hserver.plugin.beetlsql.annotation.BeetlSQL;
import miao.byusi.hp.server.domian.entity.AuditLogEntity;
import org.beetl.sql.mapper.BaseMapper;

/**
 * 审计日志 DAO。
 */
@BeetlSQL
public interface AuditLogDao extends BaseMapper<AuditLogEntity> {
}

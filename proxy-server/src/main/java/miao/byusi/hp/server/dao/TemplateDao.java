package miao.byusi.hp.server.dao;

import cn.hserver.plugin.beetlsql.annotation.BeetlSQL;
import miao.byusi.hp.server.domian.entity.TemplateEntity;
import org.beetl.sql.mapper.BaseMapper;

/**
 * 隧道模板（sys_template）数据访问。
 * 所有查询都通过 BeetlSQL 的 LambdaQuery / 主键方法完成，不出现 SQL 字符串拼接。
 */
@BeetlSQL
public interface TemplateDao extends BaseMapper<TemplateEntity> {
}

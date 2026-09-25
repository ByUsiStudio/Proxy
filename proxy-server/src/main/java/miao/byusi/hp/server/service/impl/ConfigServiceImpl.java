package miao.byusi.hp.server.service.impl;

import cn.hserver.core.ioc.annotation.Autowired;
import cn.hserver.core.ioc.annotation.Bean;
import miao.byusi.hp.server.config.ConstConfig;
import miao.byusi.hp.server.dao.ConfigDao;
import miao.byusi.hp.server.domian.entity.ConfigEntity;
import miao.byusi.hp.server.service.ConfigService;
import miao.byusi.hp.server.utils.DateUtil;
import org.beetl.sql.core.page.PageResult;
import org.beetl.sql.core.query.LambdaQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

@Bean
public class ConfigServiceImpl implements ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigServiceImpl.class);

    @Autowired
    private ConfigDao configDao;

    @Override
    public boolean save(ConfigEntity config) {
        //一个账号只能配置三个账号
        List<ConfigEntity> select = configDao.createLambdaQuery().andEq(ConfigEntity::getUserId, config.getUserId()).select();
        if (select!=null&&select.size()>= ConstConfig.PROXY_SIZE){
            return false;
        }
        config.setId(UUID.randomUUID().toString());
        config.setCreateTime(String.valueOf(System.currentTimeMillis()));
        configDao.insert(config);
        return true;
    }

    @Override
    public List<ConfigEntity> list(String userId) {
        return configDao.createLambdaQuery().andEq(ConfigEntity::getUserId,userId).select();
    }

    @Override
    public List<ConfigEntity> listDevice(String deviceId) {
        return configDao.createLambdaQuery().andEq(ConfigEntity::getDeviceId,deviceId).select();
    }
    @Override
    public PageResult<ConfigEntity> list(Integer page, Integer pageSize) {
        PageResult<ConfigEntity> create_time_desc = configDao.createLambdaQuery().orderBy("create_time desc").page(page, pageSize);
        formatTime(create_time_desc.getList());
        return create_time_desc;
    }

    @Override
    public PageResult<ConfigEntity> list(Integer page, Integer pageSize, String username, String deviceId) {
        LambdaQuery<ConfigEntity> query = configDao.createLambdaQuery();
        if (username != null && !username.trim().isEmpty()) {
            query.andLike(ConfigEntity::getUsername, "%" + username.trim() + "%");
        }
        if (deviceId != null && !deviceId.trim().isEmpty()) {
            query.andLike(ConfigEntity::getDeviceId, "%" + deviceId.trim() + "%");
        }
        PageResult<ConfigEntity> result = query.orderBy("create_time desc").page(page, pageSize);
        formatTime(result.getList());
        return result;
    }

    @Override
    public List<ConfigEntity> listForExport(String username, String deviceId, int limit) {
        LambdaQuery<ConfigEntity> query = configDao.createLambdaQuery();
        if (username != null && !username.trim().isEmpty()) {
            query.andLike(ConfigEntity::getUsername, "%" + username.trim() + "%");
        }
        if (deviceId != null && !deviceId.trim().isEmpty()) {
            query.andLike(ConfigEntity::getDeviceId, "%" + deviceId.trim() + "%");
        }
        int size = limit <= 0 ? 1000 : limit;
        List<ConfigEntity> list = query.orderBy("create_time desc").limit(0, size).select();
        formatTime(list);
        return list;
    }

    @Override
    public ConfigEntity getById(String id) {
        if (id == null || id.trim().isEmpty()) {
            return null;
        }
        try {
            return configDao.single(id.trim());
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public int[] importBatch(List<ConfigEntity> configs) {
        int created = 0;
        int skipped = 0;
        if (configs == null || configs.isEmpty()) {
            return new int[]{0, 0};
        }
        for (ConfigEntity config : configs) {
            if (config == null || config.getUserId() == null || config.getUserId().trim().isEmpty()) {
                skipped++;
                continue;
            }
            // 同一账号的自动穿透条数受 ConstConfig.PROXY_SIZE 限制，超出即跳过（不覆盖既有配置）
            List<ConfigEntity> existing = configDao.createLambdaQuery()
                    .andEq(ConfigEntity::getUserId, config.getUserId())
                    .select();
            if (existing != null && existing.size() >= ConstConfig.PROXY_SIZE) {
                skipped++;
                continue;
            }
            config.setId(UUID.randomUUID().toString());
            config.setCreateTime(String.valueOf(System.currentTimeMillis()));
            configDao.insert(config);
            created++;
        }
        log.info("自动穿透配置导入完成：新增 {} 条，跳过 {} 条", created, skipped);
        return new int[]{created, skipped};
    }

    @Override
    public boolean remove(String id) {
        return configDao.deleteById(id)>0;
    }

    @Override
    public int removeBatch(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (String id : ids) {
            removed += configDao.deleteById(id);
        }
        return removed;
    }

    private void formatTime(List<ConfigEntity> list) {
        if (list == null) {
            return;
        }
        for (ConfigEntity entity : list) {
            entity.setCreateTime(DateUtil.stampToDate(entity.getCreateTime()));
        }
    }
}

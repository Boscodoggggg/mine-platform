package com.bosco.mine.server.modules.mine.service;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * 在应用开始接收请求前校验密钥并完成旧版人员敏感密文升级。
 */
@Component
public class LegacySensitiveDataStartupGate implements SmartInitializingSingleton {

    private final LegacySensitiveDataMigrationService migrationService;

    /**
     * 创建人员敏感数据启动门禁。
     *
     * @param migrationService 旧版敏感数据升级服务
     */
    public LegacySensitiveDataStartupGate(LegacySensitiveDataMigrationService migrationService) {
        this.migrationService = migrationService;
    }

    /** 校验加密配置并确保数据库中不再存在一版密文。 */
    @Override
    public void afterSingletonsInstantiated() {
        migrationService.reencryptAll();
    }
}

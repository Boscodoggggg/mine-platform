package com.bosco.mine.simulator.profile;

import java.util.List;
import java.util.Optional;

/**
 * 模拟设备配置存储接口。
 */
public interface ProfileStore {

    /**
     * 查询全部配置。
     *
     * @return 配置列表
     */
    List<SimulatorProfile> findAll();

    /**
     * 按标识查询配置。
     *
     * @param id 配置标识
     * @return 配置，不存在时为空
     */
    Optional<SimulatorProfile> findById(String id);

    /**
     * 新增或更新配置。
     *
     * @param profile 配置内容
     * @return 已保存的配置
     */
    SimulatorProfile save(SimulatorProfile profile);

    /**
     * 删除配置。
     *
     * @param id 配置标识
     * @return 是否删除成功
     */
    boolean delete(String id);
}

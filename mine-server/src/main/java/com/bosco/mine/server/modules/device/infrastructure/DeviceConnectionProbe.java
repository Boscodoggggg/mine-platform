package com.bosco.mine.server.modules.device.infrastructure;

import com.bosco.mine.server.modules.device.dto.DeviceConnectionTestResponse;
import com.bosco.mine.server.modules.device.model.DeviceConnectionProfile;

import java.util.List;

/**
 * 执行设备接入端点的网络分层诊断。
 */
public interface DeviceConnectionProbe {

    /**
     * 检查 DNS、TCP 和可选 TLS，不使用或返回设备明文凭据。
     *
     * @param profile 接入配置
     * @return 分层诊断结果
     */
    List<DeviceConnectionTestResponse.Stage> probe(DeviceConnectionProfile profile);
}

package com.bosco.mine.simulator.api;

import com.bosco.mine.simulator.profile.ProfileStore;
import com.bosco.mine.simulator.profile.SimulatorProfile;
import com.bosco.mine.simulator.runtime.RuntimeSnapshot;
import com.bosco.mine.simulator.runtime.SimulatorRuntimeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.List;

/**
 * 提供页面所需的配置管理与模拟任务控制接口。
 */
@RestController
@RequestMapping("/api")
public class SimulatorController {

    private final ProfileStore profileStore;
    private final SimulatorRuntimeService runtimeService;
    private final String profileFile;

    /**
     * 创建模拟器管理接口。
     *
     * @param profileStore 配置仓库
     * @param runtimeService 运行服务
     * @param profileFile 配置文件路径
     */
    public SimulatorController(ProfileStore profileStore, SimulatorRuntimeService runtimeService,
            @Value("${mine.simulator.profile-file}") String profileFile) {
        this.profileStore = profileStore;
        this.runtimeService = runtimeService;
        this.profileFile = Path.of(profileFile).toAbsolutePath().normalize().toString();
    }

    /**
     * 查询全部设备配置。
     *
     * @return 配置列表
     */
    @GetMapping("/profiles")
    public List<SimulatorProfile> profiles() {
        return profileStore.findAll();
    }

    /**
     * 保存一台设备配置；更新配置时会停止其旧任务。
     *
     * @param profile 配置内容
     * @return 保存结果
     */
    @PostMapping("/profiles")
    public SimulatorProfile save(@RequestBody SimulatorProfile profile) {
        runtimeService.remove(profile.id());
        return profileStore.save(profile);
    }

    /**
     * 删除设备配置及其运行任务。
     *
     * @param id 配置标识
     */
    @DeleteMapping("/profiles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        runtimeService.remove(id);
        if (!profileStore.delete(id)) {
            throw new IllegalArgumentException("设备配置不存在：" + id);
        }
    }

    /**
     * 查询全部设备运行状态。
     *
     * @return 运行状态列表
     */
    @GetMapping("/runtime")
    public List<RuntimeSnapshot> runtime() {
        return runtimeService.findAll();
    }

    /**
     * 执行设备运行操作。
     *
     * @param id 配置标识
     * @param action 操作名称
     * @param request 启动时使用的临时凭据
     * @return 操作后的运行状态
     */
    @PostMapping("/profiles/{id}/actions/{action}")
    public RuntimeSnapshot action(@PathVariable String id, @PathVariable String action,
            @RequestBody(required = false) StartRequest request) {
        return switch (action) {
            case "start" -> runtimeService.start(id,
                    request == null || request.password() == null ? new char[0] : request.password().toCharArray());
            case "stop" -> runtimeService.stop(id);
            case "send" -> runtimeService.sendOnce(id);
            case "disconnect" -> runtimeService.disconnect(id);
            case "reconnect" -> runtimeService.reconnect(id);
            default -> throw new IllegalArgumentException("不支持的操作：" + action);
        };
    }

    /**
     * 查询本地运行信息。
     *
     * @return 系统信息
     */
    @GetMapping("/system")
    public SystemInfo system() {
        return new SystemInfo(profileFile, "0.1.0");
    }

    /**
     * 启动设备时临时提交的认证信息。
     *
     * @param password 密码
     */
    public record StartRequest(String password) {
    }

    /**
     * 独立应用本地信息。
     *
     * @param profileFile YAML 配置路径
     * @param version 应用版本
     */
    public record SystemInfo(String profileFile, String version) {
    }
}

package com.bosco.mine.server.modules.mine.scene;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.mine.scene.dto.SceneRuntimeResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 提供默认场景最后正确运行快照读取接口。
 */
@RestController
@RequestMapping("/api/v1/mine/sites/{siteId}")
public class SceneRuntimeController {

    private final SceneRuntimeService service;

    /**
     * 创建场景运行快照控制器。
     *
     * @param service 场景运行快照服务
     */
    public SceneRuntimeController(SceneRuntimeService service) {
        this.service = service;
    }

    /**
     * 查询受权矿区默认场景的完整运行快照或固定降级原因。
     *
     * @param siteId 矿区标识
     * @return 完整运行快照或结构化降级结果
     */
    @GetMapping("/scene-runtime")
    @SaCheckPermission("mine:scene:view")
    public SceneRuntimeResponse load(@PathVariable UUID siteId) {
        return service.load(siteId);
    }
}

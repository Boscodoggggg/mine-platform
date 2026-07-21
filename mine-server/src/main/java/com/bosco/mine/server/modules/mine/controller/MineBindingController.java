package com.bosco.mine.server.modules.mine.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.mine.dto.BindingPreviewResponse;
import com.bosco.mine.server.modules.mine.dto.BindingSwitchRequest;
import com.bosco.mine.server.modules.mine.service.MineBindingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 提供矿区资产换绑影响预览和带幂等键的原子执行接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/mine/sites/{siteId}/bindings")
public class MineBindingController {

    private final MineBindingService service;

    /**
     * 创建矿区资产换绑控制器。
     *
     * @param service 换绑业务服务
     */
    public MineBindingController(MineBindingService service) {
        this.service = service;
    }

    /** 预览换绑的旧关系、目标、资质和影响对象。 */
    @PostMapping("/preview")
    @SaCheckPermission("mine:binding:switch")
    public BindingPreviewResponse preview(
            @PathVariable UUID siteId,
            @Valid @RequestBody BindingSwitchRequest request
    ) {
        return service.preview(siteId, request);
    }

    /** 使用管理员幂等键原子执行换绑。 */
    @PostMapping("/switch")
    @SaCheckPermission("mine:binding:switch")
    public BindingPreviewResponse switchBinding(
            @PathVariable UUID siteId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody BindingSwitchRequest request
    ) {
        return service.switchBinding(siteId, request, idempotencyKey);
    }
}

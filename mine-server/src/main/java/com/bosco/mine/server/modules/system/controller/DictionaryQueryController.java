package com.bosco.mine.server.modules.system.controller;

import com.bosco.mine.server.modules.system.repository.DictionaryManagementMapper;
import com.bosco.mine.server.modules.system.service.DictionaryManagementService;
import jakarta.validation.constraints.Pattern;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 提供业务页面按稳定编码读取启用字典项的接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/dictionaries")
public class DictionaryQueryController {

    private final DictionaryManagementService service;

    /**
     * 创建字典查询控制器。
     */
    public DictionaryQueryController(DictionaryManagementService service) {
        this.service = service;
    }

    /**
     * 按编码查询启用字典项。
     */
    @GetMapping("/{code}/items")
    public List<DictionaryManagementMapper.DictItemRecord> findEnabledItems(
            @PathVariable @Pattern(regexp = "[a-z][a-z0-9_]{2,63}") String code
    ) {
        return service.findEnabledItems(code);
    }
}

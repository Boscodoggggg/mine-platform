package com.bosco.mine.server.modules.mine.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.bootstrap.web.PageResponse;
import com.bosco.mine.server.modules.mine.dto.DriverQualificationRequest;
import com.bosco.mine.server.modules.mine.dto.DriverQualificationResponse;
import com.bosco.mine.server.modules.mine.dto.MineArchiveRequest;
import com.bosco.mine.server.modules.mine.dto.PersonCreateRequest;
import com.bosco.mine.server.modules.mine.dto.PersonResponse;
import com.bosco.mine.server.modules.mine.dto.PersonSensitiveResponse;
import com.bosco.mine.server.modules.mine.dto.PersonUpdateRequest;
import com.bosco.mine.server.modules.mine.service.MinePersonService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * 提供矿区人员、司机资质和独立授权的敏感字段接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/mine/sites/{siteId}")
public class MinePersonController {

    private final MinePersonService personService;

    /**
     * 创建矿区人员控制器。
     *
     * @param personService 人员服务
     */
    public MinePersonController(MinePersonService personService) {
        this.personService = personService;
    }

    /** 分页查询脱敏人员档案。 */
    @GetMapping("/people")
    @SaCheckPermission("mine:person:view")
    public PageResponse<PersonResponse> findPeople(
            @PathVariable UUID siteId,
            @RequestParam(required = false) @Size(max = 128) String keyword,
            @RequestParam(required = false)
            @Pattern(regexp = "ACTIVE|INACTIVE|ARCHIVED") String status,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return personService.findPeople(siteId, keyword, status, page, size);
    }

    /** 创建人员档案。 */
    @PostMapping("/people")
    @SaCheckPermission("mine:person:edit")
    public ResponseEntity<PersonResponse> createPerson(
            @PathVariable UUID siteId,
            @Valid @RequestBody PersonCreateRequest request
    ) {
        PersonResponse created = personService.createPerson(siteId, request);
        URI location = UriComponentsBuilder.fromPath(
                        "/api/v1/mine/sites/{siteId}/people/{personId}")
                .buildAndExpand(siteId, created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** 查询脱敏人员详情。 */
    @GetMapping("/people/{personId}")
    @SaCheckPermission("mine:person:view")
    public PersonResponse findPerson(
            @PathVariable UUID siteId,
            @PathVariable UUID personId
    ) {
        return personService.findPerson(siteId, personId);
    }

    /** 修改人员档案。 */
    @PatchMapping("/people/{personId}")
    @SaCheckPermission("mine:person:edit")
    public PersonResponse updatePerson(
            @PathVariable UUID siteId,
            @PathVariable UUID personId,
            @Valid @RequestBody PersonUpdateRequest request
    ) {
        return personService.updatePerson(siteId, personId, request);
    }

    /** 归档人员档案。 */
    @PostMapping("/people/{personId}/archive")
    @SaCheckPermission("mine:person:edit")
    public ResponseEntity<Void> archivePerson(
            @PathVariable UUID siteId,
            @PathVariable UUID personId,
            @Valid @RequestBody MineArchiveRequest request
    ) {
        personService.archivePerson(siteId, personId, request.version());
        return ResponseEntity.noContent().build();
    }

    /** 通过独立权限显式读取人员敏感字段。 */
    @GetMapping("/people/{personId}/sensitive")
    @SaCheckPermission("mine:person:sensitive:view")
    public PersonSensitiveResponse readSensitive(
            @PathVariable UUID siteId,
            @PathVariable UUID personId
    ) {
        return personService.readSensitive(siteId, personId);
    }

    /** 查询人员司机资质。 */
    @GetMapping("/people/{personId}/qualifications")
    @SaCheckPermission("mine:person:view")
    public List<DriverQualificationResponse> findQualifications(
            @PathVariable UUID siteId,
            @PathVariable UUID personId
    ) {
        return personService.findQualifications(siteId, personId);
    }

    /** 新增人员司机资质。 */
    @PostMapping("/people/{personId}/qualifications")
    @SaCheckPermission("mine:person:qualification:edit")
    public ResponseEntity<DriverQualificationResponse> addQualification(
            @PathVariable UUID siteId,
            @PathVariable UUID personId,
            @Valid @RequestBody DriverQualificationRequest request
    ) {
        DriverQualificationResponse created = personService.addQualification(siteId, personId, request);
        URI location = UriComponentsBuilder.fromPath(
                        "/api/v1/mine/sites/{siteId}/people/{personId}/qualifications/{qualificationId}")
                .buildAndExpand(siteId, personId, created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** 吊销人员司机资质。 */
    @PostMapping("/people/{personId}/qualifications/{qualificationId}/revoke")
    @SaCheckPermission("mine:person:qualification:edit")
    public ResponseEntity<Void> revokeQualification(
            @PathVariable UUID siteId,
            @PathVariable UUID personId,
            @PathVariable UUID qualificationId,
            @Valid @RequestBody MineArchiveRequest request
    ) {
        personService.revokeQualification(
                siteId, personId, qualificationId, request.version());
        return ResponseEntity.noContent().build();
    }
}

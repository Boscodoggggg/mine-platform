package com.bosco.mine.server.modules.mine.operation;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bosco.mine.server.modules.mine.operation.dto.OperationCandidateResolutionRequest;
import com.bosco.mine.server.modules.mine.operation.dto.OperationCandidateResponse;
import com.bosco.mine.server.modules.mine.operation.dto.OperationCorrectionRequest;
import com.bosco.mine.server.modules.mine.operation.dto.OperationRecordResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 提供运营候选处置、人工补录和追加式修正接口。
 */
@Validated
@RestController
@RequestMapping("/api/v1/mine/sites/{siteId}")
public class OperationController {

    private final OperationQueryService queryService;
    private final OperationCorrectionService correctionService;

    /**
     * 创建运营事实人工处置控制器。
     *
     * @param queryService 运营查询服务
     * @param correctionService 运营人工处置服务
     */
    public OperationController(
            OperationQueryService queryService,
            OperationCorrectionService correctionService
    ) {
        this.queryService = queryService;
        this.correctionService = correctionService;
    }

    /** 分页查询待处置或已处置运营候选。 */
    @GetMapping("/operation-candidates")
    @SaCheckPermission("mine:operation:view")
    public List<OperationCandidateResponse> findCandidates(
            @PathVariable UUID siteId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) long offset,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        return queryService.findCandidates(siteId, status, offset, limit);
    }

    /** 人工关联、忽略或将候选转为人工补录记录。 */
    @PostMapping("/operation-candidates/{candidateId}/resolve")
    @SaCheckPermission("mine:operation:correct")
    public OperationCandidateResponse resolveCandidate(
            @PathVariable UUID siteId,
            @PathVariable UUID candidateId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128)
            String idempotencyKey,
            @Valid @RequestBody OperationCandidateResolutionRequest request
    ) {
        return correctionService.resolveCandidate(
                siteId, candidateId, idempotencyKey, request);
    }

    /** 人工补录一条不可变运输执行事实。 */
    @PostMapping("/operation-records")
    @ResponseStatus(HttpStatus.CREATED)
    @SaCheckPermission("mine:operation:correct")
    public OperationRecordResponse addRecord(
            @PathVariable UUID siteId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128)
            String idempotencyKey,
            @Valid @RequestBody OperationCorrectionRequest request
    ) {
        return correctionService.addRecord(siteId, idempotencyKey, request);
    }

    /** 查询不可变执行事实、当前投影和全部修正历史。 */
    @GetMapping("/operation-records/{recordId}")
    @SaCheckPermission("mine:operation:view")
    public OperationRecordResponse findRecord(
            @PathVariable UUID siteId,
            @PathVariable UUID recordId
    ) {
        return queryService.findRecord(siteId, recordId);
    }

    /** 追加修正并原子更新当前有效投影。 */
    @PostMapping("/operation-records/{recordId}/corrections")
    @SaCheckPermission("mine:operation:correct")
    public OperationRecordResponse correctRecord(
            @PathVariable UUID siteId,
            @PathVariable UUID recordId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128)
            String idempotencyKey,
            @Valid @RequestBody OperationCorrectionRequest request
    ) {
        return correctionService.correctRecord(
                siteId, recordId, idempotencyKey, request);
    }
}

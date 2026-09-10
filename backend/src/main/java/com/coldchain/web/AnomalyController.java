package com.coldchain.web;

import com.coldchain.common.ApiResponse;
import com.coldchain.domain.dto.AnomalyView;
import com.coldchain.domain.dto.ReviewRequest;
import com.coldchain.domain.entity.Anomaly;
import com.coldchain.domain.entity.AnomalyReview;
import com.coldchain.domain.entity.EvidenceAttachment;
import com.coldchain.service.AnomalyQueryService;
import com.coldchain.service.AnomalyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/anomalies")
@RequiredArgsConstructor
public class AnomalyController {

    private final AnomalyQueryService anomalyQueryService;
    private final AnomalyService anomalyService;

    @GetMapping
    public ApiResponse<List<AnomalyView>> list(
            @RequestParam(required = false) Long boxId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(anomalyQueryService.list(boxId, type, status));
    }

    /** 异常复核：CONFIRM / REJECT / REOPEN */
    @PostMapping("/{id}/review")
    public ApiResponse<Anomaly> review(@PathVariable Long id,
                                       @Valid @RequestBody ReviewRequest request) {
        return ApiResponse.ok(anomalyService.review(id, request, MDC.get("operator")));
    }

    /** 上传证据附件，同一异常内自动生成新版本，旧版本保留 */
    @PostMapping("/{id}/evidence")
    public ApiResponse<EvidenceAttachment> uploadEvidence(
            @PathVariable Long id,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "note", required = false) String note) throws IOException {
        return ApiResponse.ok(anomalyService.addEvidence(id, file, note, MDC.get("operator")));
    }

    @GetMapping("/{id}/evidence")
    public ApiResponse<List<EvidenceAttachment>> listEvidence(@PathVariable Long id) {
        return ApiResponse.ok(anomalyService.listEvidence(id));
    }

    @GetMapping("/{id}/reviews")
    public ApiResponse<List<AnomalyReview>> listReviews(@PathVariable Long id) {
        return ApiResponse.ok(anomalyService.listReviews(id));
    }

    @GetMapping("/types")
    public ApiResponse<List<Map<String, String>>> types() {
        return ApiResponse.ok(List.of(
                Map.of("value", "TEMP_EXCURSION", "label", "连续超温"),
                Map.of("value", "OFFLINE", "label", "传感器离线"),
                Map.of("value", "INTERVAL", "label", "采样间隔异常"),
                Map.of("value", "HASH_BROKEN", "label", "哈希链断裂")));
    }
}

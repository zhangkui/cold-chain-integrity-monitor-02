package com.coldchain.web;

import com.coldchain.common.ApiResponse;
import com.coldchain.domain.dto.ingest.CalibrationIngestRequest;
import com.coldchain.domain.dto.ingest.EventIngestRequest;
import com.coldchain.domain.dto.ingest.IngestResult;
import com.coldchain.domain.dto.ingest.NodeIngestRequest;
import com.coldchain.domain.dto.ingest.SampleIngestRequest;
import com.coldchain.service.IngestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据摄入接口。重复提交（相同 设备+时间+序号）由后端幂等键唯一约束兜底，
 * 客户端可在断网重试时携带同一 Idempotency-Key 请求头。
 */
@RestController
@RequestMapping("/api/ingest")
@RequiredArgsConstructor
public class IngestController {

    private final IngestService ingestService;

    @PostMapping("/samples")
    public ApiResponse<IngestResult> samples(@Valid @RequestBody SampleIngestRequest request) {
        return ApiResponse.ok(ingestService.ingestSamples(request));
    }

    @PostMapping("/events")
    public ApiResponse<IngestResult> events(@Valid @RequestBody EventIngestRequest request) {
        return ApiResponse.ok(ingestService.ingestEvents(request));
    }

    @PostMapping("/nodes")
    public ApiResponse<IngestResult> nodes(@Valid @RequestBody NodeIngestRequest request) {
        return ApiResponse.ok(ingestService.ingestNodes(request));
    }

    @PostMapping("/calibrations")
    public ApiResponse<IngestResult> calibrations(@Valid @RequestBody CalibrationIngestRequest request) {
        return ApiResponse.ok(ingestService.ingestCalibrations(request));
    }
}

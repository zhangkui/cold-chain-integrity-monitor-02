package com.coldchain.web;

import com.coldchain.common.ApiResponse;
import com.coldchain.domain.dto.ImportResultView;
import com.coldchain.service.ImportService;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/imports")
@RequiredArgsConstructor
public class ImportController {

    private final ImportService importService;

    /** CSV 批量导入温度采样（multipart 字段名 file） */
    @PostMapping("/samples")
    public ApiResponse<ImportResultView> upload(@RequestParam("file") MultipartFile file) throws IOException {
        return ApiResponse.ok(importService.importSamples(file, MDC.get("operator")));
    }

    @GetMapping("/{batchNo}")
    public ApiResponse<ImportResultView> result(@PathVariable String batchNo) {
        return ApiResponse.ok(importService.getResult(batchNo));
    }

    @GetMapping
    public ApiResponse<List<ImportResultView>> recent(@RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.ok(importService.recentBatches(limit));
    }
}

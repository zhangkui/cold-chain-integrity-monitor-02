package com.coldchain.web;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.coldchain.common.ApiResponse;
import com.coldchain.domain.entity.AuditLog;
import com.coldchain.domain.entity.Device;
import com.coldchain.mapper.AuditLogMapper;
import com.coldchain.mapper.DeviceMapper;
import com.coldchain.service.AuditService;
import com.coldchain.service.DetectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final DetectionService detectionService;
    private final DeviceMapper deviceMapper;
    private final AuditLogMapper auditLogMapper;
    private final AuditService auditService;

    /** 校验指定设备（缺省全部设备）的哈希链，发现断裂会登记 HASH_BROKEN 异常 */
    @PostMapping("/verify-chain")
    public ApiResponse<?> verifyChain(@RequestParam(required = false) Long deviceId) {
        if (deviceId != null) {
            return ApiResponse.ok(detectionService.verifyChain(deviceId));
        }
        List<Device> devices = deviceMapper.selectList(null);
        List<DetectionService.VerifyResult> results = devices.stream()
                .map(d -> detectionService.verifyChain(d.getId())).toList();
        boolean allIntact = results.stream().allMatch(DetectionService.VerifyResult::intact);
        return ApiResponse.ok(Map.of("allIntact", allIntact, "devices", results));
    }

    /** 全量重算异常：只清理 OPEN 异常，CONFIRMED/REJECTED 保留 */
    @PostMapping("/recompute")
    public ApiResponse<Map<String, Integer>> recompute(@RequestParam(required = false) Long deviceId) {
        int total = 0;
        if (deviceId != null) {
            total = detectionService.recomputeDevice(deviceId);
        } else {
            for (Device d : deviceMapper.selectList(null)) {
                total += detectionService.recomputeDevice(d.getId());
            }
        }
        auditService.log("ANOMALY_BATCH", "*", "RECOMPUTE", Map.of("newAnomalies", total));
        return ApiResponse.ok(Map.of("newAnomalies", total));
    }

    /** 审计日志查询（仅读接口；写入只在业务流程中发生，且数据库禁止修改/删除） */
    @GetMapping("/audit-logs")
    public ApiResponse<List<AuditLog>> auditLogs(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "100") int limit) {
        List<AuditLog> logs = auditLogMapper.selectList(Wrappers.<AuditLog>lambdaQuery()
                .eq(entityType != null && !entityType.isBlank(), AuditLog::getEntityType, entityType)
                .like(action != null && !action.isBlank(), AuditLog::getAction, action)
                .orderByDesc(AuditLog::getId)
                .last("limit " + Math.max(1, Math.min(limit, 500))));
        return ApiResponse.ok(logs);
    }
}

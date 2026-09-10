package com.coldchain.service;

import com.coldchain.domain.entity.AuditLog;
import com.coldchain.mapper.AuditLogMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * 审计日志：仅追加。audit_log 表上的 BEFORE UPDATE/DELETE 触发器在数据库层
 * 再做一道兜底，任何修改/删除都会被 MySQL 拒绝。
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    public void log(String entityType, Object entityId, String action, Object detail) {
        AuditLog row = new AuditLog();
        row.setEntityType(entityType);
        row.setEntityId(String.valueOf(entityId));
        row.setAction(action);
        String operator = MDC.get("operator");
        row.setOperator(operator == null ? "system" : operator);
        String requestId = MDC.get("requestId");
        row.setRequestId(requestId);
        if (detail != null) {
            try {
                row.setDetail(objectMapper.writeValueAsString(detail));
            } catch (JsonProcessingException e) {
                row.setDetail(String.valueOf(detail));
            }
        }
        auditLogMapper.insert(row);
    }

    public void log(String entityType, Object entityId, String action) {
        log(entityType, entityId, action, null);
    }
}

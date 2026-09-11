package com.coldchain.domain.dto.assessment;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 评估风险项。severity: WARN / CRITICAL / INFO；
 * blocker=true 的风险使结论不能为 PASS。
 * 证据引用：refType=ANOMALY 时带 anomalyId；refType=TIMELINE 时跳转运时间线；
 * refType=SAMPLE 时跳温度曲线。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssessmentRisk {
    /** 风险代码，如 HASH_BROKEN / TEMP_EXCURSION / DOOR_OPEN_UNMATCHED */
    private String code;
    /** INFO / WARN / CRITICAL */
    private String severity;
    private String message;
    private boolean blocker;
    /** ANOMALY / TIMELINE / SAMPLE / RULE */
    private String refType;
    private Long anomalyId;
    private String anomalyType;
    private String refTimeUtc;

    public AssessmentRisk(String code, String severity, String message, boolean blocker,
                          String refType, Long anomalyId, String anomalyType) {
        this(code, severity, message, blocker, refType, anomalyId, anomalyType, null);
    }
}

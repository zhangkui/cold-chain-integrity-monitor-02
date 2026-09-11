package com.coldchain.domain.dto.assessment;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 评估组成指标。status: OK / WARN / BAD / INFO / NA
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AssessmentMetric {
    /** 指标键，如 validColdChain、chain、coverage */
    private String key;
    /** 展示名称 */
    private String label;
    /** 展示值（已格式化） */
    private String value;
    /** 单位（可空） */
    private String unit;
    /** OK / WARN / BAD / INFO / NA */
    private String status;
    /** 补充说明 */
    private String detail;
}

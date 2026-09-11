package com.coldchain.domain.dto.assessment;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 箱体综合评估视图（持久化记录的完整呈现 + 箱体冗余信息）。
 */
@Data
public class AssessmentView {
    private Long id;
    private Long boxId;
    private String boxCode;
    private String batchNo;
    private Integer version;
    /** PASS / FAIL / NEEDS_REVIEW / UNASSESSABLE */
    private String conclusion;
    private String conclusionLabel;
    /** INTACT / BROKEN / UNVERIFIED / NO_DATA */
    private String chainStatus;
    private Integer chainChecked;
    private String summary;
    private List<AssessmentRisk> primaryRisks;
    private List<AssessmentMetric> metrics;
    private RuleSnapshot ruleSnapshot;
    private DataBoundary dataBoundary;
    private String ruleVersion;
    private String generatedBy;
    private LocalDateTime generatedAt;
}

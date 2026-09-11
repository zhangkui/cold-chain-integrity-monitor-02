package com.coldchain.domain.dto.assessment;

import lombok.Data;

import java.time.LocalDateTime;

/** 箱体列表/详情上的最新评估徽标（状态、生成时间、主要风险） */
@Data
public class AssessmentBadge {
    private Long id;
    private Integer version;
    /** PASS / FAIL / NEEDS_REVIEW / UNASSESSABLE */
    private String conclusion;
    private String conclusionLabel;
    /** INTACT / BROKEN / UNVERIFIED / NO_DATA */
    private String chainStatus;
    private String summary;
    private LocalDateTime generatedAt;
    /** 最主要的一条风险 */
    private AssessmentRisk topRisk;
}

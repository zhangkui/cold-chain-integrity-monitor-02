package com.coldchain.domain.dto.assessment;

import lombok.Data;

import java.time.LocalDateTime;

/** 评估历史版本列表行 */
@Data
public class AssessmentSummary {
    private Long id;
    private Long boxId;
    private Integer version;
    private String conclusion;
    private String conclusionLabel;
    private String chainStatus;
    private String summary;
    private String generatedBy;
    private LocalDateTime generatedAt;
}

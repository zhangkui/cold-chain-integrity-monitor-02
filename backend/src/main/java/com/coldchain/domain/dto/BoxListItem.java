package com.coldchain.domain.dto;

import com.coldchain.domain.dto.assessment.AssessmentBadge;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 箱体列表行（含统计与有效冷链时长） */
@Data
public class BoxListItem {
    private Long id;
    private String boxCode;
    private String batchNo;
    private String specimenType;
    private String status;
    private BigDecimal tempMin;
    private BigDecimal tempMax;
    private String deviceCode;
    private String deviceName;
    private String timezone;

    private LocalDateTime firstSampleUtc;
    private LocalDateTime lastSampleUtc;
    private Integer sampleCount;

    /** 采样覆盖总时长 s；有效冷链时长 = 覆盖时长 - 超温/离线扣减 */
    private Long coveredSeconds;
    private Long excursionSeconds;
    private Long offlineSeconds;
    private Long validColdChainSeconds;

    private Integer openAnomalyCount;
    private Integer totalAnomalyCount;

    /** 最新一次综合评估徽标（无评估记录时为 null） */
    private AssessmentBadge latestAssessment;
}

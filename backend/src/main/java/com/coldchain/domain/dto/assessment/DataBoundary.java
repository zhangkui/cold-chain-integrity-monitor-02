package com.coldchain.domain.dto.assessment;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 评估使用的数据时间边界（UTC），随评估结果持久化：
 * 任何一次历史结论都能还原“当时看到的数据窗口”。
 */
@Data
public class DataBoundary {
    /** 评估执行时刻 */
    private LocalDateTime evaluatedAtUtc;
    /** 采样时间窗口（最小/最大 sample_time_utc） */
    private LocalDateTime samplesFromUtc;
    private LocalDateTime samplesToUtc;
    /** 数据入库时间窗口（最小/最大 received_at） */
    private LocalDateTime receivedFromUtc;
    private LocalDateTime receivedToUtc;
    /** 转运单时间窗口（最早发车 ~ 最晚签收；在途单以评估时刻封口） */
    private LocalDateTime shipmentStartUtc;
    private LocalDateTime shipmentEndUtc;
    /** 落在转运窗口内的采样覆盖比例 [0,1]；无转运单为 null */
    private BigDecimal shipmentCoverageRatio;
    private Integer sampleCount;
}

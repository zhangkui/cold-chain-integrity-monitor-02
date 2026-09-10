package com.coldchain.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("anomaly")
public class Anomaly {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long boxId;
    private Long deviceId;
    private Long shipmentId;
    /** TEMP_EXCURSION / OFFLINE / INTERVAL / HASH_BROKEN */
    private String type;
    /** INFO / WARN / CRITICAL */
    private String severity;
    private LocalDateTime startTimeUtc;
    private LocalDateTime endTimeUtc;
    private Integer durationSeconds;
    private BigDecimal peakTemp;
    private Integer sampleCount;
    private String description;
    /** OPEN / CONFIRMED / REJECTED */
    private String status;
    private String dedupeKey;
    private LocalDateTime firstSeenAt;
    private LocalDateTime confirmedAt;
    private String confirmedBy;
    private String reviewComment;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

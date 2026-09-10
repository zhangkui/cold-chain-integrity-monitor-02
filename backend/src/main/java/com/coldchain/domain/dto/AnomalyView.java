package com.coldchain.domain.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AnomalyView {
    private Long id;
    private Long boxId;
    private String boxCode;
    private String batchNo;
    private Long deviceId;
    private String deviceCode;
    private String type;
    private String severity;
    private LocalDateTime startTimeUtc;
    private LocalDateTime endTimeUtc;
    private Integer durationSeconds;
    private String peakTemp;
    private Integer sampleCount;
    private String description;
    private String status;
    private String confirmedBy;
    private String reviewComment;
    private LocalDateTime confirmedAt;
    private LocalDateTime firstSeenAt;
}

package com.coldchain.domain.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 采样点（支持 UTC 与设备本地时间双时区输出） */
@Data
public class SamplePoint {
    private Long id;
    private Long seq;
    private LocalDateTime timeUtc;
    private LocalDateTime timeLocal;
    private BigDecimal temperature;
    private String source;
    private String contentHash;
    private String prevHash;
    private String chainHash;
    /** 当前点相对前一点的间隔秒，用于间隔异常展示 */
    private Long gapSeconds;
    private Boolean inRange;
}

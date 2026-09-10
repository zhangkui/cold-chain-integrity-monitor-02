package com.coldchain.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("temperature_sample")
public class TemperatureSample {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long deviceId;
    private Long boxId;
    private Long seq;
    private LocalDateTime sampleTimeUtc;
    private LocalDateTime sampleTimeLocal;
    private BigDecimal temperatureC;
    /** REALTIME / BACKFILL */
    private String source;
    private String contentHash;
    private String prevHash;
    private String chainHash;
    private String idemKey;
    private LocalDateTime receivedAt;
    private LocalDateTime createdAt;
}

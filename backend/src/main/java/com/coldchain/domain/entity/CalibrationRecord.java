package com.coldchain.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("calibration_record")
public class CalibrationRecord {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long deviceId;
    private LocalDateTime calibrateTimeUtc;
    private String agency;
    private BigDecimal offsetBefore;
    private BigDecimal offsetAfter;
    /** PASS / FAIL */
    private String result;
    private String certNo;
    private String operator;
    private LocalDateTime createdAt;
}

package com.coldchain.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("cold_box")
public class ColdBox {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String boxCode;
    private Long deviceId;
    private String batchNo;
    private String specimenType;
    private String status;
    private BigDecimal tempMin;
    private BigDecimal tempMax;
    /** 连续超温判定秒数 */
    private Integer excursionSeconds;
    /** 离线判定秒数 */
    private Integer offlineSeconds;
    private Integer intervalMinSeconds;
    private Integer intervalMaxSeconds;
    private LocalDateTime createdAt;
}

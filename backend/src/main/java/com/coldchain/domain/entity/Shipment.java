package com.coldchain.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("shipment")
public class Shipment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String shipmentNo;
    private Long boxId;
    private String origin;
    private String destination;
    private String status;
    private LocalDateTime startTimeUtc;
    private LocalDateTime endTimeUtc;
    private LocalDateTime createdAt;
}

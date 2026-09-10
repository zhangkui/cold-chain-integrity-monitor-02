package com.coldchain.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("transport_node")
public class TransportNode {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long shipmentId;
    private Integer seq;
    private String nodeCode;
    private String nodeName;
    private String nodeType;
    private LocalDateTime plannedTimeUtc;
    private LocalDateTime actualTimeUtc;
    private String operator;
    private String location;
    private LocalDateTime createdAt;
}

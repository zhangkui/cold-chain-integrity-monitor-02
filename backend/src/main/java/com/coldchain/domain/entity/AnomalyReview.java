package com.coldchain.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("anomaly_review")
public class AnomalyReview {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long anomalyId;
    /** CONFIRM / REJECT / REOPEN */
    private String action;
    private String comment;
    private String reviewer;
    private LocalDateTime createdAt;
}

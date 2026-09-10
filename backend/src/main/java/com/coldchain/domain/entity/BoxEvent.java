package com.coldchain.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("box_event")
public class BoxEvent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long boxId;
    private Long deviceId;
    /** OPEN / CLOSE */
    private String eventType;
    private LocalDateTime eventTimeUtc;
    private LocalDateTime eventTimeLocal;
    private String note;
    private String idemKey;
    private LocalDateTime createdAt;
}

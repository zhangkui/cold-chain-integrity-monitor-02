package com.coldchain.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("evidence_attachment")
public class EvidenceAttachment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long anomalyId;
    private Integer version;
    private String fileName;
    private String storedPath;
    private String fileHash;
    private Long sizeBytes;
    private String contentType;
    private String note;
    private String uploadedBy;
    private LocalDateTime createdAt;
}

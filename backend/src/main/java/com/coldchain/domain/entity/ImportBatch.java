package com.coldchain.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("import_batch")
public class ImportBatch {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String batchNo;
    private String fileName;
    /** RUNNING / FINISHED / FAILED */
    private String status;
    private Integer totalRows;
    private Integer successRows;
    private Integer duplicateRows;
    private Integer failedRows;
    private String operator;
    private String message;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
}

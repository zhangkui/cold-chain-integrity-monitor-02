package com.coldchain.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("import_row")
public class ImportRow {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long importBatchId;
    private Integer rowNo;
    /** SUCCESS / DUPLICATE / FAILED */
    private String status;
    private String rawLine;
    private String errorMsg;
    private String entityId;
    private LocalDateTime createdAt;
}

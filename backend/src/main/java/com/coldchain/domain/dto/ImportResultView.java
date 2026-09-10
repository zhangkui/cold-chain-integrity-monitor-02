package com.coldchain.domain.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ImportResultView {
    private Long id;
    private String batchNo;
    private String fileName;
    private String status;
    private Integer totalRows;
    private Integer successRows;
    private Integer duplicateRows;
    private Integer failedRows;
    private String operator;
    private String message;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;
    private List<RowView> rows;

    @Data
    public static class RowView {
        private Integer rowNo;
        private String status;
        private String rawLine;
        private String errorMsg;
        private String entityId;
    }
}

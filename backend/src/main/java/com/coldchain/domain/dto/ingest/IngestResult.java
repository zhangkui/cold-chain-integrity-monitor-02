package com.coldchain.domain.dto.ingest;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 摄入结果：created 新增 / duplicated 幂等重复 / failed 失败明细 */
@Data
public class IngestResult {
    private int received;
    private int created;
    private int duplicated;
    private int failed;
    /** 本次摄入真正写入了新采样的设备 id（供批量导入后统一重算） */
    private List<Long> touchedDeviceIds = new ArrayList<>();
    private List<RowError> errors = new ArrayList<>();

    public void addError(int index, String key, String message) {
        errors.add(new RowError(index, key, message));
        failed++;
    }

    public record RowError(int index, String key, String message) {
    }
}

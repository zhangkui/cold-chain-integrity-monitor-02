package com.coldchain.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.coldchain.domain.dto.ImportResultView;
import com.coldchain.domain.dto.ingest.SampleIngestRequest;
import com.coldchain.domain.dto.ingest.SampleItem;
import com.coldchain.domain.entity.ImportBatch;
import com.coldchain.domain.entity.ImportRow;
import com.coldchain.mapper.ImportBatchMapper;
import com.coldchain.mapper.ImportRowMapper;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * CSV 批量导入温度采样。
 * 表头：deviceCode,seq,sampleTime,timezone,temperature,source
 * 逐行解析，合法行复用 {@link IngestService}（同样享受幂等、哈希链、异常检测），
 * 每行结果（SUCCESS / DUPLICATE / FAILED）落库，供“批量导入结果”页展示。
 */
@Service
@RequiredArgsConstructor
public class ImportService {

    private static final DateTimeFormatter BATCH_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final ImportBatchMapper batchMapper;
    private final ImportRowMapper rowMapper;
    private final IngestService ingestService;
    private final AuditService auditService;

    @Transactional
    public ImportResultView importSamples(MultipartFile file, String operator) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        String fileName = file.getOriginalFilename() == null ? "samples.csv" : file.getOriginalFilename();
        ImportBatch batch = new ImportBatch();
        batch.setBatchNo("IMP" + LocalDateTime.now(ZoneOffset.UTC).format(BATCH_FMT)
                + "-" + UUID.randomUUID().toString().substring(0, 6));
        batch.setFileName(fileName);
        batch.setStatus("RUNNING");
        batch.setOperator(operator == null || operator.isBlank() ? "anonymous" : operator);
        batchMapper.insert(batch);

        List<ImportRow> rows = new ArrayList<>();
        Set<Long> touchedDevices = new HashSet<>();
        int success = 0, duplicate = 0, failed = 0, total = 0;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8));
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader().setSkipHeaderRecord(true)
                     .setIgnoreSurroundingSpaces(true)
                     .setTrim(true)
                     .build()
                     .parse(reader)) {

            // 累积合法行，统一提交
            List<SampleItem> validItems = new ArrayList<>();
            List<Integer> validRowNos = new ArrayList<>();

            int rowNo = 0;
            for (CSVRecord record : parser) {
                rowNo++;
                total++;
                String raw = String.join(",", record);
                try {
                    String deviceCode = record.isMapped("deviceCode") ? record.get("deviceCode") : null;
                    String seqStr = record.isMapped("seq") ? record.get("seq") : null;
                    String sampleTime = record.isMapped("sampleTime") ? record.get("sampleTime") : null;
                    String timezone = record.isMapped("timezone") ? record.get("timezone") : null;
                    String tempStr = record.isMapped("temperature") ? record.get("temperature") : null;
                    String source = record.isMapped("source") ? record.get("source") : "REALTIME";

                    SampleItem item = new SampleItem();
                    item.setDeviceCode(deviceCode);
                    item.setSeq(Long.parseLong(seqStr.trim()));
                    item.setSampleTime(sampleTime.trim());
                    item.setTimezone(timezone);
                    item.setTemperature(new BigDecimal(tempStr.trim()));
                    item.setSource(source == null || source.isBlank() ? "REALTIME" : source.trim().toUpperCase());
                    validItems.add(item);
                    validRowNos.add(rowNo);
                } catch (Exception parseEx) {
                    failed++;
                    rows.add(buildRow(batch.getId(), rowNo, "FAILED", raw,
                            "行解析失败: " + parseEx.getMessage(), null));
                }
            }

            // 合法行交给摄入服务；为区分成功与幂等重复，逐行提交以便精确标注
            for (int i = 0; i < validItems.size(); i++) {
                SampleItem single = validItems.get(i);
                int rn = validRowNos.get(i);
                String raw = single.getDeviceCode() + "," + single.getSeq() + "," + single.getSampleTime()
                        + "," + single.getTimezone() + "," + single.getTemperature() + "," + single.getSource();
                try {
                    SampleIngestRequest req = new SampleIngestRequest();
                    req.setSamples(List.of(single));
                    // 逐行跳过检测，全部摄入后统一重算
                    var result = ingestService.ingestSamples(req, false);
                    touchedDevices.addAll(result.getTouchedDeviceIds());
                    if (result.getFailed() > 0) {
                        failed++;
                        rows.add(buildRow(batch.getId(), rn, "FAILED", raw,
                                result.getErrors().isEmpty() ? "摄入失败" : result.getErrors().get(0).message(),
                                null));
                    } else if (result.getDuplicated() > 0) {
                        duplicate++;
                        rows.add(buildRow(batch.getId(), rn, "DUPLICATE", raw, "幂等命中：数据已存在", null));
                    } else {
                        success++;
                        rows.add(buildRow(batch.getId(), rn, "SUCCESS", raw, null, "sample"));
                    }
                } catch (Exception ex) {
                    failed++;
                    rows.add(buildRow(batch.getId(), rn, "FAILED", raw, ex.getMessage(), null));
                }
            }
        }

        rows.forEach(rowMapper::insert);

        // 全部行摄入后统一重算异常并刷新设备状态（幂等，已复核异常保留）
        ingestService.finalizeAfterBatchImport(touchedDevices);

        batch.setTotalRows(total);
        batch.setSuccessRows(success);
        batch.setDuplicateRows(duplicate);
        batch.setFailedRows(failed);
        batch.setStatus(failed > 0 && success == 0 && duplicate == 0 ? "FAILED" : "FINISHED");
        batch.setMessage(String.format("共 %d 行：成功 %d，重复 %d，失败 %d", total, success, duplicate, failed));
        batch.setFinishedAt(LocalDateTime.now(ZoneOffset.UTC));
        batchMapper.updateById(batch);

        auditService.log("IMPORT_BATCH", batch.getBatchNo(), "IMPORT_CSV",
                java.util.Map.of("fileName", fileName, "total", total,
                        "success", success, "duplicate", duplicate, "failed", failed));
        return getResult(batch.getBatchNo());
    }

    private ImportRow buildRow(Long batchId, int rowNo, String status, String raw, String error, String entityId) {
        ImportRow row = new ImportRow();
        row.setImportBatchId(batchId);
        row.setRowNo(rowNo);
        row.setStatus(status);
        row.setRawLine(raw);
        row.setErrorMsg(error);
        row.setEntityId(entityId);
        return row;
    }

    public ImportResultView getResult(String batchNo) {
        ImportBatch batch = batchMapper.selectOne(Wrappers.<ImportBatch>lambdaQuery()
                .eq(ImportBatch::getBatchNo, batchNo));
        if (batch == null) {
            return null;
        }
        ImportResultView view = new ImportResultView();
        view.setId(batch.getId());
        view.setBatchNo(batch.getBatchNo());
        view.setFileName(batch.getFileName());
        view.setStatus(batch.getStatus());
        view.setTotalRows(batch.getTotalRows());
        view.setSuccessRows(batch.getSuccessRows());
        view.setDuplicateRows(batch.getDuplicateRows());
        view.setFailedRows(batch.getFailedRows());
        view.setOperator(batch.getOperator());
        view.setMessage(batch.getMessage());
        view.setCreatedAt(batch.getCreatedAt());
        view.setFinishedAt(batch.getFinishedAt());

        List<ImportRow> rows = rowMapper.selectList(Wrappers.<ImportRow>lambdaQuery()
                .eq(ImportRow::getImportBatchId, batch.getId())
                .orderByAsc(ImportRow::getRowNo));
        view.setRows(rows.stream().map(r -> {
            ImportResultView.RowView rv = new ImportResultView.RowView();
            rv.setRowNo(r.getRowNo());
            rv.setStatus(r.getStatus());
            rv.setRawLine(r.getRawLine());
            rv.setErrorMsg(r.getErrorMsg());
            rv.setEntityId(r.getEntityId());
            return rv;
        }).toList());
        return view;
    }

    public List<ImportResultView> recentBatches(int limit) {
        return batchMapper.selectList(Wrappers.<ImportBatch>lambdaQuery()
                        .orderByDesc(ImportBatch::getId).last("limit " + Math.max(1, Math.min(limit, 100))))
                .stream().map(b -> {
                    ImportResultView v = new ImportResultView();
                    v.setId(b.getId());
                    v.setBatchNo(b.getBatchNo());
                    v.setFileName(b.getFileName());
                    v.setStatus(b.getStatus());
                    v.setTotalRows(b.getTotalRows());
                    v.setSuccessRows(b.getSuccessRows());
                    v.setDuplicateRows(b.getDuplicateRows());
                    v.setFailedRows(b.getFailedRows());
                    v.setOperator(b.getOperator());
                    v.setMessage(b.getMessage());
                    v.setCreatedAt(b.getCreatedAt());
                    v.setFinishedAt(b.getFinishedAt());
                    return v;
                }).toList();
    }
}

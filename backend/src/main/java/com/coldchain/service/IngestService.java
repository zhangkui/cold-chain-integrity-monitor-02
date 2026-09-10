package com.coldchain.service;

import com.coldchain.common.BusinessException;
import com.coldchain.common.TimeUtils;
import com.coldchain.domain.dto.ingest.CalibrationIngestRequest;
import com.coldchain.domain.dto.ingest.CalibrationItem;
import com.coldchain.domain.dto.ingest.EventIngestRequest;
import com.coldchain.domain.dto.ingest.EventItem;
import com.coldchain.domain.dto.ingest.IngestResult;
import com.coldchain.domain.dto.ingest.NodeIngestRequest;
import com.coldchain.domain.dto.ingest.NodeItem;
import com.coldchain.domain.dto.ingest.SampleIngestRequest;
import com.coldchain.domain.dto.ingest.SampleItem;
import com.coldchain.domain.entity.BoxEvent;
import com.coldchain.domain.entity.CalibrationRecord;
import com.coldchain.domain.entity.ColdBox;
import com.coldchain.domain.entity.Device;
import com.coldchain.domain.enums.SampleSource;
import com.coldchain.mapper.DeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 数据摄入编排（本身不开事务，逐行委托给独立事务写入器）。
 *
 * 幂等：相同 设备+时间+序号 的采样由 uk_idem_key / uk_device_seq 唯一约束兜底，
 * 重复提交（含断网重试）记 DUPLICATE，不产生重复数据与重复告警。
 *
 * 并发：采样在 {@link SampleChainWriter} 内“先取设备锁、再开独立事务、提交后释放锁”，
 * 保证哈希链严格串行、prevHash 接到已提交链尾；补传/乱序插入自动重链并审计。
 * 每行独立事务，单行失败不连累整批。
 *
 * 补传不覆盖已确认异常：{@link DetectionService#recomputeDevice} 只清理 OPEN 检测类异常，
 * CONFIRMED/REJECTED 一律保留。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestService {

    private final ReferenceService referenceService;
    private final DeviceMapper deviceMapper;
    private final DetectionService detectionService;
    private final AuditService auditService;
    private final SampleChainWriter sampleChainWriter;
    private final IngestRowWriter rowWriter;

    public IngestResult ingestSamples(SampleIngestRequest request) {
        return ingestSamples(request, true);
    }

    /**
     * @param runDetection false 时跳过逐设备重算（批量导入逐行调用时使用，
     *                     由调用方在全部行处理完后统一重算一次，避免 O(n²) 检测）
     */
    public IngestResult ingestSamples(SampleIngestRequest request, boolean runDetection) {
        IngestResult result = new IngestResult();
        result.setReceived(request.getSamples().size());
        Set<Long> touchedDevices = new HashSet<>();

        for (int i = 0; i < request.getSamples().size(); i++) {
            SampleItem item = request.getSamples().get(i);
            try {
                Device device = referenceService.requireDevice(item.getDeviceId(), item.getDeviceCode());
                ColdBox box = referenceService.resolveBox(device, item.getBoxCode());
                String tz = item.getTimezone() != null && !item.getTimezone().isBlank()
                        ? item.getTimezone() : device.getTimezone();
                Instant instant = TimeUtils.parseToInstant(item.getSampleTime(), tz);
                BigDecimal temp = item.getTemperature().setScale(2, RoundingMode.HALF_UP);
                String source = item.getSource() == null || item.getSource().isBlank()
                        ? SampleSource.REALTIME.name() : item.getSource().trim().toUpperCase();
                if (!List.of("REALTIME", "BACKFILL").contains(source)) {
                    throw new BusinessException("source 只能是 REALTIME 或 BACKFILL");
                }
                String idemKey = device.getId() + "|" + TimeUtils.toUtc(instant) + "|" + item.getSeq();

                Long createdId = sampleChainWriter.writeLocked(
                        device, box, item.getSeq(), instant, temp, source, idemKey);
                if (createdId == null) {
                    result.setDuplicated(result.getDuplicated() + 1);
                } else {
                    result.setCreated(result.getCreated() + 1);
                    touchedDevices.add(device.getId());
                    result.getTouchedDeviceIds().add(device.getId());
                }
            } catch (BusinessException e) {
                result.addError(i, idemHint(item), e.getMessage());
            } catch (Exception e) {
                result.addError(i, idemHint(item), e.getMessage());
            }
        }

        if (runDetection) {
            finalizeAfterBatchImport(touchedDevices);
            auditService.log("SAMPLE_BATCH", "*", "INGEST", result);
        }
        return result;
    }

    /** 批量摄入结束后：对受影响设备统一重算一次并刷新在线状态 */
    public void finalizeAfterBatchImport(Set<Long> deviceIds) {
        for (Long deviceId : deviceIds) {
            detectionService.recomputeDevice(deviceId);
            refreshDeviceState(deviceId);
        }
    }

    private void refreshDeviceState(Long deviceId) {
        detectionService.refreshDeviceLastSample(deviceId);
        Device patch = new Device();
        patch.setId(deviceId);
        patch.setStatus("ONLINE");
        deviceMapper.updateById(patch);
    }

    // ============================== 开箱事件 ==============================

    public IngestResult ingestEvents(EventIngestRequest request) {
        IngestResult result = new IngestResult();
        result.setReceived(request.getEvents().size());
        for (int i = 0; i < request.getEvents().size(); i++) {
            EventItem item = request.getEvents().get(i);
            try {
                Device device = referenceService.requireDevice(item.getDeviceId(), item.getDeviceCode());
                ColdBox box = referenceService.resolveBox(device, item.getBoxCode());
                String type = item.getEventType().trim().toUpperCase();
                if (!List.of("OPEN", "CLOSE").contains(type)) {
                    throw new BusinessException("eventType 只能是 OPEN/CLOSE");
                }
                String tz = item.getTimezone() != null && !item.getTimezone().isBlank()
                        ? item.getTimezone() : device.getTimezone();
                Instant instant = TimeUtils.parseToInstant(item.getEventTime(), tz);
                String idemKey = device.getId() + "|" + type + "|" + TimeUtils.toUtc(instant);

                BoxEvent event = new BoxEvent();
                event.setBoxId(box.getId());
                event.setDeviceId(device.getId());
                event.setEventType(type);
                event.setEventTimeUtc(TimeUtils.toUtc(instant));
                event.setEventTimeLocal(LocalDateTime.ofInstant(instant, ZoneId.of(device.getTimezone())));
                event.setNote(item.getNote());
                event.setIdemKey(idemKey);

                Long id = rowWriter.insertEvent(event);
                if (id == null) {
                    result.setDuplicated(result.getDuplicated() + 1);
                } else {
                    result.setCreated(result.getCreated() + 1);
                    auditService.log("BOX_EVENT", id, type,
                            java.util.Map.of("boxId", box.getId(), "time", instant.toString()));
                }
            } catch (BusinessException e) {
                result.addError(i, item.getEventType(), e.getMessage());
            } catch (Exception e) {
                result.addError(i, item.getEventType(), e.getMessage());
            }
        }
        return result;
    }

    // ============================== 转运节点 ==============================

    public IngestResult ingestNodes(NodeIngestRequest request) {
        IngestResult result = new IngestResult();
        result.setReceived(request.getNodes().size());
        for (int i = 0; i < request.getNodes().size(); i++) {
            NodeItem item = request.getNodes().get(i);
            try {
                ColdBox box = referenceService.requireBoxByCode(item.getBoxCode());
                IngestRowWriter.NodeUpsert upsert = rowWriter.upsertNode(box, item);
                if (upsert.duplicate()) {
                    result.setDuplicated(result.getDuplicated() + 1);
                } else {
                    result.setCreated(result.getCreated() + 1);
                }
                auditService.log("TRANSPORT_NODE", upsert.nodeId(),
                        upsert.duplicate() ? "UPSERT_DUP" : "CREATE",
                        java.util.Map.of("shipmentNo", item.getShipmentNo(), "seq", item.getSeq()));
            } catch (BusinessException e) {
                result.addError(i, item.getNodeCode(), e.getMessage());
            } catch (Exception e) {
                result.addError(i, item.getNodeCode(), e.getMessage());
            }
        }
        return result;
    }

    // ============================== 校准记录 ==============================

    public IngestResult ingestCalibrations(CalibrationIngestRequest request) {
        IngestResult result = new IngestResult();
        result.setReceived(request.getCalibrations().size());
        for (int i = 0; i < request.getCalibrations().size(); i++) {
            CalibrationItem item = request.getCalibrations().get(i);
            try {
                Device device = referenceService.requireDevice(item.getDeviceId(), item.getDeviceCode());
                String tz = item.getTimezone() != null && !item.getTimezone().isBlank()
                        ? item.getTimezone() : device.getTimezone();
                Instant instant = TimeUtils.parseToInstant(item.getCalibrateTime(), tz);

                CalibrationRecord record = new CalibrationRecord();
                record.setDeviceId(device.getId());
                record.setCalibrateTimeUtc(TimeUtils.toUtc(instant));
                record.setAgency(item.getAgency());
                record.setOffsetBefore(item.getOffsetBefore());
                record.setOffsetAfter(item.getOffsetAfter());
                record.setResult(item.getResult().trim().toUpperCase());
                record.setCertNo(item.getCertNo().trim());
                record.setOperator(item.getOperator());

                Long id = rowWriter.insertCalibration(record);
                if (id == null) {
                    result.setDuplicated(result.getDuplicated() + 1);
                } else {
                    result.setCreated(result.getCreated() + 1);
                    auditService.log("CALIBRATION", id, "CREATE",
                            java.util.Map.of("device", device.getDeviceCode(), "certNo", record.getCertNo()));
                }
            } catch (BusinessException e) {
                result.addError(i, item.getCertNo(), e.getMessage());
            } catch (Exception e) {
                result.addError(i, item.getCertNo(), e.getMessage());
            }
        }
        return result;
    }

    private String idemHint(SampleItem item) {
        List<String> parts = new ArrayList<>();
        parts.add(item.getDeviceCode() != null ? item.getDeviceCode() : String.valueOf(item.getDeviceId()));
        parts.add(String.valueOf(item.getSeq()));
        parts.add(item.getSampleTime());
        return String.join("|", parts);
    }
}

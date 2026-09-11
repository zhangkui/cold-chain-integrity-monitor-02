package com.coldchain.bootstrap;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.coldchain.common.HashUtils;
import com.coldchain.common.TimeUtils;
import com.coldchain.domain.entity.BoxEvent;
import com.coldchain.domain.entity.CalibrationRecord;
import com.coldchain.domain.entity.ColdBox;
import com.coldchain.domain.entity.Device;
import com.coldchain.domain.entity.Shipment;
import com.coldchain.domain.entity.TemperatureSample;
import com.coldchain.domain.entity.TransportNode;
import com.coldchain.mapper.BoxEventMapper;
import com.coldchain.mapper.CalibrationRecordMapper;
import com.coldchain.mapper.ColdBoxMapper;
import com.coldchain.mapper.DeviceMapper;
import com.coldchain.mapper.ShipmentMapper;
import com.coldchain.mapper.TemperatureSampleMapper;
import com.coldchain.mapper.TransportNodeMapper;
import com.coldchain.service.AuditService;
import com.coldchain.service.DetectionService;
import com.coldchain.service.assessment.AssessmentService;
import com.coldchain.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 首次启动（cold_box 表为空）写入演示数据：
 * 3 台设备 / 3 个冷链箱（其中一个无采样，用于演示空数据）、跨约 30 小时（跨日）
 * 的采样、连续超温段、传感器离线缺口、采样间隔异常、开箱事件、转运节点、校准记录，
 * 以及一处被直接改库的篡改样本（哈希链校验可发现）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "coldchain.seed.on-start", havingValue = "true", matchIfMissing = true)
public class DataSeeder implements ApplicationRunner {

    private final DeviceMapper deviceMapper;
    private final ColdBoxMapper boxMapper;
    private final ShipmentMapper shipmentMapper;
    private final TransportNodeMapper nodeMapper;
    private final BoxEventMapper eventMapper;
    private final CalibrationRecordMapper calibrationMapper;
    private final TemperatureSampleMapper sampleMapper;
    private final DetectionService detectionService;
    private final AssessmentService assessmentService;
    private final AuditService auditService;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Long boxCount = boxMapper.selectCount(null);
        if (boxCount != null && boxCount > 0) {
            log.info("检测到已有数据，跳过演示数据初始化");
            return;
        }
        log.info("初始化演示数据...");
        Instant base = Instant.now().minus(Duration.ofHours(30)).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);

        Device dev1 = device("DEV-001", "干冰箱终端 A", "Asia/Shanghai");
        Device dev2 = device("DEV-002", "冷藏箱终端 B", "Asia/Shanghai");
        Device dev3 = device("DEV-003", "备用终端 C", "Europe/Berlin");
        deviceMapper.insert(dev1);
        deviceMapper.insert(dev2);
        deviceMapper.insert(dev3);

        ColdBox box1 = box("BOX-DRY-01", dev1.getId(), "BATCH-2026-09-001", "CAR-T 细胞",
                "-85.00", "-75.00", 300, 900, 240, 360);
        ColdBox box2 = box("BOX-FRZ-02", dev2.getId(), "BATCH-2026-09-002", "疫苗",
                "2.00", "8.00", 300, 900, 240, 360);
        ColdBox box3 = box("BOX-EMPTY-03", dev3.getId(), "BATCH-2026-09-003", "血浆样本",
                "-25.00", "-15.00", 300, 900, 240, 360);
        boxMapper.insert(box1);
        boxMapper.insert(box2);
        boxMapper.insert(box3);

        seedShipment(box1, dev1, "SH-001", base);
        seedCalibration(dev1, base.plus(Duration.ofMinutes(10)));
        seedDryIceSeries(dev1, box1, base);
        seedFridgeSeries(dev2, box2, base);

        // 对 box2 一条历史采样做“直接改库”式篡改（不重算哈希），用于演示哈希链断裂检测
        TemperatureSample tampered = sampleMapper.selectOne(Wrappers.<TemperatureSample>lambdaQuery()
                .eq(TemperatureSample::getDeviceId, dev2.getId())
                .eq(TemperatureSample::getSeq, 40L));
        if (tampered != null) {
            sampleMapper.update(null, Wrappers.<TemperatureSample>lambdaUpdate()
                    .eq(TemperatureSample::getId, tampered.getId())
                    .set(TemperatureSample::getTemperatureC, new BigDecimal("-0.50")));
            log.warn("已植入篡改样本：device={} seq=40 温度被直接改为 -0.50℃（哈希未更新）", dev2.getDeviceCode());
        }

        detectionService.recomputeDevice(dev1.getId());
        detectionService.recomputeDevice(dev2.getId());
        detectionService.verifyChain(dev2.getId());

        // 箱体状态：BOX1 已送达；BOX2 存在哈希断裂，标记异常；BOX3 保持运输中（无采样，演示空数据）
        ColdBox delivered = new ColdBox();
        delivered.setId(box1.getId());
        delivered.setStatus("DELIVERED");
        boxMapper.updateById(delivered);
        ColdBox abnormal = new ColdBox();
        abnormal.setId(box2.getId());
        abnormal.setStatus("EXCEPTION");
        boxMapper.updateById(abnormal);

        // 设备在线状态（最后采样时间）
        for (Device d : new Device[]{dev1, dev2}) {
            TemperatureSample last = sampleMapper.selectOne(Wrappers.<TemperatureSample>lambdaQuery()
                    .eq(TemperatureSample::getDeviceId, d.getId())
                    .orderByDesc(TemperatureSample::getSampleTimeUtc).last("limit 1"));
            Device patch = new Device();
            patch.setId(d.getId());
            patch.setStatus("ONLINE");
            patch.setLastSampleTimeUtc(last.getSampleTimeUtc());
            deviceMapper.updateById(patch);
        }
        auditService.log("SYSTEM", "*", "SEED_DEMO_DATA",
                java.util.Map.of("devices", 3, "boxes", 3));

        // 生成综合评估演示数据：box1 不合格（超温/离线）、box2 需复核（哈希断裂）、
        // box3 不可评估（无采样，结果仍落库为 UNASSESSABLE 版本，接口返回 422）
        seedAssessment(box1.getId());
        seedAssessment(box2.getId());
        seedAssessment(box3.getId());
        log.info("演示数据初始化完成");
    }

    private void seedAssessment(Long boxId) {
        try {
            assessmentService.generate(boxId);
        } catch (BusinessException e) {
            // 数据不足的 422 属于预期演示场景（不可评估版本已持久化）
            log.info("箱体 {} 初始评估：{} ({})", boxId, e.getMessage(), e.getErrorCode());
        }
    }

    private Device device(String code, String name, String tz) {
        Device d = new Device();
        d.setDeviceCode(code);
        d.setName(name);
        d.setTimezone(tz);
        d.setStatus("OFFLINE");
        return d;
    }

    private ColdBox box(String code, Long deviceId, String batch, String specimen,
                        String min, String max, int excursion, int offline, int intMin, int intMax) {
        ColdBox b = new ColdBox();
        b.setBoxCode(code);
        b.setDeviceId(deviceId);
        b.setBatchNo(batch);
        b.setSpecimenType(specimen);
        b.setStatus("IN_TRANSIT");
        b.setTempMin(new BigDecimal(min));
        b.setTempMax(new BigDecimal(max));
        b.setExcursionSeconds(excursion);
        b.setOfflineSeconds(offline);
        b.setIntervalMinSeconds(intMin);
        b.setIntervalMaxSeconds(intMax);
        return b;
    }

    /** 干冰箱：基准 -80℃，5 分钟一点，约 30 小时跨日；注入超温段/离线缺口/间隔异常 */
    private void seedDryIceSeries(Device device, ColdBox box, Instant base) {
        ZoneId zone = ZoneId.of(device.getTimezone());
        String prevChain = HashUtils.GENESIS;
        Instant t = base;
        long seq = 1;
        for (int i = 0; i < 360; i++) {
            long gapSeconds = 300;
            if (i == 120) {
                gapSeconds = 1200; // 传感器离线缺口
            } else if (i == 200) {
                gapSeconds = 120;  // 采样间隔异常（过短）
            }
            t = t.plusSeconds(gapSeconds);

            // 第 60~67 点连续超温（温度偏高，超出 -75℃ 上限）
            double temp;
            if (i >= 60 && i <= 67) {
                temp = -69.50 + (i - 60) * 0.3;
            } else {
                temp = -80.00 + ((i % 7) - 3) * 0.18;
            }
            prevChain = insertSample(device, box, seq++, t, zone, temp, "REALTIME", prevChain);
        }
    }

    /** 冷藏箱：基准 5℃；第 150~157 点连续超温；第 300 点为补传 */
    private void seedFridgeSeries(Device device, ColdBox box, Instant base) {
        ZoneId zone = ZoneId.of(device.getTimezone());
        String prevChain = HashUtils.GENESIS;
        Instant t = base;
        long seq = 1;
        for (int i = 0; i < 360; i++) {
            t = t.plusSeconds(300);
            double temp;
            if (i >= 150 && i <= 157) {
                temp = 9.80 + (i - 150) * 0.2;
            } else {
                temp = 5.00 + ((i % 5) - 2) * 0.25;
            }
            String source = i == 300 ? "BACKFILL" : "REALTIME";
            prevChain = insertSample(device, box, seq++, t, zone, temp, source, prevChain);
        }
    }

    private String insertSample(Device device, ColdBox box, long seq, Instant instant,
                                ZoneId zone, double tempC, String source, String prevChain) {
        BigDecimal temp = BigDecimal.valueOf(tempC).setScale(2, RoundingMode.HALF_UP);
        String contentHash = HashUtils.contentHash(device.getId(), seq,
                TimeUtils.hashFormat(instant), temp.toPlainString());
        String chainHash = HashUtils.chainHash(prevChain, contentHash);

        TemperatureSample s = new TemperatureSample();
        s.setDeviceId(device.getId());
        s.setBoxId(box.getId());
        s.setSeq(seq);
        s.setSampleTimeUtc(LocalDateTime.ofInstant(instant, ZoneOffset.UTC));
        s.setSampleTimeLocal(LocalDateTime.ofInstant(instant, zone));
        s.setTemperatureC(temp);
        s.setSource(source);
        s.setContentHash(contentHash);
        s.setPrevHash(prevChain);
        s.setChainHash(chainHash);
        s.setIdemKey(device.getId() + "|" + LocalDateTime.ofInstant(instant, ZoneOffset.UTC) + "|" + seq);
        s.setReceivedAt(LocalDateTime.now(ZoneOffset.UTC));
        sampleMapper.insert(s);
        return chainHash;
    }

    private void seedShipment(ColdBox box, Device device, String shipmentNo, Instant base) {
        Shipment shipment = new Shipment();
        shipment.setShipmentNo(shipmentNo);
        shipment.setBoxId(box.getId());
        shipment.setOrigin("北京样本库");
        shipment.setDestination("上海检测中心");
        shipment.setStatus("FINISHED");
        shipment.setStartTimeUtc(LocalDateTime.ofInstant(base, ZoneOffset.UTC));
        shipment.setEndTimeUtc(LocalDateTime.ofInstant(base.plus(Duration.ofHours(29)), ZoneOffset.UTC));
        shipmentMapper.insert(shipment);

        node(shipment.getId(), 1, "N-BJ-01", "北京样本库发出", "DISPATCH",
                base, base.plusSeconds(180), "张工", "北京");
        node(shipment.getId(), 2, "N-JN-02", "济南中转场安检", "TRANSIT",
                base.plus(Duration.ofHours(8)), base.plus(Duration.ofHours(8)).plusSeconds(420),
                "李工", "济南");
        node(shipment.getId(), 3, "N-NJ-03", "南京中转站换车", "TRANSIT",
                base.plus(Duration.ofHours(18)), base.plus(Duration.ofHours(18)).plusSeconds(300),
                "王工", "南京");
        node(shipment.getId(), 4, "N-SH-04", "上海检测中心到达", "ARRIVAL",
                base.plus(Duration.ofHours(28)), base.plus(Duration.ofHours(28)).plusSeconds(240),
                "赵工", "上海");
        node(shipment.getId(), 5, "N-SH-05", "签收入库", "SIGN",
                base.plus(Duration.ofHours(29)), base.plus(Duration.ofHours(29)).plusSeconds(120),
                "赵工", "上海");

        openEvent(box, device, base.plus(Duration.ofMinutes(30)), "发出时例行开箱核对");
        openEvent(box, device, base.plus(Duration.ofHours(8)).plusSeconds(600), "中转安检开箱");
        closeEvent(box, device, base.plus(Duration.ofHours(8)).plusSeconds(660), "安检完成关门");
    }

    private void node(long shipmentId, int seq, String code, String name, String type,
                      Instant planned, Instant actual, String operator, String location) {
        TransportNode n = new TransportNode();
        n.setShipmentId(shipmentId);
        n.setSeq(seq);
        n.setNodeCode(code);
        n.setNodeName(name);
        n.setNodeType(type);
        n.setPlannedTimeUtc(LocalDateTime.ofInstant(planned, ZoneOffset.UTC));
        n.setActualTimeUtc(LocalDateTime.ofInstant(actual, ZoneOffset.UTC));
        n.setOperator(operator);
        n.setLocation(location);
        nodeMapper.insert(n);
    }

    private void openEvent(ColdBox box, Device device, Instant t, String note) {
        event(box, device, "OPEN", t, note);
    }

    private void closeEvent(ColdBox box, Device device, Instant t, String note) {
        event(box, device, "CLOSE", t, note);
    }

    private void event(ColdBox box, Device device, String type, Instant t, String note) {
        BoxEvent e = new BoxEvent();
        e.setBoxId(box.getId());
        e.setDeviceId(device.getId());
        e.setEventType(type);
        e.setEventTimeUtc(LocalDateTime.ofInstant(t, ZoneOffset.UTC));
        e.setEventTimeLocal(LocalDateTime.ofInstant(t, ZoneId.of(device.getTimezone())));
        e.setNote(note);
        e.setIdemKey(device.getId() + "|" + type + "|" + LocalDateTime.ofInstant(t, ZoneOffset.UTC));
        eventMapper.insert(e);
    }

    private void seedCalibration(Device device, Instant t) {
        CalibrationRecord c = new CalibrationRecord();
        c.setDeviceId(device.getId());
        c.setCalibrateTimeUtc(LocalDateTime.ofInstant(t, ZoneOffset.UTC));
        c.setAgency("国家计量测试中心");
        c.setOffsetBefore(new BigDecimal("0.35"));
        c.setOffsetAfter(new BigDecimal("0.05"));
        c.setResult("PASS");
        c.setCertNo("CERT-2026-0001");
        c.setOperator("校准员-陈");
        calibrationMapper.insert(c);
    }
}

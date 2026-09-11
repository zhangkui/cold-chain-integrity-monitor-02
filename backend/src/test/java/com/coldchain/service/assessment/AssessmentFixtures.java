package com.coldchain.service.assessment;

import com.coldchain.domain.entity.Anomaly;
import com.coldchain.domain.entity.BoxEvent;
import com.coldchain.domain.entity.ColdBox;
import com.coldchain.domain.entity.Device;
import com.coldchain.domain.entity.Shipment;
import com.coldchain.domain.entity.TemperatureSample;
import com.coldchain.domain.entity.TransportNode;
import com.coldchain.domain.enums.AssessmentConclusion;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** 评估测试数据构造小工具 */
final class AssessmentFixtures {

    static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");

    private AssessmentFixtures() {
    }

    static ColdBox box(long id, double min, double max) {
        ColdBox b = new ColdBox();
        b.setId(id);
        b.setBoxCode("BOX-" + id);
        b.setBatchNo("BATCH-" + id);
        b.setDeviceId(100L + id);
        b.setTempMin(BigDecimal.valueOf(min));
        b.setTempMax(BigDecimal.valueOf(max));
        b.setExcursionSeconds(300);
        b.setOfflineSeconds(900);
        b.setIntervalMinSeconds(240);
        b.setIntervalMaxSeconds(360);
        return b;
    }

    static ColdBox boxNoDevice(long id) {
        ColdBox b = box(id, 2, 8);
        b.setDeviceId(null);
        return b;
    }

    static Device device(long id) {
        Device d = new Device();
        d.setId(id);
        d.setDeviceCode("DEV-" + id);
        d.setName("终端 " + id);
        d.setTimezone("Asia/Shanghai");
        return d;
    }

    /** 生成从 T0 起每 300s 一个、恒定温度的采样链（哈希自洽） */
    static List<TemperatureSample> chain(long deviceId, long boxId, int points, double temp) {
        List<TemperatureSample> out = new ArrayList<>();
        String prev = com.coldchain.common.HashUtils.GENESIS;
        for (int i = 0; i < points; i++) {
            long seq = i + 1;
            Instant t = T0.plusSeconds(300L * i);
            BigDecimal tempV = BigDecimal.valueOf(temp).setScale(2, java.math.RoundingMode.HALF_UP);
            String content = com.coldchain.common.HashUtils.contentHash(deviceId, seq,
                    com.coldchain.common.TimeUtils.hashFormat(t), tempV.toPlainString());
            String chain = com.coldchain.common.HashUtils.chainHash(prev, content);
            TemperatureSample s = new TemperatureSample();
            s.setId(seq);
            s.setDeviceId(deviceId);
            s.setBoxId(boxId);
            s.setSeq(seq);
            s.setSampleTimeUtc(LocalDateTime.ofInstant(t, ZoneOffset.UTC));
            s.setTemperatureC(tempV);
            s.setContentHash(content);
            s.setPrevHash(prev);
            s.setChainHash(chain);
            s.setReceivedAt(LocalDateTime.ofInstant(t.plusSeconds(2), ZoneOffset.UTC));
            out.add(s);
            prev = chain;
        }
        return out;
    }

    /** 直接篡改某条采样温度（不重算哈希），模拟改库 */
    static void tamper(List<TemperatureSample> samples, int index, double newTemp) {
        samples.get(index).setTemperatureC(BigDecimal.valueOf(newTemp).setScale(2, java.math.RoundingMode.HALF_UP));
    }

    /** 把第 index 条采样时间改成更早（制造时间倒序），同时保留其哈希自洽字段不变 */
    static void reorder(List<TemperatureSample> samples, int index) {
        TemperatureSample s = samples.get(index);
        s.setSampleTimeUtc(s.getSampleTimeUtc().minusSeconds(600));
    }

    static Anomaly anomaly(long id, String type, String status, String severity,
                           Instant start, Instant end, int durationSec) {
        Anomaly a = new Anomaly();
        a.setId(id);
        a.setBoxId(1L);
        a.setDeviceId(101L);
        a.setType(type);
        a.setStatus(status);
        a.setSeverity(severity);
        a.setStartTimeUtc(LocalDateTime.ofInstant(start, ZoneOffset.UTC));
        a.setEndTimeUtc(LocalDateTime.ofInstant(end, ZoneOffset.UTC));
        a.setDurationSeconds(durationSec);
        a.setDescription(type + " 测试异常");
        return a;
    }

    static Shipment shipment(long id, long boxId, Instant start, Instant end) {
        Shipment s = new Shipment();
        s.setId(id);
        s.setBoxId(boxId);
        s.setShipmentNo("SH-" + id);
        s.setStatus(end == null ? "RUNNING" : "FINISHED");
        s.setStartTimeUtc(LocalDateTime.ofInstant(start, ZoneOffset.UTC));
        if (end != null) {
            s.setEndTimeUtc(LocalDateTime.ofInstant(end, ZoneOffset.UTC));
        }
        return s;
    }

    static TransportNode node(long id, long shipmentId, int seq, String name, String type,
                              Instant planned, Instant actual) {
        TransportNode n = new TransportNode();
        n.setId(id);
        n.setShipmentId(shipmentId);
        n.setSeq(seq);
        n.setNodeCode("N-" + seq);
        n.setNodeName(name);
        n.setNodeType(type);
        if (planned != null) n.setPlannedTimeUtc(LocalDateTime.ofInstant(planned, ZoneOffset.UTC));
        if (actual != null) n.setActualTimeUtc(LocalDateTime.ofInstant(actual, ZoneOffset.UTC));
        return n;
    }

    static BoxEvent event(long id, long boxId, long deviceId, String kind, Instant t) {
        BoxEvent e = new BoxEvent();
        e.setId(id);
        e.setBoxId(boxId);
        e.setDeviceId(deviceId);
        e.setEventType(kind);
        e.setEventTimeUtc(LocalDateTime.ofInstant(t, ZoneOffset.UTC));
        return e;
    }

    static AssessmentEngine.ChainResult intact(int checked) {
        return new AssessmentEngine.ChainResult("INTACT", checked, List.of());
    }

    static AssessmentEngine.ChainResult broken(int checked, long... seqs) {
        List<Long> list = new ArrayList<>();
        for (long s : seqs) list.add(s);
        return new AssessmentEngine.ChainResult("BROKEN", checked, list);
    }

    static AssessmentEngine.Input.Builder baseInput(List<TemperatureSample> samples) {
        ColdBox box = box(1, 2, 8);
        return AssessmentEngine.Input.builder()
                .box(box)
                .device(device(box.getDeviceId()))
                .samples(samples)
                // 真实服务中无采样时不执行链校验（守卫判不可评估），夹具保持同一口径
                .chain(samples.isEmpty() ? null : intact(samples.size()))
                .anomalies(List.of())
                .shipments(List.of())
                .nodes(List.of())
                .events(List.of())
                .evaluatedAt(T0.plusSeconds(300L * Math.max(1, samples.size()) + 3600));
    }

    static boolean hasRisk(AssessmentEngine.Output out, String code) {
        return out.getRisks().stream().anyMatch(r -> r.getCode().equals(code));
    }

    static AssessmentConclusion conclusion(List<TemperatureSample> samples) {
        return AssessmentEngine.evaluate(baseInput(samples).build()).getConclusion();
    }
}

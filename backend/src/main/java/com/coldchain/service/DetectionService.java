package com.coldchain.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.coldchain.common.BusinessException;
import com.coldchain.common.HashUtils;
import com.coldchain.common.TimeUtils;
import com.coldchain.domain.entity.Anomaly;
import com.coldchain.domain.entity.ColdBox;
import com.coldchain.domain.entity.Device;
import com.coldchain.domain.entity.TemperatureSample;
import com.coldchain.domain.enums.AnomalyStatus;
import com.coldchain.domain.enums.AnomalyType;
import com.coldchain.mapper.AnomalyMapper;
import com.coldchain.mapper.ColdBoxMapper;
import com.coldchain.mapper.DeviceMapper;
import com.coldchain.mapper.TemperatureSampleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 异常检测 + 哈希链校验。
 *
 * 检测规则（阈值全部来自箱体规则）：
 *  - TEMP_EXCURSION：连续超温（低于下限或高于上限）且持续 >= excursionSeconds
 *  - OFFLINE：相邻采样间隔 > offlineSeconds
 *  - INTERVAL：间隔落在 [intervalMin, intervalMax] 之外但未达到离线阈值
 *
 * 重算策略：删除该设备所有 OPEN 异常后按全量采样重新识别；CONFIRMED / REJECTED
 * 的异常一律保留（补传不能覆盖已经确认的异常）。dedupe_key 唯一约束保证同一
 * 异常窗口不会与已保留的记录重复。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DetectionService {

    private final TemperatureSampleMapper sampleMapper;
    private final AnomalyMapper anomalyMapper;
    private final ColdBoxMapper coldBoxMapper;
    private final DeviceMapper deviceMapper;

    /** 更新设备最后采样时间（采样摄入后调用） */
    public void refreshDeviceLastSample(Long deviceId) {
        TemperatureSample last = sampleMapper.selectOne(Wrappers.<TemperatureSample>lambdaQuery()
                .eq(TemperatureSample::getDeviceId, deviceId)
                .orderByDesc(TemperatureSample::getSampleTimeUtc)
                .last("limit 1"));
        if (last == null) {
            return;
        }
        Device patch = new Device();
        patch.setId(deviceId);
        patch.setLastSampleTimeUtc(last.getSampleTimeUtc());
        deviceMapper.updateById(patch);
    }

    @Transactional
    public int recomputeDevice(Long deviceId) {
        List<TemperatureSample> samples = sampleMapper.selectList(Wrappers.<TemperatureSample>lambdaQuery()
                .eq(TemperatureSample::getDeviceId, deviceId)
                .orderByAsc(TemperatureSample::getSeq));
        if (samples.isEmpty()) {
            return 0;
        }

        // 1. 删除 OPEN 的检测类异常（CONFIRMED/REJECTED 保留；HASH_BROKEN 只能由链校验产生，不被重算清除）
        anomalyMapper.delete(Wrappers.<Anomaly>lambdaQuery()
                .eq(Anomaly::getDeviceId, deviceId)
                .eq(Anomaly::getStatus, AnomalyStatus.OPEN.name())
                .in(Anomaly::getType, AnomalyType.TEMP_EXCURSION.name(),
                        AnomalyType.OFFLINE.name(), AnomalyType.INTERVAL.name()));

        // 2. 已存在的 dedupeKey（含被保留的已复核异常），新检测到同窗口则跳过
        Map<String, Anomaly> existing = new HashMap<>();
        anomalyMapper.selectList(Wrappers.<Anomaly>lambdaQuery()
                        .eq(Anomaly::getDeviceId, deviceId))
                .forEach(a -> existing.put(a.getDedupeKey(), a));

        List<Anomaly> detected = new ArrayList<>();
        Map<Long, ColdBox> boxCache = new HashMap<>();
        detectByBoxRuns(samples, detected, boxCache);
        detectGaps(deviceId, samples, detected, boxCache);

        int inserted = 0;
        for (Anomaly a : detected) {
            if (existing.containsKey(a.getDedupeKey())) {
                continue;
            }
            a.setStatus(AnomalyStatus.OPEN.name());
            anomalyMapper.insert(a);
            inserted++;
        }
        log.info("device {} 重算完成：采样 {} 条，识别异常 {} 条，新增 {}",
                deviceId, samples.size(), detected.size(), inserted);
        return inserted;
    }

    /** 连续超温：按箱体分组的连续越限采样段 */
    private void detectByBoxRuns(List<TemperatureSample> samples, List<Anomaly> out,
                                 Map<Long, ColdBox> boxCache) {
        int i = 0;
        while (i < samples.size()) {
            TemperatureSample first = samples.get(i);
            ColdBox box = boxCache.computeIfAbsent(first.getBoxId(), coldBoxMapper::selectById);
            if (box == null || !isOutOfRange(first, box)) {
                i++;
                continue;
            }
            // 扩展连续越限段（同箱、连续序号语义——列表即按 seq 排序）
            int j = i;
            BigDecimal peak = first.getTemperatureC();
            while (j + 1 < samples.size()) {
                TemperatureSample next = samples.get(j + 1);
                ColdBox nextBox = boxCache.computeIfAbsent(next.getBoxId(), coldBoxMapper::selectById);
                if (nextBox == null || !nextBox.getId().equals(box.getId()) || !isOutOfRange(next, box)) {
                    break;
                }
                j++;
                peak = fartherFromRange(peak, next.getTemperatureC(), box);
            }
            TemperatureSample last = samples.get(j);
            long duration = Duration.between(first.getSampleTimeUtc(), last.getSampleTimeUtc()).getSeconds();
            if (duration >= box.getExcursionSeconds()) {
                Anomaly a = baseAnomaly(AnomalyType.TEMP_EXCURSION, box, first.getDeviceId(),
                        first.getSampleTimeUtc(), last.getSampleTimeUtc(), (int) duration);
                a.setPeakTemp(peak);
                a.setSampleCount(j - i + 1);
                a.setSeverity(duration >= box.getExcursionSeconds() * 3L ? "CRITICAL" : "WARN");
                a.setDescription(String.format(
                        "连续 %d 个采样点温度超出规则范围 [%s, %s]℃，峰值 %s℃，持续 %ds",
                        j - i + 1, box.getTempMin().toPlainString(), box.getTempMax().toPlainString(),
                        peak.toPlainString(), duration));
                a.setDedupeKey("TEMP|" + box.getId() + "|" + first.getSampleTimeUtc());
                out.add(a);
            }
            i = j + 1;
        }
    }

    /** 相邻采样间隔：离线 + 间隔异常 */
    private void detectGaps(Long deviceId, List<TemperatureSample> samples, List<Anomaly> out,
                            Map<Long, ColdBox> boxCache) {
        for (int i = 1; i < samples.size(); i++) {
            TemperatureSample prev = samples.get(i - 1);
            TemperatureSample cur = samples.get(i);
            long gap = Duration.between(prev.getSampleTimeUtc(), cur.getSampleTimeUtc()).getSeconds();
            if (gap < 0) {
                continue; // 乱序数据不参与间隔检测（幂等键已保证同点唯一）
            }
            ColdBox box = boxCache.computeIfAbsent(cur.getBoxId(), coldBoxMapper::selectById);
            if (box == null) {
                box = boxCache.computeIfAbsent(prev.getBoxId(), coldBoxMapper::selectById);
            }
            if (box == null) {
                continue;
            }

            if (gap > box.getOfflineSeconds()) {
                Anomaly a = baseAnomaly(AnomalyType.OFFLINE, box, deviceId,
                        prev.getSampleTimeUtc(), cur.getSampleTimeUtc(), (int) gap);
                a.setSeverity(gap > box.getOfflineSeconds() * 2L ? "CRITICAL" : "WARN");
                a.setDescription(String.format("采样间隔 %ds 超过离线阈值 %ds（序号 %d → %d）",
                        gap, box.getOfflineSeconds(), prev.getSeq(), cur.getSeq()));
                a.setDedupeKey("OFFLINE|" + deviceId + "|" + prev.getSeq() + "|" + cur.getSeq());
                out.add(a);
            } else if (gap < box.getIntervalMinSeconds() || gap > box.getIntervalMaxSeconds()) {
                Anomaly a = baseAnomaly(AnomalyType.INTERVAL, box, deviceId,
                        prev.getSampleTimeUtc(), cur.getSampleTimeUtc(), (int) gap);
                a.setSeverity("INFO");
                a.setDescription(String.format("采样间隔 %ds 超出规则区间 [%d, %d]s（序号 %d → %d）",
                        gap, box.getIntervalMinSeconds(), box.getIntervalMaxSeconds(),
                        prev.getSeq(), cur.getSeq()));
                a.setDedupeKey("INTERVAL|" + deviceId + "|" + prev.getSeq() + "|" + cur.getSeq());
                out.add(a);
            }
        }
    }

    private Anomaly baseAnomaly(Enum<?> type, ColdBox box, Long deviceId,
                                java.time.LocalDateTime start, java.time.LocalDateTime end, int duration) {
        Anomaly a = new Anomaly();
        a.setBoxId(box.getId());
        a.setDeviceId(deviceId);
        a.setType(type.name());
        a.setStartTimeUtc(start);
        a.setEndTimeUtc(end);
        a.setDurationSeconds(duration);
        a.setSampleCount(0);
        return a;
    }

    private boolean isOutOfRange(TemperatureSample s, ColdBox box) {
        BigDecimal t = s.getTemperatureC();
        return t.compareTo(box.getTempMin()) < 0 || t.compareTo(box.getTempMax()) > 0;
    }

    /** 偏离允许区间更远的温度（用于峰值） */
    private BigDecimal fartherFromRange(BigDecimal a, BigDecimal b, ColdBox box) {
        BigDecimal da = a.compareTo(box.getTempMax()) > 0
                ? a.subtract(box.getTempMax()) : box.getTempMin().subtract(a);
        BigDecimal db = b.compareTo(box.getTempMax()) > 0
                ? b.subtract(box.getTempMax()) : box.getTempMin().subtract(b);
        return da.compareTo(db) >= 0 ? a : b;
    }

    // ============================ 哈希链校验 ============================

    public record ChainBreak(long seq, String expected, String actual, String reason) {
    }

    public record VerifyResult(Long deviceId, boolean intact, int checked, List<ChainBreak> breaks) {
    }

    /**
     * 逐环重算 contentHash / chainHash，与入库值比对。
     * 任何对历史采样时间/温度的直接篡改都会在被改行及其后所有环上失配。
     */
    @Transactional
    public VerifyResult verifyChain(Long deviceId) {
        List<TemperatureSample> samples = sampleMapper.selectList(Wrappers.<TemperatureSample>lambdaQuery()
                .eq(TemperatureSample::getDeviceId, deviceId)
                .orderByAsc(TemperatureSample::getSeq));
        if (samples.isEmpty()) {
            throw new BusinessException(404, "设备暂无采样数据: " + deviceId);
        }
        List<ChainBreak> breaks = new ArrayList<>();
        String prevChain = HashUtils.GENESIS;
        Long lastSeq = null;

        for (TemperatureSample s : samples) {
            Instant instant = s.getSampleTimeUtc().toInstant(ZoneOffset.UTC);
            String canonicalTemp = s.getTemperatureC().setScale(2, RoundingMode.HALF_UP).toPlainString();
            String expectedContent = HashUtils.contentHash(s.getDeviceId(), s.getSeq(),
                    TimeUtils.hashFormat(instant), canonicalTemp);
            String expectedChain = HashUtils.chainHash(prevChain, expectedContent);

            if (lastSeq != null && s.getSeq() <= lastSeq) {
                breaks.add(new ChainBreak(s.getSeq(), expectedChain, s.getChainHash(), "序号非递增"));
            }
            if (!expectedContent.equals(s.getContentHash())) {
                breaks.add(new ChainBreak(s.getSeq(), expectedContent, s.getContentHash(),
                        "contentHash 不一致：采样时间或温度被篡改"));
            }
            if (!prevChain.equals(s.getPrevHash())) {
                breaks.add(new ChainBreak(s.getSeq(), prevChain, s.getPrevHash(), "prevHash 断链"));
            }
            if (!expectedChain.equals(s.getChainHash())) {
                breaks.add(new ChainBreak(s.getSeq(), expectedChain, s.getChainHash(), "chainHash 不一致"));
            }
            prevChain = s.getChainHash();
            lastSeq = s.getSeq();
        }

        // 仅记录第一个失配点为 HASH_BROKEN 异常（避免告警风暴），幂等
        if (!breaks.isEmpty()) {
            ChainBreak first = breaks.get(0);
            TemperatureSample hit = samples.stream()
                    .filter(s -> s.getSeq() == first.seq()).findFirst().orElse(samples.get(0));
            String dedupe = "HASH|" + deviceId + "|" + first.seq() + "|" + first.reason();
            Long count = anomalyMapper.selectCount(Wrappers.<Anomaly>lambdaQuery()
                    .eq(Anomaly::getDedupeKey, dedupe));
            if (count == 0) {
                ColdBox box = coldBoxMapper.selectById(hit.getBoxId());
                Anomaly a = new Anomaly();
                a.setBoxId(hit.getBoxId());
                a.setDeviceId(deviceId);
                a.setType(AnomalyType.HASH_BROKEN.name());
                a.setSeverity("CRITICAL");
                a.setStartTimeUtc(hit.getSampleTimeUtc());
                a.setEndTimeUtc(hit.getSampleTimeUtc());
                a.setDurationSeconds(0);
                a.setSampleCount(0);
                a.setDescription("哈希链校验失败：序号 " + first.seq() + " " + first.reason());
                a.setDedupeKey(dedupe);
                a.setStatus(AnomalyStatus.OPEN.name());
                anomalyMapper.insert(a);
                if (box != null) {
                    log.warn("箱体 {} 哈希链断裂于 seq={}", box.getBoxCode(), first.seq());
                }
            }
        }
        breaks.sort(Comparator.comparingLong(ChainBreak::seq));
        return new VerifyResult(deviceId, breaks.isEmpty(), samples.size(), breaks);
    }
}

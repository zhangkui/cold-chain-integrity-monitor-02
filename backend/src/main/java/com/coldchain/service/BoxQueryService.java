package com.coldchain.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.coldchain.domain.dto.BoxListItem;
import com.coldchain.domain.entity.Anomaly;
import com.coldchain.domain.entity.ColdBox;
import com.coldchain.domain.entity.Device;
import com.coldchain.domain.entity.Shipment;
import com.coldchain.domain.entity.TemperatureSample;
import com.coldchain.domain.entity.TransportNode;
import com.coldchain.domain.enums.AnomalyStatus;
import com.coldchain.mapper.AnomalyMapper;
import com.coldchain.mapper.ColdBoxMapper;
import com.coldchain.mapper.DeviceMapper;
import com.coldchain.mapper.ShipmentMapper;
import com.coldchain.mapper.TemperatureSampleMapper;
import com.coldchain.mapper.TransportNodeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 箱体查询 + 有效冷链时长计算。
 *
 * 有效冷链时长 = 首末采样覆盖时长 − （超温区间 ∪ 离线区间）并集长度。
 * 已 REJECTED 的异常不参与扣减；区间先合并再做差，避免重复扣减。
 */
@Service
@RequiredArgsConstructor
public class BoxQueryService {

    private final ColdBoxMapper boxMapper;
    private final DeviceMapper deviceMapper;
    private final ShipmentMapper shipmentMapper;
    private final TemperatureSampleMapper sampleMapper;
    private final AnomalyMapper anomalyMapper;
    private final TransportNodeMapper nodeMapper;

    public List<BoxListItem> listBoxes(String boxCode, String batchNo,
                                       BigDecimal tempFrom, BigDecimal tempTo,
                                       String nodeCode, String status) {
        List<ColdBox> boxes = boxMapper.selectList(Wrappers.<ColdBox>lambdaQuery()
                .like(boxCode != null && !boxCode.isBlank(), ColdBox::getBoxCode, boxCode)
                .like(batchNo != null && !batchNo.isBlank(), ColdBox::getBatchNo, batchNo)
                .eq(status != null && !status.isBlank(), ColdBox::getStatus, status)
                .orderByAsc(ColdBox::getId));

        Map<Long, Device> devices = new HashMap<>();
        deviceMapper.selectList(null).forEach(d -> devices.put(d.getId(), d));

        // 节点筛选：nodeCode -> 转运单 -> 箱
        Set<Long> nodeBoxIds = null;
        if (nodeCode != null && !nodeCode.isBlank()) {
            nodeBoxIds = boxesHitByNode(nodeCode);
        }

        List<BoxListItem> result = new ArrayList<>();
        for (ColdBox box : boxes) {
            if (nodeBoxIds != null && !nodeBoxIds.contains(box.getId())) {
                continue;
            }
            if (tempFrom != null || tempTo != null) {
                Long hit = sampleMapper.selectCount(Wrappers.<TemperatureSample>lambdaQuery()
                        .eq(TemperatureSample::getBoxId, box.getId())
                        .ge(tempFrom != null, TemperatureSample::getTemperatureC, tempFrom)
                        .le(tempTo != null, TemperatureSample::getTemperatureC, tempTo));
                if (hit == null || hit == 0) {
                    continue;
                }
            }
            result.add(toListItem(box, devices.get(box.getDeviceId())));
        }
        return result;
    }

    private Set<Long> boxesHitByNode(String nodeCode) {
        List<TransportNode> nodes = nodeMapper.selectList(Wrappers.<TransportNode>lambdaQuery()
                .like(TransportNode::getNodeCode, nodeCode));
        Set<Long> shipmentIds = new HashSet<>();
        nodes.forEach(n -> shipmentIds.add(n.getShipmentId()));
        Set<Long> boxIds = new HashSet<>();
        if (!shipmentIds.isEmpty()) {
            shipmentMapper.selectList(Wrappers.<Shipment>lambdaQuery()
                            .in(Shipment::getId, shipmentIds))
                    .forEach(s -> boxIds.add(s.getBoxId()));
        }
        return boxIds;
    }

    public BoxListItem getBox(Long boxId) {
        ColdBox box = boxMapper.selectById(boxId);
        if (box == null) {
            return null;
        }
        Device device = box.getDeviceId() == null ? null : deviceMapper.selectById(box.getDeviceId());
        return toListItem(box, device);
    }

    private BoxListItem toListItem(ColdBox box, Device device) {
        BoxListItem item = new BoxListItem();
        item.setId(box.getId());
        item.setBoxCode(box.getBoxCode());
        item.setBatchNo(box.getBatchNo());
        item.setSpecimenType(box.getSpecimenType());
        item.setStatus(box.getStatus());
        item.setTempMin(box.getTempMin());
        item.setTempMax(box.getTempMax());
        if (device != null) {
            item.setDeviceCode(device.getDeviceCode());
            item.setDeviceName(device.getName());
            item.setTimezone(device.getTimezone());
        }

        List<TemperatureSample> samples = sampleMapper.selectList(Wrappers.<TemperatureSample>lambdaQuery()
                .eq(TemperatureSample::getBoxId, box.getId())
                .orderByAsc(TemperatureSample::getSampleTimeUtc));
        item.setSampleCount(samples.size());
        if (samples.isEmpty()) {
            item.setCoveredSeconds(0L);
            item.setExcursionSeconds(0L);
            item.setOfflineSeconds(0L);
            item.setValidColdChainSeconds(0L);
            item.setOpenAnomalyCount(0);
            item.setTotalAnomalyCount(0);
            return item;
        }
        item.setFirstSampleUtc(samples.get(0).getSampleTimeUtc());
        item.setLastSampleUtc(samples.get(samples.size() - 1).getSampleTimeUtc());
        long covered = Duration.between(item.getFirstSampleUtc(), item.getLastSampleUtc()).getSeconds();
        item.setCoveredSeconds(covered);

        List<Anomaly> anomalies = anomalyMapper.selectList(Wrappers.<Anomaly>lambdaQuery()
                .eq(Anomaly::getBoxId, box.getId()));
        item.setTotalAnomalyCount(anomalies.size());
        item.setOpenAnomalyCount((int) anomalies.stream()
                .filter(a -> AnomalyStatus.OPEN.name().equals(a.getStatus())).count());

        List<long[]> intervals = new ArrayList<>();
        long excursion = 0;
        long offline = 0;
        for (Anomaly a : anomalies) {
            if (AnomalyStatus.REJECTED.name().equals(a.getStatus())) {
                continue; // 复核驳回的异常不扣减
            }
            if ("TEMP_EXCURSION".equals(a.getType()) || "OFFLINE".equals(a.getType())) {
                if ("TEMP_EXCURSION".equals(a.getType())) {
                    excursion += a.getDurationSeconds();
                } else {
                    offline += a.getDurationSeconds();
                }
                intervals.add(new long[]{
                        a.getStartTimeUtc().toEpochSecond(ZoneOffset.UTC),
                        a.getEndTimeUtc().toEpochSecond(ZoneOffset.UTC)});
            }
        }
        item.setExcursionSeconds(excursion);
        item.setOfflineSeconds(offline);
        long deducted = unionLength(intervals);
        item.setValidColdChainSeconds(Math.max(0, covered - deducted));
        return item;
    }

    /** 区间并集长度（秒），避免超温与离线区间重叠时重复扣减 */
    private long unionLength(List<long[]> intervals) {
        if (intervals.isEmpty()) {
            return 0;
        }
        intervals.sort(Comparator.comparingLong(a -> a[0]));
        long total = 0;
        long curStart = intervals.get(0)[0];
        long curEnd = intervals.get(0)[1];
        for (int i = 1; i < intervals.size(); i++) {
            long[] iv = intervals.get(i);
            if (iv[0] <= curEnd) {
                curEnd = Math.max(curEnd, iv[1]);
            } else {
                total += curEnd - curStart;
                curStart = iv[0];
                curEnd = iv[1];
            }
        }
        total += curEnd - curStart;
        return total;
    }
}

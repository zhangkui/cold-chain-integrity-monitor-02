package com.coldchain.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.coldchain.domain.dto.AnomalyView;
import com.coldchain.domain.entity.Anomaly;
import com.coldchain.domain.entity.ColdBox;
import com.coldchain.domain.entity.Device;
import com.coldchain.mapper.AnomalyMapper;
import com.coldchain.mapper.ColdBoxMapper;
import com.coldchain.mapper.DeviceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AnomalyQueryService {

    private final AnomalyMapper anomalyMapper;
    private final ColdBoxMapper boxMapper;
    private final DeviceMapper deviceMapper;

    public List<AnomalyView> list(Long boxId, String type, String status) {
        List<Anomaly> anomalies = anomalyMapper.selectList(Wrappers.<Anomaly>lambdaQuery()
                .eq(boxId != null, Anomaly::getBoxId, boxId)
                .eq(type != null && !type.isBlank(), Anomaly::getType, type)
                .eq(status != null && !status.isBlank(), Anomaly::getStatus, status)
                .orderByDesc(Anomaly::getStartTimeUtc));

        Map<Long, ColdBox> boxes = new HashMap<>();
        boxMapper.selectList(null).forEach(b -> boxes.put(b.getId(), b));
        Map<Long, Device> devices = new HashMap<>();
        deviceMapper.selectList(null).forEach(d -> devices.put(d.getId(), d));

        return anomalies.stream().map(a -> {
            AnomalyView v = new AnomalyView();
            v.setId(a.getId());
            v.setBoxId(a.getBoxId());
            v.setDeviceId(a.getDeviceId());
            v.setType(a.getType());
            v.setSeverity(a.getSeverity());
            v.setStartTimeUtc(a.getStartTimeUtc());
            v.setEndTimeUtc(a.getEndTimeUtc());
            v.setDurationSeconds(a.getDurationSeconds());
            v.setPeakTemp(a.getPeakTemp() == null ? null : a.getPeakTemp().toPlainString());
            v.setSampleCount(a.getSampleCount());
            v.setDescription(a.getDescription());
            v.setStatus(a.getStatus());
            v.setConfirmedBy(a.getConfirmedBy());
            v.setReviewComment(a.getReviewComment());
            v.setConfirmedAt(a.getConfirmedAt());
            v.setFirstSeenAt(a.getFirstSeenAt());
            ColdBox box = boxes.get(a.getBoxId());
            if (box != null) {
                v.setBoxCode(box.getBoxCode());
                v.setBatchNo(box.getBatchNo());
            }
            Device device = devices.get(a.getDeviceId());
            if (device != null) {
                v.setDeviceCode(device.getDeviceCode());
            }
            return v;
        }).toList();
    }
}

package com.coldchain.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.coldchain.common.BusinessException;
import com.coldchain.domain.dto.TimelineResponse;
import com.coldchain.domain.entity.Anomaly;
import com.coldchain.domain.entity.BoxEvent;
import com.coldchain.domain.entity.ColdBox;
import com.coldchain.domain.entity.Shipment;
import com.coldchain.domain.entity.TransportNode;
import com.coldchain.mapper.AnomalyMapper;
import com.coldchain.mapper.BoxEventMapper;
import com.coldchain.mapper.ColdBoxMapper;
import com.coldchain.mapper.ShipmentMapper;
import com.coldchain.mapper.TransportNodeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

/**
 * 转运时间线：节点（计划/实际）、开箱事件、异常按时间归并排序。
 */
@Service
@RequiredArgsConstructor
public class TimelineService {

    private final ColdBoxMapper boxMapper;
    private final ShipmentMapper shipmentMapper;
    private final TransportNodeMapper nodeMapper;
    private final BoxEventMapper eventMapper;
    private final AnomalyMapper anomalyMapper;

    public TimelineResponse timeline(Long boxId) {
        ColdBox box = boxMapper.selectById(boxId);
        if (box == null) {
            throw new BusinessException(404, "冷链箱不存在: " + boxId);
        }
        ZoneId zone = ZoneId.of("UTC");

        TimelineResponse resp = new TimelineResponse();
        resp.setBoxId(boxId);
        resp.setBoxCode(box.getBoxCode());

        List<Shipment> shipments = shipmentMapper.selectList(Wrappers.<Shipment>lambdaQuery()
                .eq(Shipment::getBoxId, boxId)
                .orderByAsc(Shipment::getStartTimeUtc));
        if (shipments.isEmpty()) {
            // 即便没有转运单，也展示事件与异常
            appendEventsAndAnomalies(resp, boxId, shipments, -1, zone);
            resp.getItems().sort(Comparator.comparing(TimelineResponse.Item::getTimeUtc,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            return resp;
        }

        for (int si = 0; si < shipments.size(); si++) {
            Shipment shipment = shipments.get(si);
            if (resp.getShipmentNo() == null) {
                resp.setShipmentNo(shipment.getShipmentNo());
            }
            List<TransportNode> nodes = nodeMapper.selectList(Wrappers.<TransportNode>lambdaQuery()
                    .eq(TransportNode::getShipmentId, shipment.getId())
                    .orderByAsc(TransportNode::getSeq));
            for (TransportNode n : nodes) {
                TimelineResponse.Item item = new TimelineResponse.Item();
                item.setKind("NODE");
                item.setTimeUtc(n.getActualTimeUtc() != null ? n.getActualTimeUtc() : n.getPlannedTimeUtc());
                item.setTimeLocal(item.getTimeUtc());
                item.setTitle(n.getSeq() + ". " + n.getNodeName());
                item.setType(n.getNodeType());
                item.setStatus(n.getActualTimeUtc() != null ? "已完成"
                        : (n.getPlannedTimeUtc() != null ? "待执行(仅计划时间)" : "未安排"));
                item.setDetail("节点 " + n.getNodeCode()
                        + (n.getLocation() != null ? " · " + n.getLocation() : "")
                        + (n.getOperator() != null ? " · " + n.getOperator() : "")
                        + (n.getPlannedTimeUtc() != null ? " · 计划 " + n.getPlannedTimeUtc() + "Z" : "")
                        + (n.getActualTimeUtc() != null ? " · 实际 " + n.getActualTimeUtc() + "Z" : ""));
                resp.getItems().add(item);
            }
            appendEventsAndAnomalies(resp, boxId, shipments, si, zone);
        }
        resp.getItems().sort(Comparator.comparing(TimelineResponse.Item::getTimeUtc,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return resp;
    }

    private void appendEventsAndAnomalies(TimelineResponse resp, Long boxId,
                                          List<Shipment> shipments, int shipmentIndex, ZoneId zone) {
        List<BoxEvent> events = eventMapper.selectList(Wrappers.<BoxEvent>lambdaQuery()
                .eq(BoxEvent::getBoxId, boxId)
                .orderByAsc(BoxEvent::getEventTimeUtc));
        for (BoxEvent e : events) {
            if (!belongsToShipment(e.getEventTimeUtc(), shipments, shipmentIndex)) {
                continue;
            }
            TimelineResponse.Item item = new TimelineResponse.Item();
            item.setKind("EVENT");
            item.setTimeUtc(e.getEventTimeUtc());
            item.setTimeLocal(e.getEventTimeUtc());
            item.setTitle("OPEN".equals(e.getEventType()) ? "开箱" : "关门");
            item.setType(e.getEventType());
            item.setStatus(e.getNote());
            item.setDetail(e.getNote() != null ? e.getNote() : "箱门事件");
            resp.getItems().add(item);
        }

        List<Anomaly> anomalies = anomalyMapper.selectList(Wrappers.<Anomaly>lambdaQuery()
                .eq(Anomaly::getBoxId, boxId)
                .orderByAsc(Anomaly::getStartTimeUtc));
        for (Anomaly a : anomalies) {
            if (!belongsToShipment(a.getStartTimeUtc(), shipments, shipmentIndex)) {
                continue;
            }
            TimelineResponse.Item item = new TimelineResponse.Item();
            item.setKind("ANOMALY");
            item.setTimeUtc(a.getStartTimeUtc());
            item.setTimeLocal(a.getStartTimeUtc());
            item.setTitle(typeLabel(a.getType()));
            item.setType(a.getType());
            item.setStatus(a.getStatus());
            item.setDetail(a.getDescription());
            resp.getItems().add(item);
        }
    }

    /**
     * 无转运单（shipmentIndex=-1）：全部归属；
     * 否则归属到第一个窗口包含该时刻的转运单，都不包含时挂到最后一个转运单，保证不丢事件。
     */
    private boolean belongsToShipment(java.time.LocalDateTime t, List<Shipment> shipments, int shipmentIndex) {
        if (shipmentIndex < 0) {
            return true;
        }
        for (int k = 0; k < shipments.size(); k++) {
            if (withinShipment(t, shipments.get(k))) {
                return k == shipmentIndex;
            }
        }
        return shipmentIndex == shipments.size() - 1;
    }

    private boolean withinShipment(java.time.LocalDateTime t, Shipment s) {
        if (t == null) {
            return false;
        }
        if (t.isBefore(s.getStartTimeUtc())) {
            return false;
        }
        return s.getEndTimeUtc() == null || !t.isAfter(s.getEndTimeUtc());
    }

    private String typeLabel(String type) {
        return switch (type) {
            case "TEMP_EXCURSION" -> "连续超温";
            case "OFFLINE" -> "传感器离线";
            case "INTERVAL" -> "采样间隔异常";
            case "HASH_BROKEN" -> "哈希链断裂";
            default -> type;
        };
    }
}

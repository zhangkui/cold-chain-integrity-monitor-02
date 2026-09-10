package com.coldchain.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.coldchain.common.TimeUtils;
import com.coldchain.domain.dto.ingest.NodeItem;
import com.coldchain.domain.entity.BoxEvent;
import com.coldchain.domain.entity.CalibrationRecord;
import com.coldchain.domain.entity.ColdBox;
import com.coldchain.domain.entity.Device;
import com.coldchain.domain.entity.Shipment;
import com.coldchain.domain.entity.TransportNode;
import com.coldchain.mapper.BoxEventMapper;
import com.coldchain.mapper.CalibrationRecordMapper;
import com.coldchain.mapper.ShipmentMapper;
import com.coldchain.mapper.TransportNodeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 事件 / 节点 / 校准的逐行独立事务写入（REQUIRES_NEW）。
 * 每行各自提交，单行失败只回滚该行，不影响同批次其它行。
 */
@Component
@RequiredArgsConstructor
public class IngestRowWriter {

    private final BoxEventMapper eventMapper;
    private final ShipmentMapper shipmentMapper;
    private final TransportNodeMapper nodeMapper;
    private final CalibrationRecordMapper calibrationMapper;

    /** @return 事件 id；幂等重复返回 null */
    @Transactional(propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = DuplicateKeyException.class)
    public Long insertEvent(BoxEvent event) {
        Long exists = eventMapper.selectCount(Wrappers.<BoxEvent>lambdaQuery()
                .eq(BoxEvent::getIdemKey, event.getIdemKey()));
        if (exists != null && exists > 0) {
            return null;
        }
        try {
            eventMapper.insert(event);
            return event.getId();
        } catch (DuplicateKeyException e) {
            return null;
        }
    }

    public record NodeUpsert(long nodeId, boolean duplicate, long shipmentId) {
    }

    /** 转运单不存在则创建，节点按 (shipment, seq) upsert，SIGN 时完结转运单 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public NodeUpsert upsertNode(ColdBox box, NodeItem item) {
        Instant planned = item.getPlannedTime() == null || item.getPlannedTime().isBlank()
                ? null : TimeUtils.parseToInstant(item.getPlannedTime(), item.getTimezone());
        Instant actual = item.getActualTime() == null || item.getActualTime().isBlank()
                ? null : TimeUtils.parseToInstant(item.getActualTime(), item.getTimezone());
        String nodeType = item.getNodeType() == null || item.getNodeType().isBlank()
                ? "TRANSIT" : item.getNodeType().trim().toUpperCase();

        Shipment shipment = shipmentMapper.selectOne(Wrappers.<Shipment>lambdaQuery()
                .eq(Shipment::getShipmentNo, item.getShipmentNo()));
        if (shipment == null) {
            shipment = new Shipment();
            shipment.setShipmentNo(item.getShipmentNo());
            shipment.setBoxId(box.getId());
            shipment.setStatus("RUNNING");
            Instant start = actual != null ? actual : planned;
            shipment.setStartTimeUtc(start == null ? LocalDateTime.now(ZoneOffset.UTC)
                    : TimeUtils.toUtc(start));
            shipmentMapper.insert(shipment);
        }

        TransportNode node = nodeMapper.selectOne(Wrappers.<TransportNode>lambdaQuery()
                .eq(TransportNode::getShipmentId, shipment.getId())
                .eq(TransportNode::getSeq, item.getSeq()));
        boolean duplicate = node != null;
        if (node == null) {
            node = new TransportNode();
            node.setShipmentId(shipment.getId());
            node.setSeq(item.getSeq());
        }
        node.setNodeCode(item.getNodeCode());
        node.setNodeName(item.getNodeName());
        node.setNodeType(nodeType);
        node.setPlannedTimeUtc(planned == null ? null : TimeUtils.toUtc(planned));
        node.setActualTimeUtc(actual == null ? null : TimeUtils.toUtc(actual));
        node.setOperator(item.getOperator());
        node.setLocation(item.getLocation());
        if (duplicate) {
            nodeMapper.updateById(node);
        } else {
            nodeMapper.insert(node);
        }

        if ("SIGN".equals(nodeType)) {
            Shipment patch = new Shipment();
            patch.setId(shipment.getId());
            patch.setStatus("FINISHED");
            patch.setEndTimeUtc(actual == null ? LocalDateTime.now(ZoneOffset.UTC) : TimeUtils.toUtc(actual));
            shipmentMapper.updateById(patch);
        }
        return new NodeUpsert(node.getId(), duplicate, shipment.getId());
    }

    /** @return 校准记录 id；证书编号重复返回 null */
    @Transactional(propagation = Propagation.REQUIRES_NEW,
            noRollbackFor = DuplicateKeyException.class)
    public Long insertCalibration(CalibrationRecord record) {
        Long exists = calibrationMapper.selectCount(Wrappers.<CalibrationRecord>lambdaQuery()
                .eq(CalibrationRecord::getCertNo, record.getCertNo()));
        if (exists != null && exists > 0) {
            return null;
        }
        try {
            calibrationMapper.insert(record);
            return record.getId();
        } catch (DuplicateKeyException e) {
            return null;
        }
    }
}

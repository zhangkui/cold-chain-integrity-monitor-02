package com.coldchain.service.assessment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.coldchain.common.BusinessException;
import com.coldchain.domain.dto.assessment.AssessmentBadge;
import com.coldchain.domain.dto.assessment.AssessmentMetric;
import com.coldchain.domain.dto.assessment.AssessmentRisk;
import com.coldchain.domain.dto.assessment.AssessmentSummary;
import com.coldchain.domain.dto.assessment.AssessmentView;
import com.coldchain.domain.dto.assessment.DataBoundary;
import com.coldchain.domain.dto.assessment.RuleSnapshot;
import com.coldchain.domain.entity.Anomaly;
import com.coldchain.domain.entity.BoxAssessment;
import com.coldchain.domain.entity.BoxEvent;
import com.coldchain.domain.entity.ColdBox;
import com.coldchain.domain.entity.Device;
import com.coldchain.domain.entity.Shipment;
import com.coldchain.domain.entity.TemperatureSample;
import com.coldchain.domain.entity.TransportNode;
import com.coldchain.domain.enums.AssessmentConclusion;
import com.coldchain.mapper.AnomalyMapper;
import com.coldchain.mapper.BoxAssessmentMapper;
import com.coldchain.mapper.BoxEventMapper;
import com.coldchain.mapper.ColdBoxMapper;
import com.coldchain.mapper.DeviceMapper;
import com.coldchain.mapper.ShipmentMapper;
import com.coldchain.mapper.TemperatureSampleMapper;
import com.coldchain.mapper.TransportNodeMapper;
import com.coldchain.service.AuditService;
import com.coldchain.service.RedisLockService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 箱体综合评估编排：取数（规则/采样/异常/转运/事件）→ 只读链校验 →
 * {@link AssessmentEngine} 计算 → box_assessment 版本化持久化（仅追加）。
 *
 * 并发：同箱体串行（Redis 锁，宕机时降级进程锁），版本号取 MAX(version)+1，
 * (box_id, version) 唯一约束兜底，冲突时重试一次。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssessmentService {

    private static final int SUMMARY_MAX_LEN = 500;

    private final ColdBoxMapper boxMapper;
    private final DeviceMapper deviceMapper;
    private final TemperatureSampleMapper sampleMapper;
    private final AnomalyMapper anomalyMapper;
    private final ShipmentMapper shipmentMapper;
    private final TransportNodeMapper nodeMapper;
    private final BoxEventMapper eventMapper;
    private final BoxAssessmentMapper assessmentMapper;
    private final AuditService auditService;
    private final RedisLockService lockService;
    private final ObjectMapper objectMapper;

    /**
     * 触发一次评估，永远生成新版本（历史不覆盖）。
     * 数据不足时仍持久化 UNASSESSABLE 版本作为审计事实，并抛 422 供调用方区分；
     * 箱体不存在抛 404；其它异常抛 500 并写 FAILED 审计。
     */
    public AssessmentView generate(Long boxId) {
        ColdBox box = boxMapper.selectById(boxId);
        if (box == null) {
            auditService.log("BOX_ASSESSMENT", boxId, "ASSESS_GENERATE_FAILED",
                    Map.of("reason", "BOX_NOT_FOUND"));
            throw new BusinessException(404, "BOX_NOT_FOUND", "冷链箱不存在: " + boxId);
        }
        String operator = currentOperator();
        try (RedisLockService.AutoCloseableLock ignored = lockService.tryLock("assessment:box:" + boxId)) {
            AssessmentEngine.Output out = compute(box);
            BoxAssessment saved = persistVersion(box, out, operator);
            AssessmentView view = toView(saved, box);
            auditService.log("BOX_ASSESSMENT", saved.getId(), "ASSESS_GENERATE", Map.of(
                    "boxId", boxId,
                    "boxCode", box.getBoxCode(),
                    "version", saved.getVersion(),
                    "conclusion", saved.getConclusion(),
                    "chainStatus", saved.getChainStatus(),
                    "riskCount", out.getRisks().size()));

            if (out.getConclusion() == AssessmentConclusion.UNASSESSABLE) {
                // 明确的“不可评估”结果已落库；用 422 与“箱体不存在/内部失败”区分
                throw new BusinessException(422, "DATA_INSUFFICIENT",
                        "数据不足，评估结论为不可评估：" + out.getSummary(), view);
            }
            return view;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("箱体 {} 评估生成失败", boxId, e);
            auditService.log("BOX_ASSESSMENT", boxId, "ASSESS_GENERATE_FAILED", Map.of(
                    "boxCode", box.getBoxCode(),
                    "error", e.getClass().getSimpleName(),
                    "message", String.valueOf(e.getMessage())));
            throw new BusinessException(500, "ASSESSMENT_FAILED",
                    "评估生成失败，请稍后重试: " + e.getMessage());
        }
    }

    /** 最新评估；箱体不存在 404，从未评估 404(ASSESSMENT_NOT_FOUND) */
    public AssessmentView getLatest(Long boxId) {
        ColdBox box = boxMapper.selectById(boxId);
        if (box == null) {
            auditService.log("BOX_ASSESSMENT", boxId, "ASSESS_VIEW_FAILED",
                    Map.of("reason", "BOX_NOT_FOUND"));
            throw new BusinessException(404, "BOX_NOT_FOUND", "冷链箱不存在: " + boxId);
        }
        BoxAssessment row = latestRow(boxId);
        if (row == null) {
            auditService.log("BOX_ASSESSMENT", boxId, "ASSESS_VIEW_FAILED",
                    Map.of("boxCode", box.getBoxCode(), "reason", "ASSESSMENT_NOT_FOUND"));
            throw new BusinessException(404, "ASSESSMENT_NOT_FOUND", "该箱体尚无评估记录: " + boxId);
        }
        auditView(row, box.getBoxCode());
        return toView(row, box);
    }

    /** 指定历史版本；版本不存在 404 */
    public AssessmentView getVersion(Long boxId, Integer version) {
        ColdBox box = boxMapper.selectById(boxId);
        if (box == null) {
            auditService.log("BOX_ASSESSMENT", boxId, "ASSESS_VIEW_FAILED",
                    Map.of("reason", "BOX_NOT_FOUND", "version", version == null ? -1 : version));
            throw new BusinessException(404, "BOX_NOT_FOUND", "冷链箱不存在: " + boxId);
        }
        if (version == null || version < 1) {
            throw new BusinessException(400, "VALIDATION_FAILED", "版本号必须为正整数");
        }
        BoxAssessment row = assessmentMapper.selectOne(Wrappers.<BoxAssessment>lambdaQuery()
                .eq(BoxAssessment::getBoxId, boxId)
                .eq(BoxAssessment::getVersion, version));
        if (row == null) {
            auditService.log("BOX_ASSESSMENT", boxId, "ASSESS_VIEW_FAILED",
                    Map.of("boxCode", box.getBoxCode(), "reason", "ASSESSMENT_VERSION_NOT_FOUND",
                            "version", version));
            throw new BusinessException(404, "ASSESSMENT_VERSION_NOT_FOUND",
                    "评估版本不存在: 箱体 " + boxId + " v" + version);
        }
        auditView(row, box.getBoxCode());
        return toView(row, box);
    }

    /** 历史版本列表（新版本在前） */
    public List<AssessmentSummary> listVersions(Long boxId) {
        ColdBox box = boxMapper.selectById(boxId);
        if (box == null) {
            auditService.log("BOX_ASSESSMENT", boxId, "ASSESS_VIEW_FAILED",
                    Map.of("reason", "BOX_NOT_FOUND"));
            throw new BusinessException(404, "BOX_NOT_FOUND", "冷链箱不存在: " + boxId);
        }
        List<BoxAssessment> rows = assessmentMapper.selectList(Wrappers.<BoxAssessment>lambdaQuery()
                .eq(BoxAssessment::getBoxId, boxId)
                .orderByDesc(BoxAssessment::getVersion));
        auditService.log("BOX_ASSESSMENT", boxId, "ASSESS_VIEW_HISTORY",
                Map.of("boxCode", box.getBoxCode(), "versions", rows.size()));
        return rows.stream().map(this::toSummary).toList();
    }

    /** 批量取多个箱体的最新评估（列表页用，一条 SQL） */
    public Map<Long, BoxAssessment> latestByBoxIds(Collection<Long> boxIds) {
        if (boxIds == null || boxIds.isEmpty()) {
            return Map.of();
        }
        List<BoxAssessment> all = assessmentMapper.selectList(Wrappers.<BoxAssessment>lambdaQuery()
                .in(BoxAssessment::getBoxId, boxIds)
                .orderByDesc(BoxAssessment::getVersion));
        Map<Long, BoxAssessment> latest = new HashMap<>();
        for (BoxAssessment a : all) {
            latest.putIfAbsent(a.getBoxId(), a); // 版本倒序，首次即最新
        }
        return latest;
    }

    /** 列表/详情用的最新评估徽标（含一条最主要风险），批量 */
    public Map<Long, AssessmentBadge> latestBadges(Collection<Long> boxIds) {
        Map<Long, BoxAssessment> rows = latestByBoxIds(boxIds);
        Map<Long, AssessmentBadge> badges = new HashMap<>();
        rows.forEach((boxId, row) -> badges.put(boxId, toBadge(row)));
        return badges;
    }

    /** 单个箱体的最新评估徽标；无记录返回 null */
    public AssessmentBadge latestBadge(Long boxId) {
        BoxAssessment row = latestRow(boxId);
        return row == null ? null : toBadge(row);
    }

    private AssessmentBadge toBadge(BoxAssessment row) {
        AssessmentBadge badge = new AssessmentBadge();
        badge.setId(row.getId());
        badge.setVersion(row.getVersion());
        badge.setConclusion(row.getConclusion());
        badge.setConclusionLabel(conclusionLabel(row.getConclusion()));
        badge.setChainStatus(row.getChainStatus());
        badge.setSummary(row.getSummary());
        badge.setGeneratedAt(row.getGeneratedAt());
        List<AssessmentRisk> risks = readList(row.getPrimaryRisks(), AssessmentRisk.class);
        risks.stream()
                .min(java.util.Comparator.comparingInt((AssessmentRisk r) ->
                        "CRITICAL".equals(r.getSeverity()) ? 0
                                : "WARN".equals(r.getSeverity()) ? 1 : 2))
                .ifPresent(badge::setTopRisk);
        return badge;
    }

    public BoxAssessment latestRow(Long boxId) {
        return assessmentMapper.selectOne(Wrappers.<BoxAssessment>lambdaQuery()
                .eq(BoxAssessment::getBoxId, boxId)
                .orderByDesc(BoxAssessment::getVersion)
                .last("limit 1"));
    }

    // ============================ 计算与持久化 ============================

    private AssessmentEngine.Output compute(ColdBox box) {
        Device device = box.getDeviceId() == null ? null : deviceMapper.selectById(box.getDeviceId());
        List<TemperatureSample> samples = sampleMapper.selectList(Wrappers.<TemperatureSample>lambdaQuery()
                .eq(TemperatureSample::getBoxId, box.getId())
                .orderByAsc(TemperatureSample::getSeq));
        List<Anomaly> anomalies = anomalyMapper.selectList(Wrappers.<Anomaly>lambdaQuery()
                .eq(Anomaly::getBoxId, box.getId()));
        List<Shipment> shipments = shipmentMapper.selectList(Wrappers.<Shipment>lambdaQuery()
                .eq(Shipment::getBoxId, box.getId())
                .orderByAsc(Shipment::getStartTimeUtc));
        List<Long> shipmentIds = shipments.stream().map(Shipment::getId).toList();
        List<TransportNode> nodes = shipmentIds.isEmpty() ? List.of()
                : nodeMapper.selectList(Wrappers.<TransportNode>lambdaQuery()
                        .in(TransportNode::getShipmentId, shipmentIds)
                        .orderByAsc(TransportNode::getSeq));
        List<BoxEvent> events = eventMapper.selectList(Wrappers.<BoxEvent>lambdaQuery()
                .eq(BoxEvent::getBoxId, box.getId()));

        // 只读链校验：评估绝不写异常表；未绑定设备/无采样 => 不校验（守卫会判不可评估）
        AssessmentEngine.ChainResult chain = null;
        if (device != null && !samples.isEmpty()) {
            List<ChainVerifier.Break> breaks = ChainVerifier.verify(samples);
            chain = new AssessmentEngine.ChainResult(
                    breaks.isEmpty() ? "INTACT" : "BROKEN",
                    samples.size(),
                    breaks.stream().map(ChainVerifier.Break::seq).distinct().sorted().toList());
        }

        Instant now = Instant.now();
        return AssessmentEngine.evaluate(AssessmentEngine.Input.builder()
                .box(box).device(device).samples(samples).chain(chain)
                .anomalies(anomalies).shipments(shipments).nodes(nodes).events(events)
                .evaluatedAt(now)
                .build());
    }

    private BoxAssessment persistVersion(ColdBox box, AssessmentEngine.Output out, String operator) {
        Integer maxVersion = assessmentMapper.selectList(Wrappers.<BoxAssessment>lambdaQuery()
                        .eq(BoxAssessment::getBoxId, box.getId()))
                .stream().map(BoxAssessment::getVersion).max(Integer::compareTo).orElse(0);

        BoxAssessment row = new BoxAssessment();
        row.setBoxId(box.getId());
        row.setVersion(maxVersion + 1);
        row.setConclusion(out.getConclusion().name());
        row.setChainStatus(out.getChainStatus());
        row.setChainChecked(out.getChainChecked());
        row.setSummary(truncate(out.getSummary()));
        row.setPrimaryRisks(json(out.getRisks()));
        row.setMetricsJson(json(out.getMetrics()));
        row.setRuleSnapshotJson(json(out.getRuleSnapshot()));
        row.setDataBoundaryJson(json(out.getBoundary()));
        row.setRuleVersion(AssessmentEngine.RULE_VERSION);
        row.setGeneratedBy(operator);
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        row.setGeneratedAt(nowUtc);
        try {
            assessmentMapper.insert(row);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            // 唯一约束兜底（极端并发）：重取最大版本再插一次
            log.warn("箱体 {} 评估版本 {} 冲突，重试版本分配", box.getId(), row.getVersion());
            Integer retryMax = assessmentMapper.selectList(Wrappers.<BoxAssessment>lambdaQuery()
                            .eq(BoxAssessment::getBoxId, box.getId()))
                    .stream().map(BoxAssessment::getVersion).max(Integer::compareTo).orElse(0);
            row.setVersion(retryMax + 1);
            assessmentMapper.insert(row);
        }
        return row;
    }

    // ============================ 视图反序列化 ============================

    public AssessmentView toView(BoxAssessment row, ColdBox box) {
        AssessmentView v = new AssessmentView();
        v.setId(row.getId());
        v.setBoxId(row.getBoxId());
        v.setBoxCode(box == null ? null : box.getBoxCode());
        v.setBatchNo(box == null ? null : box.getBatchNo());
        v.setVersion(row.getVersion());
        v.setConclusion(row.getConclusion());
        v.setConclusionLabel(conclusionLabel(row.getConclusion()));
        v.setChainStatus(row.getChainStatus());
        v.setChainChecked(row.getChainChecked());
        v.setSummary(row.getSummary());
        v.setPrimaryRisks(readList(row.getPrimaryRisks(), AssessmentRisk.class));
        v.setMetrics(readList(row.getMetricsJson(), AssessmentMetric.class));
        v.setRuleSnapshot(read(row.getRuleSnapshotJson(), RuleSnapshot.class));
        v.setDataBoundary(read(row.getDataBoundaryJson(), DataBoundary.class));
        v.setRuleVersion(row.getRuleVersion());
        v.setGeneratedBy(row.getGeneratedBy());
        v.setGeneratedAt(row.getGeneratedAt());
        return v;
    }

    private AssessmentSummary toSummary(BoxAssessment row) {
        AssessmentSummary s = new AssessmentSummary();
        s.setId(row.getId());
        s.setBoxId(row.getBoxId());
        s.setVersion(row.getVersion());
        s.setConclusion(row.getConclusion());
        s.setConclusionLabel(conclusionLabel(row.getConclusion()));
        s.setChainStatus(row.getChainStatus());
        s.setSummary(row.getSummary());
        s.setGeneratedBy(row.getGeneratedBy());
        s.setGeneratedAt(row.getGeneratedAt());
        return s;
    }

    public static String conclusionLabel(String c) {
        return switch (c) {
            case "PASS" -> "合格";
            case "FAIL" -> "不合格";
            case "NEEDS_REVIEW" -> "需复核";
            case "UNASSESSABLE" -> "不可评估";
            default -> c;
        };
    }

    private void auditView(BoxAssessment row, String boxCode) {
        auditService.log("BOX_ASSESSMENT", row.getId(), "ASSESS_VIEW", Map.of(
                "boxId", row.getBoxId(),
                "boxCode", boxCode == null ? "" : boxCode,
                "version", row.getVersion(),
                "conclusion", row.getConclusion()));
    }

    private String currentOperator() {
        String operator = MDC.get("operator");
        return operator == null || operator.isBlank() ? "system" : operator;
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new BusinessException(500, "SERIALIZE_FAILED", "评估结果序列化失败: " + e.getMessage());
        }
    }

    private <T> List<T> readList(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, type));
        } catch (JsonProcessingException e) {
            log.warn("评估 JSON 反序列化失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            log.warn("评估 JSON 反序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= SUMMARY_MAX_LEN ? s : s.substring(0, SUMMARY_MAX_LEN) + "…";
    }
}

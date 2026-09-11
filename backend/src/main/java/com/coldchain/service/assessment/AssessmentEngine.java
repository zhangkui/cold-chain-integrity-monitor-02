package com.coldchain.service.assessment;

import com.coldchain.domain.dto.assessment.AssessmentMetric;
import com.coldchain.domain.dto.assessment.AssessmentRisk;
import com.coldchain.domain.dto.assessment.DataBoundary;
import com.coldchain.domain.dto.assessment.RuleSnapshot;
import com.coldchain.domain.entity.Anomaly;
import com.coldchain.domain.entity.BoxEvent;
import com.coldchain.domain.entity.ColdBox;
import com.coldchain.domain.entity.Device;
import com.coldchain.domain.entity.Shipment;
import com.coldchain.domain.entity.TemperatureSample;
import com.coldchain.domain.entity.TransportNode;
import com.coldchain.domain.enums.AssessmentConclusion;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 箱体综合评估引擎（纯计算，不触碰数据库/HTTP，便于规则级单测）。
 *
 * 综合六个维度：箱体温控规则、有效冷链时长、未驳回异常、哈希链校验、采样覆盖、转运时间线。
 *
 * 结论策略（评估规则 v1）：
 *  - UNASSESSABLE（不可评估，绝不静默判合格）：无采样 / 无绑定设备 / 规则上下限反向；
 *  - NEEDS_REVIEW（需人工复核）：哈希链断裂或未校验、采样时间倒序、存在未驳回 HASH_BROKEN、
 *    存在 OPEN 的 WARN 异常；
 *  - FAIL（不合格）：有效冷链占比 &lt; 80%、存在 CONFIRMED 异常、存在 OPEN CRITICAL 异常；
 *  - PASS（合格）：链完整、无阻断/复核风险、有效冷链占比 ≥ 95%（其余为带提示的合格）；
 *  - 已 REJECTED 的异常一律不参与扣减与结论，仅在指标中展示数量。
 */
@Slf4j
public final class AssessmentEngine {

    public static final String RULE_VERSION = "assessment-v1";
    public static final BigDecimal FAIL_VALID_RATIO = new BigDecimal("0.80");
    public static final BigDecimal WARN_VALID_RATIO = new BigDecimal("0.95");
    /** 转运节点实际晚于计划超过该秒数记为延误 */
    public static final int NODE_DELAY_TOLERANCE_SECONDS = 3600;

    private AssessmentEngine() {
    }

    /** 哈希链校验结果（由调用方逐环重算得出，引擎本身不读库） */
    public record ChainResult(String status, int checked, List<Long> breakSeqs) {
        public boolean intact() {
            return "INTACT".equals(status);
        }
    }

    @Getter
    @Builder(builderClassName = "Builder")
    public static class Input {
        private ColdBox box;
        private Device device;
        /** 箱体采样，按 sampleTimeUtc 升序 */
        private List<TemperatureSample> samples;
        private ChainResult chain;
        /** 箱体全部异常（含已驳回） */
        private List<Anomaly> anomalies;
        private List<Shipment> shipments;
        private List<TransportNode> nodes;
        private List<BoxEvent> events;
        private Instant evaluatedAt;
    }

    @Getter
    public static class Output {
        private final AssessmentConclusion conclusion;
        private final String chainStatus;
        private final int chainChecked;
        private final String summary;
        private final List<AssessmentRisk> risks;
        private final List<AssessmentMetric> metrics;
        private final RuleSnapshot ruleSnapshot;
        private final DataBoundary boundary;

        Output(AssessmentConclusion conclusion, String chainStatus, int chainChecked, String summary,
               List<AssessmentRisk> risks, List<AssessmentMetric> metrics,
               RuleSnapshot ruleSnapshot, DataBoundary boundary) {
            this.conclusion = conclusion;
            this.chainStatus = chainStatus;
            this.chainChecked = chainChecked;
            this.summary = summary;
            this.risks = risks;
            this.metrics = metrics;
            this.ruleSnapshot = ruleSnapshot;
            this.boundary = boundary;
        }
    }

    public static Output evaluate(Input in) {
        RuleSnapshot rule = snapshotRule(in.getBox());
        DataBoundary boundary = buildBoundary(in);
        List<AssessmentRisk> risks = new ArrayList<>();
        List<AssessmentMetric> metrics = new ArrayList<>();
        String chainStatus = in.getChain() == null ? "UNVERIFIED" : in.getChain().status();

        // ---------- 守卫：数据不足 / 规则无效 -> UNASSESSABLE ----------
        List<AssessmentRisk> guards = new ArrayList<>();
        if (in.getDevice() == null) {
            guards.add(risk("NO_DEVICE", "CRITICAL", "箱体未绑定采集设备，无法取得温控数据", true, "RULE", null, null));
        }
        boolean ruleReversed = in.getBox().getTempMin() == null || in.getBox().getTempMax() == null
                || in.getBox().getTempMin().compareTo(in.getBox().getTempMax()) >= 0;
        if (ruleReversed) {
            guards.add(risk("RULE_REVERSED", "CRITICAL",
                    "温控规则上下限反向或缺失（下限 " + in.getBox().getTempMin() + " ≥ 上限 "
                            + in.getBox().getTempMax() + "），规则不可用", true, "RULE", null, null));
        }
        boolean noSamples = in.getSamples() == null || in.getSamples().isEmpty();
        if (noSamples) {
            guards.add(risk("NO_SAMPLES", "CRITICAL", "箱体无任何温度采样，评估数据不足", true, "SAMPLE", null, null));
        }
        if (!guards.isEmpty()) {
            metrics.add(ruleMetric(in.getBox(), ruleReversed));
            int n = noSamples ? 0 : (in.getSamples() == null ? 0 : in.getSamples().size());
            metrics.add(new AssessmentMetric("coverage", "采样覆盖",
                    n + " 点", null, noSamples ? "BAD" : "WARN",
                    noSamples ? "无采样数据" : "存在采样数据，但前置条件不满足，评估中止"));
            return new Output(AssessmentConclusion.UNASSESSABLE, chainStatus, 0,
                    "不可评估：" + joinMessages(guards), List.copyOf(guards),
                    List.copyOf(metrics), rule, boundary);
        }

        List<TemperatureSample> samples = in.getSamples();
        List<Anomaly> anomalies = in.getAnomalies() == null ? List.of() : in.getAnomalies();
        ChainResult chain = in.getChain();

        // ---------- 维度 1：温控规则 ----------
        metrics.add(ruleMetric(in.getBox(), false));

        // ---------- 维度 2：采样覆盖 + 时间倒序 ----------
        Instant first = samples.get(0).getSampleTimeUtc().toInstant(ZoneOffset.UTC);
        Instant last = samples.get(samples.size() - 1).getSampleTimeUtc().toInstant(ZoneOffset.UTC);
        long covered = Duration.between(first, last).getSeconds();
        int reorderCount = 0;
        for (int i = 1; i < samples.size(); i++) {
            Instant prev = samples.get(i - 1).getSampleTimeUtc().toInstant(ZoneOffset.UTC);
            Instant cur = samples.get(i).getSampleTimeUtc().toInstant(ZoneOffset.UTC);
            if (cur.isBefore(prev)) {
                reorderCount++;
            }
        }
        boolean reordered = reorderCount > 0;
        metrics.add(new AssessmentMetric("coverage", "采样覆盖",
                samples.size() + " 点 / " + fmt(covered), null,
                reordered ? "WARN" : "OK",
                "首点 " + first + "，末点 " + last
                        + (reordered ? "；检测到 " + reorderCount + " 处采样时间倒序" : "")));
        if (reordered) {
            risks.add(risk("SAMPLE_TIME_REORDERED", "CRITICAL",
                    "存在 " + reorderCount + " 处采样时间倒序，数据时序不可信，需人工复核",
                    false, "SAMPLE", null, null));
        }

        // ---------- 维度 3：未驳回异常 + 有效冷链时长 ----------
        List<Anomaly> effective = anomalies.stream()
                .filter(a -> !"REJECTED".equals(a.getStatus())).toList();
        List<Anomaly> rejected = anomalies.stream()
                .filter(a -> "REJECTED".equals(a.getStatus())).toList();
        List<Anomaly> deduct = effective.stream()
                .filter(a -> "TEMP_EXCURSION".equals(a.getType()) || "OFFLINE".equals(a.getType())).toList();

        List<long[]> intervals = new ArrayList<>();
        long excursion = 0;
        long offline = 0;
        for (Anomaly a : deduct) {
            if ("TEMP_EXCURSION".equals(a.getType())) {
                excursion += nz(a.getDurationSeconds());
            } else {
                offline += nz(a.getDurationSeconds());
            }
            intervals.add(new long[]{
                    a.getStartTimeUtc().toEpochSecond(ZoneOffset.UTC),
                    a.getEndTimeUtc().toEpochSecond(ZoneOffset.UTC)});
        }
        long deducted = com.coldchain.common.IntervalUtils.unionLength(intervals);
        long valid = Math.max(0, covered - deducted);
        BigDecimal ratio = covered <= 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(valid).divide(BigDecimal.valueOf(covered), 4, RoundingMode.HALF_UP);

        String ratioText = ratio.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP) + "%";
        String validStatus = ratio.compareTo(WARN_VALID_RATIO) >= 0 ? "OK"
                : ratio.compareTo(FAIL_VALID_RATIO) >= 0 ? "WARN" : "BAD";
        metrics.add(new AssessmentMetric("validColdChain", "有效冷链时长",
                fmt(valid), null, validStatus,
                "覆盖 " + fmt(covered) + "，扣减并集 " + fmt(deducted)
                        + "（超温 " + fmt(excursion) + "、离线 " + fmt(offline) + "），占比 " + ratioText));
        if (ratio.compareTo(FAIL_VALID_RATIO) < 0) {
            risks.add(risk("VALID_CHAIN_TOO_LOW", "CRITICAL",
                    "有效冷链占比仅 " + ratioText + "，低于不合格阈值 "
                            + FAIL_VALID_RATIO.multiply(BigDecimal.valueOf(100)) + "%",
                    true, "SAMPLE", null, null));
        } else if (ratio.compareTo(WARN_VALID_RATIO) < 0) {
            risks.add(risk("VALID_CHAIN_LOW", "WARN",
                    "有效冷链占比 " + ratioText + "，低于关注阈值 "
                            + WARN_VALID_RATIO.multiply(BigDecimal.valueOf(100)) + "%",
                    false, "SAMPLE", null, null));
        }

        // 异常逐项转风险（未驳回才影响结论）；链已校验时 HASH_BROKEN 由链维度统一呈现，避免重复
        int openWarn = 0;
        for (Anomaly a : effective) {
            if (chain != null && "HASH_BROKEN".equals(a.getType())) {
                continue;
            }
            risks.add(anomalyRisk(a));
            if ("OPEN".equals(a.getStatus()) && "WARN".equals(a.getSeverity())
                    && !"HASH_BROKEN".equals(a.getType())) {
                openWarn++;
            }
        }
        String anomalyDetail = "未驳回 " + effective.size() + " 条"
                + (rejected.isEmpty() ? "" : "，已驳回 " + rejected.size() + " 条（不参与结论）");
        boolean anomalyBad = effective.stream().anyMatch(a ->
                "CONFIRMED".equals(a.getStatus())
                        || ("OPEN".equals(a.getStatus()) && "CRITICAL".equals(a.getSeverity())));
        metrics.add(new AssessmentMetric("anomaly", "未驳回异常",
                effective.size() + " 条", null,
                effective.isEmpty() ? "OK" : anomalyBad ? "BAD" : "WARN", anomalyDetail));

        // ---------- 维度 4：哈希链 ----------
        if (chain == null) {
            risks.add(risk("CHAIN_UNVERIFIED", "CRITICAL",
                    "哈希链未执行校验，完整性未知，需复核后再下结论", false, "SAMPLE", null, null));
            metrics.add(new AssessmentMetric("chain", "哈希链", "未校验", null, "WARN",
                    "评估时未获得链校验结果"));
        } else if (!chain.intact()) {
            long firstBreak = chain.breakSeqs().isEmpty() ? -1L : chain.breakSeqs().get(0);
            risks.add(risk("HASH_BROKEN", "CRITICAL",
                    "哈希链校验失败，断裂序号 seq=" + firstBreak + "（共 "
                            + chain.breakSeqs().size() + " 处），采样可能被篡改，需人工复核",
                    false, "SAMPLE", null, null));
            metrics.add(new AssessmentMetric("chain", "哈希链",
                    "断裂 " + chain.breakSeqs().size() + " 处", null, "BAD",
                    "校验 " + chain.checked() + " 环，首个失配 seq=" + firstBreak));
        } else {
            metrics.add(new AssessmentMetric("chain", "哈希链", "完整", null, "OK",
                    "逐环重算 " + chain.checked() + " 环，contentHash/chainHash 均一致"));
        }

        // ---------- 维度 6：转运时间线（节点延误/缺失、开箱未关） ----------
        appendTimeline(in, metrics, risks);

        // ---------- 汇总结论 ----------
        boolean integrityDoubt = chain == null || !chain.intact() || reordered;
        boolean hardFail = risks.stream().anyMatch(AssessmentRisk::isBlocker);
        boolean needsReview = integrityDoubt
                || risks.stream().anyMatch(r -> "HASH_BROKEN".equals(r.getCode())
                        || "CHAIN_UNVERIFIED".equals(r.getCode()))
                || openWarn > 0;

        AssessmentConclusion conclusion;
        if (integrityDoubt) {
            conclusion = AssessmentConclusion.NEEDS_REVIEW;
        } else if (hardFail) {
            conclusion = AssessmentConclusion.FAIL;
        } else if (needsReview) {
            conclusion = AssessmentConclusion.NEEDS_REVIEW;
        } else {
            conclusion = AssessmentConclusion.PASS;
        }
        String summary = switch (conclusion) {
            case PASS -> "评估合格：哈希链完整，有效冷链占比 " + ratioText
                    + (risks.isEmpty() ? "，无未驳回异常" : "，存在 " + risks.size() + " 项提示，建议关注");
            case FAIL -> "评估不合格：" + joinMessages(topRisks(risks));
            case NEEDS_REVIEW -> "需人工复核：" + joinMessages(topRisks(risks));
            default -> "不可评估：" + joinMessages(guards);
        };
        return new Output(conclusion, chainStatus, chain == null ? 0 : chain.checked(), summary,
                List.copyOf(risks), List.copyOf(metrics), rule, boundary);
    }

    // ============================ 转运时间线 ============================

    private static void appendTimeline(Input in, List<AssessmentMetric> metrics,
                                       List<AssessmentRisk> risks) {
        List<TransportNode> nodes = in.getNodes() == null ? List.of() : in.getNodes();
        List<BoxEvent> events = in.getEvents() == null ? List.of() : in.getEvents();
        long shipments = in.getShipments() == null ? 0 : in.getShipments().size();

        int delayed = 0;
        int pending = 0;
        for (TransportNode n : nodes) {
            if (n.getActualTimeUtc() != null && n.getPlannedTimeUtc() != null) {
                long delaySec = Duration.between(n.getPlannedTimeUtc(), n.getActualTimeUtc()).getSeconds();
                if (delaySec > NODE_DELAY_TOLERANCE_SECONDS) {
                    delayed++;
                    risks.add(risk("TIMELINE_DELAY", "INFO",
                            "节点「" + n.getNodeName() + "」实际晚于计划 "
                                    + fmt(delaySec) + "（超过 " + fmt(NODE_DELAY_TOLERANCE_SECONDS) + "）",
                            false, "TIMELINE", null, null));
                }
            } else if (n.getActualTimeUtc() == null && n.getPlannedTimeUtc() != null
                    && in.getEvaluatedAt() != null
                    && n.getPlannedTimeUtc().toInstant(ZoneOffset.UTC).isBefore(in.getEvaluatedAt())) {
                pending++;
                risks.add(risk("TIMELINE_NODE_PENDING", "WARN",
                        "节点「" + n.getNodeName() + "」计划时间已过但无实际到点记录",
                        false, "TIMELINE", null, null));
            }
        }

        // 开箱/关门按时间配对，未闭合的 OPEN 记风险
        List<BoxEvent> ordered = events.stream()
                .sorted(Comparator.comparing(BoxEvent::getEventTimeUtc)).toList();
        int openCount = 0;
        int unmatchedOpen = 0;
        for (BoxEvent e : ordered) {
            if ("OPEN".equals(e.getEventType())) {
                openCount++;
            }
        }
        int balance = 0;
        for (BoxEvent e : ordered) {
            if ("OPEN".equals(e.getEventType())) {
                balance++;
            } else if ("CLOSE".equals(e.getEventType()) && balance > 0) {
                balance--;
            }
        }
        unmatchedOpen = balance;
        if (unmatchedOpen > 0) {
            BoxEvent lastOpen = ordered.stream().filter(e -> "OPEN".equals(e.getEventType()))
                    .reduce((a, b) -> b).orElse(null);
            risks.add(risk("DOOR_OPEN_UNMATCHED", "WARN",
                    unmatchedOpen + " 次开箱无对应关门记录"
                            + (lastOpen != null ? "（最近 " + lastOpen.getEventTimeUtc() + "Z）" : ""),
                    false, "TIMELINE", null, null,
                    lastOpen == null ? null : lastOpen.getEventTimeUtc().toString()));
        }

        String status = pending > 0 || unmatchedOpen > 0 ? "WARN" : (nodes.isEmpty() ? "INFO" : "OK");
        metrics.add(new AssessmentMetric("timeline", "转运时间线",
                shipments + " 单 / " + nodes.size() + " 节点 / " + openCount + " 次开箱",
                null, status,
                "延误 " + delayed + "，缺到点 " + pending + "，未闭合开箱 " + unmatchedOpen));
    }

    // ============================ 边界 / 规则快照 ============================

    private static DataBoundary buildBoundary(Input in) {
        DataBoundary b = new DataBoundary();
        b.setEvaluatedAtUtc(in.getEvaluatedAt() == null ? null
                : java.time.LocalDateTime.ofInstant(in.getEvaluatedAt(), ZoneOffset.UTC));
        List<TemperatureSample> samples = in.getSamples();
        if (samples != null && !samples.isEmpty()) {
            b.setSampleCount(samples.size());
            b.setSamplesFromUtc(samples.stream().map(TemperatureSample::getSampleTimeUtc)
                    .min(Comparator.naturalOrder()).orElse(null));
            b.setSamplesToUtc(samples.stream().map(TemperatureSample::getSampleTimeUtc)
                    .max(Comparator.naturalOrder()).orElse(null));
            b.setReceivedFromUtc(samples.stream().map(TemperatureSample::getReceivedAt)
                    .filter(java.util.Objects::nonNull).min(Comparator.naturalOrder()).orElse(null));
            b.setReceivedToUtc(samples.stream().map(TemperatureSample::getReceivedAt)
                    .filter(java.util.Objects::nonNull).max(Comparator.naturalOrder()).orElse(null));
        } else {
            b.setSampleCount(0);
        }
        List<Shipment> shipments = in.getShipments();
        if (shipments != null && !shipments.isEmpty()) {
            b.setShipmentStartUtc(shipments.stream().map(Shipment::getStartTimeUtc)
                    .min(Comparator.naturalOrder()).orElse(null));
            b.setShipmentEndUtc(shipments.stream().map(s ->
                            s.getEndTimeUtc() != null ? s.getEndTimeUtc()
                                    : java.time.LocalDateTime.ofInstant(in.getEvaluatedAt(), ZoneOffset.UTC))
                    .max(Comparator.naturalOrder()).orElse(null));
            if (b.getSamplesFromUtc() != null && b.getShipmentStartUtc() != null && b.getShipmentEndUtc() != null) {
                long win = Duration.between(b.getShipmentStartUtc(), b.getShipmentEndUtc()).getSeconds();
                long inside = samples.stream().map(TemperatureSample::getSampleTimeUtc)
                        .filter(t -> !t.isBefore(b.getShipmentStartUtc()) && !t.isAfter(b.getShipmentEndUtc()))
                        .count();
                b.setShipmentCoverageRatio(win <= 0 ? null
                        : BigDecimal.valueOf(inside).divide(BigDecimal.valueOf(samples.size()), 4, RoundingMode.HALF_UP));
            }
        }
        return b;
    }

    private static RuleSnapshot snapshotRule(ColdBox box) {
        RuleSnapshot r = new RuleSnapshot();
        r.setTempMin(box.getTempMin());
        r.setTempMax(box.getTempMax());
        r.setExcursionSeconds(box.getExcursionSeconds());
        r.setOfflineSeconds(box.getOfflineSeconds());
        r.setIntervalMinSeconds(box.getIntervalMinSeconds());
        r.setIntervalMaxSeconds(box.getIntervalMaxSeconds());
        r.setFailValidRatio(FAIL_VALID_RATIO);
        r.setWarnValidRatio(WARN_VALID_RATIO);
        r.setNodeDelayToleranceSeconds(NODE_DELAY_TOLERANCE_SECONDS);
        r.setRuleVersion(RULE_VERSION);
        return r;
    }

    private static AssessmentMetric ruleMetric(ColdBox box, boolean reversed) {
        String text = "[" + box.getTempMin() + ", " + box.getTempMax() + "]℃";
        String detail = "连续超温阈值 " + nz(box.getExcursionSeconds()) + "s；离线阈值 "
                + nz(box.getOfflineSeconds()) + "s；采样间隔允许 ["
                + nz(box.getIntervalMinSeconds()) + ", " + nz(box.getIntervalMaxSeconds()) + "]s";
        return new AssessmentMetric("rule", "温控规则", text, "℃",
                reversed ? "BAD" : "OK", reversed ? "规则上下限反向或缺失" : detail);
    }

    // ============================ 风险/文案 ============================

    private static AssessmentRisk anomalyRisk(Anomaly a) {
        boolean hash = "HASH_BROKEN".equals(a.getType());
        boolean blocker = switch (a.getStatus()) {
            case "CONFIRMED" -> true;
            case "OPEN" -> !hash && "CRITICAL".equals(a.getSeverity());
            default -> false;
        };
        String message = typeLabel(a.getType()) + "（" + statusLabel(a.getStatus()) + "）"
                + (a.getDurationSeconds() != null && a.getDurationSeconds() > 0
                        ? "，持续 " + fmt(a.getDurationSeconds()) : "")
                + (a.getDescription() != null ? "：" + a.getDescription() : "");
        return risk(a.getType(),
                hash ? "CRITICAL" : (a.getSeverity() == null ? "WARN" : a.getSeverity()),
                message, blocker, "ANOMALY", a.getId(), a.getType(),
                a.getStartTimeUtc() == null ? null : a.getStartTimeUtc().toString());
    }

    private static AssessmentRisk risk(String code, String severity, String message, boolean blocker,
                                       String refType, Long anomalyId, String anomalyType) {
        return new AssessmentRisk(code, severity, message, blocker, refType, anomalyId, anomalyType, null);
    }

    private static AssessmentRisk risk(String code, String severity, String message, boolean blocker,
                                       String refType, Long anomalyId, String anomalyType, String refTime) {
        return new AssessmentRisk(code, severity, message, blocker, refType, anomalyId, anomalyType, refTime);
    }

    private static List<AssessmentRisk> topRisks(List<AssessmentRisk> risks) {
        return risks.stream()
                .sorted(Comparator.comparingInt((AssessmentRisk r) ->
                        "CRITICAL".equals(r.getSeverity()) ? 0 : "WARN".equals(r.getSeverity()) ? 1 : 2))
                .limit(3).toList();
    }

    private static String joinMessages(List<AssessmentRisk> risks) {
        return risks.stream().map(AssessmentRisk::getMessage)
                .reduce((a, b) -> a + "；" + b).orElse("无");
    }

    private static String typeLabel(String type) {
        return switch (type) {
            case "TEMP_EXCURSION" -> "连续超温";
            case "OFFLINE" -> "传感器离线";
            case "INTERVAL" -> "采样间隔异常";
            case "HASH_BROKEN" -> "哈希链断裂";
            default -> type;
        };
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "OPEN" -> "待复核";
            case "CONFIRMED" -> "已确认";
            case "REJECTED" -> "已驳回";
            default -> status;
        };
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String fmt(long seconds) {
        return com.coldchain.common.DurationFormatter.chinese(seconds);
    }
}

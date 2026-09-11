package com.coldchain.service.assessment;

import com.coldchain.domain.entity.Anomaly;
import com.coldchain.domain.entity.ColdBox;
import com.coldchain.domain.entity.TemperatureSample;
import com.coldchain.domain.enums.AssessmentConclusion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.coldchain.service.assessment.AssessmentFixtures.T0;
import static com.coldchain.service.assessment.AssessmentFixtures.anomaly;
import static com.coldchain.service.assessment.AssessmentFixtures.baseInput;
import static com.coldchain.service.assessment.AssessmentFixtures.box;
import static com.coldchain.service.assessment.AssessmentFixtures.broken;
import static com.coldchain.service.assessment.AssessmentFixtures.chain;
import static com.coldchain.service.assessment.AssessmentFixtures.conclusion;
import static com.coldchain.service.assessment.AssessmentFixtures.hasRisk;
import static com.coldchain.service.assessment.AssessmentFixtures.intact;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 评估引擎规则与边界测试。
 * 必覆盖：空箱、临界持续时长、存在已驳回异常、哈希链校验失败。
 */
class AssessmentEngineTest {

    @Nested
    @DisplayName("不可评估守卫（不得静默判合格）")
    class Guards {

        @Test
        @DisplayName("场景1：空箱（无采样）=> UNASSESSABLE，且记录 NO_SAMPLES 风险")
        void emptyBox() {
            var out = AssessmentEngine.evaluate(baseInput(List.of()).build());
            assertEquals(AssessmentConclusion.UNASSESSABLE, out.getConclusion());
            assertTrue(hasRisk(out, "NO_SAMPLES"));
            assertEquals("UNVERIFIED", out.getChainStatus());
            assertNotNull(out.getSummary());
            assertTrue(out.getSummary().contains("不可评估"));
            // 仍然输出规则与覆盖指标
            assertTrue(out.getMetrics().stream().anyMatch(m -> "rule".equals(m.getKey())));
        }

        @Test
        @DisplayName("无绑定设备（即使有采样）=> UNASSESSABLE + NO_DEVICE")
        void noDevice() {
            List<TemperatureSample> samples = chain(109L, 9L, 6, 5.0);
            var input = baseInput(samples).device(null).chain(null).build();
            var out = AssessmentEngine.evaluate(input);
            assertEquals(AssessmentConclusion.UNASSESSABLE, out.getConclusion());
            assertTrue(hasRisk(out, "NO_DEVICE"));
            // 无设备时链不参与校验
            assertEquals("UNVERIFIED", out.getChainStatus());
        }

        @Test
        @DisplayName("规则上下限反向（min >= max）=> UNASSESSABLE + RULE_REVERSED")
        void reversedRule() {
            ColdBox reversed = box(1, 8, 2);
            List<TemperatureSample> samples = chain(reversed.getDeviceId(), 1L, 6, 5.0);
            var input = baseInput(samples).box(reversed).build();
            var out = AssessmentEngine.evaluate(input);
            assertEquals(AssessmentConclusion.UNASSESSABLE, out.getConclusion());
            assertTrue(hasRisk(out, "RULE_REVERSED"));
            var ruleMetric = out.getMetrics().stream().filter(m -> "rule".equals(m.getKey())).findFirst().orElseThrow();
            assertEquals("BAD", ruleMetric.getStatus());
        }

        @Test
        @DisplayName("上下限相等同样视为无效规则")
        void equalBoundsRule() {
            ColdBox reversed = box(1, 5, 5);
            List<TemperatureSample> samples = chain(reversed.getDeviceId(), 1L, 3, 5.0);
            var out = AssessmentEngine.evaluate(baseInput(samples).box(reversed).build());
            assertEquals(AssessmentConclusion.UNASSESSABLE, out.getConclusion());
            assertTrue(hasRisk(out, "RULE_REVERSED"));
        }
    }

    @Nested
    @DisplayName("正常与临界判定")
    class PassAndBoundaries {

        @Test
        @DisplayName("链完整、无异常、温度全程达标 => PASS")
        void healthyPass() {
            List<TemperatureSample> samples = chain(101L, 1L, 24, 5.0);
            var out = AssessmentEngine.evaluate(baseInput(samples).build());
            assertEquals(AssessmentConclusion.PASS, out.getConclusion());
            assertEquals("INTACT", out.getChainStatus());
            assertEquals(24, out.getChainChecked());
            var valid = out.getMetrics().stream().filter(m -> "validColdChain".equals(m.getKey())).findFirst().orElseThrow();
            assertEquals("OK", valid.getStatus());
            assertTrue(valid.getDetail().contains("100.0%"));
            // 六维指标齐备
            assertTrue(out.getMetrics().stream().anyMatch(m -> "rule".equals(m.getKey())));
            assertTrue(out.getMetrics().stream().anyMatch(m -> "coverage".equals(m.getKey())));
            assertTrue(out.getMetrics().stream().anyMatch(m -> "anomaly".equals(m.getKey())));
            assertTrue(out.getMetrics().stream().anyMatch(m -> "chain".equals(m.getKey())));
            assertTrue(out.getMetrics().stream().anyMatch(m -> "timeline".equals(m.getKey())));
        }

        @Test
        @DisplayName("临界持续时长：有效占比恰好 80.0% => 不判 FAIL（非阻断提示，结论仍 PASS）")
        void validRatioExactlyAtFailBoundary() {
            // 21 点 * 300s = 覆盖 6000s；离线区间 1200s 恰为 20% => 有效占比 80.0%
            List<TemperatureSample> samples = chain(101L, 1L, 21, 5.0);
            Anomaly offline = anomaly(1, "OFFLINE", "OPEN", "INFO",
                    T0, T0.plusSeconds(1200), 1200);
            var out = AssessmentEngine.evaluate(baseInput(samples).anomalies(List.of(offline)).build());
            assertEquals(AssessmentConclusion.PASS, out.getConclusion());
            assertTrue(hasRisk(out, "VALID_CHAIN_LOW"), "80% 应触发关注风险而非不合格");
            assertFalse(hasRisk(out, "VALID_CHAIN_TOO_LOW"));
            assertTrue(out.getRisks().stream().noneMatch(
                    com.coldchain.domain.dto.assessment.AssessmentRisk::isBlocker));
        }

        @Test
        @DisplayName("临界持续时长：有效占比 79.9%（刚过临界 1 秒）=> FAIL")
        void validRatioJustBelowBoundary() {
            List<TemperatureSample> samples = chain(101L, 1L, 21, 5.0); // 6000s
            Anomaly offline = anomaly(1, "OFFLINE", "OPEN", "INFO",
                    T0, T0.plusSeconds(1206), 1206); // 4794/6000 = 79.9%
            var out = AssessmentEngine.evaluate(baseInput(samples).anomalies(List.of(offline)).build());
            assertEquals(AssessmentConclusion.FAIL, out.getConclusion());
            assertTrue(hasRisk(out, "VALID_CHAIN_TOO_LOW"));
            var risk = out.getRisks().stream().filter(r -> "VALID_CHAIN_TOO_LOW".equals(r.getCode())).findFirst().orElseThrow();
            assertTrue(risk.isBlocker());
        }

        @Test
        @DisplayName("有效占比恰好 95.0% => 仍为 PASS（关注阈值含等号）")
        void validRatioExactlyAtWarnBoundary() {
            List<TemperatureSample> samples = chain(101L, 1L, 21, 5.0); // 6000s
            Anomaly offline = anomaly(1, "OFFLINE", "OPEN", "INFO",
                    T0, T0.plusSeconds(300), 300); // 5700/6000 = 95.0%
            var out = AssessmentEngine.evaluate(baseInput(samples).anomalies(List.of(offline)).build());
            assertEquals(AssessmentConclusion.PASS, out.getConclusion());
            assertFalse(hasRisk(out, "VALID_CHAIN_LOW"));
        }
    }

    @Nested
    @DisplayName("异常状态对结论的影响")
    class AnomalyStatus {

        @Test
        @DisplayName("场景3：存在已驳回异常 => 不扣减有效时长、不产生风险，结论仍 PASS")
        void rejectedAnomalyIgnored() {
            List<TemperatureSample> samples = chain(101L, 1L, 21, 5.0); // 6000s
            Anomaly rejected = anomaly(1, "TEMP_EXCURSION", "REJECTED", "CRITICAL",
                    T0, T0.plusSeconds(3000), 3000);
            var out = AssessmentEngine.evaluate(baseInput(samples).anomalies(List.of(rejected)).build());
            assertEquals(AssessmentConclusion.PASS, out.getConclusion());
            assertFalse(hasRisk(out, "TEMP_EXCURSION"));
            var valid = out.getMetrics().stream().filter(m -> "validColdChain".equals(m.getKey())).findFirst().orElseThrow();
            assertTrue(valid.getDetail().contains("100.0%"), "已驳回异常不得扣减：" + valid.getDetail());
            var anomalyMetric = out.getMetrics().stream().filter(m -> "anomaly".equals(m.getKey())).findFirst().orElseThrow();
            assertEquals("OK", anomalyMetric.getStatus());
            assertTrue(anomalyMetric.getDetail().contains("已驳回 1 条"));
        }

        @Test
        @DisplayName("存在已确认异常 => FAIL 且风险为阻断项")
        void confirmedAnomalyFails() {
            List<TemperatureSample> samples = chain(101L, 1L, 21, 5.0);
            Anomaly confirmed = anomaly(2, "TEMP_EXCURSION", "CONFIRMED", "WARN",
                    T0.plusSeconds(300), T0.plusSeconds(900), 600);
            var out = AssessmentEngine.evaluate(baseInput(samples).anomalies(List.of(confirmed)).build());
            assertEquals(AssessmentConclusion.FAIL, out.getConclusion());
            var risk = out.getRisks().stream().filter(r -> r.getCode().equals("TEMP_EXCURSION")).findFirst().orElseThrow();
            assertTrue(risk.isBlocker());
            assertEquals("ANOMALY", risk.getRefType());
            assertEquals(2L, risk.getAnomalyId());
        }

        @Test
        @DisplayName("OPEN 的 WARN 异常 => NEEDS_REVIEW（待人工复核）")
        void openWarnNeedsReview() {
            List<TemperatureSample> samples = chain(101L, 1L, 21, 5.0);
            Anomaly openWarn = anomaly(3, "TEMP_EXCURSION", "OPEN", "WARN",
                    T0.plusSeconds(300), T0.plusSeconds(900), 600);
            var out = AssessmentEngine.evaluate(baseInput(samples).anomalies(List.of(openWarn)).build());
            assertEquals(AssessmentConclusion.NEEDS_REVIEW, out.getConclusion());
        }

        @Test
        @DisplayName("OPEN 的 CRITICAL 异常（非哈希）=> FAIL")
        void openCriticalFails() {
            List<TemperatureSample> samples = chain(101L, 1L, 21, 5.0);
            Anomaly openCritical = anomaly(4, "OFFLINE", "OPEN", "CRITICAL",
                    T0, T0.plusSeconds(600), 600);
            var out = AssessmentEngine.evaluate(baseInput(samples).anomalies(List.of(openCritical)).build());
            assertEquals(AssessmentConclusion.FAIL, out.getConclusion());
        }
    }

    @Nested
    @DisplayName("哈希链与采样时序")
    class ChainAndOrder {

        @Test
        @DisplayName("场景4：哈希链校验失败 => NEEDS_REVIEW + HASH_BROKEN，绝不判合格")
        void brokenChain() {
            List<TemperatureSample> samples = chain(101L, 1L, 12, 5.0);
            var out = AssessmentEngine.evaluate(baseInput(samples)
                    .chain(broken(12, 7, 8, 9, 10, 11, 12))
                    .build());
            assertEquals(AssessmentConclusion.NEEDS_REVIEW, out.getConclusion());
            assertEquals("BROKEN", out.getChainStatus());
            assertTrue(hasRisk(out, "HASH_BROKEN"));
            var chainMetric = out.getMetrics().stream().filter(m -> "chain".equals(m.getKey())).findFirst().orElseThrow();
            assertEquals("BAD", chainMetric.getStatus());
            assertTrue(chainMetric.getDetail().contains("seq=7"));
        }

        @Test
        @DisplayName("ChainVerifier 能发现温度被直接改库（内容哈希失配）")
        void verifierDetectsTamper() {
            List<TemperatureSample> samples = chain(101L, 1L, 8, 5.0);
            AssessmentFixtures.tamper(samples, 3, 99.0);
            var breaks = ChainVerifier.verify(samples);
            assertFalse(breaks.isEmpty());
            assertEquals(4L, breaks.get(0).seq());
        }

        @Test
        @DisplayName("哈希链未校验（chain=null）=> NEEDS_REVIEW + CHAIN_UNVERIFIED")
        void chainUnverified() {
            List<TemperatureSample> samples = chain(101L, 1L, 12, 5.0);
            var out = AssessmentEngine.evaluate(baseInput(samples).chain(null).build());
            assertEquals(AssessmentConclusion.NEEDS_REVIEW, out.getConclusion());
            assertTrue(hasRisk(out, "CHAIN_UNVERIFIED"));
            assertEquals("UNVERIFIED", out.getChainStatus());
        }

        @Test
        @DisplayName("采样时间倒序 => NEEDS_REVIEW + SAMPLE_TIME_REORDERED")
        void sampleTimeReordered() {
            List<TemperatureSample> samples = chain(101L, 1L, 12, 5.0);
            AssessmentFixtures.reorder(samples, 5);
            var out = AssessmentEngine.evaluate(baseInput(samples).build());
            assertEquals(AssessmentConclusion.NEEDS_REVIEW, out.getConclusion());
            assertTrue(hasRisk(out, "SAMPLE_TIME_REORDERED"));
        }

        @Test
        @DisplayName("已存在 OPEN HASH_BROKEN 异常但链当前完整 => 异常不重复计风险，链指标 OK")
        void hashAnomalyWithIntactChain() {
            List<TemperatureSample> samples = chain(101L, 1L, 12, 5.0);
            Anomaly hashAnomaly = anomaly(5, "HASH_BROKEN", "OPEN", "CRITICAL",
                    T0.plusSeconds(1500), T0.plusSeconds(1500), 0);
            var out = AssessmentEngine.evaluate(baseInput(samples)
                    .anomalies(List.of(hashAnomaly)).chain(intact(12)).build());
            // 链完整优先；OPEN HASH_BROKEN 不直接判 FAIL（链维度是唯一事实来源）
            assertEquals(AssessmentConclusion.PASS, out.getConclusion());
        }
    }

    @Nested
    @DisplayName("转运时间线与数据边界")
    class TimelineAndBoundary {

        @Test
        @DisplayName("节点实际晚于计划超过 1 小时 => TIMELINE_DELAY 提示风险（不阻断）")
        void delayedNode() {
            List<TemperatureSample> samples = chain(101L, 1L, 24, 5.0);
            var shipment = AssessmentFixtures.shipment(1, 1, T0, T0.plusSeconds(86400));
            var node = AssessmentFixtures.node(1, 1, 1, "到达节点", "ARRIVAL",
                    T0.plusSeconds(3600), T0.plusSeconds(3600 + 3700));
            var out = AssessmentEngine.evaluate(baseInput(samples)
                    .shipments(List.of(shipment)).nodes(List.of(node)).build());
            assertTrue(hasRisk(out, "TIMELINE_DELAY"));
            assertEquals(AssessmentConclusion.PASS, out.getConclusion());
        }

        @Test
        @DisplayName("开箱无对应关门 => DOOR_OPEN_UNMATCHED 风险")
        void unmatchedOpenDoor() {
            List<TemperatureSample> samples = chain(101L, 1L, 24, 5.0);
            var open = AssessmentFixtures.event(1, 1, 101, "OPEN", T0.plusSeconds(600));
            var out = AssessmentEngine.evaluate(baseInput(samples).events(List.of(open)).build());
            assertTrue(hasRisk(out, "DOOR_OPEN_UNMATCHED"));
            var risk = out.getRisks().stream().filter(r -> "DOOR_OPEN_UNMATCHED".equals(r.getCode())).findFirst().orElseThrow();
            assertEquals("TIMELINE", risk.getRefType());
        }

        @Test
        @DisplayName("数据时间边界：采样/入库/转运窗口与规则快照随结果输出")
        void boundaryAndRuleSnapshot() {
            List<TemperatureSample> samples = chain(101L, 1L, 12, 5.0);
            var shipment = AssessmentFixtures.shipment(1, 1, T0.minusSeconds(600), T0.plusSeconds(7200));
            Instant evaluatedAt = T0.plusSeconds(10000);
            var out = AssessmentEngine.evaluate(baseInput(samples)
                    .shipments(List.of(shipment)).evaluatedAt(evaluatedAt).build());

            var b = out.getBoundary();
            assertEquals(12, b.getSampleCount());
            assertEquals(java.time.LocalDateTime.ofInstant(T0, java.time.ZoneOffset.UTC), b.getSamplesFromUtc());
            assertNotNull(b.getReceivedFromUtc());
            assertNotNull(b.getShipmentStartUtc());
            // 12 个采样点全部落在转运窗口 [T0-600, T0+7200) 内
            assertEquals(0, new java.math.BigDecimal("1.0000").compareTo(b.getShipmentCoverageRatio()));
            assertEquals(java.time.LocalDateTime.ofInstant(evaluatedAt, java.time.ZoneOffset.UTC), b.getEvaluatedAtUtc());

            var rule = out.getRuleSnapshot();
            assertEquals(0, new java.math.BigDecimal("2.00").compareTo(rule.getTempMin()));
            assertEquals(AssessmentEngine.RULE_VERSION, rule.getRuleVersion());
            assertEquals(300, rule.getExcursionSeconds());
        }
    }
}

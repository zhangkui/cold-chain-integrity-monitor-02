package com.coldchain.service.assessment;

import com.coldchain.domain.entity.TemperatureSample;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.coldchain.service.assessment.AssessmentFixtures.chain;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainVerifierTest {

    @Test
    @DisplayName("自洽哈希链：无失配")
    void intactChain() {
        List<TemperatureSample> samples = chain(101L, 1L, 30, 5.0);
        assertTrue(ChainVerifier.verify(samples).isEmpty());
    }

    @Test
    @DisplayName("空入参：无失配（是否可评估由引擎守卫决定）")
    void emptyChain() {
        assertTrue(ChainVerifier.verify(List.of()).isEmpty());
    }

    @Test
    @DisplayName("温度篡改：自篡改点起 contentHash 失配")
    void tamperedTemperature() {
        List<TemperatureSample> samples = chain(101L, 1L, 10, 5.0);
        AssessmentFixtures.tamper(samples, 4, -99.0);
        var breaks = ChainVerifier.verify(samples);
        assertFalse(breaks.isEmpty());
        assertEquals(5L, breaks.get(0).seq());
        assertTrue(breaks.get(0).reason().contains("contentHash"));
    }

    @Test
    @DisplayName("prevHash 被改写：断链失配")
    void brokenPrevHash() {
        List<TemperatureSample> samples = chain(101L, 1L, 10, 5.0);
        samples.get(3).setPrevHash("f".repeat(64));
        var breaks = ChainVerifier.verify(samples);
        assertTrue(breaks.stream().anyMatch(b -> b.seq() == 4 && b.reason().contains("prevHash")));
    }

    @Test
    @DisplayName("补传采样（BACKFILL）在哈希自洽时同样通过")
    void backfillStillIntact() {
        List<TemperatureSample> samples = chain(101L, 1L, 6, 5.0);
        samples.forEach(s -> s.setSource("BACKFILL"));
        assertTrue(ChainVerifier.verify(samples).isEmpty());
    }
}

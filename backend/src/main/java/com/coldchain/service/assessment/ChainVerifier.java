package com.coldchain.service.assessment;

import com.coldchain.common.HashUtils;
import com.coldchain.common.TimeUtils;
import com.coldchain.domain.entity.TemperatureSample;

import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 哈希链逐环重算（只读、无副作用）：评估与异常检测共用同一套校验口径。
 * 入参须按 seq 升序；任何对历史采样时间/温度/链字段的篡改都会表现为失配。
 */
public final class ChainVerifier {

    private ChainVerifier() {
    }

    public record Break(long seq, String expected, String actual, String reason) {
    }

    /**
     * @return 失配点列表（可能为空 = 链完整）；空列表入参返回空列表（由调用方区分 NO_DATA/UNVERIFIED）
     */
    public static List<Break> verify(List<TemperatureSample> samplesAsc) {
        List<Break> breaks = new ArrayList<>();
        String prevChain = HashUtils.GENESIS;
        Long lastSeq = null;

        for (TemperatureSample s : samplesAsc) {
            Instant instant = s.getSampleTimeUtc().toInstant(ZoneOffset.UTC);
            String canonicalTemp = s.getTemperatureC().setScale(2, RoundingMode.HALF_UP).toPlainString();
            String expectedContent = HashUtils.contentHash(s.getDeviceId(), s.getSeq(),
                    TimeUtils.hashFormat(instant), canonicalTemp);
            String expectedChain = HashUtils.chainHash(prevChain, expectedContent);

            if (lastSeq != null && s.getSeq() <= lastSeq) {
                breaks.add(new Break(s.getSeq(), expectedChain, s.getChainHash(), "序号非递增"));
            }
            if (!expectedContent.equals(s.getContentHash())) {
                breaks.add(new Break(s.getSeq(), expectedContent, s.getContentHash(),
                        "contentHash 不一致：采样时间或温度被篡改"));
            }
            if (!prevChain.equals(s.getPrevHash())) {
                breaks.add(new Break(s.getSeq(), prevChain, s.getPrevHash(), "prevHash 断链"));
            }
            if (!expectedChain.equals(s.getChainHash())) {
                breaks.add(new Break(s.getSeq(), expectedChain, s.getChainHash(), "chainHash 不一致"));
            }
            prevChain = s.getChainHash();
            lastSeq = s.getSeq();
        }
        return breaks;
    }
}

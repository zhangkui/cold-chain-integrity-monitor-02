package com.coldchain.domain.dto.assessment;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 评估所依据的箱体温控规则快照。评估记录持久化本对象，
 * 规则日后调整不影响历史评估结论的可解释性。
 */
@Data
public class RuleSnapshot {
    private BigDecimal tempMin;
    private BigDecimal tempMax;
    private Integer excursionSeconds;
    private Integer offlineSeconds;
    private Integer intervalMinSeconds;
    private Integer intervalMaxSeconds;
    /** 有效冷链占比低于该值判 FAIL */
    private BigDecimal failValidRatio;
    /** 有效冷链占比低于该值（且未 FAIL）判需复核 */
    private BigDecimal warnValidRatio;
    /** 节点实际时间晚于计划多少秒记为延误（INFO 指标） */
    private Integer nodeDelayToleranceSeconds;
    /** 评估规则版本 */
    private String ruleVersion;
}

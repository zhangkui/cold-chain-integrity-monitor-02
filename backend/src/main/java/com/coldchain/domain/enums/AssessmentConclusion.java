package com.coldchain.domain.enums;

/**
 * 箱体综合评估结论。
 * PASS/FAIL 只在数据充分且哈希链完整时给出；
 * NEEDS_REVIEW 表示数据基本充分但存在需人工判断的情形（链断裂、时间倒序、待复核异常等）；
 * UNASSESSABLE 表示数据不足或规则无效，明确不具备评估条件，绝不静默判合格。
 */
public enum AssessmentConclusion {
    PASS,
    FAIL,
    NEEDS_REVIEW,
    UNASSESSABLE
}

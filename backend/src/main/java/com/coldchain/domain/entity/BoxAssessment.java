package com.coldchain.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 箱体综合评估记录（版本化、仅追加）。
 * 同一箱体重复触发 -> version 自增写入新行；任何 UPDATE/DELETE 都不允许发生，
 * 历史评估是不可覆盖的审计事实。
 */
@Data
@TableName("box_assessment")
public class BoxAssessment {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long boxId;
    private Integer version;
    /** AssessmentConclusion：PASS / FAIL / NEEDS_REVIEW / UNASSESSABLE */
    private String conclusion;
    /** 哈希链校验状态：INTACT / BROKEN / UNVERIFIED / NO_DATA */
    private String chainStatus;
    /** 本次实际重算校验的哈希环数 */
    private Integer chainChecked;
    private String summary;
    /** 主要风险 JSON 数组 */
    private String primaryRisks;
    /** 组成指标快照 JSON */
    private String metricsJson;
    /** 评估使用的箱体温控规则快照 JSON */
    private String ruleSnapshotJson;
    /** 评估使用的数据时间边界 JSON */
    private String dataBoundaryJson;
    /** 评估规则版本（规则演进后仍可解释历史结论） */
    private String ruleVersion;
    private String generatedBy;
    private LocalDateTime generatedAt;
    private LocalDateTime createdAt;
}

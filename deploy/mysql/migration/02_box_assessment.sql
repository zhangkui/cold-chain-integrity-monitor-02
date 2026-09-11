-- ============================================================
-- 迁移：箱体综合评估（版本化、仅追加）
-- 适用于已存在冷数据的库；新库由 01_schema.sql 直接包含。
-- ============================================================
USE coldchain;

CREATE TABLE IF NOT EXISTS box_assessment (
  id                  BIGINT       NOT NULL AUTO_INCREMENT,
  box_id              BIGINT       NOT NULL,
  version             INT          NOT NULL COMMENT '箱体内自增版本号',
  conclusion          VARCHAR(24)  NOT NULL COMMENT 'PASS/FAIL/NEEDS_REVIEW/UNASSESSABLE',
  chain_status        VARCHAR(24)  NOT NULL COMMENT 'INTACT/BROKEN/UNVERIFIED/NO_DATA',
  chain_checked       INT          NOT NULL DEFAULT 0 COMMENT '本次实际校验的哈希环数',
  summary             VARCHAR(512) NOT NULL COMMENT '结论摘要',
  primary_risks       TEXT         NULL COMMENT '主要风险 JSON 数组',
  metrics_json        MEDIUMTEXT   NOT NULL COMMENT '组成指标快照 JSON',
  rule_snapshot_json  MEDIUMTEXT   NOT NULL COMMENT '评估使用的温控规则快照 JSON',
  data_boundary_json  MEDIUMTEXT   NOT NULL COMMENT '评估使用的数据时间边界 JSON',
  rule_version        VARCHAR(64)  NOT NULL DEFAULT 'assessment-v1',
  generated_by        VARCHAR(64)  NOT NULL DEFAULT 'system',
  generated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_at          DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_assessment_version (box_id, version),
  KEY idx_assessment_box_time (box_id, generated_at)
) ENGINE=InnoDB COMMENT='箱体综合评估（版本化、仅追加）';

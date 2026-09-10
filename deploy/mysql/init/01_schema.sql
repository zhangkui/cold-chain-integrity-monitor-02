-- ============================================================
-- 生物样本冷链完整性监测系统 - 初始化 DDL
-- 字符集 utf8mb4，时间统一以 UTC 存入 DATETIME(3)
-- ============================================================
CREATE DATABASE IF NOT EXISTS coldchain
  DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE coldchain;

-- 采集设备（温感终端 / 网关）
CREATE TABLE IF NOT EXISTS device (
  id                 BIGINT       NOT NULL AUTO_INCREMENT,
  device_code        VARCHAR(64)  NOT NULL COMMENT '设备编号',
  name               VARCHAR(128) NOT NULL,
  timezone           VARCHAR(64)  NOT NULL DEFAULT 'Asia/Shanghai' COMMENT '设备所在 IANA 时区',
  status             VARCHAR(16)  NOT NULL DEFAULT 'OFFLINE' COMMENT 'ONLINE/OFFLINE',
  last_sample_time_utc DATETIME(3) NULL,
  created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_device_code (device_code)
) ENGINE=InnoDB COMMENT='采集设备';

-- 冷链箱（含箱体温控规则）
CREATE TABLE IF NOT EXISTS cold_box (
  id                   BIGINT       NOT NULL AUTO_INCREMENT,
  box_code             VARCHAR(64)  NOT NULL COMMENT '箱号',
  device_id            BIGINT       NULL,
  batch_no             VARCHAR(64)  NOT NULL COMMENT '样本批次',
  specimen_type        VARCHAR(64)  NULL COMMENT '样本类型',
  status               VARCHAR(16)  NOT NULL DEFAULT 'IN_TRANSIT' COMMENT 'IN_TRANSIT/DELIVERED/EXCEPTION',
  temp_min             DECIMAL(6,2) NOT NULL COMMENT '规则温度下限 ℃',
  temp_max             DECIMAL(6,2) NOT NULL COMMENT '规则温度上限 ℃',
  excursion_seconds    INT          NOT NULL DEFAULT 300 COMMENT '连续超温判定秒数',
  offline_seconds      INT          NOT NULL DEFAULT 900 COMMENT '传感器离线判定秒数',
  interval_min_seconds INT          NOT NULL DEFAULT 240 COMMENT '最小允许采样间隔秒',
  interval_max_seconds INT          NOT NULL DEFAULT 360 COMMENT '最大允许采样间隔秒',
  created_at           DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_box_code (box_code),
  KEY idx_box_batch (batch_no)
) ENGINE=InnoDB COMMENT='冷链箱及箱体规则';

-- 转运单
CREATE TABLE IF NOT EXISTS shipment (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  shipment_no   VARCHAR(64)  NOT NULL,
  box_id        BIGINT       NOT NULL,
  origin        VARCHAR(128) NULL,
  destination   VARCHAR(128) NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/FINISHED',
  start_time_utc DATETIME(3) NOT NULL,
  end_time_utc  DATETIME(3)  NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_shipment_no (shipment_no),
  KEY idx_shipment_box (box_id)
) ENGINE=InnoDB COMMENT='转运单';

-- 转运节点
CREATE TABLE IF NOT EXISTS transport_node (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  shipment_id     BIGINT       NOT NULL,
  seq             INT          NOT NULL,
  node_code       VARCHAR(64)  NOT NULL COMMENT '节点编号',
  node_name       VARCHAR(128) NOT NULL COMMENT '节点名称',
  node_type       VARCHAR(32)  NOT NULL COMMENT 'DISPATCH/TRANSIT/ARRIVAL/SIGN',
  planned_time_utc  DATETIME(3) NULL,
  actual_time_utc   DATETIME(3) NULL,
  operator        VARCHAR(64)  NULL,
  location        VARCHAR(128) NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_node_seq (shipment_id, seq),
  KEY idx_node_code (node_code)
) ENGINE=InnoDB COMMENT='转运节点';

-- 温度采样（哈希链核心表）
CREATE TABLE IF NOT EXISTS temperature_sample (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  device_id         BIGINT       NOT NULL,
  box_id            BIGINT       NOT NULL,
  seq               BIGINT       NOT NULL COMMENT '设备上报序号',
  sample_time_utc   DATETIME(3)  NOT NULL COMMENT '采样时间(UTC)',
  sample_time_local DATETIME(3)  NOT NULL COMMENT '按设备时区纠正后的本地时间',
  temperature_c     DECIMAL(6,2) NOT NULL COMMENT '温度 ℃',
  source            VARCHAR(16)  NOT NULL DEFAULT 'REALTIME' COMMENT 'REALTIME 实时 / BACKFILL 补传',
  content_hash      CHAR(64)     NOT NULL COMMENT 'SHA-256(载荷)，写入后不再变化，用于发现篡改',
  prev_hash         CHAR(64)     NOT NULL DEFAULT '' COMMENT '前序采样 chain_hash',
  chain_hash        CHAR(64)     NOT NULL COMMENT 'SHA-256(prev_hash|载荷)，链完整性',
  idem_key          VARCHAR(160) NOT NULL COMMENT '幂等键 deviceId|utcTime|seq',
  received_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_device_seq (device_id, seq),
  UNIQUE KEY uk_idem_key (idem_key),
  KEY idx_sample_device_time (device_id, sample_time_utc),
  KEY idx_sample_box_time (box_id, sample_time_utc)
) ENGINE=InnoDB COMMENT='温度采样与哈希链';

-- 开箱 / 关门等事件
CREATE TABLE IF NOT EXISTS box_event (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  box_id           BIGINT       NOT NULL,
  device_id        BIGINT       NOT NULL,
  event_type       VARCHAR(16)  NOT NULL COMMENT 'OPEN/CLOSE',
  event_time_utc   DATETIME(3)  NOT NULL,
  event_time_local DATETIME(3) NOT NULL,
  note             VARCHAR(255) NULL,
  idem_key         VARCHAR(160) NOT NULL,
  created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_event_idem (idem_key),
  KEY idx_event_box_time (box_id, event_time_utc)
) ENGINE=InnoDB COMMENT='开箱事件';

-- 校准记录
CREATE TABLE IF NOT EXISTS calibration_record (
  id                 BIGINT       NOT NULL AUTO_INCREMENT,
  device_id          BIGINT       NOT NULL,
  calibrate_time_utc DATETIME(3)  NOT NULL,
  agency             VARCHAR(128) NOT NULL COMMENT '校准机构',
  offset_before      DECIMAL(6,2) NULL COMMENT '校准前偏差',
  offset_after       DECIMAL(6,2) NULL COMMENT '校准后偏差',
  result             VARCHAR(16)  NOT NULL COMMENT 'PASS/FAIL',
  cert_no            VARCHAR(64)  NOT NULL COMMENT '证书编号',
  operator           VARCHAR(64)  NULL,
  created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_cal_device (device_id, calibrate_time_utc),
  UNIQUE KEY uk_cert_no (cert_no)
) ENGINE=InnoDB COMMENT='设备校准记录';

-- 异常
CREATE TABLE IF NOT EXISTS anomaly (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  box_id         BIGINT       NOT NULL,
  device_id      BIGINT       NOT NULL,
  shipment_id    BIGINT       NULL,
  type           VARCHAR(24)  NOT NULL COMMENT 'TEMP_EXCURSION/OFFLINE/INTERVAL/HASH_BROKEN',
  severity       VARCHAR(8)   NOT NULL DEFAULT 'WARN' COMMENT 'INFO/WARN/CRITICAL',
  start_time_utc DATETIME(3)  NOT NULL,
  end_time_utc   DATETIME(3)  NOT NULL,
  duration_seconds INT        NOT NULL DEFAULT 0,
  peak_temp      DECIMAL(6,2) NULL,
  sample_count   INT          NOT NULL DEFAULT 0,
  description    VARCHAR(512) NULL,
  status         VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/CONFIRMED/REJECTED，CONFIRMED 不被补传覆盖',
  dedupe_key     VARCHAR(160) NOT NULL COMMENT '异常去重键',
  first_seen_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  confirmed_at   DATETIME(3)  NULL,
  confirmed_by   VARCHAR(64)  NULL,
  review_comment VARCHAR(512) NULL,
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_anomaly_dedupe (dedupe_key),
  KEY idx_anomaly_box (box_id),
  KEY idx_anomaly_device_time (device_id, start_time_utc),
  KEY idx_anomaly_status (status)
) ENGINE=InnoDB COMMENT='冷链异常';

-- 异常复核记录（每次复核留痕）
CREATE TABLE IF NOT EXISTS anomaly_review (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  anomaly_id BIGINT       NOT NULL,
  action     VARCHAR(16)  NOT NULL COMMENT 'CONFIRM/REJECT/REOPEN',
  comment    VARCHAR(512) NULL,
  reviewer   VARCHAR(64)  NOT NULL,
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_review_anomaly (anomaly_id)
) ENGINE=InnoDB COMMENT='异常复核记录';

-- 证据附件（版本化，不覆盖旧版本）
CREATE TABLE IF NOT EXISTS evidence_attachment (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  anomaly_id   BIGINT       NOT NULL,
  version      INT          NOT NULL COMMENT '同一异常内自增版本号',
  file_name    VARCHAR(255) NOT NULL,
  stored_path  VARCHAR(512) NOT NULL,
  file_hash    CHAR(64)     NOT NULL COMMENT 'SHA-256 内容指纹',
  size_bytes   BIGINT       NOT NULL,
  content_type VARCHAR(128) NULL,
  note         VARCHAR(255) NULL,
  uploaded_by  VARCHAR(64)  NOT NULL,
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_evidence_version (anomaly_id, version)
) ENGINE=InnoDB COMMENT='异常证据附件（版本化）';

-- 审计日志（仅追加；触发器禁止修改/删除）
CREATE TABLE IF NOT EXISTS audit_log (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  entity_type VARCHAR(32)  NOT NULL,
  entity_id   VARCHAR(64)  NOT NULL,
  action      VARCHAR(32)  NOT NULL,
  operator    VARCHAR(64)  NOT NULL DEFAULT 'system',
  request_id  VARCHAR(64)  NULL,
  detail      TEXT         NULL COMMENT 'JSON 快照',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_audit_entity (entity_type, entity_id),
  KEY idx_audit_time (created_at)
) ENGINE=InnoDB COMMENT='不可修改审计日志';

DROP TRIGGER IF EXISTS trg_audit_log_no_update;
DELIMITER //
CREATE TRIGGER trg_audit_log_no_update BEFORE UPDATE ON audit_log
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only';
END//
DROP TRIGGER IF EXISTS trg_audit_log_no_delete;
CREATE TRIGGER trg_audit_log_no_delete BEFORE DELETE ON audit_log
FOR EACH ROW
BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only';
END//
DELIMITER ;

-- 批量导入批次
CREATE TABLE IF NOT EXISTS import_batch (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  batch_no       VARCHAR(64)  NOT NULL,
  file_name      VARCHAR(255) NOT NULL,
  status         VARCHAR(16)  NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING/FINISHED/FAILED',
  total_rows     INT          NOT NULL DEFAULT 0,
  success_rows   INT          NOT NULL DEFAULT 0,
  duplicate_rows INT          NOT NULL DEFAULT 0,
  failed_rows    INT          NOT NULL DEFAULT 0,
  operator       VARCHAR(64)  NOT NULL DEFAULT 'anonymous',
  message        VARCHAR(512) NULL,
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at    DATETIME(3)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_import_batch_no (batch_no)
) ENGINE=InnoDB COMMENT='批量导入批次';

-- 批量导入逐行结果
CREATE TABLE IF NOT EXISTS import_row (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  import_batch_id BIGINT       NOT NULL,
  row_no          INT          NOT NULL,
  status          VARCHAR(16)  NOT NULL COMMENT 'SUCCESS/DUPLICATE/FAILED',
  raw_line        VARCHAR(1024) NULL,
  error_msg       VARCHAR(512) NULL,
  entity_id       VARCHAR(64)  NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_import_row_batch (import_batch_id)
) ENGINE=InnoDB COMMENT='批量导入行结果';

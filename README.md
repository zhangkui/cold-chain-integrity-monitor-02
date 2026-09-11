# 生物样本冷链完整性监测系统（Cold Chain Integrity）

接收冷链箱**温度采样、开箱事件、转运节点、校准记录**，按设备时区纠正时间，用**哈希链**校验采样是否被篡改，自动检测**连续超温 / 传感器离线 / 采样间隔异常**，并按箱体规则计算**有效冷链时长**。在此基础上提供**箱体综合评估**（综合温控规则、有效冷链时长、未驳回异常、哈希链、采样覆盖与转运时间线，输出结论/关键指标/风险/评估时间，版本化仅追加、不可静默判合格）。支持**幂等摄入、补传不覆盖已确认异常、异常复核、证据附件版本管理、不可修改审计日志、CSV 批量导入**。

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Java 17、Spring Boot 3.2、MyBatis-Plus 3.5、MySQL 8、Redis 7、Caffeine（本地缓存） |
| 前端 | Vue 3、TypeScript、Vite 5、Element Plus、Pinia、ECharts、Axios |
| 部署 | Docker Compose（MySQL + Redis + 后端 + 前端 Nginx） |

## 一键启动（不依赖本机 Java/Node/MySQL）

```bash
docker compose up --build
```

首次启动会：

1. 启动 MySQL 8 并自动执行 `deploy/mysql/init/01_schema.sql` 建表；
2. 启动 Redis 7；
3. 构建并启动后端（多阶段 Maven 构建，镜像内完成），健康检查通过后；
4. 构建并启动前端（镜像内 `npm ci && npm run build`，Nginx 托管并反代 `/api`）；
5. 后端自动写入一套演示数据（2 台设备、3 个冷链箱、跨 2 天的采样、开箱事件、转运节点、校准记录，其中包含超温/离线/间隔异常与一处被篡改的采样）。

启动完成后访问：

- 前端：<http://localhost:8088>
- 后端 API：<http://localhost:8080/api>
- 健康检查：<http://localhost:8080/actuator/health>

停止并清空数据：`docker compose down -v`

## 页面说明

- **箱体列表**：按箱号、批次、温度区间、转运节点、状态筛选；显示每箱规则、有效冷链时长、异常数，以及最新综合评估的结论、生成时间与主要风险。
- **温度曲线**：按箱查看跨日温度曲线（UTC↔设备本地时间切换），叠加规则上下限与异常区段；无数据时显示空状态；抽屉顶部展示最近评估并可一键进入评估详情。
- **转运时间线**：节点计划/实际时间、开箱事件、异常按时间合并展示，可作为评估风险的证据跳转目标。
- **综合评估**：生成/查看版本化评估（组成指标、规则快照、数据时间边界、风险跳转异常或时间线）；重复触发产生新版本，历史不覆盖。
- **异常复核**：确认 / 驳回 / 重开，上传证据附件（自动版本化，保留历史版本与 SHA-256 指纹）。
- **批量导入结果**：上传 CSV，展示成功 / 幂等重复 / 失败逐行结果。

## 关键设计

### 时间与时区
设备上报时间为“设备本地时间 + 设备 IANA 时区”（或直接带偏移量）。摄入时统一转换为 **UTC 存储**（`sample_time_utc`），同时保留纠正后的本地时间（`sample_time_local`），曲线可按设备时区跨日绘制。

### 哈希链（防篡改）
每个设备一条链，按序号递增：

```
content_hash = SHA256(deviceId|seq|utcTime|temperature)
chain_hash   = SHA256(prevHash | contentHash)
prevHash     = 该设备上一条采样的 chain_hash（首条为全 0）
```

`content_hash/prev_hash/chain_hash` 写入后不再更新。后端提供链校验接口，重新计算并逐环比对，任何对历史采样时间或温度的直接改库都会使后续全部环失配，产生 `HASH_BROKEN` 异常（**直接改库不会触发应用层检测，需调用校验接口或等待定时校验**）。

### 异常检测
- **连续超温**：连续采样温度超出箱体 `[temp_min, temp_max]`，持续时长达到 `excursion_seconds`（默认 300s）。
- **传感器离线**：相邻采样间隔超过 `offline_seconds`（默认 900s）。
- **采样间隔异常**：间隔落在箱体规则区间 `[interval_min, interval_max]` 之外（但未达到离线阈值）。
检测按设备序号顺序在摄入时增量进行；也可通过 `/api/admin/recompute` 全量重算（已 CONFIRMED/REJECTED 的异常保留不被覆盖）。

### 幂等与补传
- 幂等键：`deviceId|utcTime|seq`（事件为 `deviceId|type|utcTime`），数据库唯一约束兜底，重复提交直接返回已存在记录，不重复计费/告警。
- 补传数据 `source=BACKFILL`：只用于补全链与重新检测；**状态为 CONFIRMED 的异常永不被补传/重算覆盖或删除**，新发现的异常另行插入。
- 前端重复点击/断网重试均带同一 `Idempotency-Key` 请求头。

### 审计
所有关键写操作（采样、复核、附件、导入、重算、评估生成/查看/失败）向 `audit_log` 追加 JSON 快照；表上有 `BEFORE UPDATE/DELETE` 触发器，数据库层面拒绝修改与删除。

### 箱体综合评估（版本化）
评估综合六个维度给出结论：**温控规则、有效冷链时长、未驳回异常、哈希链校验、采样覆盖、转运时间线**。

- 结论四档：`PASS` 合格 / `FAIL` 不合格 / `NEEDS_REVIEW` 需复核 / `UNASSESSABLE` 不可评估。
- **不可评估守卫**（绝不静默判合格）：无采样、未绑定设备、规则上下限反向（min≥max）。
- **需复核**：哈希链断裂或未校验、采样时间倒序、存在待复核 WARN 异常等；链完整性存疑时优先于合格/不合格。
- 已 `REJECTED` 异常不参与扣减与结论；评估内的链校验为**只读**，不产生新异常。
- 每次结果连同**规则快照**与**数据时间边界**（采样/入库/转运窗口、评估时刻、转运覆盖占比）持久化到 `box_assessment`；重复触发 `version` 自增、历史永不覆盖（唯一约束 `(box_id, version)`）。
- 生成/查看/失败写追加式审计（`ASSESS_GENERATE` / `ASSESS_VIEW` / `ASSESS_*_FAILED`），操作人取请求身份头 `X-Operator`。
- 错误区分：`404 BOX_NOT_FOUND`（箱体不存在）、`422 DATA_INSUFFICIENT`（不可评估结果已落库，响应 `data` 携带）、`500 ASSESSMENT_FAILED`（内部失败）。

## API 摘要

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/ingest/samples` | 温度采样摄入（支持批量、补传、幂等头） |
| POST | `/api/ingest/events` | 开箱/关门事件 |
| POST | `/api/ingest/nodes` | 转运节点上报 |
| POST | `/api/ingest/calibrations` | 校准记录 |
| GET | `/api/boxes` | 箱体列表（含 `latestAssessment` 最新评估徽标） |
| GET | `/api/boxes/{id}` | 箱体详情（含有效冷链时长与最新评估） |
| GET | `/api/boxes/{id}/samples` | 采样序列（from/to、时区切换） |
| GET | `/api/boxes/{id}/timeline` | 转运时间线 |
| POST | `/api/boxes/{id}/assessments` | 生成评估（总是新版本；数据不足返回 422 且已留存） |
| GET | `/api/boxes/{id}/assessments/latest` | 最新评估（组成指标/风险/规则/边界） |
| GET | `/api/boxes/{id}/assessments` | 评估历史版本列表 |
| GET | `/api/boxes/{id}/assessments/versions/{v}` | 指定历史版本 |
| GET | `/api/anomalies` | 异常列表（按箱/状态/类型） |
| POST | `/api/anomalies/{id}/review` | 复核 CONFIRM/REJECT/REOPEN |
| POST | `/api/anomalies/{id}/evidence` | 上传证据（multipart，自动版本号） |
| GET | `/api/anomalies/{id}/evidence` | 证据版本列表 |
| POST | `/api/imports/samples` | CSV 批量导入（multipart） |
| GET | `/api/imports/{batchNo}` | 导入批次与逐行结果 |
| POST | `/api/admin/verify-chain?deviceId=` | 校验哈希链 |
| POST | `/api/admin/recompute` | 全量重算异常（保留已复核结论） |
| GET | `/api/admin/audit-logs` | 审计日志查询（可按 `BOX_ASSESSMENT` 过滤） |

采样上报示例：

```json
POST /api/ingest/samples
{ "samples": [
  { "deviceCode": "DEV-001", "seq": 101,
    "sampleTime": "2026-09-09T08:00:00", "timezone": "Asia/Shanghai",
    "temperature": -78.20, "source": "REALTIME" } ] }
```

## 目录结构

```
docker-compose.yml
deploy/mysql/init/01_schema.sql   # 自动建表 + 审计触发器
backend/                          # Spring Boot 3（含 Dockerfile，镜像内 Maven 构建）
frontend/                         # Vue3 + Vite（含 Dockerfile，镜像内构建 + Nginx）
samples/temperature-sample.csv    # 批量导入示例
```

## 本地开发（可选）

- 后端：`cd backend && ./mvnw spring-boot:run`（需本机 JDK17、可连接的 MySQL/Redis，默认连 localhost）。
- 前端：`cd frontend && npm install && npm run dev`（Vite 代理 `/api` 到 8080）。

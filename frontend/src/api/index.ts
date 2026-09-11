import { get, post, upload } from './http'

export interface BoxListItem {
  id: number
  boxCode: string
  batchNo: string
  specimenType?: string
  status: string
  tempMin: number
  tempMax: number
  deviceCode?: string
  deviceName?: string
  timezone?: string
  firstSampleUtc?: string
  lastSampleUtc?: string
  sampleCount: number
  coveredSeconds?: number
  excursionSeconds?: number
  offlineSeconds?: number
  validColdChainSeconds?: number
  openAnomalyCount: number
  totalAnomalyCount: number
  latestAssessment?: AssessmentBadge | null
}

export type AssessmentConclusion = 'PASS' | 'FAIL' | 'NEEDS_REVIEW' | 'UNASSESSABLE'

export interface AssessmentRisk {
  code: string
  severity: 'INFO' | 'WARN' | 'CRITICAL'
  message: string
  blocker: boolean
  refType: 'ANOMALY' | 'TIMELINE' | 'SAMPLE' | 'RULE'
  anomalyId?: number | null
  anomalyType?: string | null
  refTimeUtc?: string | null
}

export interface AssessmentMetric {
  key: string
  label: string
  value: string
  unit?: string | null
  status: 'OK' | 'WARN' | 'BAD' | 'INFO' | 'NA'
  detail?: string
}

export interface RuleSnapshot {
  tempMin: number
  tempMax: number
  excursionSeconds: number
  offlineSeconds: number
  intervalMinSeconds: number
  intervalMaxSeconds: number
  failValidRatio: number
  warnValidRatio: number
  nodeDelayToleranceSeconds: number
  ruleVersion: string
}

export interface DataBoundary {
  evaluatedAtUtc?: string | null
  samplesFromUtc?: string | null
  samplesToUtc?: string | null
  receivedFromUtc?: string | null
  receivedToUtc?: string | null
  shipmentStartUtc?: string | null
  shipmentEndUtc?: string | null
  shipmentCoverageRatio?: number | null
  sampleCount: number
}

export interface AssessmentBadge {
  id: number
  version: number
  conclusion: AssessmentConclusion
  conclusionLabel: string
  chainStatus: string
  summary: string
  generatedAt: string
  topRisk?: AssessmentRisk | null
}

export interface AssessmentView {
  id: number
  boxId: number
  boxCode?: string
  batchNo?: string
  version: number
  conclusion: AssessmentConclusion
  conclusionLabel: string
  chainStatus: string
  chainChecked: number
  summary: string
  primaryRisks: AssessmentRisk[]
  metrics: AssessmentMetric[]
  ruleSnapshot: RuleSnapshot
  dataBoundary: DataBoundary
  ruleVersion: string
  generatedBy: string
  generatedAt: string
}

export interface AssessmentSummary {
  id: number
  boxId: number
  version: number
  conclusion: AssessmentConclusion
  conclusionLabel: string
  chainStatus: string
  summary: string
  generatedBy: string
  generatedAt: string
}

export interface SamplePoint {
  id: number
  seq: number
  timeUtc: string
  timeLocal: string
  temperature: number
  source: string
  contentHash: string
  prevHash: string
  chainHash: string
  gapSeconds?: number
  inRange: boolean
}

export interface TimelineItem {
  kind: 'NODE' | 'EVENT' | 'ANOMALY'
  timeUtc?: string
  timeLocal?: string
  title: string
  detail?: string
  type?: string
  status?: string
}

export interface TimelineResponse {
  boxId: number
  boxCode: string
  shipmentNo?: string
  timezone?: string
  items: TimelineItem[]
}

export interface AnomalyView {
  id: number
  boxId: number
  boxCode?: string
  batchNo?: string
  deviceCode?: string
  type: string
  severity: string
  startTimeUtc: string
  endTimeUtc: string
  durationSeconds: number
  peakTemp?: string
  sampleCount?: number
  description?: string
  status: string
  confirmedBy?: string
  reviewComment?: string
  confirmedAt?: string
  firstSeenAt?: string
}

export interface Evidence {
  id: number
  anomalyId: number
  version: number
  fileName: string
  fileHash: string
  sizeBytes: number
  contentType?: string
  note?: string
  uploadedBy: string
  createdAt: string
}

export interface ImportResult {
  id: number
  batchNo: string
  fileName: string
  status: string
  totalRows: number
  successRows: number
  duplicateRows: number
  failedRows: number
  operator: string
  message?: string
  createdAt: string
  finishedAt?: string
  rows?: Array<{
    rowNo: number
    status: string
    rawLine?: string
    errorMsg?: string
    entityId?: string
  }>
}

export interface BoxFilter {
  boxCode?: string
  batchNo?: string
  tempFrom?: number
  tempTo?: number
  nodeCode?: string
  status?: string
}

function params(obj: Record<string, unknown>) {
  const p: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(obj)) {
    if (v !== '' && v !== undefined && v !== null) p[k] = v
  }
  return { params: p }
}

export const api = {
  listBoxes: (f: BoxFilter) => get<BoxListItem[]>('/api/boxes', params({ ...f })),
  boxDetail: (id: number) => get<BoxListItem>(`/api/boxes/${id}`),
  boxSamples: (id: number, query: { from?: string; to?: string; timezone?: string }) =>
    get<SamplePoint[]>(`/api/boxes/${id}/samples`, params(query)),
  boxTimeline: (id: number) => get<TimelineResponse>(`/api/boxes/${id}/timeline`),

  generateAssessment: (id: number) =>
    // 静默：由评估面板按 422(不可评估已落库)/404/500 分别提示，避免双重弹窗
    post<AssessmentView>(`/api/boxes/${id}/assessments`, undefined, { _silent: true }),
  latestAssessment: (id: number) =>
    get<AssessmentView>(`/api/boxes/${id}/assessments/latest`),
  assessmentVersions: (id: number) =>
    get<AssessmentSummary[]>(`/api/boxes/${id}/assessments`),
  assessmentVersion: (id: number, version: number) =>
    get<AssessmentView>(`/api/boxes/${id}/assessments/versions/${version}`),

  listAnomalies: (f: { boxId?: number; type?: string; status?: string }) =>
    get<AnomalyView[]>('/api/anomalies', params(f)),
  reviewAnomaly: (id: number, body: { action: string; comment?: string }) =>
    post(`/api/anomalies/${id}/review`, body),
  listEvidence: (id: number) => get<Evidence[]>(`/api/anomalies/${id}/evidence`),
  uploadEvidence: (id: number, formData: FormData) =>
    upload<Evidence>(`/api/anomalies/${id}/evidence`, formData),

  importSamples: (formData: FormData) =>
    upload<ImportResult>('/api/imports/samples', formData, { _retries: 0 }),
  importResult: (batchNo: string) => get<ImportResult>(`/api/imports/${batchNo}`),
  recentImports: () => get<ImportResult[]>('/api/imports'),

  verifyChain: () => post('/api/admin/verify-chain'),
  recompute: () => post('/api/admin/recompute'),
  auditLogs: (f: { entityType?: string; action?: string }) =>
    get('/api/admin/audit-logs', params(f))
}

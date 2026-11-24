export type Submission = { submission_id: string; text: string; source_ids?: string[]; language?: string; needs_input?: boolean }
export type Criterion = { id: string; description: string; points: number }
export type TestCase = { id: string; input: string; expected: string; points: number }
export type Question = {
  id: string
  prompt: string
  kind: 'TEXT' | 'EXACT' | 'CODE'
  max_score: number
  reference_answer?: string
  rubric?: Criterion[]
  accepted?: string[]
  tests?: TestCase[]
  answers: Submission[]
  source_ids?: string[]
}
export type Source = {
  id: string; asset_id?: string; name: string; text: string; role: string; submission_id: string
  page?: number; paragraph?: number; line?: number; table?: number; row?: number; column?: number
  region?: { x: number; y: number; width: number; height: number }
}
export type Material = { asset_id: string; name: string; format: string; role: string; submission_id: string }
export type Job = { id: string; title: string; created_at: string; status: string; revision: number; run: number; error_code?: string; counts?: Record<string, number> }
export type Draft = { questions: Question[]; diagnostics?: {code: string; asset_id?: string; page?: number}[] }
export type DraftView = { draft: Draft; sources: Source[]; submissions: string[]; materials: Material[] }
export type Template = { id: string; version: number; title: string; questions: Omit<Question, 'answers'>[] }
export type ScoredCriterion = { id: string; points: number; max_points: number; reason: string; description?: string; detail?: string; quote?: string; start?: number; end?: number; wall_time_ms?: number; source_ids?: string[] }
export type LineRange = { start: number; end: number }
export type SimilarityAlert = {
  question_id: string; left_submission_id: string; right_submission_id: string; language: string; score: number; summary: string; level: 'HIGH' | 'MEDIUM'
  matches: { left_lines: LineRange; right_lines: LineRange; structure_tokens: number }[]
}
export type ModelUsage = { agent_role: string; model: string; input_tokens: number | null; output_tokens: number | null; latency_ms: number }
export type ModelTrace = { roles?: string[]; usage?: ModelUsage[]; engine_version?: string | null; review_error?: string }
export type Item = {
  ordinal: number; revision: number; status: string; submission_id: string; question_id: string; prompt: string; kind: string
  max_score: number; answer: string; source_ids: string[]; error_code?: string
  result: null | { final_score?: number | null; suggested_score?: number | null; criteria?: ScoredCriterion[]; review_reason?: string;
    review_summary?: string; reviewer_revised?: boolean; initial_criteria?: ScoredCriterion[]; risk_signals?: string[];
    decision?: 'RULE' | 'AGENTS' | 'JUDGE' | 'TEACHER'; trace?: ModelTrace;
    findings?: { quote: string; reason: string; line: number }[]; analysis_error?: string }
}
export type Report = {
  run: number; latest_run: number; items: Item[]; sources: Source[]; materials: Material[]
  similarity_status: 'NOT_APPLICABLE' | 'PENDING' | 'COMPLETED' | 'FAILED'
  similarity_alerts: SimilarityAlert[]
  submissions: {submission_id: string; max_score: number; suggested_points_so_far: number; final_points_so_far: number; scored_items: number; final_items: number; total_items: number}[]
}
export const labels: Record<string, string> = {
  PREPARING: '解析中', AWAITING_CONFIRMATION: '等待确认细则', PREPARATION_FAILED: '解析失败',
  QUEUED: '排队中', GRADING: '批改中', RUNNING: '批改中', REVIEW_REQUIRED: '待人工复核', COMPLETED: '已完成',
  CANCELLED: '已取消', SCORED: '已出分', FAILED: '批改失败',
}

export type Profile = { display_name: string; tenant_id: string; workspaces: {tenant_id:string; name:string}[]; can_manage_keys:boolean }
export type Key = { id:string; name:string; expires_at:string; revoked_at?:string; api_key?:string }
export type CollectionStatus = 'OPEN' | 'CLOSED' | 'CONVERTED'
export type Collection = { id: string; title: string; description: string; status: CollectionStatus; share_token: string; job_id: string | null; created_at: string; updated_at: string; entry_count: number }
export type CollectionEntry = { id: string; submission_id: string; answer_text: string | null; language: string | null; asset_ids: string[]; submitted_at: string }
export type CollectionDetail = Collection & { entries: CollectionEntry[] }
export type PublicCollection = Pick<Collection,'title' | 'description' | 'status'> & { allowed_file_types: string[] }
export type Capabilities = Record<'text_model' | 'vision' | 'ocr' | 'similarity', { ready: boolean }> & {judge: {ready: boolean; languages: string[]}}
export type ModelConfig = {
  configured: boolean
  server_default_configured: boolean
  provider?: string
  base_url?: string
  model_name?: string
  response_format?: string
  api_key_masked?: string
  updated_at?: string
}
export type ModelTestResult = { ok: boolean; model?: string; latency_ms?: number; error?: string }
export type ZipImport = { items: {asset_id: string; name: string; size_bytes: number; suggested_submission_id: string}[]; skipped: {name: string; reason: string}[] }

export const APP_NAME = 'SmartEval 智能作业批改与核验系统'
export const APP_VERSION = 'V1.0'

export type RecentAnalyticsJob = {
  id: string; title: string; status: string; created_at: string
  submission_count: number; mean_rate: number; pass_rate: number; review_required_count: number; similarity_alerts: number
}
export type AnalyticsOverview = {
  total_jobs: number; active_jobs: number; awaiting_confirmation_jobs: number; review_required_jobs: number; completed_jobs: number
  total_items: number; scored_items: number; review_required_items: number; failed_items: number; human_reviewed_items: number
  similarity_high_alerts: number; similarity_medium_alerts: number
  recent_jobs: RecentAnalyticsJob[]
}
export type JobAnalytics = {
  job_id: string; title: string; run: number; latest_run: number; submission_count: number; final_submission_count: number; max_total_score: number
  summary: { mean_score: number; median_score: number; max_score: number; min_score: number; std_dev: number; mean_rate: number; pass_rate: number; excellent_rate: number }
  distribution: { label: string; count: number }[]
  questions: { question_id: string; kind: string; max_score: number; scored_count: number; average_score: number; score_rate: number; review_required_count: number; failed_count: number; teacher_reviewed_count: number }[]
  review_stats: { auto_scored: number; teacher_reviewed: number; review_required: number; failed: number; reviewer_revised: number }
  similarity: { status: string; high_count: number; medium_count: number }
}
export type WorkspaceMember = {
  member_id: string; user_id: string; username: string; display_name: string; status: 'ACTIVE' | 'DISABLED'; roles: string[]; created_at: string
}
export type AuditLogEntry = {
  id: string; actor_type: string; actor_id: string; actor_name: string; actor_username?: string | null
  action: string; target_type: string; target_id?: string | null; trace_id: string; details: Record<string, unknown>; created_at: string
}
export const riskSignalLabels: Record<string, string> = {
  ANCHOR_MISMATCH: '引用未命中原文', DECLARED_TOTAL_MISMATCH: '总分求和不一致',
  REVIEW_AGENT_UNAVAILABLE: '复核不可用', REVIEWER_VETO: '复核异议', REVIEWER_REVISED: '双阶段已修订',
}
export const auditActionLabels: Record<string, string> = {
  JOB_CONFIRMED: '确认批改细则', ITEM_REVIEWED: '人工复核改分',
  MEMBER_CREATED: '新增成员账号', MEMBER_UPDATED: '更新成员权限/状态',
  API_KEY_CREATED: '签发 API 密钥', API_KEY_REVOKED: '撤销 API 密钥',
  MODEL_CONFIG_UPDATED: '更新个人模型密钥', MODEL_CONFIG_CLEARED: '清除个人模型密钥',
  PASSWORD_RESET: '密码重置', RECOVERY_ADDRESS_VERIFIED: '验证找回邮箱',
}

export const languages = ['PYTHON','JAVA','CPP','C','JAVASCRIPT']
export const fileTypes = '.pdf,.doc,.docx,.jpg,.jpeg,.png,.txt,.py,.java,.cpp,.c,.js'
export const collectionLabels: Record<CollectionStatus,string> = { OPEN:'收卷中', CLOSED:'已关闭', CONVERTED:'已转批改' }
export const activeJob = (job: Job) => ['PREPARING','QUEUED','GRADING'].includes(job.status)
export const humanTime = (iso: string) => new Date(iso).toLocaleString('zh-CN', {hour12:false})
export const stateClass = (status: string) => ['COMPLETED','SCORED','OPEN','CONVERTED'].includes(status) ? 'good'
  : ['PREPARATION_FAILED','FAILED'].includes(status) ? 'bad'
  : ['AWAITING_CONFIRMATION','REVIEW_REQUIRED','CLOSED'].includes(status) ? 'warn' : 'run'
export const diagnostics: Record<string, string> = {
  VERIFY_TRANSCRIPTION: '请对照原件确认手写识别结果', VISION_FALLBACK_OCR: '图像识别已回退到 OCR，请核对原文',
  LOW_OCR_CONFIDENCE: '文字识别把握较低', OCR_ENGINE_UNAVAILABLE: 'OCR 未配置', OCR_FAILED: 'OCR 未能识别此页',
  EXTRACTION_FAILED: '材料解析失败，可对照原件手动录入', EMPTY_MATERIAL: '未提取到有效文字',
  QUESTION_SPLIT_REQUIRES_REVIEW: '请检查分题与跨页作答', QUESTION_AGENT_FAILED: '识题 Agent 未能完成',
  RUBRIC_AGENT_FAILED: '评分标准 Agent 未能完成', RUBRIC_REQUIRES_INPUT: '请补充评分细则',
  READER_CONTEXT_LIMIT: '材料超出自动识题的长度限制', EXTRACTION_LIMIT: '材料超出解析上限',
  PDF_PAGE_LIMIT: 'PDF 未处理完毕，请确认余下页面', WORD_IMAGE_UNSUPPORTED: 'Word 中有未识别的嵌入图像',
  WORD_PARSE_LIMIT: 'Word 未处理完毕，请检查余下段落或插图',
  MODEL_NOT_CONFIGURED: '尚未配置主观题模型', AI_UNAVAILABLE: '智能服务暂不可用',
  JUDGE_UNAVAILABLE: '判题服务或确认时使用的语言配置不可用', JUDGE_ERROR: '判题服务执行失败',
  JUDGE_WAIT_TIMEOUT: '判题服务等待超时', INVALID_GRADE: '模型返回的评分未通过校验', LEASE_EXPIRED: '处理超时',
}

export function randomId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  const bytes = new Uint8Array(16)
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    crypto.getRandomValues(bytes)
  } else {
    for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256)
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

export async function copyText(text: string): Promise<void> {
  if (typeof navigator !== 'undefined' && navigator.clipboard && window.isSecureContext) {
    await navigator.clipboard.writeText(text)
    return
  }
  const area = document.createElement('textarea')
  area.value = text
  area.setAttribute('readonly', '')
  area.style.position = 'fixed'
  area.style.opacity = '0'
  document.body.appendChild(area)
  area.select()
  try {
    if (!document.execCommand('copy')) throw new Error('当前环境不支持自动复制，请手动复制。')
  } finally {
    document.body.removeChild(area)
  }
}

export function fileStem(name: string, fallback = '答卷 1'): string {
  const base = name.replace(/\\/g, '/').split('/').pop() ?? ''
  const dot = base.lastIndexOf('.')
  const stem = (dot > 0 ? base.slice(0, dot) : base).trim().slice(0, 100)
  return stem || fallback
}

export function sanitizeQuestions(questions: Question[], standardsOnly = false): Record<string, unknown>[] {
  return questions.map(q => {
    const out: Record<string, unknown> = { id: q.id, prompt: q.prompt, kind: q.kind, max_score: q.max_score }
    if (q.kind === 'TEXT') {
      if (q.reference_answer?.trim()) out.reference_answer = q.reference_answer
      if (q.rubric?.length) out.rubric = q.rubric
    } else if (q.kind === 'EXACT') {
      const cleaned = (q.accepted ?? []).map(item => item.trim()).filter(Boolean)
      if (cleaned.length) out.accepted = cleaned
    } else if (q.kind === 'CODE') {
      if (q.tests?.length) out.tests = q.tests
    }
    if (!standardsOnly) {
      if (q.source_ids?.length) out.source_ids = q.source_ids
      out.answers = (q.answers ?? []).map(a => {
        const entry: Record<string, unknown> = { submission_id: a.submission_id, text: a.text }
        if (q.kind === 'CODE' && a.language) entry.language = a.language
        if (a.needs_input) entry.needs_input = true
        if (a.source_ids?.length) entry.source_ids = a.source_ids
        return entry
      })
    }
    return out
  })
}

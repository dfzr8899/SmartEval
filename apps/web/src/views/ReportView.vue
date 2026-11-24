<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api, exportEvalDataset, exportGrades } from '../api'
import { activeJob, diagnostics, humanTime, type Item, type Job, type JobAnalytics, type Report, type Template } from '../grading'
import { useAction } from '../useAction'
import GlassModal from '../components/GlassModal.vue'
import StatusPill from '../components/StatusPill.vue'
import ReportEvidence from '../ReportEvidence.vue'
import SimilarityPanel from '../SimilarityPanel.vue'

const props=defineProps<{job:Job;report:Report;selectedRun?:number;changeRun:(run?:number)=>Promise<void>;
  mutate:(route:string)=>Promise<void>;exclusive:<T>(work:()=>Promise<T>)=>Promise<T>;reload:()=>Promise<void>}>()
const {action,busy,error,notice}=useAction()
const filter=ref('')
const templateName=ref('')
const targetTemplateId=ref('')
const existingTemplates=ref<{id:string;title:string;version:number}[]>([])
const analytics=ref<JobAnalytics>()
const review=ref<Item>()
const points=ref<number|''>('')
const reason=ref('')
type ReviewRow={revision:number;reason:string;created_at:string;revised:{final_score:number}}
const history=ref<ReviewRow[]>([])
const rows=computed(() => props.report.items.filter(item => !filter.value || filter.value===item.submission_id))
const latest=computed(() => props.report.run===props.report.latest_run)
const usage=computed(() => {
  const calls=props.report.items.flatMap(item => item.result?.trace?.usage ?? [])
  return {count:calls.length, input:calls.reduce((sum,c) => sum+(c.input_tokens ?? 0),0),
    output:calls.reduce((sum,c) => sum+(c.output_tokens ?? 0),0),
    complete:calls.filter(c => c.input_tokens != null && c.output_tokens != null).length,
    roles:[...new Set(props.report.items.flatMap(item => item.result?.trace?.roles ?? []))]}
})
watch(() => props.report.run,() => { review.value=undefined; filter.value='' })
const path=() => '/grading-jobs/'+props.job.id

async function loadTemplates() {
  try {
    const list=(await api<Template[]>('/grading-templates')).data
    const byId=new Map<string,{id:string;title:string;version:number}>()
    for (const item of list) {
      const prior=byId.get(item.id)
      if (!prior || item.version>prior.version) byId.set(item.id,{id:item.id,title:item.title,version:item.version})
    }
    existingTemplates.value=Array.from(byId.values())
  } catch { /* optional helper list */ }
}
async function loadAnalytics() {
  try {
    analytics.value=(await api<JobAnalytics>(path()+'/analytics?run='+props.report.run)).data
  } catch { /* optional summary */ }
}
watch(() => [props.report.run, props.report.items.length, props.job.revision],() => { void loadAnalytics() })
onMounted(() => { void loadTemplates(); void loadAnalytics() })

async function saveTemplate() {
  await action(async () => {
    const body:{title:string;template_id?:string}={title:templateName.value || props.job.title}
    if (targetTemplateId.value) body.template_id=targetTemplateId.value
    await api(path()+'/templates','POST',body)
    templateName.value=''
    targetTemplateId.value=''
    await loadTemplates()
  },'评分模板已保存')
}
function closeReview() {
  review.value=undefined
  error.value=''
}
async function openReview(item:Item) {
  await action(async () => {
    history.value=(await api<ReviewRow[]>(path()+'/reviews/'+item.ordinal+'?run='+props.report.run)).data
    points.value=item.result?.final_score ?? item.result?.suggested_score ?? ''
    reason.value=''
    review.value=item
  })
}
async function saveReview() {
  await action(() => props.exclusive(async () => {
    const item=review.value
    if (!item) return
    await api(path()+'/reviews/'+item.ordinal,'POST',{run:props.report.run,score:points.value,reason:reason.value},
      {'If-Match':'"'+item.revision+'"'})
    review.value=undefined
    await props.reload()
  }),'人工复核已保存')
}
</script>

<template>
  <p v-if="error && !review" role="alert" class="message error">{{ error }}</p>
  <p v-if="notice" role="status" class="message success">{{ notice }}</p>
  <section class="panel panel-stack">
    <div class="between"><h2>批改报告</h2><div class="row">
      <label>批改轮次<select :value="selectedRun ?? ''" :disabled="busy" @change="action(() => changeRun(Number(($event.target as HTMLSelectElement).value) || undefined))">
        <option value="">最新 · 第 {{ report.latest_run }} 次</option>
        <option v-for="run in report.latest_run-1" :key="run" :value="run">第 {{ run }} 次</option>
      </select></label>
      <button class="secondary" :disabled="busy" @click="action(() => exportGrades(job.id,report.run))">导出 CSV</button>
      <button class="quiet" :disabled="busy" @click="action(() => exportEvalDataset(job.id,report.run))">导出评测集 JSON</button>
    </div></div>
    <div v-if="analytics && analytics.final_submission_count" class="score-list">
      <article><small>平均分 / 中位数</small><div class="score">{{ analytics.summary.mean_score }} <span>/ {{ analytics.summary.median_score }}</span></div></article>
      <article><small>及格率 / 优秀率</small><div class="score">{{ (analytics.summary.pass_rate * 100).toFixed(1) }}% <span>/ {{ (analytics.summary.excellent_rate * 100).toFixed(1) }}%</span></div></article>
      <article><small>双阶段修订 / 人工复核</small><div class="score">{{ analytics.review_stats.reviewer_revised }} <span>/ {{ analytics.review_stats.teacher_reviewed }}</span></div></article>
    </div>
    <p v-if="usage.count" class="muted">模型角色 {{ usage.roles.join('、') }} · 输入 {{ usage.input }} / 输出 {{ usage.output }} Tokens
      <span v-if="usage.complete<usage.count">（{{ usage.count-usage.complete }} 次调用的用量不完整）</span></p>
    <div class="score-list"><article v-for="submission in report.submissions" :key="submission.submission_id">
      <strong>{{ submission.submission_id }}</strong>
      <div v-if="submission.final_items" class="score">{{ submission.final_points_so_far }} <span>/ {{ submission.max_score }}</span></div>
      <p v-else class="muted">等待出分</p><small>已确认 {{ submission.final_items }} / {{ submission.total_items }} 题</small>
    </article></div>
    <div v-if="latest" class="row">
      <label>模板名称<input v-model="templateName" :placeholder="job.title" maxlength="160"></label>
      <label v-if="existingTemplates.length">保存方式<select v-model="targetTemplateId">
        <option value="">新建模板</option>
        <option v-for="tpl in existingTemplates" :key="tpl.id" :value="tpl.id">追加新版本：{{ tpl.title }}（当前 v{{ tpl.version }}）</option>
      </select></label>
      <button class="secondary" :disabled="busy" @click="saveTemplate">保存评分模板</button>
      <button class="quiet" :disabled="busy || activeJob(job)" @click="action(() => mutate('/regrades'))">按原标准重评</button>
    </div>
  </section>
  <SimilarityPanel :key="report.run" :report="report" />
  <div class="between"><h2>逐题结果</h2><select v-model="filter" aria-label="筛选答卷"><option value="">所有答卷</option>
    <option v-for="submission in report.submissions" :key="submission.submission_id">{{ submission.submission_id }}</option>
  </select></div>
  <article v-for="item in rows" :key="report.run+':'+item.ordinal" class="panel panel-stack report-item">
    <div class="between"><h3>{{ item.submission_id }} · 第 {{ item.question_id }} 题</h3><StatusPill :state="item.status" /></div>
    <ReportEvidence :item="item" :sources="report.sources" :materials="report.materials">
      <p v-if="item.error_code" class="message error">{{ diagnostics[item.error_code] ?? item.error_code }}</p>
      <div v-if="item.result" class="stack result">
        <strong v-if="item.result.final_score != null">确认分 {{ item.result.final_score }} / {{ item.max_score }}</strong>
        <strong v-else-if="item.result.suggested_score != null">建议分 {{ item.result.suggested_score }} / {{ item.max_score }}</strong>
        <p v-if="item.result.review_reason">{{ item.result.review_reason }}</p>
        <p v-if="item.result.trace?.roles?.length" class="muted">{{ item.result.trace.roles.join(' → ') }}</p>
        <p v-if="item.result.analysis_error" class="muted">代码分析 Agent 暂不可用</p>
      </div>
    </ReportEvidence>
    <div v-if="['REVIEW_REQUIRED','SCORED','FAILED'].includes(item.status)" class="row">
      <button class="secondary" :disabled="busy" @click="openReview(item)">{{ latest ? '人工复核' : '查看复核记录' }}</button>
      <button v-if="item.status==='FAILED' && latest" class="quiet" :disabled="busy" @click="action(() => mutate('/retry?item='+item.ordinal))">重试本题</button>
    </div>
  </article>
  <GlassModal v-if="review" :title="'人工复核 · 第 '+review.question_id+' 题'" @close="closeReview">
    <p v-if="error" class="message error" role="alert">{{ error }}</p>
    <details v-if="history.length" open><summary>改分历史</summary>
      <p v-for="record in history" :key="record.revision">{{ humanTime(record.created_at) }} · {{ record.revised.final_score }} 分 · {{ record.reason }}</p>
    </details>
    <p v-else-if="!latest" class="muted">这一轮暂无人工复核记录。</p>
    <form v-if="latest" class="stack" @submit.prevent="saveReview">
      <label>确认分<input v-model.number="points" required type="number" min="0" :max="review.max_score"></label>
      <label>复核理由<textarea v-model="reason" required maxlength="1000" rows="3"></textarea></label>
      <button class="primary self-start" :disabled="busy">保存复核</button>
    </form>
  </GlassModal>
</template>

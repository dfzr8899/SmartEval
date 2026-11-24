<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { api, assetUrl } from '../api'
import { activeJob, diagnostics, humanTime, sanitizeQuestions, type DraftView, type Job, type Material, type Report, type Source } from '../grading'
import { useAction } from '../useAction'
import QuestionEditor from '../QuestionEditor.vue'
import StatusPill from '../components/StatusPill.vue'
import GlassModal from '../components/GlassModal.vue'
import ReportView from './ReportView.vue'

const props = defineProps<{ id:string }>()
const emit = defineEmits<{ back: [] }>()
const { action,busy,error,notice } = useAction()
const job = ref<Job>()
const tag = ref('')
const draft = ref<DraftView>()
const report = ref<Report>()
const run = ref<number>()
const pollError = ref('')
const evidence = ref<{asset?:Material;source?:Source;url?:string;rendered?:boolean;error?:string}>()
let timer: ReturnType<typeof setInterval>
let lastState = ''
let polling = false
let childMutating = 0
let live = true
const path = () => '/grading-jobs/' + props.id
const version = () => ({'If-Match':tag.value})
const paused = () => busy.value || childMutating > 0

async function runExclusive<T>(work: () => Promise<T>): Promise<T> {
  childMutating++
  try { return await work() }
  finally { childMutating-- }
}

async function refresh(force = false, signal?: AbortSignal) {
  const status = await api<Job>(path(),'GET',undefined,{},signal)
  if (!live || signal?.aborted || signal && paused()) return
  const state = status.data
  job.value = state
  tag.value = status.tag
  if (state.status === 'AWAITING_CONFIRMATION' && !draft.value) {
    const response = await api<DraftView>(path()+'/draft','GET',undefined,{},signal)
    if (live && (!signal || !paused())) { draft.value=response.data; tag.value=response.tag }
  } else if (state.run > 0) {
    draft.value = undefined
    const snapshot = JSON.stringify([state.revision,state.run,state.status,state.counts,run.value])
    if (force || snapshot !== lastState || report.value?.similarity_status === 'PENDING') {
      const response = await api<Report>(path()+'/report'+(run.value ? '?run='+run.value : ''),'GET',undefined,{},signal)
      if (live && (!signal || !paused())) { report.value=response.data; lastState=snapshot }
    }
  }
}
async function poll() {
  if (!job.value || paused() || polling || !(activeJob(job.value) || job.value.status==='AWAITING_CONFIRMATION' && !draft.value
      || report.value?.similarity_status==='PENDING' || job.value.run>0 && !report.value)) return
  polling = true
  try { await refresh(false,AbortSignal.timeout(15_000)); pollError.value='' }
  catch (failure) { pollError.value=String(failure) }
  finally { polling=false }
}
onMounted(() => { void action(() => refresh()); timer=setInterval(poll,1800) })
onUnmounted(() => { live=false; clearInterval(timer); closeEvidence() })

function addQuestion() {
  if (!draft.value) return
  const rows=draft.value.draft.questions
  let n=rows.length+1
  while (rows.some(q => q.id === String(n))) n++
  rows.push({id:String(n),prompt:'',kind:'TEXT',max_score:10,rubric:[],
    answers:draft.value.submissions.map(submission_id => ({submission_id,text:''}))})
}
async function save(confirm: boolean) {
  await action(async () => {
    if (!draft.value) return
    const payload = { ...draft.value.draft, questions: sanitizeQuestions(draft.value.draft.questions) }
    const saved=await api<Job>(path()+'/draft','PATCH',payload,version())
    tag.value=saved.tag
    job.value=saved.data
    if (!confirm) return
    await api(path()+'/confirm','POST',undefined,version())
    await refresh(true)
  },confirm ? '已确认评分细则，任务已入队' : '草案已保存')
}
async function mutate(route: string) {
  await runExclusive(async () => {
    await api(path()+route,'POST',undefined,version())
    run.value=undefined
    await refresh(true)
  })
}
async function cancel() {
  await action(async () => {
    await api(path(),'DELETE',undefined,version())
    draft.value=undefined
    await refresh(true)
  },'任务已取消')
}
async function switchRun(value: number | undefined) {
  const previous=run.value
  run.value=value
  try { await refresh(true) }
  catch (failure) { run.value=previous; throw failure }
}
function closeEvidence() {
  if (evidence.value?.url) URL.revokeObjectURL(evidence.value.url)
  evidence.value=undefined
}
async function preview(id:string, fromAsset=false) {
  const current=draft.value
  const source=fromAsset ? undefined : current?.sources.find(s => s.id===id)
  const asset=current?.materials.find(m => m.asset_id===(source?.asset_id ?? id))
  if (!asset && !source) return
  closeEvidence()
  evidence.value={asset,source}
  const pending=evidence.value
  if (!asset) return
  try {
    let url: string
    if (['pdf','png','jpg'].includes(asset.format) && source?.page && source.region) {
      try { url=await assetUrl(asset.asset_id,source.page); pending.rendered=true }
      catch { url=await assetUrl(asset.asset_id) }
    } else url=await assetUrl(asset.asset_id)
    if (evidence.value===pending) pending.url=url
    else URL.revokeObjectURL(url)
  } catch (failure) { if (evidence.value===pending) pending.error=String(failure) }
}
async function download() {
  const current=evidence.value
  if (!current?.asset) return
  try {
    const url=await assetUrl(current.asset.asset_id)
    const link=document.createElement('a')
    link.href=url; link.download=current.asset.name; link.click()
    setTimeout(() => URL.revokeObjectURL(url),1000)
  } catch (failure) { if (evidence.value===current) current.error=String(failure) }
}
</script>

<template>
  <button class="quiet back" @click="emit('back')">← 返回列表</button>
  <p v-if="error || pollError" class="message error" role="alert">{{ error || pollError }}</p>
  <p v-if="notice" class="message success" role="status">{{ notice }}</p>
  <p v-if="!job && busy" role="status">正在读取任务…</p>
  <template v-if="job">
    <header class="page-heading between"><div><h1>{{ job.title }}</h1><p>{{ humanTime(job.created_at) }}</p></div><StatusPill :state="job.status" /></header>
    <p v-if="job.error_code" class="message error">{{ diagnostics[job.error_code] ?? job.error_code }}</p>
    <div v-if="job.status==='PREPARATION_FAILED'" class="row">
      <button class="primary" :disabled="busy" @click="action(() => mutate('/retry'))">重试解析</button>
      <button class="quiet danger" :disabled="busy" @click="cancel">取消任务</button>
    </div>
    <template v-if="draft">
      <section class="panel panel-stack">
        <div class="between"><h2>核对题答与评分细则</h2><button class="quiet danger" :disabled="busy" @click="cancel">取消任务</button></div>
        <div v-if="draft.materials.length" class="chips">
          <button v-for="material in draft.materials" :key="material.asset_id" class="chip" @click="preview(material.asset_id,true)">{{ material.name }}</button>
        </div>
        <div v-if="draft.draft.diagnostics?.length" class="message warning">
          <p v-for="(entry,index) in draft.draft.diagnostics" :key="index">{{ diagnostics[entry.code] ?? entry.code }}<span v-if="entry.page"> · 第 {{ entry.page }} 页</span></p>
        </div>
        <details v-if="draft.sources.length">
          <summary>已识别原文（{{ draft.sources.length }}）</summary>
          <div class="sources"><button v-for="source in draft.sources" :key="source.id" type="button" @click="preview(source.id)">
            <strong>{{ source.id }} · {{ source.name }}</strong><span>{{ source.text.slice(0,120) }}</span>
          </button></div>
        </details>
      </section>
      <QuestionEditor :questions="draft.draft.questions" :submissions="draft.submissions" :sources="draft.sources" awaiting @add="addQuestion" @preview="preview" />
      <div class="actions"><button class="secondary" :disabled="busy" @click="save(false)">保存草案</button>
        <button class="primary" :disabled="busy" @click="save(true)">确认细则并开始批改</button></div>
    </template>
    <ReportView v-if="report" :job="job" :report="report" :selected-run="run" :change-run="switchRun"
                :mutate="mutate" :exclusive="runExclusive" :reload="() => refresh(true)" />
  </template>
  <GlassModal v-if="evidence" title="原文对照" @close="closeEvidence">
    <p>{{ evidence.asset?.name ?? evidence.source?.name }}</p>
    <p v-if="evidence.source?.page" class="muted">第 {{ evidence.source.page }} 页</p>
    <p v-if="evidence.error" role="alert" class="message error">{{ evidence.error }}</p>
    <template v-else-if="evidence.url && evidence.asset">
      <div v-if="['jpg','png'].includes(evidence.asset.format) || evidence.rendered" class="evidence-image">
        <img :src="evidence.url" alt="原始答卷">
        <span v-if="evidence.source?.region" class="region" :style="{
          left:evidence.source.region.x*100+'%',top:evidence.source.region.y*100+'%',
          width:evidence.source.region.width*100+'%',height:evidence.source.region.height*100+'%',
        }"></span>
      </div>
      <iframe v-else-if="evidence.asset.format==='pdf'" :src="evidence.url+'#page='+(evidence.source?.page ?? 1)" title="原作业 PDF"></iframe>
      <button class="secondary self-start" @click="download">下载原件</button>
    </template>
    <p v-else-if="evidence.asset" role="status">正在加载原件…</p>
    <pre v-if="evidence.source">{{ evidence.source.text }}</pre>
  </GlassModal>
</template>

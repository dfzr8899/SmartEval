<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { api, downloadAsset, upload } from '../api'
import { copyText, fileTypes, humanTime, sanitizeQuestions, type Collection, type CollectionDetail, type CollectionEntry, type Job, type Question, type Template } from '../grading'
import { useAction } from '../useAction'
import GlassModal from '../components/GlassModal.vue'
import SegmentedControl from '../components/SegmentedControl.vue'
import StatusPill from '../components/StatusPill.vue'
import QuestionEditor from '../QuestionEditor.vue'

const props=defineProps<{ initialId?:string }>()
const emit=defineEmits<{ openJob:[id:string]; select:[id:string] }>()
const {busy,error,notice,action}=useAction()
const rows=ref<Collection[]>([])
const detail=ref<CollectionDetail>()
const templates=ref<Template[]>([])
const creating=ref(false)
const returning=ref<CollectionEntry>()
const title=ref('')
const description=ref('')
const mode=ref('none')
const template=ref('')
const questions=ref<Question[]>([])
const materials=ref<{file:File;role:'QUESTION'|'REFERENCE';id?:string}[]>([])
const share= computed(() => detail.value ? new URL('/collect/'+detail.value.share_token,location.origin).href : '')
const path=() => '/collections/'+detail.value?.id

async function reload() { rows.value=(await api<Collection[]>('/collections')).data }
async function load(id:string) {
  detail.value=(await api<CollectionDetail>('/collections/'+id)).data
  emit('select', id)
}
onMounted(() => action(async () => {
  await reload()
  if (props.initialId) await load(props.initialId)
}))
watch(() => props.initialId, next => {
  if (!next) detail.value = undefined
  else if (detail.value?.id !== next) void action(() => load(next))
})
function closeCreating() { creating.value=false; error.value='' }
function closeReturning() { returning.value=undefined; error.value='' }
async function begin() {
  await action(async () => {
    templates.value=(await api<Template[]>('/grading-templates')).data
    creating.value=true
  })
}
function addQuestion() {
  let n=questions.value.length+1
  while (questions.value.some(q => q.id===String(n))) n++
  questions.value.push({id:String(n),prompt:'',kind:'EXACT',max_score:1,answers:[],accepted:[]})
}
function addMaterials(event:Event, role:'QUESTION'|'REFERENCE') {
  const input=event.target as HTMLInputElement
  const incoming=Array.from(input.files ?? [])
  input.value=''
  if (materials.value.length + incoming.length > 100) { error.value='预置材料最多 100 份。'; return }
  for (const file of incoming) materials.value.push({file,role})
}
async function create() {
  await action(async () => {
    const selected=templates.value.find(t => t.id+':'+t.version===template.value)
    if (mode.value==='template' && !selected) throw new Error('请选择评分模板。')
    if (mode.value==='questions' && !questions.value.length) throw new Error('请添加预置题目。')
    for (const item of materials.value) item.id ??= (await upload(item.file)).asset_id
    const preset_config={
      ...(mode.value==='template' && selected ? {template:{id:selected.id,version:selected.version}} : {}),
      ...(mode.value==='questions' ? {questions:sanitizeQuestions(questions.value, true)} : {}),
      ...(materials.value.length ? {materials:materials.value.map(item => ({asset_id:item.id,role:item.role}))} : {}),
    }
    const created=await api<Collection>('/collections','POST',{title:title.value,description:description.value,
      ...(Object.keys(preset_config).length ? {preset_config} : {})})
    creating.value=false
    title.value=''; description.value=''; questions.value=[]; materials.value=[]; mode.value='none'; template.value=''
    await load(created.data.id)
  },'收卷链接已创建')
}
async function closeCollection() {
  await action(async () => {
    if (!detail.value) return
    const id=detail.value.id
    await api(path(),'PATCH',{status:detail.value.status==='OPEN' ? 'CLOSED' : 'OPEN'})
    await load(id)
  })
}
async function removeEntry() {
  await action(async () => {
    if (!returning.value || !detail.value) return
    const id=detail.value.id
    await api(path()+'/entries/'+returning.value.id,'DELETE')
    returning.value=undefined
    await load(id)
  },'答卷已退回，可用原编号重交')
}
async function convert() {
  await action(async () => {
    const response=await api<Job>(path()+'/create-job','POST')
    emit('openJob',response.data.id)
  })
}
async function copyShare() {
  await action(() => copyText(share.value),'交卷链接已复制')
}
async function download(id:string) {
  await action(() => downloadAsset(id, '答卷-'+id))
}
async function back() {
  detail.value=undefined
  emit('select', '')
  await action(reload)
}
</script>

<template>
  <header class="page-heading between"><div><h1>作业收卷</h1><p>分享免登录交卷链接，将收到的答卷转成批改任务。</p></div>
    <button v-if="!detail" class="primary" :disabled="busy" @click="begin">＋ 创建收卷</button>
  </header>
  <p v-if="error && !creating && !returning" class="message error" role="alert">{{ error }}</p>
  <p v-if="notice" class="message success" role="status">{{ notice }}</p>
  <template v-if="!detail">
    <section class="panel tasks">
      <div class="task-columns task-headers"><span>收卷</span><span>状态</span><span>答卷数量</span><span></span></div>
      <button v-for="row in rows" :key="row.id" class="task-columns task-row" :disabled="busy" @click="action(() => load(row.id))">
        <strong>{{ row.title }}</strong><span><StatusPill :state="row.status" /></span><span>{{ row.entry_count }} 份答卷</span><span aria-hidden="true">›</span>
      </button>
      <p v-if="!rows.length" class="empty">{{ busy ? '正在读取收卷…' : '暂无收卷' }}</p>
    </section>
  </template>
  <template v-else>
    <button class="quiet back" :disabled="busy" @click="back">← 返回收卷列表</button>
    <section class="panel panel-stack">
      <div class="between"><h2>{{ detail.title }}</h2><StatusPill :state="detail.status" /></div>
      <p v-if="detail.description" class="preserve">{{ detail.description }}</p>
      <div class="row"><label class="fill">学生交卷链接<input :value="share" readonly @focus="($event.target as HTMLInputElement).select()"></label>
        <button class="secondary" :disabled="busy || !share" @click="copyShare">复制链接</button>
        <a class="quiet" :href="share" target="_blank" rel="noopener noreferrer">打开交卷页 ↗</a></div>
      <div class="row">
        <template v-if="detail.status!=='CONVERTED'">
          <button class="secondary" :disabled="busy" @click="closeCollection">{{ detail.status==='OPEN' ? '关闭收卷' : '重新开放' }}</button>
          <button class="primary" :disabled="busy || !detail.entry_count" @click="convert">转为批改任务</button>
        </template>
        <button v-else-if="detail.job_id" class="primary" @click="emit('openJob',detail.job_id)">打开批改任务</button>
        <button class="quiet" :disabled="busy" @click="action(() => load(detail!.id))">刷新答卷</button>
      </div>
    </section>
    <section class="panel panel-stack">
      <h2>已提交答卷（{{ detail.entry_count }}）</h2>
      <p v-if="!detail.entries.length" class="muted">还没有收到答卷。</p>
      <article v-for="entry in detail.entries" :key="entry.id" class="entry stack">
        <div class="between"><div><strong>{{ entry.submission_id }}</strong><p class="muted">{{ humanTime(entry.submitted_at) }} · {{ entry.asset_ids.length }} 个附件</p></div>
          <button v-if="detail.status!=='CONVERTED'" class="quiet danger" :disabled="busy" @click="returning=entry">退回答卷</button></div>
        <details v-if="entry.answer_text || entry.asset_ids.length">
          <summary>查看提交</summary>
          <pre v-if="entry.answer_text">{{ entry.answer_text }}</pre>
          <div class="chips"><button v-for="(asset,n) in entry.asset_ids" :key="asset" class="chip" @click="download(asset)">下载附件 {{ n+1 }}</button></div>
        </details>
      </article>
    </section>
  </template>
  <GlassModal v-if="creating" title="创建收卷" @close="closeCreating">
    <p v-if="error" class="message error" role="alert">{{ error }}</p>
    <form class="stack" @submit.prevent="create">
      <label>标题<input v-model="title" required maxlength="160"></label>
      <label>交卷说明<textarea v-model="description" maxlength="4000" rows="3"></textarea></label>
      <SegmentedControl v-model="mode" label="预置评分标准" :options="[{value:'none',label:'转批改后设置'},{value:'template',label:'使用模板'},{value:'questions',label:'预置题目'}]" />
      <label v-if="mode==='template'">评分模板<select v-model="template" required><option value="">请选择</option>
        <option v-for="item in templates" :key="item.id+':'+item.version" :value="item.id+':'+item.version">{{ item.title }} · v{{ item.version }}</option>
      </select></label>
      <QuestionEditor v-if="mode==='questions'" :questions="questions" standards-only @add="addQuestion" />
      <div class="row">
        <label class="secondary file-button">上传题面<input type="file" multiple :accept="fileTypes" @change="addMaterials($event,'QUESTION')"></label>
        <label class="secondary file-button">上传参考资料<input type="file" multiple :accept="fileTypes" @change="addMaterials($event,'REFERENCE')"></label>
      </div>
      <div v-for="(item,i) in materials" :key="i" class="between"><span>{{ item.file.name }}</span>
        <button type="button" class="quiet danger" @click="materials.splice(i,1)">移除</button></div>
      <button class="primary self-start" :disabled="busy">创建分享链接</button>
    </form>
  </GlassModal>
  <GlassModal v-if="returning" title="退回答卷" @close="closeReturning">
    <p>退回 {{ returning.submission_id }} 的提交后，该编号可以重新交卷。</p>
    <p v-if="error" class="message error" role="alert">{{ error }}</p>
    <button class="secondary danger self-start" :disabled="busy" @click="removeEntry">确认退回</button>
  </GlassModal>
</template>

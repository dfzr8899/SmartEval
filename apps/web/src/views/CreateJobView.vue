<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, form, upload } from '../api'
import { fileStem, fileTypes, randomId, sanitizeQuestions, type Job, type Question, type Template, type ZipImport } from '../grading'
import { useAction } from '../useAction'
import QuestionEditor from '../QuestionEditor.vue'

const props = defineProps<{ initialTemplate?: string; modelReady?: boolean }>()
const emit = defineEmits<{ created: [id:string]; configureModel: [] }>()
const { busy, error, notice, action } = useAction()
type MaterialRow = {id: number; role:'ANSWER'|'QUESTION'|'REFERENCE'; submission_id:string; file?:File; asset_id?:string; name?:string}
const title = ref('')
const templates = ref<Template[]>([])
const templateChoice = ref(props.initialTemplate ?? '')
const questions = ref<Question[]>([])
const materials = ref<MaterialRow[]>([])
const skipped = ref<ZipImport['skipped']>([])
let rowId = 0
let currentKey = ''
let previousPayload = ''

const skipReason: Record<string,string> = {
  FILE_SIZE:'文件大小超限', FILE_NAME:'文件名无效', UNSUPPORTED_EXTENSION:'不支持的后缀',
  UNSUPPORTED_FILE:'格式与文件内容不符', INVALID_SUBMISSION_ID:'答卷编号无效',
}
onMounted(() => action(async () => { templates.value = (await api<Template[]>('/grading-templates')).data }))
function addQuestion() {
  let n = questions.value.length+1
  while (questions.value.some(q => q.id === String(n))) n++
  questions.value.push({id:String(n),prompt:'',kind:'TEXT',max_score:10,rubric:[],answers:[{submission_id:'答卷 1',text:''}]})
}
function addFiles(event: Event) {
  const input = event.target as HTMLInputElement
  const incoming = Array.from(input.files ?? [])
  input.value = ''
  if (materials.value.length + incoming.length > 100) { error.value = '材料最多 100 份。'; return }
  incoming.forEach(file => materials.value.push({id:++rowId,role:'ANSWER',submission_id:fileStem(file.name),file,name:file.name}))
}
async function importZip(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  await action(async () => {
    const data = new FormData()
    data.append('file',file)
    const result = await form<ZipImport>('/assets/import-zip',data)
    skipped.value = result.skipped
    if (materials.value.length+result.items.length>100) throw new Error('导入后材料超过 100 份。请减少已有材料后重试。')
    result.items.forEach(item => materials.value.push({id:++rowId,role:'ANSWER',submission_id:item.suggested_submission_id,
      asset_id:item.asset_id,name:item.name}))
  }, 'ZIP 解压结果已读取')
}
async function create() {
  await action(async () => {
    if (!materials.value.length && !questions.value.length) throw new Error('请上传作业，或录入题目和作答。')
    for (const row of materials.value) {
      if (!row.asset_id && row.file) row.asset_id = (await upload(row.file)).asset_id
    }
    const selected = templates.value.find(t => t.id+':'+t.version === templateChoice.value)
    const body = {title:title.value,questions:sanitizeQuestions(questions.value),
      materials:materials.value.map(row => ({asset_id:row.asset_id,role:row.role,...(row.role==='ANSWER' ? {submission_id:row.submission_id} : {})})),
      ...(selected ? {template:{id:selected.id,version:selected.version}} : {})}
    const payload = JSON.stringify(body)
    if (payload !== previousPayload) { previousPayload=payload; currentKey=randomId() }
    const response = await api<Job>('/grading-jobs','POST',body,{'Idempotency-Key':currentKey})
    emit('created',response.data.id)
  })
}
</script>

<template>
  <header class="page-heading"><h1>新建批改</h1><p>上传答卷、导入 ZIP，或直接录入题目和作答。</p></header>
  <div v-if="props.modelReady === false" class="message warning between" role="status">
    <span>当前尚未配置模型 API Key，主观题批改与自动生成细则需要您填入自己的模型密钥（DeepSeek / 通义千问 / 硅基流动 / OpenAI 等）。</span>
    <button class="secondary" type="button" @click="emit('configureModel')">立即配置 API Key</button>
  </div>
  <p v-if="error" class="message error" role="alert">{{ error }}</p>
  <p v-if="notice" class="message success" role="status">{{ notice }}</p>
  <form class="stack" @submit.prevent="create">
    <section class="panel panel-stack">
      <h2>任务信息</h2>
      <div class="form-columns">
        <label>任务名称<input v-model="title" required maxlength="160" placeholder="例如 第三次课后作业"></label>
        <label>评分模板<select v-model="templateChoice"><option value="">创建新的评分细则</option>
          <option v-for="t in templates" :key="t.id+':'+t.version" :value="t.id+':'+t.version">{{ t.title }} · v{{ t.version }}</option>
        </select></label>
      </div>
    </section>
    <section class="panel panel-stack">
      <div class="between"><h2>作业材料</h2>
        <div class="row">
          <label class="secondary file-button">添加文件<input type="file" :accept="fileTypes" multiple :disabled="busy" @change="addFiles"></label>
          <label class="secondary file-button">导入 ZIP<input type="file" accept=".zip" :disabled="busy" @change="importZip"></label>
        </div>
      </div>
      <p class="muted">同一份答卷的多个文件使用相同编号。ZIP 最多 100 MiB，材料合计最多 200 MiB。</p>
      <div v-for="(row,index) in materials" :key="row.id" class="upload-row">
        <strong>{{ row.name }}</strong>
        <label>角色<select v-model="row.role" :disabled="busy"><option value="ANSWER">作答</option><option value="QUESTION">题面</option><option value="REFERENCE">参考资料</option></select></label>
        <label v-if="row.role==='ANSWER'">答卷编号<input v-model="row.submission_id" maxlength="100" required :disabled="busy"></label>
        <button class="quiet danger" type="button" :disabled="busy" @click="materials.splice(index,1)">移除</button>
      </div>
      <details v-if="skipped.length" class="message warning" open><summary>未导入 {{ skipped.length }} 个文件</summary>
        <ul><li v-for="(file,index) in skipped" :key="index">{{ file.name }} · {{ skipReason[file.reason] ?? file.reason }}</li></ul>
      </details>
    </section>
    <section class="panel panel-stack"><h2>结构化题答</h2><QuestionEditor :questions="questions" @add="addQuestion" /></section>
    <div class="actions"><button class="primary" :disabled="busy">{{ busy ? '处理中…' : '生成批改草案' }}</button></div>
  </form>
</template>

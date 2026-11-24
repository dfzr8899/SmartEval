<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, form } from '../api'
import { languages, type PublicCollection } from '../grading'
import { useAction } from '../useAction'
import StatusPill from '../components/StatusPill.vue'

const props=defineProps<{ token:string }>()
const {busy,error,action}=useAction()
const collection=ref<PublicCollection>()
const submitted=ref(false)
const submission=ref('')
const answer=ref('')
const language=ref('')
const files=ref<File[]>([])
const path=() => '/public/collections/'+encodeURIComponent(props.token)
onMounted(() => action(async () => { collection.value=(await api<PublicCollection>(path())).data }))
function select(event:Event) {
  const input=event.target as HTMLInputElement
  files.value=Array.from(input.files ?? [])
  error.value=files.value.length>5 ? '每份答卷最多 5 个文件。' : ''
}
async function send() {
  await action(async () => {
    if (files.value.length>5) throw new Error('每份答卷最多 5 个文件。')
    if (!answer.value.trim() && !files.value.length) throw new Error('请填写作答或上传文件。')
    const data=new FormData()
    data.append('submission_id',submission.value)
    if (answer.value.trim()) data.append('answer_text',answer.value)
    if (language.value) data.append('language',language.value)
    files.value.forEach(file => data.append('files',file))
    await form(path()+'/submit',data,false)
    submitted.value=true
    files.value=[]; answer.value=''
  })
}
</script>

<template>
  <main class="student content">
    <p v-if="error" class="message error" role="alert">{{ error }}</p>
    <p v-if="!collection && busy" role="status">正在读取收卷…</p>
    <template v-if="collection">
      <header class="page-heading between"><div><h1>{{ collection.title }}</h1></div>
        <StatusPill v-if="!submitted" :state="collection.status" /></header>
      <section class="panel panel-stack">
        <p v-if="collection.description" class="preserve">{{ collection.description }}</p>
        <p v-if="submitted" class="message success" role="status">答卷已提交。</p>
        <p v-else-if="collection.status!=='OPEN'" role="status">该作业已停止接收答卷。</p>
        <form v-else class="stack" @submit.prevent="send">
          <label>学号或指定答卷编号<input v-model="submission" required maxlength="100" autocomplete="off"></label>
          <label>作答文本<textarea v-model="answer" maxlength="24000" rows="7"></textarea></label>
          <label>编程语言 <small>可选</small><select v-model="language"><option value="">无</option>
            <option v-for="code in languages" :key="code">{{ code }}</option>
          </select></label>
          <label>附件 <small>最多 5 个，每个不超过 20 MiB</small>
            <input type="file" :accept="collection.allowed_file_types.map(type=>'.'+type).join(',')" multiple @change="select"></label>
          <button class="primary self-start" :disabled="busy || files.length>5">{{ busy ? '正在提交…' : '提交答卷' }}</button>
        </form>
      </section>
    </template>
  </main>
</template>

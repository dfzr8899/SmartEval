<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api'
import type { Template } from '../grading'
import { useAction } from '../useAction'
import GlassModal from '../components/GlassModal.vue'

const emit=defineEmits<{ use:[key:string] }>()
const {busy,error,notice,action}=useAction()
const templates=ref<Template[]>([])
const removing=ref<Template>()
async function load() { templates.value=(await api<Template[]>('/grading-templates')).data }
onMounted(() => action(load))
async function remove() {
  await action(async () => {
    if (!removing.value) return
    await api('/grading-templates/'+removing.value.id,'DELETE')
    removing.value=undefined
    await load()
  },'评分模板已删除')
}
</script>

<template>
  <header class="page-heading"><h1>评分模板</h1><p>复用报告中保存的评分细则。</p></header>
  <p v-if="error && !removing" class="message error" role="alert">{{ error }}</p>
  <p v-if="notice" class="message success" role="status">{{ notice }}</p>
  <section v-if="!templates.length" class="panel empty">{{ busy ? '正在读取模板…' : '暂无评分模板' }}</section>
  <section v-for="item in templates" :key="item.id+':'+item.version" class="panel panel-stack">
    <div class="between"><h2>{{ item.title }}</h2><small>第 {{ item.version }} 版</small></div>
    <div v-for="question in item.questions" :key="question.id" class="template-row">
      <span>{{ question.id }} · {{ question.prompt }}</span><strong>{{ question.max_score }} 分</strong>
    </div>
    <div class="row">
      <button class="secondary" @click="emit('use',item.id+':'+item.version)">使用模板</button>
      <button class="quiet danger" :disabled="busy" @click="removing=item">删除模板</button>
    </div>
  </section>
  <GlassModal v-if="removing" title="删除评分模板" @close="removing=undefined; error=''">
    <p>删除「{{ removing.title }}」的全部版本。</p>
    <p v-if="error" class="message error" role="alert">{{ error }}</p>
    <button class="secondary danger self-start" :disabled="busy" @click="remove">确认删除</button>
  </GlassModal>
</template>

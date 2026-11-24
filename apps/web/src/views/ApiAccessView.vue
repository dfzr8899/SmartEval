<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api'
import { humanTime, type Capabilities, type Key } from '../grading'
import { useAction } from '../useAction'
import GlassModal from '../components/GlassModal.vue'

const props=defineProps<{ canManageKeys:boolean }>()
const {busy,error,notice,action}=useAction()
const keys=ref<Key[]>([])
const capabilities=ref<Capabilities>()
const keyName=ref('')
const keyDays=ref(90)
const revealed=ref('')
const revoking=ref<Key>()
const endpoint=new URL('/mcp',location.origin).href
const features={text_model:'主观题模型',vision:'图像模型',ocr:'本地 OCR',similarity:'代码结构查重',judge:'Judge0'} as const
function loadCapabilities() {
  return action(async () => { capabilities.value=(await api<Capabilities>('/capabilities')).data })
}
async function loadKeys() { if (props.canManageKeys) keys.value=(await api<Key[]>('/keys')).data }
onMounted(() => action(async () => {
  await loadKeys()
  capabilities.value=(await api<Capabilities>('/capabilities')).data
}))
async function createKey() {
  await action(async () => {
    const result=await api<Key>('/keys','POST',{name:keyName.value,expires_in_days:keyDays.value})
    keyName.value=''; revealed.value=result.data.api_key ?? ''
    await loadKeys()
  })
}
async function revoke() {
  await action(async () => {
    if (!revoking.value) return
    await api('/keys/'+revoking.value.id,'DELETE')
    revoking.value=undefined
    await loadKeys()
  },'API 密钥已撤销')
}
</script>

<template>
  <header class="page-heading"><h1>API 与 MCP</h1><p>接入外部系统，检查批改服务就绪状态。</p></header>
  <p v-if="error && !revoking" class="message error" role="alert">{{ error }}</p>
  <p v-if="notice" class="message success" role="status">{{ notice }}</p>
  <section class="panel panel-stack">
    <div class="between"><h2>服务能力</h2><button class="quiet" :disabled="busy" @click="loadCapabilities">刷新状态</button></div>
    <div v-if="capabilities" class="capabilities">
      <div v-for="(label,key) in features" :key="key" class="capability">
        <strong>{{ label }}</strong>
        <span class="status" :class="capabilities[key].ready ? 'good' : 'warn'"><i></i>{{ capabilities[key].ready ? '就绪' : '未就绪' }}</span>
        <small v-if="key==='judge' && capabilities.judge.languages.length">{{ capabilities.judge.languages.join('、') }}</small>
      </div>
    </div>
    <p v-else class="muted">{{ busy ? '正在检查能力…' : '尚未读取能力状态。' }}</p>
  </section>
  <section class="panel panel-stack">
    <h2>远程 MCP</h2>
    <label>Streamable HTTP 地址<input :value="endpoint" readonly @focus="($event.target as HTMLInputElement).select()"></label>
    <p class="muted">在 MCP 客户端的请求头中配置 <code>X-API-Key</code>。</p>
    <details><summary>常用 HTTP API</summary>
      <div class="template-row"><code>POST /api/v2/assets/import-zip</code><span>批量导入</span></div>
      <div class="template-row"><code>POST /api/v2/grading-jobs</code><span>创建批改</span></div>
      <div class="template-row"><code>GET /api/v2/grading-jobs/{id}/report</code><span>获取报告</span></div>
    </details>
  </section>
  <section v-if="canManageKeys" class="panel panel-stack">
    <h2>API 密钥</h2>
    <form class="row" @submit.prevent="createKey"><label>用途名称<input v-model="keyName" required maxlength="140"></label>
      <label>有效期（天）<input type="number" min="1" max="365" v-model.number="keyDays" required></label>
      <button class="primary" :disabled="busy">创建密钥</button>
    </form>
    <div v-for="key in keys" :key="key.id" class="template-row">
      <strong>{{ key.name }}</strong><span>{{ key.revoked_at ? '已撤销' : '到期 '+humanTime(key.expires_at) }}</span>
      <button v-if="!key.revoked_at" class="quiet danger" :disabled="busy" @click="revoking=key">撤销</button>
    </div>
  </section>
  <GlassModal v-if="revealed" title="保存新密钥" :dismissable="false" @close="revealed=''">
    <label>新密钥<input :value="revealed" readonly @focus="($event.target as HTMLInputElement).select()"></label>
    <p class="muted">关闭后，工作台仅显示密钥的名称和有效期。</p>
  </GlassModal>
  <GlassModal v-if="revoking" title="撤销密钥" @close="revoking=undefined; error=''">
    <p>撤销「{{ revoking.name }}」后，使用该密钥的集成将失去访问权限。</p>
    <p v-if="error" class="message error" role="alert">{{ error }}</p>
    <button class="secondary danger self-start" :disabled="busy" @click="revoke">确认撤销</button>
  </GlassModal>
</template>

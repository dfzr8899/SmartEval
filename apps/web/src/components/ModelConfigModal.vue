<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api'
import type { ModelConfig, ModelTestResult } from '../grading'
import { useAction } from '../useAction'
import GlassModal from './GlassModal.vue'

const emit = defineEmits<{ close: []; updated: [configured: boolean] }>()
const { busy, error, notice, action } = useAction()

const presets = [
  { id: 'deepseek', label: 'DeepSeek', base_url: 'https://api.deepseek.com', model_name: 'deepseek-chat', response_format: 'json_object' },
  { id: 'siliconflow', label: '硅基流动 SiliconFlow', base_url: 'https://api.siliconflow.cn/v1', model_name: 'Qwen/Qwen2.5-72B-Instruct', response_format: 'json_object' },
  { id: 'dashscope', label: '通义千问 DashScope', base_url: 'https://dashscope.aliyuncs.com/compatible-mode/v1', model_name: 'qwen-plus', response_format: 'json_object' },
  { id: 'zhipu', label: '智谱 GLM', base_url: 'https://open.bigmodel.cn/api/paas/v4', model_name: 'glm-4-flash', response_format: 'json_object' },
  { id: 'openai', label: 'OpenAI', base_url: 'https://api.openai.com/v1', model_name: 'gpt-4o-mini', response_format: 'json_object' },
  { id: 'custom', label: '自定义 OpenAI 兼容', base_url: '', model_name: '', response_format: 'json_object' },
] as const

const current = ref<ModelConfig>()
const provider = ref<string>('deepseek')
const baseUrl = ref('https://api.deepseek.com')
const modelName = ref('deepseek-chat')
const apiKey = ref('')
const responseFormat = ref<'json_object' | 'text' | 'json_schema'>('json_object')
const testResult = ref<ModelTestResult | null>(null)

function applyPreset(id: string) {
  provider.value = id
  const found = presets.find(p => p.id === id)
  if (found && id !== 'custom') {
    baseUrl.value = found.base_url
    modelName.value = found.model_name
    responseFormat.value = found.response_format
  }
  testResult.value = null
}

async function load() {
  const data = (await api<ModelConfig>('/me/model-config')).data
  current.value = data
  if (data.configured) {
    provider.value = data.provider || 'custom'
    baseUrl.value = data.base_url || ''
    modelName.value = data.model_name || ''
    responseFormat.value = (data.response_format as 'json_object' | 'text' | 'json_schema') || 'json_object'
  }
}

onMounted(() => action(load))

async function save() {
  testResult.value = null
  await action(async () => {
    const payload: Record<string, string> = {
      provider: provider.value,
      base_url: baseUrl.value.trim(),
      model_name: modelName.value.trim(),
      response_format: responseFormat.value,
    }
    if (apiKey.value.trim()) payload.api_key = apiKey.value.trim()
    const res = await api<ModelConfig>('/me/model-config', 'PUT', payload)
    current.value = res.data
    apiKey.value = ''
    emit('updated', Boolean(res.data.configured))
  }, '个人模型配置已加密保存，后续批改将使用您自己的 API Key')
}

async function testConnection() {
  testResult.value = null
  await action(async () => {
    const payload: Record<string, string> = {
      base_url: baseUrl.value.trim(),
      model_name: modelName.value.trim(),
      response_format: responseFormat.value,
    }
    if (apiKey.value.trim()) payload.api_key = apiKey.value.trim()
    testResult.value = (await api<ModelTestResult>('/me/model-config/test', 'POST', payload)).data
  })
}

async function clearConfig() {
  testResult.value = null
  await action(async () => {
    await api('/me/model-config', 'DELETE')
    apiKey.value = ''
    await load()
    emit('updated', false)
  }, '已清除个人模型 API Key')
}
</script>

<template>
  <GlassModal title="模型与 API Key 配置（自备密钥）" @close="emit('close')">
    <p class="muted">
      每位教师可使用自己的 OpenAI 兼容模型 API Key 进行主观题批改与证据复核。密钥在服务端使用 AES-256-GCM 加密存储，不会明文回显或分享给其他教师。
    </p>
    <p v-if="current?.configured" class="message success" role="status">
      当前已启用个人密钥：<strong>{{ current.model_name }}</strong>（Key: <code>{{ current.api_key_masked }}</code>）
    </p>
    <p v-else-if="current && !current.server_default_configured" class="message warning" role="status">
      当前尚未配置个人 API Key（服务端未提供公共免费额度），请先填入您自己的密钥并测试通过后再批改主观题。
    </p>
    <p v-if="error" class="message error" role="alert">{{ error }}</p>
    <p v-if="notice" class="message success" role="status">{{ notice }}</p>
    <p v-if="testResult" class="message" :class="testResult.ok ? 'success' : 'error'" role="status">
      <template v-if="testResult.ok">
        连通性测试通过：模型 {{ testResult.model }} 已响应（耗时 {{ testResult.latency_ms }} ms）
      </template>
      <template v-else>
        连通性测试未通过：{{ testResult.error }}
      </template>
    </p>

    <form class="stack" @submit.prevent="save">
      <label>快捷选择服务商
        <select :value="provider" :disabled="busy" @change="applyPreset(($event.target as HTMLSelectElement).value)">
          <option v-for="p in presets" :key="p.id" :value="p.id">{{ p.label }}</option>
        </select>
      </label>
      <label>接口地址（Base URL，仅支持公网 HTTPS）
        <input v-model="baseUrl" required maxlength="500" placeholder="例如 https://api.deepseek.com" :disabled="busy">
      </label>
      <label>模型名称（Model Name）
        <input v-model="modelName" required maxlength="120" placeholder="例如 deepseek-chat / qwen-plus / gpt-4o-mini" :disabled="busy">
      </label>
      <label>API Key（{{ current?.configured ? '留空表示沿用已保存密钥' : '必填' }}）
        <input v-model="apiKey" type="password" :required="!current?.configured" maxlength="512" autocomplete="off"
               :placeholder="current?.configured ? `已保存 ${current.api_key_masked}，输入新 Key 可覆盖` : 'sk-...'" :disabled="busy">
      </label>
      <label>结构化输出模式
        <select v-model="responseFormat" :disabled="busy">
          <option value="json_object">json_object（推荐，DeepSeek / 通义 / 硅基流动 / OpenAI 均支持）</option>
          <option value="text">text（兼容不支持 response_format 的模型）</option>
          <option value="json_schema">json_schema（严格模式，仅部分 OpenAI 模型支持）</option>
        </select>
      </label>
      <div class="row" style="flex-wrap:wrap;gap:.5rem">
        <button class="primary" type="submit" :disabled="busy">{{ busy ? '处理中…' : '保存配置' }}</button>
        <button class="secondary" type="button" :disabled="busy || !baseUrl.trim() || !modelName.trim() || (!apiKey.trim() && !current?.configured)" @click="testConnection">
          测试连通性
        </button>
        <button v-if="current?.configured" class="quiet danger" type="button" :disabled="busy" @click="clearConfig">
          清除我的 Key
        </button>
      </div>
    </form>
  </GlassModal>
</template>

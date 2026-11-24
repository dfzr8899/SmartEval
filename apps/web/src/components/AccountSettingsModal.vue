<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api'
import type { Profile } from '../grading'
import { useAction } from '../useAction'
import GlassModal from './GlassModal.vue'

const props = defineProps<{ profile: Profile; tag: string }>()
const emit = defineEmits<{ close: []; updated: [name: string, tag: string] }>()
const { busy, error, notice, action } = useAction()
const displayName = ref(props.profile.display_name)
const profileTag = ref(props.tag)
const recovery = ref<{ verified: boolean; address?: string }>()
const email = ref('')
const currentPassword = ref('')
const verificationToken = ref('')
const awaitingConfirm = ref(false)

onMounted(() => action(async () => {
  recovery.value = (await api<{ verified: boolean; address?: string }>('/me/recovery-address')).data
}))

async function saveName() {
  await action(async () => {
    const result = await api<{ id: string; row_version: number }>('/me', 'PATCH',
      { display_name: displayName.value.trim() }, { 'If-Match': profileTag.value })
    profileTag.value = result.tag || `"${result.data.row_version}"`
    emit('updated', displayName.value.trim(), profileTag.value)
  }, '显示名称已更新')
}

async function requestBind() {
  await action(async () => {
    await api('/me/recovery-address', 'POST', { address: email.value.trim(), current_password: currentPassword.value })
    currentPassword.value = ''
    awaitingConfirm.value = true
  }, '验证码已发送到该邮箱，请在 15 分钟内输入确认')
}

async function confirmBind() {
  await action(async () => {
    await api('/me/recovery-address/confirm', 'POST', { token: verificationToken.value.trim() })
    verificationToken.value = ''
    email.value = ''
    awaitingConfirm.value = false
    recovery.value = (await api<{ verified: boolean; address?: string }>('/me/recovery-address')).data
  }, '找回邮箱已验证并绑定')
}
</script>

<template>
  <GlassModal title="账户设置" @close="emit('close')">
    <p v-if="error" class="message error" role="alert">{{ error }}</p>
    <p v-if="notice" class="message success" role="status">{{ notice }}</p>
    <form class="stack" @submit.prevent="saveName">
      <h3>显示名称</h3>
      <label>姓名或称呼<input v-model="displayName" required maxlength="160"></label>
      <button class="secondary self-start" :disabled="busy">保存名称</button>
    </form>
    <hr>
    <div class="stack">
      <h3>找回密码邮箱</h3>
      <p v-if="recovery?.verified" class="muted">当前已验证邮箱：{{ recovery.address }}</p>
      <p v-else class="muted">尚未绑定找回密码邮箱。</p>
      <form class="stack" @submit.prevent="requestBind">
        <label>新邮箱地址<input v-model="email" type="email" required maxlength="254" autocomplete="email"></label>
        <label>当前密码<input v-model="currentPassword" type="password" required maxlength="128" autocomplete="current-password"></label>
        <button class="secondary self-start" :disabled="busy">发送验证码</button>
      </form>
      <form v-if="awaitingConfirm" class="stack" @submit.prevent="confirmBind">
        <label>邮箱验证码<input v-model="verificationToken" required maxlength="100" autocomplete="one-time-code"></label>
        <button class="primary self-start" :disabled="busy">确认绑定邮箱</button>
      </form>
    </div>
  </GlassModal>
</template>

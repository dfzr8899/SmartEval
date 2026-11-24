<script setup lang="ts">
import { ref } from 'vue'
import { api } from '../api'
import { useAction } from '../useAction'
import GlassModal from './GlassModal.vue'

const emit = defineEmits<{ close: [] }>()
const { busy, error, notice, action } = useAction()
const step = ref<'request' | 'confirm'>('request')
const username = ref('')
const token = ref('')
const newPassword = ref('')

async function requestReset() {
  await action(async () => {
    await api('/auth/password-reset', 'POST', { username: username.value.trim() })
    step.value = 'confirm'
  }, '如果该账号已绑定找回邮箱，验证码已发出，请在 15 分钟内完成重置')
}

async function confirmReset() {
  await action(async () => {
    await api('/auth/password-reset/confirm', 'POST', { token: token.value.trim(), new_password: newPassword.value })
    token.value = ''
    newPassword.value = ''
  }, '密码已重置，请关闭此窗口后使用新密码登录')
}
</script>

<template>
  <GlassModal title="找回密码" @close="emit('close')">
    <p v-if="error" class="message error" role="alert">{{ error }}</p>
    <p v-if="notice" class="message success" role="status">{{ notice }}</p>
    <form v-if="step === 'request'" class="stack" @submit.prevent="requestReset">
      <label>账号<input v-model="username" required maxlength="120" autocomplete="username"></label>
      <div class="row">
        <button class="primary" :disabled="busy">发送重置验证码</button>
        <button class="quiet" type="button" @click="step = 'confirm'">已有验证码</button>
      </div>
    </form>
    <form v-else class="stack" @submit.prevent="confirmReset">
      <label>邮件验证码<input v-model="token" required maxlength="100" autocomplete="one-time-code"></label>
      <label>新密码（12–128 位）<input v-model="newPassword" type="password" required minlength="12" maxlength="128" autocomplete="new-password"></label>
      <div class="row">
        <button class="primary" :disabled="busy">确认重置密码</button>
        <button class="quiet" type="button" @click="step = 'request'">重新发送</button>
      </div>
    </form>
  </GlassModal>
</template>

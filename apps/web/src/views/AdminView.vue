<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api'
import { auditActionLabels, humanTime, type AuditLogEntry, type WorkspaceMember } from '../grading'
import { useAction } from '../useAction'
import GlassModal from '../components/GlassModal.vue'
import SegmentedControl from '../components/SegmentedControl.vue'
import StatusPill from '../components/StatusPill.vue'

const { busy, error, notice, action } = useAction()
const tab = ref('members')
const members = ref<WorkspaceMember[]>([])
const logs = ref<AuditLogEntry[]>([])
const logPage = ref(1)
const actionFilter = ref('')
const creating = ref(false)
const username = ref('')
const displayName = ref('')
const password = ref('')
const role = ref('TEACHER')

async function loadMembers() {
  members.value = (await api<WorkspaceMember[]>('/workspace/members')).data
}

async function loadLogs(nextPage = logPage.value) {
  const query = '?page=' + nextPage + (actionFilter.value ? '&action=' + encodeURIComponent(actionFilter.value) : '')
  logs.value = (await api<AuditLogEntry[]>('/workspace/audit-logs' + query)).data
  logPage.value = nextPage
}

async function refresh() {
  await action(async () => {
    await loadMembers()
    await loadLogs(1)
  })
}

async function createMember() {
  await action(async () => {
    await api('/workspace/members', 'POST', {
      username: username.value.trim(),
      display_name: displayName.value.trim(),
      password: password.value,
      roles: [role.value],
    })
    creating.value = false
    username.value = ''
    displayName.value = ''
    password.value = ''
    role.value = 'TEACHER'
    await loadMembers()
    await loadLogs(1)
  }, '成员账号已创建')
}

async function toggleStatus(member: WorkspaceMember) {
  const next = member.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  await action(async () => {
    await api('/workspace/members/' + member.member_id, 'PATCH', { status: next })
    await loadMembers()
    await loadLogs(1)
  }, next === 'ACTIVE' ? '成员已启用' : '成员已停用')
}

async function toggleRole(member: WorkspaceMember) {
  const isAdmin = member.roles.includes('SCHOOL_ADMIN')
  const roles = isAdmin ? ['TEACHER'] : ['SCHOOL_ADMIN']
  await action(async () => {
    await api('/workspace/members/' + member.member_id, 'PATCH', { roles })
    await loadMembers()
    await loadLogs(1)
  }, '成员角色已更新')
}

onMounted(() => { void refresh() })
</script>

<template>
  <header class="page-heading between">
    <div><h1>成员与审计管理</h1><p>管理工作空间教师权限并追溯关键批改与安全审计记录。</p></div>
    <button v-if="tab === 'members'" class="primary" :disabled="busy" @click="creating = true">＋ 新增教师账号</button>
  </header>
  <div class="between toolbar">
    <SegmentedControl v-model="tab" label="管理视图" :options="[{ value: 'members', label: '成员权限' }, { value: 'audit', label: '操作审计日志' }]" />
    <button class="quiet" :disabled="busy" @click="refresh">刷新</button>
  </div>
  <p v-if="error && !creating" class="message error" role="alert">{{ error }}</p>
  <p v-if="notice" class="message success" role="status">{{ notice }}</p>

  <section v-if="tab === 'members'" class="panel panel-stack">
    <h2>工作空间成员（{{ members.length }}）</h2>
    <div v-for="member in members" :key="member.member_id" class="template-row">
      <div class="stack" style="gap:.2rem;flex:1 1 14rem">
        <strong>{{ member.display_name }} <small>({{ member.username }})</small></strong>
        <small>加入于 {{ humanTime(member.created_at) }} · 角色：{{ member.roles.includes('SCHOOL_ADMIN') ? '管理员' : '教师' }}</small>
      </div>
      <StatusPill :state="member.status === 'ACTIVE' ? 'OPEN' : 'CLOSED'" />
      <div class="row">
        <button class="secondary" :disabled="busy" @click="toggleRole(member)">
          {{ member.roles.includes('SCHOOL_ADMIN') ? '设为普通教师' : '设为管理员' }}
        </button>
        <button class="quiet" :class="{ danger: member.status === 'ACTIVE' }" :disabled="busy" @click="toggleStatus(member)">
          {{ member.status === 'ACTIVE' ? '停用' : '启用' }}
        </button>
      </div>
    </div>
  </section>

  <section v-else class="panel panel-stack">
    <div class="between">
      <h2>操作审计日志</h2>
      <label style="min-width:12rem">筛选动作
        <select v-model="actionFilter" @change="action(() => loadLogs(1))">
          <option value="">全部动作</option>
          <option v-for="(label, key) in auditActionLabels" :key="key" :value="key">{{ label }}</option>
        </select>
      </label>
    </div>
    <p v-if="!logs.length" class="muted">暂无审计记录。</p>
    <div v-for="entry in logs" :key="entry.id" class="template-row">
      <div class="stack" style="gap:.2rem;flex:1 1 16rem">
        <strong>{{ auditActionLabels[entry.action] ?? entry.action }}</strong>
        <small>操作人：{{ entry.actor_name }} · 对象：{{ entry.target_type }} · Trace: {{ entry.trace_id.slice(0, 8) }}</small>
      </div>
      <time :datetime="entry.created_at">{{ humanTime(entry.created_at) }}</time>
    </div>
    <div v-if="logPage > 1 || logs.length === 50" class="pager">
      <button class="quiet" :disabled="busy || logPage === 1" @click="action(() => loadLogs(logPage - 1))">上一页</button>
      <span>第 {{ logPage }} 页</span>
      <button class="quiet" :disabled="busy || logs.length < 50" @click="action(() => loadLogs(logPage + 1))">下一页</button>
    </div>
  </section>

  <GlassModal v-if="creating" title="新增成员账号" @close="creating = false; error = ''">
    <p v-if="error" class="message error" role="alert">{{ error }}</p>
    <form class="stack" @submit.prevent="createMember">
      <label>登录账号<input v-model="username" required maxlength="120" autocomplete="off" placeholder="例如 teacher_wang"></label>
      <label>显示姓名<input v-model="displayName" required maxlength="160" placeholder="例如 王老师"></label>
      <label>初始密码（12–128 位）<input v-model="password" type="password" required minlength="12" maxlength="128" autocomplete="new-password"></label>
      <label>工作空间角色
        <select v-model="role">
          <option value="TEACHER">教师（批改、收卷与复核）</option>
          <option value="SCHOOL_ADMIN">管理员（含密钥、成员与审计管理）</option>
        </select>
      </label>
      <button class="primary self-start" :disabled="busy">创建账号</button>
    </form>
  </GlassModal>
</template>

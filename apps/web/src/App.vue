<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api, authorize, onUnauthorized } from './api'
import { useAction } from './useAction'
import { APP_NAME, APP_VERSION, type Profile } from './grading'
import AppIcon, { type IconName } from './components/AppIcon.vue'
import BrandLogo from './components/BrandLogo.vue'
import AppearanceMenu from './components/AppearanceMenu.vue'
import FloatingMenu from './components/FloatingMenu.vue'
import GlassModal from './components/GlassModal.vue'
import AccountSettingsModal from './components/AccountSettingsModal.vue'
import ModelConfigModal from './components/ModelConfigModal.vue'
import PasswordResetModal from './components/PasswordResetModal.vue'
import TasksView from './views/TasksView.vue'
import CreateJobView from './views/CreateJobView.vue'
import JobView from './views/JobView.vue'
import CollectionsView from './views/CollectionsView.vue'
import StudentCollectView from './views/StudentCollectView.vue'
import TemplatesView from './views/TemplatesView.vue'
import ApiAccessView from './views/ApiAccessView.vue'
import AnalyticsView from './views/AnalyticsView.vue'
import AdminView from './views/AdminView.vue'

type Page = 'tasks' | 'new' | 'analytics' | 'collections' | 'templates' | 'api' | 'admin'
const baseLinks: { id: Exclude<Page, 'new'>; icon: IconName; title: string; adminOnly?: boolean }[] = [
  { id: 'tasks', icon: 'tasks', title: '批改任务' },
  { id: 'analytics', icon: 'analytics', title: '学情看板' },
  { id: 'collections', icon: 'collections', title: '作业收卷' },
  { id: 'templates', icon: 'templates', title: '评分模板' },
  { id: 'api', icon: 'api', title: 'API 与 MCP' },
  { id: 'admin', icon: 'admin', title: '成员与审计', adminOnly: true },
]
const studentToken = location.pathname.match(/^\/collect\/([A-Za-z0-9_-]+)\/?$/)?.[1]
const { busy, error, action } = useAction()
const loading = ref(!studentToken)
const profile = ref<Profile>()
const profileTag = ref('')
const username = ref('')
const password = ref('')
const page = ref<Page>('tasks')
const jobId = ref('')
const collectionId = ref('')
const chosenTemplate = ref('')
const showAccount = ref(false)
const showModelConfig = ref(false)
const showReset = ref(false)
const showAbout = ref(false)
const modelReady = ref(true)
const modelPersonal = ref(false)
const links = computed(() => baseLinks.filter(l => !l.adminOnly || profile.value?.can_manage_keys))
const workspace = computed(() => profile.value?.workspaces.find(w => w.tenant_id === profile.value?.tenant_id)?.name)
const userInitial = computed(() => (profile.value?.display_name || 'T').trim().slice(0, 1).toUpperCase())

function parseHash() {
  if (studentToken) return
  const raw = location.hash.replace(/^#\/?/, '')
  const jobMatch = raw.match(/^job\/([0-9a-fA-F-]{36})$/)
  if (jobMatch) { jobId.value = jobMatch[1]; collectionId.value = ''; return }
  const colMatch = raw.match(/^collections\/([0-9a-fA-F-]{36})$/)
  if (colMatch) { page.value = 'collections'; collectionId.value = colMatch[1]; jobId.value = ''; return }
  if (raw === 'new' || raw === 'analytics' || raw === 'collections' || raw === 'templates' || raw === 'api' || raw === 'admin' || raw === 'tasks') {
    page.value = raw; jobId.value = ''; collectionId.value = ''
  } else if (!raw) {
    page.value = 'tasks'; jobId.value = ''; collectionId.value = ''
  }
}

function setHash(target: string) {
  if (studentToken) return
  const next = '#' + target
  if (location.hash !== next) location.hash = next
}

async function refreshModelStatus() {
  try {
    const cfg = (await api<{ configured: boolean; server_default_configured: boolean }>('/me/model-config')).data
    modelPersonal.value = cfg.configured
    modelReady.value = cfg.configured || cfg.server_default_configured
  } catch {
    modelReady.value = true
  }
}

async function session(token: string) {
  authorize(token)
  try {
    const res = await api<Profile>('/me')
    profile.value = res.data
    profileTag.value = res.tag
    void refreshModelStatus()
  } catch (failure) {
    authorize(''); profile.value = undefined; profileTag.value = ''; throw failure
  }
}

onUnauthorized(() => {
  if (profile.value) {
    profile.value = undefined
    profileTag.value = ''
    showAccount.value = false
    showModelConfig.value = false
    error.value = '会话已过期，请重新登录。'
  }
})

onMounted(async () => {
  if (studentToken) return
  parseHash()
  window.addEventListener('hashchange', parseHash)
  try { await session((await api<{ access_token: string }>('/auth/refresh', 'POST')).data.access_token) }
  catch { authorize('', false) }
  finally { loading.value = false }
})
onUnmounted(() => { window.removeEventListener('hashchange', parseHash) })

async function signIn() {
  await action(async () => {
    const result = await api<{ access_token: string }>('/auth/login', 'POST', { username: username.value, password: password.value })
    await session(result.data.access_token)
    password.value = ''
  })
}
async function signOut() {
  await action(async () => {
    try { await api('/auth/logout', 'POST') }
    catch { /* Session may already be expired or revoked server-side. */ }
    finally { authorize(''); profile.value = undefined; profileTag.value = ''; showAccount.value = false; showModelConfig.value = false; navigate('tasks') }
  })
}
async function switchWorkspace(id: string) {
  await action(async () => {
    const result = await api<{ access_token: string }>('/auth/tenant', 'POST', { tenant_id: id })
    navigate('tasks')
    await session(result.data.access_token)
  })
}
function navigate(to: Page, template = '') {
  error.value = ''; jobId.value = ''; collectionId.value = ''; chosenTemplate.value = template; page.value = to
  setHash(to)
}
function openJob(id: string) { jobId.value = id; setHash('job/' + id) }
function closeJob() { jobId.value = ''; if (page.value === 'new') page.value = 'tasks'; setHash(page.value) }
function selectCollection(id: string) {
  collectionId.value = id
  setHash(id ? 'collections/' + id : 'collections')
}
function onProfileUpdated(name: string, tag: string) {
  if (profile.value) profile.value = { ...profile.value, display_name: name }
  profileTag.value = tag
}
</script>

<template>
  <div class="lg-ambient-bg app-frame">
    <header class="lg-chrome app-header">
      <div class="header-left">
        <span v-if="studentToken" class="brand">
          <BrandLogo :size="30" />
          <span>SmartEval</span>
          <span class="brand-tag">收卷端</span>
        </span>
        <a v-else href="#tasks" class="brand" @click.prevent="profile && navigate('tasks')">
          <BrandLogo :size="30" />
          <span>SmartEval</span>
          <span class="brand-tag">V1.0</span>
        </a>
        <template v-if="profile && !studentToken">
          <span class="workspace-divider" aria-hidden="true"></span>
          <span class="workspace-name">{{ workspace ?? '教师工作台' }}</span>
        </template>
      </div>
      <div class="header-tools">
        <button v-if="profile && !studentToken" type="button" class="key-pill"
                :class="modelPersonal ? 'ready' : !modelReady ? 'warn' : ''"
                :disabled="busy" @click="showModelConfig = true">
          <AppIcon :name="modelReady ? 'key' : 'alert'" :size="14" />
          <span>{{ modelPersonal ? '个人 Key 已就绪' : modelReady ? '模型与 Key' : '配置 API Key' }}</span>
        </button>
        <AppearanceMenu />
        <FloatingMenu v-if="profile && !studentToken" :label="profile.display_name" :avatar="userInitial">
          <label v-if="profile.workspaces.length > 1">切换工作空间
            <select :value="profile.tenant_id" :disabled="busy" @change="switchWorkspace(($event.target as HTMLSelectElement).value)">
              <option v-for="w in profile.workspaces" :key="w.tenant_id" :value="w.tenant_id">{{ w.name }}</option>
            </select>
          </label>
          <button class="quiet" type="button" :disabled="busy" @click="showModelConfig = true">
            <AppIcon name="key" :size="15" />模型与 API Key
          </button>
          <button class="quiet" type="button" :disabled="busy" @click="showAccount = true">
            <AppIcon name="settings" :size="15" />账户设置
          </button>
          <button class="quiet" type="button" @click="showAbout = true">
            <AppIcon name="info" :size="15" />关于系统
          </button>
          <hr>
          <button class="quiet danger" type="button" :disabled="busy" @click="signOut">
            <AppIcon name="logout" :size="15" />退出登录
          </button>
        </FloatingMenu>
      </div>
    </header>

    <StudentCollectView v-if="studentToken" :token="studentToken" />
    <main v-else-if="loading" class="centered"><p role="status" class="muted">正在加载 SmartEval 工作台…</p></main>

    <main v-else-if="!profile" class="login-page">
      <div class="login-shell">
        <section class="login-hero">
          <div class="stack" style="gap: 1.4rem">
            <div>
              <span class="hero-kicker"><AppIcon name="sparkles" :size="13" /> 证据锚定式智能评测平台</span>
              <h1>让每一份自动给分，<br>都经得起原文核查。</h1>
              <p class="hero-sub">融合双阶段语义复核与 Java 确定性程序核验，面向高校课程与学科考试打造可审计、可追溯的人机协同阅卷工作台。</p>
            </div>
            <div class="hero-features">
              <div class="hero-feature">
                <span class="hero-feature-icon"><AppIcon name="layers" :size="16" /></span>
                <div>
                  <strong>双阶段语义评审与细则修订</strong>
                  <span>初评逐条拆解采分点并摘录原句，独立复核角色审查证据支撑并自动修正偏差。</span>
                </div>
              </div>
              <div class="hero-feature">
                <span class="hero-feature-icon"><AppIcon name="shield" :size="16" /></span>
                <div>
                  <strong>确定性原文锚定与零伪造门控</strong>
                  <span>UTF-16 字符区间逐字定位、分项总分守恒校验，异常答卷自动附带风险标签转交教师终审。</span>
                </div>
              </div>
              <div class="hero-feature">
                <span class="hero-feature-icon"><AppIcon name="api" :size="16" /></span>
                <div>
                  <strong>五语言代码沙箱判题与 AST 结构查重</strong>
                  <span>内置隔离沙箱运行 Python、Java、C、C++、JavaScript 测试用例，自动检测改写抄袭并生成代码批注。</span>
                </div>
              </div>
              <div class="hero-feature">
                <span class="hero-feature-icon"><AppIcon name="key" :size="16" /></span>
                <div>
                  <strong>教师自备密钥（BYOK）加密托管</strong>
                  <span>支持 DeepSeek、通义千问、硅基流动、OpenAI 等兼容接口，AES-256-GCM 加密隔离保存。</span>
                </div>
              </div>
            </div>
          </div>
          <div class="hero-foot">
            <span>SmartEval {{ APP_VERSION }}</span>
            <span>·</span>
            <span>Java 21 Deterministic Verifier</span>
            <span>·</span>
            <span>客观题 / 主观题 / 代码查重 / 学情分析</span>
          </div>
        </section>

        <section class="login-card">
          <div class="login-card-head">
            <h2>登录教师工作台</h2>
            <p>输入您的工作空间账号与密码开始收卷、批改与复核。</p>
          </div>
          <form class="stack" @submit.prevent="signIn">
            <label>登录账号
              <input v-model="username" required autocomplete="username" placeholder="例如 admin 或 teacher_wang">
            </label>
            <label>登录密码
              <input v-model="password" required type="password" autocomplete="current-password" placeholder="请输入密码">
            </label>
            <p v-if="error" class="message error" role="alert">{{ error }}</p>
            <button class="primary login-submit" :disabled="busy">{{ busy ? '正在验证身份…' : '登录工作台' }}</button>
            <div class="between">
              <span class="muted">需要账号请联系空间管理员创建</span>
              <button class="quiet" type="button" @click="showReset = true">忘记密码？</button>
            </div>
          </form>
          <div class="login-note">
            登录后点击顶部 <strong>「配置 API Key」</strong> 填入您自己的大模型密钥即可开始主观题批改，系统同时支持一键生成学生免登录收卷链接。
          </div>
        </section>
      </div>
      <PasswordResetModal v-if="showReset" @close="showReset = false" />
    </main>

    <div v-else class="workspace-shell" :key="profile.tenant_id">
      <aside class="lg-chrome sidebar">
        <button class="primary new-action" @click="navigate('new')">
          <AppIcon name="plus" :size="16" />新建批改任务
        </button>
        <nav aria-label="工作台导航">
          <button v-for="link in links" :key="link.id"
                  :aria-current="page === link.id || (page === 'new' && link.id === 'tasks') ? 'page' : undefined"
                  @click="navigate(link.id)">
            <AppIcon :name="link.icon" :size="16" />
            <span>{{ link.title }}</span>
          </button>
        </nav>
        <div class="sidebar-footer">
          <button class="quiet" type="button" style="justify-content:flex-start" @click="showModelConfig = true">
            <AppIcon name="key" :size="15" />
            <span>{{ modelPersonal ? '管理个人 API Key' : '配置模型 API Key' }}</span>
          </button>
          <div class="sidebar-version">
            <span>SmartEval</span>
            <span>{{ APP_VERSION }}</span>
          </div>
        </div>
      </aside>

      <main class="content">
        <p v-if="error" class="message error" role="alert">{{ error }}</p>
        <JobView v-if="jobId" :key="jobId" :id="jobId" @back="closeJob" />
        <CreateJobView v-else-if="page === 'new'" :initial-template="chosenTemplate" :model-ready="modelReady"
                       @created="openJob" @configure-model="showModelConfig = true" />
        <AnalyticsView v-else-if="page === 'analytics'" @open-job="openJob" />
        <CollectionsView v-else-if="page === 'collections'" :initial-id="collectionId" @select="selectCollection" @open-job="openJob" />
        <TemplatesView v-else-if="page === 'templates'" @use="navigate('new', $event)" />
        <ApiAccessView v-else-if="page === 'api'" :can-manage-keys="profile.can_manage_keys" />
        <AdminView v-else-if="page === 'admin' && profile.can_manage_keys" />
        <TasksView v-else :model-ready="modelReady" @create="navigate('new')" @open="openJob"
                   @configure-model="showModelConfig = true" @open-collections="navigate('collections')" />
      </main>
    </div>

    <AccountSettingsModal v-if="showAccount && profile" :profile="profile" :tag="profileTag"
                          @updated="onProfileUpdated" @close="showAccount = false" />
    <ModelConfigModal v-if="showModelConfig && profile"
                      @updated="() => { void refreshModelStatus() }" @close="showModelConfig = false" />
    <GlassModal v-if="showAbout" title="关于 SmartEval" @close="showAbout = false">
      <div class="row" style="align-items:center;gap:.75rem">
        <BrandLogo :size="40" />
        <div>
          <strong>{{ APP_NAME }}</strong>
          <p class="muted">版本 {{ APP_VERSION }} · 证据锚定式双阶段阅卷与确定性核验系统</p>
        </div>
      </div>
      <p>面向教育场景打造的可审计人机协同阅卷平台，支持客观题精确判分、编程题沙箱测评与 AST 结构查重、主观题双阶段复核与 UTF-16 原文锚定核验、班级学情看板、脱敏评测集导出及多角色审计治理。</p>
      <p class="muted">技术架构：Java 21 + Spring Boot 3.5 · Python FastAPI Intelligence · Vue 3 + TypeScript · MySQL / Flyway · MCP</p>
    </GlassModal>
  </div>
</template>

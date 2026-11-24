<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api } from '../api'
import { humanTime, type Job } from '../grading'
import { useAction } from '../useAction'
import AppIcon from '../components/AppIcon.vue'
import StatusPill from '../components/StatusPill.vue'
import SegmentedControl from '../components/SegmentedControl.vue'

const props = defineProps<{ modelReady?: boolean }>()
const emit = defineEmits<{ create: []; open: [id: string]; configureModel: []; openCollections: [] }>()
const { busy, error, action } = useAction()
const jobs = ref<Job[]>([])
const page = ref(1)
const filter = ref('all')

const attentionCount = computed(() =>
  jobs.value.filter(j => ['AWAITING_CONFIRMATION', 'REVIEW_REQUIRED', 'PREPARATION_FAILED'].includes(j.status)).length)
const completedCount = computed(() => jobs.value.filter(j => j.status === 'COMPLETED').length)
const activeCount = computed(() => jobs.value.filter(j => ['PREPARING', 'QUEUED', 'GRADING'].includes(j.status)).length)

const rows = computed(() => filter.value === 'all' ? jobs.value
  : jobs.value.filter(job => ['AWAITING_CONFIRMATION', 'REVIEW_REQUIRED', 'PREPARATION_FAILED'].includes(job.status)))

let timer: ReturnType<typeof setInterval>
async function load(next = page.value, quiet = false) {
  const work = async () => {
    const response = await api<Job[]>('/grading-jobs?page=' + next, 'GET', undefined, {}, AbortSignal.timeout(12_000))
    page.value = next
    jobs.value = response.data
  }
  if (quiet) {
    if (busy.value) return
    const current = page.value
    try {
      const response = await api<Job[]>('/grading-jobs?page=' + current, 'GET', undefined, {}, AbortSignal.timeout(8_000))
      if (page.value === current && !busy.value) jobs.value = response.data
    } catch { /* The next refresh may recover. Manual refresh displays errors. */ }
  } else await action(work)
}
onMounted(() => { void load(); timer = setInterval(() => void load(page.value, true), 10_000) })
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <header class="page-heading between">
    <div>
      <h1>批改任务</h1>
      <p>管理作业解析草案、查看原文证据锚点并处理待复核答卷。</p>
    </div>
    <button class="primary mobile-new-action" @click="emit('create')">
      <AppIcon name="plus" :size="15" />新建批改
    </button>
  </header>

  <div v-if="props.modelReady === false" class="message warning between" role="status">
    <span class="row" style="align-items:center;gap:.5rem">
      <AppIcon name="alert" :size="16" />
      <span>尚未配置个人模型 API Key，请先填入您自己的 DeepSeek / 通义千问 / OpenAI 等兼容密钥以启用主观题批改。</span>
    </span>
    <button class="secondary" type="button" @click="emit('configureModel')">立即配置 API Key</button>
  </div>

  <section v-if="jobs.length" class="kpi-strip" aria-label="当前页任务概览">
    <div class="kpi-card">
      <span class="kpi-label">本页任务数</span>
      <span class="kpi-value">{{ jobs.length }}</span>
    </div>
    <div class="kpi-card">
      <span class="kpi-label">待确认 / 待复核</span>
      <span class="kpi-value" :class="{ warn: attentionCount > 0 }">{{ attentionCount }}</span>
    </div>
    <div class="kpi-card">
      <span class="kpi-label">进行中</span>
      <span class="kpi-value accent">{{ activeCount }}</span>
    </div>
    <div class="kpi-card">
      <span class="kpi-label">已完成出分</span>
      <span class="kpi-value good">{{ completedCount }}</span>
    </div>
  </section>

  <div class="between toolbar">
    <SegmentedControl v-model="filter" label="任务筛选"
                      :options="[{ value: 'all', label: '全部任务' }, { value: 'attention', label: `待处理 (${attentionCount})` }]" />
    <button class="quiet" :disabled="busy" @click="load()">
      <AppIcon name="refresh" :size="14" />刷新
    </button>
  </div>

  <p v-if="error" role="alert" class="message error">{{ error }}</p>

  <div class="panel tasks" :aria-busy="busy">
    <div class="task-columns task-headers">
      <span>任务名称</span><span>状态</span><span>创建时间</span><span></span>
    </div>
    <button v-for="job in rows" :key="job.id" class="task-columns task-row" @click="emit('open', job.id)">
      <strong>{{ job.title }}</strong>
      <span><StatusPill :state="job.status" /></span>
      <time :datetime="job.created_at">{{ humanTime(job.created_at) }}</time>
      <AppIcon name="chevron-right" :size="15" class="chevron-muted" />
    </button>

    <div v-if="!rows.length" class="empty">
      <p style="font-size:1rem;font-weight:600;color:var(--lg-text-primary)">
        {{ busy ? '正在读取批改任务…' : filter === 'all' ? '开始您的第一次智能阅卷' : '当前页没有待处理任务' }}
      </p>
      <div v-if="!busy && filter === 'all'" class="onboarding-steps">
        <div class="onboarding-step">
          <span class="step-num">1</span>
          <strong>配置个人模型 Key</strong>
          <small>填入自己的 DeepSeek / 通义 / OpenAI 兼容密钥，AES-256 加密隔离保存。</small>
        </div>
        <div class="onboarding-step">
          <span class="step-num">2</span>
          <strong>收卷或上传作业</strong>
          <small>一键生成学生免登录收卷链接，或直接上传 PDF / Word / 图片 / ZIP 压缩包。</small>
        </div>
        <div class="onboarding-step">
          <span class="step-num">3</span>
          <strong>证据核验与出分</strong>
          <small>系统自动逐字锚定原答证据，仅把有风险的答卷交由您复核，完成后导出成绩单。</small>
        </div>
      </div>
      <div v-if="!busy && filter === 'all'" class="row" style="margin-top:.35rem">
        <button v-if="props.modelReady === false" class="secondary" type="button" @click="emit('configureModel')">
          <AppIcon name="key" :size="15" />先配置 API Key
        </button>
        <button class="primary" type="button" @click="emit('create')">
          <AppIcon name="plus" :size="15" />新建批改任务
        </button>
        <button class="secondary" type="button" @click="emit('openCollections')">
          <AppIcon name="collections" :size="15" />发起在线收卷
        </button>
      </div>
    </div>
  </div>

  <div v-if="page > 1 || jobs.length === 50" class="pager">
    <button class="quiet" :disabled="busy || page === 1" @click="load(page - 1)">上一页</button>
    <span>第 {{ page }} 页</span>
    <button class="quiet" :disabled="busy || jobs.length < 50" @click="load(page + 1)">下一页</button>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, exportEvalDataset } from '../api'
import { humanTime, type AnalyticsOverview, type JobAnalytics } from '../grading'
import { useAction } from '../useAction'
import StatusPill from '../components/StatusPill.vue'

const emit = defineEmits<{ openJob: [id: string] }>()
const { busy, error, action } = useAction()
const overview = ref<AnalyticsOverview>()
const detail = ref<JobAnalytics>()
const selectedJobId = ref('')

const maxBand = computed(() => Math.max(1, ...(detail.value?.distribution.map(b => b.count) ?? [1])))
const weakQuestions = computed(() =>
  [...(detail.value?.questions ?? [])].sort((a, b) => a.score_rate - b.score_rate))
const pct = (rate: number) => (rate * 100).toFixed(1) + '%'

async function loadOverview() {
  await action(async () => {
    overview.value = (await api<AnalyticsOverview>('/analytics/overview')).data
    const first = overview.value.recent_jobs[0]
    if (first && !selectedJobId.value) {
      await loadJob(first.id)
    } else if (selectedJobId.value) {
      await loadJob(selectedJobId.value)
    }
  })
}

async function loadJob(id: string) {
  selectedJobId.value = id
  detail.value = (await api<JobAnalytics>('/grading-jobs/' + encodeURIComponent(id) + '/analytics')).data
}

onMounted(() => { void loadOverview() })
</script>

<template>
  <header class="page-heading between">
    <div><h1>学情与成绩看板</h1><p>查看工作空间批改进度、成绩分布、薄弱题项与查重概览。</p></div>
    <button class="quiet" :disabled="busy" @click="loadOverview">刷新统计</button>
  </header>
  <p v-if="error" class="message error" role="alert">{{ error }}</p>
  <p v-if="!overview && busy" role="status">正在统计工作空间数据…</p>
  <template v-if="overview">
    <section class="score-list">
      <article class="panel">
        <small>累计批改任务</small>
        <div class="score">{{ overview.total_jobs }}</div>
        <small>已完成 {{ overview.completed_jobs }} · 进行中 {{ overview.active_jobs }}</small>
      </article>
      <article class="panel">
        <small>已出分题项</small>
        <div class="score">{{ overview.scored_items }} <span>/ {{ overview.total_items }}</span></div>
        <small>人工复核确认 {{ overview.human_reviewed_items }} 项</small>
      </article>
      <article class="panel">
        <small>待人工复核</small>
        <div class="score">{{ overview.review_required_items + overview.failed_items }}</div>
        <small>涉及 {{ overview.review_required_jobs }} 个任务 · 待确认草案 {{ overview.awaiting_confirmation_jobs }}</small>
      </article>
      <article class="panel">
        <small>代码查重告警</small>
        <div class="score">{{ overview.similarity_high_alerts + overview.similarity_medium_alerts }}</div>
        <small>高危 {{ overview.similarity_high_alerts }} · 疑似改写 {{ overview.similarity_medium_alerts }}</small>
      </article>
    </section>

    <section class="panel panel-stack">
      <h2>近期已批改任务</h2>
      <p v-if="!overview.recent_jobs.length" class="muted">暂无已进入批改轮次的任务。</p>
      <div v-else class="tasks">
        <div class="analytics-columns task-headers">
          <span>任务名称</span><span>状态</span><span>平均得分率</span><span>及格率</span><span>操作</span>
        </div>
        <div v-for="job in overview.recent_jobs" :key="job.id" class="analytics-columns task-row"
             :class="{ 'row-selected': selectedJobId === job.id }">
          <button class="link-cell" type="button" @click="action(() => loadJob(job.id))">
            <strong>{{ job.title }}</strong>
            <small>{{ humanTime(job.created_at) }} · {{ job.submission_count }} 份答卷</small>
          </button>
          <span><StatusPill :state="job.status" /></span>
          <div class="bar-cell">
            <div class="bar-track"><div class="bar-fill" :style="{ width: pct(job.mean_rate) }"></div></div>
            <small>{{ pct(job.mean_rate) }}</small>
          </div>
          <span>{{ pct(job.pass_rate) }}</span>
          <button class="quiet" type="button" @click="emit('openJob', job.id)">打开报告 ›</button>
        </div>
      </div>
    </section>

    <section v-if="detail" class="panel panel-stack">
      <div class="between">
        <div>
          <h2>单任务学情分析 · {{ detail.title }}</h2>
          <p class="muted">第 {{ detail.run }} 轮 · 已出分答卷 {{ detail.final_submission_count }} / {{ detail.submission_count }} 份 · 满分 {{ detail.max_total_score }}</p>
        </div>
        <div class="row">
          <button class="secondary" :disabled="busy" @click="action(() => exportEvalDataset(detail!.job_id, detail!.run))">导出脱敏评测集</button>
          <button class="primary" type="button" @click="emit('openJob', detail.job_id)">查看详细报告</button>
        </div>
      </div>

      <div class="score-list">
        <article><small>平均分</small><div class="score">{{ detail.summary.mean_score }} <span>/ {{ detail.max_total_score }}</span></div></article>
        <article><small>中位数 / 标准差</small><div class="score">{{ detail.summary.median_score }} <span>± {{ detail.summary.std_dev }}</span></div></article>
        <article><small>最高 / 最低分</small><div class="score">{{ detail.summary.max_score }} <span>/ {{ detail.summary.min_score }}</span></div></article>
        <article><small>及格率 / 优秀率</small><div class="score">{{ pct(detail.summary.pass_rate) }} <span>/ {{ pct(detail.summary.excellent_rate) }}</span></div></article>
        <article><small>双阶段复核统计</small><div class="score">{{ detail.review_stats.reviewer_revised }}</div>
          <small>自动出分 {{ detail.review_stats.auto_scored }} · 人工改分 {{ detail.review_stats.teacher_reviewed }}</small></article>
      </div>

      <div class="evidence-split">
        <div class="evidence-pane stack">
          <h3>成绩分布（按得分率）</h3>
          <div class="band-chart">
            <div v-for="band in detail.distribution" :key="band.label" class="band-row">
              <span class="band-label">{{ band.label }}</span>
              <div class="bar-track"><div class="bar-fill" :style="{ width: (band.count / maxBand * 100) + '%' }"></div></div>
              <strong class="band-count">{{ band.count }}</strong>
            </div>
          </div>
        </div>
        <div class="evidence-pane stack">
          <h3>逐题得分率与薄弱点排行</h3>
          <div v-for="q in weakQuestions" :key="q.question_id" class="band-row">
            <span class="band-label">第 {{ q.question_id }} 题 <small>{{ q.kind }}</small></span>
            <div class="bar-track">
              <div class="bar-fill" :class="{ warn: q.score_rate < 0.7, bad: q.score_rate < 0.5 }"
                   :style="{ width: pct(q.score_rate) }"></div>
            </div>
            <small class="band-count">{{ q.average_score }}/{{ q.max_score }} ({{ pct(q.score_rate) }})</small>
          </div>
        </div>
      </div>
    </section>
  </template>
</template>

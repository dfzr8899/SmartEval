<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Report, SimilarityAlert } from './grading'
import SourceLines from './SourceLines.vue'

const props = defineProps<{ report: Report }>()
const selectedKey = ref<string>()
const fragment = ref<number>()
const selection = ref(0)
const alertKey = (alert: SimilarityAlert) => JSON.stringify([alert.question_id, alert.left_submission_id, alert.right_submission_id])
const selected = computed(() => selectedKey.value ? props.report.similarity_alerts.find(a => alertKey(a) === selectedKey.value) : undefined)
const pairs = computed(() => {
  const alert = selected.value
  if (!alert) return []
  const matches = fragment.value == null ? alert.matches : alert.matches.slice(fragment.value, fragment.value + 1)
  return [
    { id: alert.left_submission_id, lines: matches.map(match => match.left_lines) },
    { id: alert.right_submission_id, lines: matches.map(match => match.right_lines) },
  ].map(side => ({
    ...side, text: props.report.items.find(item => item.question_id === alert.question_id && item.submission_id === side.id)?.answer ?? '',
  }))
})
function open(alert: SimilarityAlert) {
  selectedKey.value = alertKey(alert)
  fragment.value = undefined
  selection.value++
}
function focus(index?: number) {
  fragment.value = index
  selection.value++
}
</script>

<template>
  <section v-if="report.similarity_status !== 'NOT_APPLICABLE'" class="panel panel-stack">
    <h2>代码结构相似度告警</h2>
    <p v-if="report.similarity_status === 'PENDING'" role="status">等待判分结束或正在比较代码结构。</p>
    <p v-else-if="report.similarity_status === 'FAILED'" role="status">本轮代码结构比较未完成。</p>
    <p v-else-if="!report.similarity_alerts.length">本轮没有达到阈值的配对。</p>
    <div v-else class="stack">
      <button v-for="alert in report.similarity_alerts" :key="alertKey(alert)"
              class="evidence-choice" :aria-pressed="selectedKey === alertKey(alert)" @click="open(alert)">
        第 {{ alert.question_id }} 题 · {{ alert.left_submission_id }} ↔ {{ alert.right_submission_id }}
        <span class="row"><strong>{{ (alert.score * 100).toFixed(1) }}% · {{ alert.language }}</strong>
          <span class="status" :class="alert.level === 'HIGH' ? 'bad' : 'warn'"><i></i>{{ alert.level === 'HIGH' ? '高危雷同' : '疑似改写' }}</span>
        </span>
      </button>
    </div>
    <div v-if="selected" class="stack">
      <div class="between"><h3>匹配行对比 · 第 {{ selected.question_id }} 题</h3>
        <button class="quiet" @click="selectedKey = undefined">关闭对比</button>
      </div>
      <p class="muted">{{ selected.summary }}</p>
      <div class="chips">
        <button class="chip" :aria-pressed="fragment == null" @click="focus()">全部片段</button>
        <button v-for="(match, index) in selected.matches" :key="index" class="chip"
                :aria-pressed="fragment === index" @click="focus(index)">
          {{ match.left_lines.start }}–{{ match.left_lines.end }} ↔ {{ match.right_lines.start }}–{{ match.right_lines.end }} 行
        </button>
      </div>
      <div class="comparison-split">
        <div v-for="side in pairs" :key="side.id" class="evidence-pane stack">
          <h4>{{ side.id }}</h4>
          <SourceLines :text="side.text" :lines="side.lines" :selection="selection" />
        </div>
      </div>
    </div>
  </section>
</template>

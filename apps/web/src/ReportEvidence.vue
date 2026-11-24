<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { assetUrl } from './api'
import { riskSignalLabels, type Item, type Material, type ScoredCriterion, type Source } from './grading'
import SourceLines from './SourceLines.vue'

const props = defineProps<{ item: Item; sources: Source[]; materials: Material[] }>()
const active = ref('')
const range = ref<{ start: number; end: number }>()
const line = ref<number>()
const source = ref<Source>()
const selection = ref(0)
const url = ref('')
const error = ref('')
let generation = 0
const linked = computed(() => props.item.source_ids.flatMap(id => props.sources.find(s => s.id === id) ?? []))
const lines = computed(() => line.value ? [{ start: line.value, end: line.value }] : undefined)
const coordinates = computed(() => {
  const r = source.value?.region
  return r ? { left: r.x * 100 + '%', top: r.y * 100 + '%', width: r.width * 100 + '%', height: r.height * 100 + '%' } : undefined
})

function visual(entry: Source) {
  return entry.asset_id && entry.page && entry.region && props.materials.some(m => m.asset_id === entry.asset_id && ['pdf', 'png', 'jpg'].includes(m.format))
}
function select(id: string, offsets?: {start: number; end: number}, row?: number, original?: Source) {
  active.value = id
  range.value = offsets
  line.value = row
  source.value = original
  selection.value++
}
function criterion(value: ScoredCriterion) {
  const { start, end } = value
  const original = linked.value.find(s => visual(s) && (
    value.source_ids?.includes(s.id) || !!value.quote && s.text.includes(value.quote)))
  const offsets = start != null && end != null && start >= 0 && end > start && end <= props.item.answer.length
    ? { start, end } : undefined
  select('criterion-' + value.id, offsets, undefined, original)
}
function finding(index: number, row: number) {
  select('finding-' + index, undefined, row)
}
function dispose() {
  generation++
  if (url.value) URL.revokeObjectURL(url.value)
  url.value = ''
  error.value = ''
}
watch(source, async current => {
  dispose()
  if (!current?.asset_id || !visual(current)) return
  const ticket = generation
  try {
    const loaded = await assetUrl(current.asset_id, current.page)
    if (ticket === generation) url.value = loaded
    else URL.revokeObjectURL(loaded)
  } catch (failure) {
    if (ticket === generation) error.value = String(failure)
  }
})
onUnmounted(dispose)
</script>

<template>
  <div class="evidence-split">
    <div class="stack">
      <p>{{ item.prompt }}</p>
      <slot />
      <div v-if="item.result?.risk_signals?.length" class="chips">
        <span v-for="sig in item.result.risk_signals" :key="sig" class="chip">{{ riskSignalLabels[sig] ?? sig }}</span>
      </div>
      <p v-if="item.result?.review_summary" class="message warning">
        <strong>双阶段复核意见：</strong>{{ item.result.review_summary }}
      </p>
      <details v-if="item.result?.reviewer_revised && item.result.initial_criteria?.length" class="message">
        <summary>查看首轮评分与复核修订对比</summary>
        <div v-for="init in item.result.initial_criteria" :key="init.id" class="template-row">
          <span>{{ init.id }}：首轮 {{ init.points }} 分 → 修订后 {{ item.result.criteria?.find(c => c.id === init.id)?.points ?? '-' }} 分</span>
          <small>{{ init.reason }}</small>
        </div>
      </details>
      <div v-if="item.result?.criteria?.length" class="stack">
        <h4>评分细则</h4>
        <button v-for="entry in item.result.criteria" :key="entry.id" class="evidence-choice"
                :aria-pressed="active === 'criterion-' + entry.id" @click="criterion(entry)">
          <strong>{{ entry.id }} · {{ entry.points }} / {{ entry.max_points }}</strong>
          <span v-if="entry.description">{{ entry.description }}</span>
          <span>{{ entry.reason }}</span>
          <pre v-if="entry.detail" class="diagnostic-detail">{{ entry.detail }}</pre>
          <q v-if="entry.quote">{{ entry.quote }}</q>
        </button>
      </div>
      <div v-if="item.result?.findings?.length" class="stack">
        <h4>代码点评</h4>
        <button v-for="(entry, index) in item.result.findings" :key="index" class="evidence-choice"
                :aria-pressed="active === 'finding-' + index" @click="finding(index,entry.line)">
          <strong>第 {{ entry.line }} 行</strong><span>{{ entry.reason }}</span><q>{{ entry.quote }}</q>
        </button>
      </div>
      <div v-if="linked.length" class="chips">
        <button v-for="entry in linked" :key="entry.id" class="chip" @click="select(entry.id,undefined,undefined,entry)">
          {{ entry.name }} · {{ entry.page ? '第 ' + entry.page + ' 页' : entry.id }}
        </button>
      </div>
    </div>
    <div class="evidence-pane stack" aria-label="原始答卷">
      <div class="between"><h4>原始答卷</h4>
        <button v-if="source" class="quiet" @click="source = undefined; selection++">返回作答文本</button>
      </div>
      <template v-if="source && visual(source)">
        <p class="muted">{{ source.name }} · 第 {{ source.page }} 页</p>
        <div v-if="url" class="evidence-image">
          <img :src="url" :alt="source.name + ' 第 ' + source.page + ' 页'">
          <span v-if="coordinates" :style="coordinates" class="region" aria-label="证据位置"></span>
        </div>
        <p v-else role="status">{{ error || '正在加载原始页面…' }}</p>
      </template>
      <SourceLines v-else :text="source?.text ?? (item.answer || '空白作答')" :range="range" :lines="lines" :selection="selection" />
      <p v-if="active.startsWith('criterion-') && !range && !source" class="muted" role="status">该评分项没有可定位的原文引用。</p>
    </div>
  </div>
</template>

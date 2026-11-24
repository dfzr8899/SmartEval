<script setup lang="ts">
import { computed, ref, watch } from 'vue'

const props = defineProps<{
  text: string
  range?: { start: number; end: number }
  lines?: { start: number; end: number }[]
  selection?: number
}>()
const viewer = ref<HTMLElement>()
const rows = computed(() => {
  let offset = 0
  return props.text.split('\n').map((text, index) => {
    const start = offset
    offset += text.length + 1
    const highlighted = props.lines?.some(line => index + 1 >= line.start && index + 1 <= line.end)
    const from = props.range ? Math.max(0, props.range.start - start) : 0
    const to = props.range ? Math.min(text.length, props.range.end - start) : 0
    const selected = to > from || !!props.range && start + text.length >= props.range.start && start + text.length < props.range.end
    return { number: index + 1, text, highlighted, selected, from, to }
  })
})

watch([viewer, rows, () => props.selection], () => {
  const frame = viewer.value
  const hit = frame?.querySelector<HTMLElement>('[data-hit]')
  if (frame && hit) {
    frame.scrollTop = Math.max(0, hit.getBoundingClientRect().top - frame.getBoundingClientRect().top + frame.scrollTop - 44)
    const mark = hit.querySelector('mark')
    frame.scrollLeft = mark ? Math.max(0, mark.getBoundingClientRect().left - frame.getBoundingClientRect().left + frame.scrollLeft - 48) : 0
  } else if (frame) frame.scrollTo(0, 0)
}, { flush: 'post', immediate: true })
</script>

<template>
  <div ref="viewer" class="source-lines" tabindex="0" aria-label="答卷内容">
    <div v-for="row in rows" :key="row.number" class="source-line" :class="{ 'source-hit': row.highlighted }"
         :data-hit="row.selected || row.highlighted ? '' : undefined">
      <span class="line-no" aria-hidden="true">{{ row.number }}</span>
      <code v-if="row.to > row.from">{{ row.text.slice(0,row.from) }}<mark>{{ row.text.slice(row.from,row.to) }}</mark>{{ row.text.slice(row.to) }}</code>
      <code v-else>{{ row.text || ' ' }}<mark v-if="row.selected">↵</mark></code>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import AppIcon, { type IconName } from './AppIcon.vue'

defineProps<{ label: string; icon?: IconName; avatar?: string }>()
const root = ref<HTMLDetailsElement>()
function dismiss(event: PointerEvent) {
  if (root.value && !root.value.contains(event.target as Node)) root.value.open = false
}
function close() {
  if (!root.value) return
  root.value.open = false
  root.value.querySelector('summary')?.focus()
}
onMounted(() => document.addEventListener('pointerdown', dismiss))
onUnmounted(() => document.removeEventListener('pointerdown', dismiss))
</script>

<template>
  <details ref="root" class="floating-menu" @keydown.esc.stop="close">
    <summary class="quiet">
      <span v-if="avatar" class="avatar-badge" aria-hidden="true">{{ avatar }}</span>
      <AppIcon v-else-if="icon" :name="icon" :size="15" />
      <span>{{ label }}</span>
      <AppIcon name="chevron-down" :size="14" />
    </summary>
    <div class="lg-float floating-menu-body stack"><slot /></div>
  </details>
</template>

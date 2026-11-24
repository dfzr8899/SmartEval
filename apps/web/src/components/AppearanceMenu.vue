<script setup lang="ts">
import { onUnmounted, ref, watchEffect } from 'vue'
import FloatingMenu from './FloatingMenu.vue'
import SegmentedControl from './SegmentedControl.vue'

const system = matchMedia('(prefers-color-scheme: dark)')
const systemDark = ref(system.matches)
const theme = ref(localStorage.getItem('smarteval-theme') ?? 'system')
const material = ref(localStorage.getItem('smarteval-material') === 'solid' ? 'solid' : 'glass')
const update = (event: MediaQueryListEvent) => { systemDark.value = event.matches }
system.addEventListener('change', update)
onUnmounted(() => system.removeEventListener('change', update))
watchEffect(() => {
  document.documentElement.dataset.theme = theme.value === 'dark' || (theme.value === 'system' && systemDark.value) ? 'dark' : 'light'
  document.documentElement.dataset.material = material.value
  localStorage.setItem('smarteval-theme', theme.value)
  localStorage.setItem('smarteval-material', material.value)
})
</script>

<template>
  <FloatingMenu label="外观" icon="sun">
    <strong>界面主题</strong>
    <SegmentedControl v-model="theme" label="主题" :options="[{value:'light',label:'浅色'},{value:'dark',label:'深色'},{value:'system',label:'系统'}]" />
    <button class="between appearance-row" type="button" role="switch" :aria-checked="material === 'glass'"
            @click="material = material === 'glass' ? 'solid' : 'glass'">
      磨砂材质 <span class="lg-switch" aria-hidden="true"></span>
    </button>
  </FloatingMenu>
</template>

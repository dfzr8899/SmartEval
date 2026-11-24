<script setup lang="ts">
import { collectionLabels, labels, stateClass } from '../grading'
defineProps<{ state: string }>()
const copy: Record<string,string> = { ...labels, ...collectionLabels }
</script>

<template><span class="status" :class="stateClass(state)"><i></i>{{ copy[state] ?? state }}</span></template>

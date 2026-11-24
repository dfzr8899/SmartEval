<script setup lang="ts">
defineProps<{ modelValue: string; label: string; options: { value: string; label: string }[] }>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
</script>

<template>
  <div class="segmented" role="group" :aria-label="label">
    <button v-for="option in options" :key="option.value" type="button" :aria-pressed="modelValue === option.value"
            @click="emit('update:modelValue',option.value)">{{ option.label }}</button>
  </div>
</template>

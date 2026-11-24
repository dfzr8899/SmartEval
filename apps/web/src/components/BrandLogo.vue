<script setup lang="ts">
withDefaults(defineProps<{ size?: number }>(), { size: 32 })
</script>

<template>
  <svg :width="size" :height="size" viewBox="0 0 32 32" fill="none" aria-hidden="true" class="brand-svg">
    <defs>
      <linearGradient id="smarteval-brand-grad" x1="2" y1="2" x2="30" y2="30" gradientUnits="userSpaceOnUse">
        <stop offset="0%" stop-color="#1B4D6E" />
        <stop offset="100%" stop-color="#0F766E" />
      </linearGradient>
    </defs>
    <rect width="32" height="32" rx="8.5" fill="url(#smarteval-brand-grad)" />
    <rect x="0.75" y="0.75" width="30.5" height="30.5" rx="7.75" stroke="white" stroke-opacity="0.18" stroke-width="1.5" />
    <path d="M8.5 10.5H23.5M8.5 16H17.5M8.5 21.5H14.5" stroke="white" stroke-width="2.1" stroke-linecap="round" stroke-opacity="0.92" />
    <path d="M18 20.6L20.6 23.2L25.5 17.6" stroke="#5EEAD4" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" />
  </svg>
</template>

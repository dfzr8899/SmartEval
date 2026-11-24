<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import AppIcon from './AppIcon.vue'

const props = withDefaults(defineProps<{ title: string; dismissable?: boolean }>(), { dismissable: true })
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement>()
let backdropDown = false

onMounted(async () => {
  await nextTick()
  if (dialog.value && !dialog.value.open) dialog.value.showModal()
})
function onPointerDown(event: PointerEvent) {
  backdropDown = event.target === dialog.value
}
function onBackdropClick(event: MouseEvent) {
  if (props.dismissable && backdropDown && event.target === dialog.value) emit('close')
  backdropDown = false
}
function onCancel() {
  if (props.dismissable) emit('close')
}
</script>

<template>
  <dialog ref="dialog" class="lg-float glass-modal" :aria-label="title" @cancel.prevent="onCancel"
          @pointerdown="onPointerDown" @click="onBackdropClick">
    <section class="modal-body stack">
      <header class="between modal-header">
        <h2>{{ title }}</h2>
        <button class="quiet modal-close" type="button" aria-label="关闭窗口" @click="emit('close')">
          <AppIcon name="x" :size="16" />
        </button>
      </header>
      <slot />
    </section>
  </dialog>
</template>

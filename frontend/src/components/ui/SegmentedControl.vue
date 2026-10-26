<template>
  <div class="seg" role="group" :aria-label="ariaLabel">
    <button
      v-for="o in options"
      :key="o.value"
      type="button"
      :aria-pressed="o.value === modelValue"
      @click="$emit('update:modelValue', o.value)"
    >{{ o.label }}</button>
  </div>
</template>

<script setup>
/**
 * SegmentedControl — 移植自 21st.dev「Finance Bento」(romanvasnev) 的 .seg 周期/币种切换控件。
 * 上游结构：外层胶囊 rgba 底 + 1px 内描边，按钮 28px 高、圆角 11px，
 * 选中态为白色实心胶囊（aria-pressed=true），未选中透明 + 次级文字；
 * 附 focus-visible 外描边与 250ms 颜色过渡。此处按站内暖象牙主题换为品牌令牌。
 */
defineProps({
  modelValue: { type: [String, Number], default: '' },
  options: { type: Array, default: () => [] },
  ariaLabel: { type: String, default: '' }
})
defineEmits(['update:modelValue'])
</script>

<style scoped>
.seg {
  display: inline-flex;
  padding: 3px;
  gap: 2px;
  border-radius: 14px;
  background: rgba(19, 35, 58, .06);
  border: 1px solid rgba(19, 35, 58, .07);
  backdrop-filter: blur(10px);
}
.seg button {
  border: 0;
  height: 30px;
  min-width: 48px;
  padding: 0 14px;
  border-radius: 11px;
  background: transparent;
  color: var(--ll-muted, #64748B);
  font: 600 12.5px/1 -apple-system, BlinkMacSystemFont, 'SF Pro Text', 'Segoe UI', sans-serif;
  letter-spacing: -.01em;
  cursor: pointer;
  transition: background .25s ease, color .25s ease, box-shadow .25s ease;
  white-space: nowrap;
}
.seg button:hover { color: var(--ll-brand-base, #13233A); }
.seg button[aria-pressed='true'] {
  background: #fff;
  color: var(--ll-brand-base, #13233A);
  box-shadow: 0 1px 2px rgba(19, 35, 58, .12), 0 4px 14px -6px rgba(19, 35, 58, .22);
}
.seg button:focus-visible { outline: 2px solid var(--ll-brand-blue, #1E6EB8); outline-offset: 2px; }
@media (max-width: 640px) {
  .seg button { min-width: 42px; padding: 0 10px; height: 28px; font-size: 12px; }
}
</style>
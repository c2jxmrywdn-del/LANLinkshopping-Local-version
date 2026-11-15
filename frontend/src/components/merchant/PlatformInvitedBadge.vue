<template>
  <span v-if="show" class="ll-invited-badge">平台特邀商户</span>
</template>

<script setup>
defineProps({
  show: { type: Boolean, default: false }
})
</script>

<style scoped>
.ll-invited-badge {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: .02em;
  line-height: 1.45;
  white-space: nowrap;
  color: #6b4e16;
  background: linear-gradient(180deg, #fff8e8, #f6d38a);
  border: 1px solid rgba(128, 96, 31, .35);
}
@media (max-width: 767px) {
  .ll-invited-badge { font-size: 10px; padding: 1px 6px; }
}
</style>

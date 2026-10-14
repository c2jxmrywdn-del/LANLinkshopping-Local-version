<template>
  <div
    class="ll-wordmark brand-wordmark"
    :class="{ 'brand-wordmark--light': light }"
    :style="{ fontSize: size }"
    :aria-label="ariaLabel"
    role="img"
  >
    <span class="ll-lan">LAN</span><span class="ll-link">Link</span><span class="ll-shopping">shopping</span>
  </div>
</template>

<script setup>
defineProps({
  size: { type: String, default: 'inherit' },
  light: { type: Boolean, default: false },
  ariaLabel: { type: String, default: 'LANLinkshopping' }
})
</script>

<style scoped>
.brand-wordmark {
  display: inline-flex;
  align-items: baseline;
  margin: 0;
  font-family: inherit;
  font-weight: 700;
  letter-spacing: -0.35px;
  line-height: 1;
  white-space: nowrap;
}
.brand-wordmark .ll-lan { color: #fff; }
.brand-wordmark .ll-link { color: var(--ll-amber); font-weight: 700; }
.brand-wordmark .ll-shopping { color: rgba(255,255,255,.92); font-weight: 700; }
.brand-wordmark--light .ll-lan,
.brand-wordmark--light .ll-shopping { color: var(--ll-ink); }
.brand-wordmark--light .ll-link { color: var(--ll-amber-strong); }
</style>

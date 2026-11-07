<template>
  <header class="tech-section-head">
    <div>
      <span v-if="eyebrow" class="tech-eyebrow-dark">{{ eyebrow }}</span>
      <h2>{{ title }}</h2>
      <p v-if="desc">{{ desc }}</p>
    </div>
    <slot name="action" />
  </header>
</template>

<script setup>
defineProps({
  eyebrow: { type: String, default: '' },
  title: { type: String, required: true },
  desc: { type: String, default: '' }
})
</script>

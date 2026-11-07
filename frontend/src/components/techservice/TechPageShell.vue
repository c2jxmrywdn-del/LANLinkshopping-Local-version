<template>
  <div class="tech-page">
    <TechSubnav />
    <slot />
    <TechComplianceNote class="tech-mt-lg" />
  </div>
</template>

<script setup>
import TechSubnav from './TechSubnav.vue'
import TechComplianceNote from './TechComplianceNote.vue'
</script>

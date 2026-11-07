<template>
  <article class="tech-card">
    <div class="tech-card-top">
      <div class="tech-row">
        <span class="tech-chip" :class="achievement.achievementType === 'PATENT' ? 'cyan' : 'indigo'">
          {{ typeLabel }}
        </span>
        <span v-if="achievement.dormant" class="tech-chip amber">{{ isEn ? 'Dormant' : '沉睡专利' }}</span>
        <span v-if="achievement.verified" class="tech-chip green">{{ isEn ? 'Verified' : '权属已核验' }}</span>
      </div>
      <span class="tech-chip">{{ achievement.legalStatus || '—' }}</span>
    </div>
    <h3>{{ achievement.title }}</h3>
    <p class="achievement-desc">{{ achievement.summary }}</p>
    <div class="tech-card-meta">
      <span><Gauge :size="13" :stroke-width="2" /> TRL {{ achievement.maturityLevel }}</span>
      <span><Scale :size="13" :stroke-width="2" /> {{ licensingLabel }}</span>
      <span><Eye :size="13" :stroke-width="2" /> {{ achievement.viewCount }}</span>
    </div>
    <div class="tech-card-foot">
      <span class="tech-price">{{ priceLabel }}</span>
      <div class="tech-row">
        <button type="button" class="tech-btn tech-btn-outline tech-btn-sm" @click="$emit('assess', achievement)">
          {{ isEn ? 'Quick scan' : 'AI 快筛' }}
        </button>
        <button type="button" class="tech-btn tech-btn-primary tech-btn-sm" @click="$emit('view', achievement)">
          {{ isEn ? 'Detail' : '查看详情' }}
        </button>
      </div>
    </div>
  </article>
</template>

<script setup>
import { computed } from 'vue'
import { Gauge, Scale, Eye } from 'lucide-vue-next'
import { useTechLang } from '../../utils/techLang'

const props = defineProps({
  achievement: { type: Object, required: true }
})
defineEmits(['view', 'assess'])
const { isEn } = useTechLang()

const typeLabel = computed(() => {
  if (props.achievement.achievementType === 'PATENT') {
    return isEn.value ? 'Patent' : '专利'
  }
  return isEn.value ? 'Technology' : '技术成果'
})
const licensingLabel = computed(() => {
  const map = {
    ASSIGN: isEn.value ? 'Assignment' : '转让',
    LICENSE: isEn.value ? 'License' : '许可',
    OPEN_LICENSE: isEn.value ? 'Open license' : '开放许可'
  }
  return map[props.achievement.licensingMode] || props.achievement.licensingMode || '—'
})
const priceLabel = computed(() => {
  const n = Number(props.achievement.expectedPrice)
  if (!n) return isEn.value ? 'Negotiable' : '面议'
  return n >= 10000 ? `¥${(n / 10000).toFixed(1).replace('.0', '')}万` : `¥${n}`
})
</script>

<style scoped>
.achievement-desc {
  display: -webkit-box;
  overflow: hidden;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
}
</style>

<template>
  <article class="tech-card">
    <div class="tech-card-top">
      <div class="tech-row">
        <span class="tech-chip cyan">{{ demand.industry }}</span>
        <span class="tech-chip">{{ demand.techField }}</span>
      </div>
      <span class="tech-status" :class="statusTone"><i></i>{{ statusLabel }}</span>
    </div>
    <h3>{{ demand.title }}</h3>
    <p class="demand-desc">{{ demand.description }}</p>
    <div class="tech-card-meta">
      <span><CircleDollarSign :size="13" :stroke-width="2" /> {{ budgetLabel }}</span>
      <span v-if="demand.deadline"><Calendar :size="13" :stroke-width="2" /> {{ demand.deadline }}</span>
      <span><Eye :size="13" :stroke-width="2" /> {{ demand.viewCount }}</span>
    </div>
    <div class="tech-card-foot">
      <span class="tech-muted">
        {{ demand.anonymous ? (isEn ? 'Anonymous enterprise' : '匿名企业') : (isEn ? 'Enterprise demand' : '企业需求') }}
      </span>
      <div class="tech-row">
        <button type="button" class="tech-btn tech-btn-outline tech-btn-sm" @click="$emit('view', demand)">
          {{ isEn ? 'Detail' : '详情' }}
        </button>
        <button type="button" class="tech-btn tech-btn-primary tech-btn-sm" @click="$emit('contact', demand)">
          {{ isEn ? 'Connect' : '我来对接' }}
        </button>
      </div>
    </div>
  </article>
</template>

<script setup>
import { computed } from 'vue'
import { CircleDollarSign, Calendar, Eye } from 'lucide-vue-next'
import { useTechLang } from '../../utils/techLang'

const props = defineProps({
  demand: { type: Object, required: true }
})
defineEmits(['view', 'contact'])
const { isEn } = useTechLang()

const statusLabel = computed(() => {
  const map = {
    MATCHING: isEn.value ? 'Matching' : '匹配中',
    IN_PROGRESS: isEn.value ? 'In progress' : '对接中',
    CLOSED: isEn.value ? 'Closed' : '已关闭'
  }
  return map[props.demand.status] || props.demand.status
})
const statusTone = computed(() => {
  if (props.demand.status === 'MATCHING') return 'warn'
  if (props.demand.status === 'IN_PROGRESS') return 'active'
  return ''
})

function money(value) {
  if (value == null || value === '') return null
  const n = Number(value)
  if (Number.isNaN(n) || n <= 0) return null
  return n >= 10000 ? `¥${(n / 10000).toFixed(1).replace('.0', '')}万` : `¥${n}`
}
const budgetLabel = computed(() => {
  const max = money(props.demand.budgetMax)
  const min = money(props.demand.budgetMin)
  if (!max && !min) return isEn.value ? 'Negotiable' : '预算面议'
  if (min && max && min !== max) return `${min}–${max}`
  return max || min
})
</script>

<style scoped>
.demand-desc {
  display: -webkit-box;
  overflow: hidden;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
}
</style>

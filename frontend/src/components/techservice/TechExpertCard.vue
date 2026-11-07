<template>
  <article class="tech-card tech-expert-card">
    <div class="tech-card-top">
      <div class="expert-avatar" aria-hidden="true">{{ initials }}</div>
      <span class="tech-chip" :class="expert.expertType === 'BROKER' ? 'indigo' : 'cyan'">
        {{ expert.expertType === 'BROKER' ? (isEn ? 'Tech broker' : '技术经纪人') : (isEn ? 'Expert' : '技术专家') }}
      </span>
    </div>
    <h3>
      {{ expert.name }}
      <BadgeCheck v-if="expert.verified" :size="16" :stroke-width="2.2" class="verified" aria-label="verified" />
    </h3>
    <p class="expert-title">{{ expert.title }} · {{ expert.organization }}</p>
    <div class="tech-card-meta">
      <span><Star :size="13" :stroke-width="2" /> {{ expert.rating }}</span>
      <span><Clock :size="13" :stroke-width="2" /> {{ expert.responseHours }}h</span>
      <span>{{ expert.serviceCount }} {{ isEn ? 'services' : '次服务' }}</span>
    </div>
    <div class="tech-row tech-mt">
      <span v-for="tag in tags" :key="tag" class="tech-chip">{{ tag }}</span>
    </div>
    <div class="tech-card-foot">
      <span class="tech-price">
        ¥{{ expert.consultFee }}<small>/{{ isEn ? 'session' : '次' }}</small>
      </span>
      <div class="tech-row">
        <button type="button" class="tech-btn tech-btn-outline tech-btn-sm" @click="$emit('view', expert)">
          {{ isEn ? 'Profile' : '详情' }}
        </button>
        <button type="button" class="tech-btn tech-btn-primary tech-btn-sm" @click="$emit('consult', expert)">
          {{ isEn ? 'Book' : '预约咨询' }}
        </button>
      </div>
    </div>
  </article>
</template>

<script setup>
import { computed } from 'vue'
import { BadgeCheck, Star, Clock } from 'lucide-vue-next'
import { useTechLang } from '../../utils/techLang'

const props = defineProps({
  expert: { type: Object, required: true }
})
defineEmits(['view', 'consult'])
const { isEn } = useTechLang()

const initials = computed(() => (props.expert.name || '专').slice(0, 1))
const tags = computed(() => {
  const raw = `${props.expert.industries || ''},${props.expert.domains || ''}`
  return raw.split(',').map((s) => s.trim()).filter(Boolean).slice(0, 3)
})
</script>

<style scoped>
.tech-expert-card h3 { display: flex; align-items: center; gap: 5px; }
.verified { color: var(--ll-primary); flex: 0 0 auto; }
.expert-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 46px;
  height: 46px;
  border-radius: 15px;
  color: #fff;
  background: linear-gradient(145deg, #D6ECFA, #7DD3FC);
  font-size: 17px;
  font-weight: 700;
}
.expert-title { margin-top: 6px !important; }
</style>

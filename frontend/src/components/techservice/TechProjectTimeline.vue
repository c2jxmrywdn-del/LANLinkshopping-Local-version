<template>
  <ol class="tech-timeline">
    <li
      v-for="m in milestones"
      :key="m.milestoneId || m.seq"
      :class="{ done: m.status === 'DONE', current: m.status === 'IN_PROGRESS' }"
    >
      <i class="dot" aria-hidden="true"></i>
      <b>{{ m.name }}</b>
      <span>
        {{ statusLabel(m.status) }}
        <template v-if="m.dueDate"> · {{ isEn ? 'Due' : '截止' }} {{ m.dueDate }}</template>
        <template v-if="m.completedAt"> · {{ isEn ? 'Completed' : '完成于' }} {{ shortTime(m.completedAt) }}</template>
      </span>
      <span v-if="m.deliverable">{{ m.deliverable }}</span>
    </li>
  </ol>
</template>

<script setup>
import { useTechLang } from '../../utils/techLang'

defineProps({
  milestones: { type: Array, default: () => [] }
})
const { isEn } = useTechLang()

function statusLabel(status) {
  const map = {
    DONE: isEn.value ? 'Completed' : '已完成',
    IN_PROGRESS: isEn.value ? 'In progress' : '进行中',
    PENDING: isEn.value ? 'Pending' : '待处理'
  }
  return map[status] || status
}
function shortTime(value) {
  return String(value || '').replace('T', ' ').slice(0, 16)
}
</script>

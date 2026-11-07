<template>
  <div
    class="radar-pulse-badge"
    :class="[statusClass, sizeClass, { 'is-outline': outline }]"
  >
    <!-- 雷达脉冲扩散波纹 (Radar Pulse Wave) -->
    <div class="pulse-beacon-container" aria-hidden="true">
      <span class="beacon-core" />
      <span class="beacon-ring ring-1" />
      <span class="beacon-ring ring-2" />
    </div>

    <!-- 文本与可选图标 -->
    <div class="badge-content">
      <slot name="icon">
        <component :is="icon" v-if="icon" :size="iconSize" class="badge-icon" aria-hidden="true" />
      </slot>
      <slot>
        <span class="badge-text">{{ text }}</span>
      </slot>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  text: {
    type: String,
    default: ''
  },
  /** 'primary' (蓝) | 'success' (绿) | 'warning' (橙) | 'danger' (红) | 'ai' (紫) */
  status: {
    type: String,
    default: 'primary'
  },
  /** 'sm' (12px) | 'md' (14px) | 'lg' (16px) */
  size: {
    type: String,
    default: 'md'
  },
  icon: {
    type: Object,
    default: null
  },
  outline: {
    type: Boolean,
    default: false
  }
})

const statusClass = computed(() => `status-${props.status}`)
const sizeClass = computed(() => `size-${props.size}`)

const iconSize = computed(() => {
  if (props.size === 'sm') return 12
  if (props.size === 'lg') return 16
  return 14
})
</script>

<style scoped>
.radar-pulse-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 4px 10px;
  border-radius: 9999px;
  font-weight: 500;
  line-height: 1.2;
  box-sizing: border-box;
  user-select: none;
  transition: all 200ms ease;
}

.size-sm {
  padding: 2px 8px;
  font-size: 11px;
}
.size-md {
  padding: 4px 10px;
  font-size: 12px;
}
.size-lg {
  padding: 6px 14px;
  font-size: 13px;
}

/* 21st.dev 标杆雷达脉冲微动效 */
.pulse-beacon-container {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 10px;
  height: 10px;
}

.beacon-core {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  z-index: 2;
}

.beacon-ring {
  position: absolute;
  inset: -2px;
  border-radius: 50%;
  border: 1.5px solid currentColor;
  opacity: 0;
  transform: scale(0.6);
  animation: radarWave 2.4s cubic-bezier(0, 0.2, 0.8, 1) infinite;
  pointer-events: none;
}

.ring-2 {
  animation-delay: 0.8s;
}

@keyframes radarWave {
  0% {
    transform: scale(0.6);
    opacity: 0.8;
  }
  70% {
    transform: scale(2.4);
    opacity: 0;
  }
  100% {
    transform: scale(2.8);
    opacity: 0;
  }
}

.badge-content {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.badge-icon {
  flex-shrink: 0;
}

/* 状态色彩映射 */
.status-primary {
  background: rgba(2, 132, 199, 0.1);
  color: #0284c7;
  border: 1px solid rgba(2, 132, 199, 0.2);
}
.status-success {
  background: rgba(22, 163, 74, 0.1);
  color: #16a34a;
  border: 1px solid rgba(22, 163, 74, 0.2);
}
.status-warning {
  background: rgba(217, 119, 6, 0.1);
  color: #d97706;
  border: 1px solid rgba(217, 119, 6, 0.2);
}
.status-danger {
  background: rgba(225, 29, 72, 0.1);
  color: #e11d48;
  border: 1px solid rgba(225, 29, 72, 0.2);
}
.status-ai {
  background: rgba(99, 102, 241, 0.1);
  color: #6366f1;
  border: 1px solid rgba(99, 102, 241, 0.2);
}

.is-outline {
  background: transparent !important;
}
</style>

<template>
  <div v-if="cacheStatus" class="ai-cache-badge-wrapper" :class="badgeClass">
    <span class="ai-cache-badge" :title="tooltipText" tabindex="0" role="status">
      <component :is="iconComponent" :size="12" class="badge-icon" aria-hidden="true" />
      <span class="badge-label">{{ label }}</span>
      <span v-if="metaSuffix" class="badge-meta">{{ metaSuffix }}</span>
    </span>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Zap, Wand2, ShieldCheck, Cpu } from 'lucide-vue-next'

const props = defineProps({
  cacheStatus: {
    type: String,
    default: ''
  },
  similarity: {
    type: [Number, String],
    default: 0
  },
  latencyMs: {
    type: [Number, String],
    default: 0
  },
  role: {
    type: String,
    default: 'buyer'
  }
})

const normalizedStatus = computed(() => {
  const s = (props.cacheStatus || '').toUpperCase()
  if (s.includes('EXACT')) return 'EXACT'
  if (s.includes('SEMANTIC')) return 'SEMANTIC'
  if (s.includes('HEURISTIC')) return 'HEURISTIC'
  if (s.includes('MISS')) return 'MISS'
  return ''
})

const badgeClass = computed(() => {
  switch (normalizedStatus.value) {
    case 'EXACT':
      return 'is-exact'
    case 'SEMANTIC':
      return 'is-semantic'
    case 'HEURISTIC':
      return 'is-heuristic'
    case 'MISS':
    default:
      return 'is-miss'
  }
})

const iconComponent = computed(() => {
  switch (normalizedStatus.value) {
    case 'EXACT':
      return Zap
    case 'SEMANTIC':
      return Wand2
    case 'HEURISTIC':
      return ShieldCheck
    case 'MISS':
    default:
      return Cpu
  }
})

const label = computed(() => {
  switch (normalizedStatus.value) {
    case 'EXACT':
      return '闪击命中'
    case 'SEMANTIC':
      return '语义近邻'
    case 'HEURISTIC':
      return '应急接管'
    case 'MISS':
    default:
      return '本地推理'
  }
})

const metaSuffix = computed(() => {
  if (normalizedStatus.value === 'EXACT') {
    const lat = Number(props.latencyMs) || 1.2
    return `${lat.toFixed(1)}ms`
  }
  if (normalizedStatus.value === 'SEMANTIC') {
    const sim = Number(props.similarity)
    if (sim > 0) return `${(sim * 100).toFixed(1)}%`
  }
  if (normalizedStatus.value === 'MISS') {
    const lat = Number(props.latencyMs)
    if (lat > 0) return `${(lat / 1000).toFixed(2)}s`
  }
  return ''
})

const tooltipText = computed(() => {
  const lines = [`[LANLink AI 三级缓存状态]`]
  lines.push(`• 状态: ${props.cacheStatus}`)
  if (Number(props.similarity) > 0) {
    lines.push(`• 语义相似度: ${(Number(props.similarity) * 100).toFixed(2)}%`)
  }
  if (Number(props.latencyMs) > 0) {
    lines.push(`• 检索耗时: ${props.latencyMs}ms`)
  }
  if (props.role) {
    lines.push(`• 租户隔离: ${props.role}`)
  }
  return lines.join('\n')
})
</script>

<style scoped>
.ai-cache-badge-wrapper {
  display: inline-flex;
  align-items: center;
  user-select: none;
}

.ai-cache-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 7px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  line-height: 1.4;
  letter-spacing: 0.02em;
  transition: all 0.2s ease;
  cursor: help;
  border: 1px solid transparent;
}

.badge-icon {
  font-size: 11px;
  line-height: 1;
}

.badge-meta {
  padding-left: 3px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 10px;
  opacity: 0.9;
}

/* Tier 1: Exact Hit - 琥珀流光 / 闪电金 */
.is-exact .ai-cache-badge {
  background: linear-gradient(135deg, rgba(245, 158, 11, 0.12), rgba(217, 119, 6, 0.22));
  color: #b45309;
  border-color: rgba(245, 158, 11, 0.35);
  box-shadow: 0 0 8px rgba(245, 158, 11, 0.2);
}
.is-exact .ai-cache-badge:hover {
  background: linear-gradient(135deg, rgba(245, 158, 11, 0.2), rgba(217, 119, 6, 0.3));
  border-color: rgba(245, 158, 11, 0.55);
}

/* Tier 2: Semantic Hit - 星云紫渐变 */
.is-semantic .ai-cache-badge {
  background: linear-gradient(135deg, rgba(139, 92, 246, 0.12), rgba(124, 58, 237, 0.22));
  color: #6d28d9;
  border-color: rgba(139, 92, 246, 0.35);
  box-shadow: 0 0 8px rgba(139, 92, 246, 0.18);
}
.is-semantic .ai-cache-badge:hover {
  background: linear-gradient(135deg, rgba(139, 92, 246, 0.2), rgba(124, 58, 237, 0.3));
  border-color: rgba(139, 92, 246, 0.55);
}

/* Tier 3: Heuristic Fallback - 翡翠绿应急 */
.is-heuristic .ai-cache-badge {
  background: linear-gradient(135deg, rgba(16, 185, 129, 0.12), rgba(5, 150, 105, 0.2));
  color: #047857;
  border-color: rgba(16, 185, 129, 0.35);
}

/* Miss: 本地模型推理 - 极简灰蓝 */
.is-miss .ai-cache-badge {
  background: rgba(100, 116, 139, 0.1);
  color: #475569;
  border-color: rgba(148, 163, 184, 0.3);
}
</style>

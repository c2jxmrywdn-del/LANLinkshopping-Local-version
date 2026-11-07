<template>
  <div v-if="sources && sources.length > 0" class="ai-source-citations">
    <div class="citations-header">
      <span class="citations-title">
        <svg viewBox="0 0 24 24" width="13" height="13" stroke="currentColor" stroke-width="2" fill="none" class="title-icon">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
          <polyline points="14 2 14 8 20 8"></polyline>
          <line x1="16" y1="13" x2="8" y2="13"></line>
          <line x1="16" y1="17" x2="8" y2="17"></line>
        </svg>
        参考来源与双轨接地依据 ({{ sources.length }})
      </span>
    </div>

    <div class="citations-grid">
      <div
        v-for="(src, idx) in sources"
        :key="src.id || idx"
        class="citation-card"
        :class="{ 'has-link': Boolean(src.refId || src.url) }"
        @click="handleClick(src)"
      >
        <div class="card-head">
          <span class="card-num">[{{ idx + 1 }}]</span>
          <strong class="card-title" :title="src.title">{{ src.title }}</strong>
        </div>

        <div class="card-badges">
          <!-- 稀疏精确命中徽章 -->
          <span v-if="src.sparse_rank" class="badge-chip is-sparse" title="BM25 倒排索引精确命中" style="display:inline-flex;align-items:center;gap:3px">
            <Target :size="12" class="text-rose-500" aria-hidden="true" />
            BM25 #{{ src.sparse_rank }}
          </span>
          <!-- 稠密向量近邻徽章 -->
          <span v-if="src.dense_rank" class="badge-chip is-dense" title="HNSW 向量余弦近邻命中" style="display:inline-flex;align-items:center;gap:3px">
            <Network :size="12" class="text-sky-500" aria-hidden="true" />
            语义 #{{ src.dense_rank }}
          </span>
          <!-- 来源权威度 -->
          <span v-if="src.authority && src.authority > 1.0" class="badge-chip is-authority" title="国标/官方规范权威加权" style="display:inline-flex;align-items:center;gap:3px">
            <Award :size="12" class="text-amber-600" aria-hidden="true" />
            权威度 {{ src.authority }}x
          </span>
          <!-- 分类标签 -->
          <span v-if="src.category" class="badge-chip is-cat">
            {{ src.category }}
          </span>
        </div>

        <div v-if="src.snippet" class="card-snippet">
          {{ src.snippet }}
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { Target, Network, Award } from 'lucide-vue-next'

const props = defineProps({
  sources: {
    type: Array,
    default: () => []
  }
})

const router = useRouter()

/**
 * 来源到页面的映射。此前任何带 refId 的来源都被无差别跳到 `/product/{refId}`，
 * 商品 ID 与技术服务主键空间重叠，点一条专家引用会跳进一个不相干的商品详情页。
 * 未知来源宁可不跳转，也不猜一个可能错误的页面。
 */
const SOURCE_ROUTES = {
  product: (id) => `/product/${id}`,
  'tech-expert': () => '/tech/experts',
  'tech-demand': () => '/tech/demands',
  'tech-achievement': () => '/tech/achievements',
  'tech-post': () => '/tech/exchange',
  'tech-org': () => '/tech/policies',
  'tech-policy': () => '/tech/policies',
  'tech-service': () => '/tech',
  'tech-manual': () => '/tech'
}

function handleClick(src) {
  const build = src.source ? SOURCE_ROUTES[src.source] : null
  if (build && src.refId) {
    router.push(build(src.refId))
  } else if (src.refId && (!src.source || src.source === 'product')) {
    router.push(`/product/${src.refId}`)
  } else if (src.url) {
    window.open(src.url, '_blank', 'noopener,noreferrer')
  }
}
</script>

<style scoped>
.ai-source-citations {
  margin-top: 10px;
  padding: 8px 10px;
  border-radius: 8px;
  background: rgba(248, 250, 252, 0.7);
  border: 1px solid rgba(226, 232, 240, 0.8);
}

.citations-header {
  margin-bottom: 6px;
}

.citations-title {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  font-weight: 600;
  color: #64748b;
  text-transform: uppercase;
  letter-spacing: 0.02em;
}

.title-icon {
  color: #6366f1;
}

.citations-grid {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.citation-card {
  padding: 6px 9px;
  border-radius: 6px;
  background: #ffffff;
  border: 1px solid rgba(226, 232, 240, 0.9);
  transition: all 0.15s ease;
}

.citation-card.has-link {
  cursor: pointer;
}

.citation-card.has-link:hover {
  border-color: #a5b4fc;
  box-shadow: 0 2px 8px rgba(99, 102, 241, 0.08);
  transform: translateY(-1px);
}

.card-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}

.card-num {
  font-family: ui-monospace, monospace;
  font-weight: 700;
  color: #6366f1;
  font-size: 11px;
}

.card-title {
  color: #1e293b;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
}

.card-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 4px;
}

.badge-chip {
  display: inline-flex;
  align-items: center;
  padding: 1px 5px;
  border-radius: 4px;
  font-size: 10px;
  font-weight: 500;
  line-height: 1.3;
}

.badge-chip.is-sparse {
  background: rgba(14, 165, 233, 0.1);
  color: #0284c7;
  border: 1px solid rgba(14, 165, 233, 0.25);
}

.badge-chip.is-dense {
  background: rgba(139, 92, 246, 0.1);
  color: #7c3aed;
  border: 1px solid rgba(139, 92, 246, 0.25);
}

.badge-chip.is-authority {
  background: rgba(245, 158, 11, 0.1);
  color: #b45309;
  border: 1px solid rgba(245, 158, 11, 0.25);
}

.badge-chip.is-cat {
  background: rgba(100, 116, 139, 0.08);
  color: #64748b;
}

.card-snippet {
  margin-top: 4px;
  font-size: 11px;
  line-height: 1.4;
  color: #64748b;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
</style>

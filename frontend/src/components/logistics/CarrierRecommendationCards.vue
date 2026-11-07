<template>
  <div class="carrier-recommend-container">
    <div class="recommend-head">
      <div class="head-title">
        <Sparkles :size="15" class="text-amber-500 inline-block mr-1 align-middle animate-pulse" aria-hidden="true" />
        AI 运力多目标智能比价推荐
      </div>
      <div v-if="recommendResult?.maxSavedAmount && Number(recommendResult.maxSavedAmount) > 0" class="head-savings">
        全网比对 {{ recommendResult.totalCarriersChecked || 0 }} 家服务商，最高立省 <b>¥{{ Number(recommendResult.maxSavedAmount).toFixed(2) }}</b>
      </div>
    </div>

    <!-- 骨架加载状态 -->
    <div v-if="loading" class="recommend-loading-grid">
      <div v-for="i in 3" :key="i" class="recommend-card skeleton-card">
        <div class="sk-line sk-title"></div>
        <div class="sk-line sk-price"></div>
        <div class="sk-line sk-desc"></div>
      </div>
    </div>

    <!-- 三维决策推荐卡片 -->
    <div v-else-if="cards.length" class="recommend-cards-grid">
      <div
        v-for="card in cards"
        :key="card.key"
        class="recommend-card"
        :class="[card.typeClass, { 'is-selected': isSelected(card.quote) }]"
        @click="selectCard(card.quote)"
      >
        <!-- 顶部标签与选中态徽章 -->
        <div class="card-badge-row">
          <span class="type-pill">
            <component :is="card.icon" :size="12" class="inline mr-1 align-middle" />
            {{ card.badgeText }}
          </span>
          <span v-if="isSelected(card.quote)" class="selected-check">
            <CheckCircle2 :size="14" class="text-white" />
          </span>
        </div>

        <!-- 运费价格大字与节省标注 -->
        <div class="card-price-row">
          <span class="price-currency">¥</span>
          <span class="price-value">{{ Number(card.quote.totalFreight || 0).toFixed(2) }}</span>
          <span class="price-unit">预估运费</span>
        </div>

        <!-- 承运商与模板信息 -->
        <div class="card-carrier-row">
          <strong class="carrier-name">{{ card.quote.providerName }}</strong>
          <span class="template-name" :title="card.quote.templateName">{{ card.quote.templateName }}</span>
        </div>

        <!-- 时效与履约指标 -->
        <div class="card-metrics-row">
          <span class="metric-item">
            <Clock :size="11" class="text-slate-400 inline mr-1" />
            {{ card.quote.etaDesc || (card.quote.etaHours + 'h 达') }}
          </span>
          <span class="metric-item">
            <ShieldCheck :size="11" class="text-slate-400 inline mr-1" />
            SLA {{ card.quote.slaRate }}%
          </span>
        </div>

        <!-- 优势亮点标签池 -->
        <div class="card-highlights">
          <span v-for="(hl, hIdx) in (card.quote.highlights || []).slice(0, 2)" :key="hIdx" class="highlight-tag">
            {{ hl }}
          </span>
        </div>

        <!-- 底部一键应用按钮 -->
        <button type="button" class="apply-btn" @click.stop="selectCard(card.quote)">
          {{ isSelected(card.quote) ? '已选用该方案' : '一键应用该方案' }}
        </button>
      </div>
    </div>

    <!-- 无推荐数据兜底 -->
    <div v-else class="recommend-empty">
      填写重量体积与目的地后，AI 将自动并行试算全网最优承运方案。
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import {
  Sparkles, Zap, Truck, ShieldCheck, CheckCircle2, Clock, Award
} from 'lucide-vue-next'

const props = defineProps({
  recommendResult: {
    type: Object,
    default: () => null
  },
  loading: {
    type: Boolean,
    default: false
  },
  selectedTemplateId: {
    type: [Number, String],
    default: null
  }
})

const emit = defineEmits(['select'])

const cards = computed(() => {
  const res = props.recommendResult
  if (!res) return []
  const list = []

  if (res.costOptimal) {
    list.push({
      key: 'cost',
      typeClass: 'cost-optimal',
      badgeText: '极致性价比 · 零担首选',
      icon: Truck,
      quote: res.costOptimal
    })
  }

  if (res.speedOptimal) {
    list.push({
      key: 'speed',
      typeClass: 'speed-optimal',
      badgeText: '极速时效 · 工业特快',
      icon: Zap,
      quote: res.speedOptimal
    })
  }

  if (res.serviceOptimal) {
    list.push({
      key: 'service',
      typeClass: 'service-optimal',
      badgeText: '原厂品质 · 特种重货专线',
      icon: Award,
      quote: res.serviceOptimal
    })
  }

  return list
})

function isSelected(quote) {
  if (!quote || !props.selectedTemplateId) return false
  return Number(quote.templateId) === Number(props.selectedTemplateId)
}

function selectCard(quote) {
  if (!quote) return
  emit('select', quote)
}
</script>

<style scoped>
.carrier-recommend-container {
  margin: 12px 0 16px;
  padding: 12px 14px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
}

.recommend-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.head-title {
  font-size: 13px;
  font-weight: 700;
  color: #1e293b;
  display: inline-flex;
  align-items: center;
  letter-spacing: -0.01em;
}

.head-savings {
  font-size: 11.5px;
  color: #059669;
  background: #ecfdf5;
  padding: 2px 8px;
  border-radius: 6px;
  border: 1px solid #a7f3d0;
}

.head-savings b {
  color: #dc2626;
  margin-left: 2px;
}

/* ===== 21st.dev 动效卡片网格 ===== */
.recommend-cards-grid,
.recommend-loading-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

.recommend-card {
  position: relative;
  display: flex;
  flex-direction: column;
  padding: 12px 14px;
  background: #fff;
  border-radius: 10px;
  border: 1.5px solid #e2e8f0;
  cursor: pointer;
  transition: all 0.22s cubic-bezier(0.16, 1, 0.3, 1);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.02);
}

.recommend-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.06);
}

/* [Tier: Best Cost] 性价比最优发光微动效 */
.recommend-card.cost-optimal {
  border-color: #a7f3d0;
  background: linear-gradient(180deg, #f0fdf4 0%, #ffffff 40%);
}
.recommend-card.cost-optimal .type-pill {
  background: #ecfdf5;
  color: #059669;
  border: 1px solid #a7f3d0;
}
.recommend-card.cost-optimal.is-selected {
  border-color: #10b981;
  box-shadow: 0 0 0 2px rgba(16, 185, 129, 0.25), 0 8px 24px rgba(16, 185, 129, 0.15);
}

/* [Tier: Fastest] 极速时效发光微动效 */
.recommend-card.speed-optimal {
  border-color: #bae6fd;
  background: linear-gradient(180deg, #f0f9ff 0%, #ffffff 40%);
}
.recommend-card.speed-optimal .type-pill {
  background: #f0f9ff;
  color: #0284c7;
  border: 1px solid #bae6fd;
}
.recommend-card.speed-optimal.is-selected {
  border-color: #0284c7;
  box-shadow: 0 0 0 2px rgba(2, 132, 199, 0.25), 0 8px 24px rgba(2, 132, 199, 0.15);
}

/* [Tier: Premium] 原厂专线发光微动效 */
.recommend-card.service-optimal {
  border-color: #ddd6fe;
  background: linear-gradient(180deg, #f5f3ff 0%, #ffffff 40%);
}
.recommend-card.service-optimal .type-pill {
  background: #f5f3ff;
  color: #7c3aed;
  border: 1px solid #ddd6fe;
}
.recommend-card.service-optimal.is-selected {
  border-color: #8b5cf6;
  box-shadow: 0 0 0 2px rgba(139, 92, 246, 0.25), 0 8px 24px rgba(139, 92, 246, 0.15);
}

.card-badge-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.type-pill {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 7px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
}

.selected-check {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #0284c7;
  display: flex;
  align-items: center;
  justify-content: center;
}

.cost-optimal .selected-check { background: #059669; }
.speed-optimal .selected-check { background: #0284c7; }
.service-optimal .selected-check { background: #7c3aed; }

.card-price-row {
  display: flex;
  align-items: baseline;
  gap: 2px;
  margin-bottom: 6px;
}

.price-currency {
  font-size: 13px;
  font-weight: 600;
  color: #1e293b;
}

.price-value {
  font-size: 22px;
  font-weight: 800;
  color: #0f172a;
  letter-spacing: -0.03em;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.price-unit {
  font-size: 11px;
  color: #64748b;
  margin-left: 4px;
}

.card-carrier-row {
  display: flex;
  flex-direction: column;
  margin-bottom: 6px;
}

.carrier-name {
  font-size: 13px;
  color: #1e293b;
  font-weight: 600;
}

.template-name {
  font-size: 11px;
  color: #64748b;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.card-metrics-row {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: #475569;
  margin-bottom: 8px;
  padding: 4px 6px;
  background: rgba(0, 0, 0, 0.02);
  border-radius: 4px;
}

.card-highlights {
  display: flex;
  gap: 4px;
  flex-wrap: wrap;
  margin-bottom: 10px;
  flex: 1;
}

.highlight-tag {
  font-size: 10px;
  background: #f1f5f9;
  color: #475569;
  padding: 1px 5px;
  border-radius: 4px;
}

.apply-btn {
  width: 100%;
  height: 32px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 600;
  border: 1px solid #cbd5e1;
  background: #fff;
  color: #334155;
  cursor: pointer;
  transition: all 0.2s;
  touch-action: manipulation;
}

.recommend-card:hover .apply-btn {
  border-color: #0284c7;
  color: #0284c7;
  background: #f0f9ff;
}

.recommend-card.is-selected .apply-btn {
  background: #0284c7;
  border-color: #0284c7;
  color: #fff;
}
.cost-optimal.is-selected .apply-btn { background: #059669; border-color: #059669; }
.service-optimal.is-selected .apply-btn { background: #7c3aed; border-color: #7c3aed; }

.recommend-empty {
  font-size: 12px;
  color: #94a3b8;
  text-align: center;
  padding: 18px 10px;
}

/* 骨架屏 */
.skeleton-card {
  height: 180px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.sk-line {
  background: #e2e8f0;
  border-radius: 4px;
  animation: sk-pulse 1.5s infinite ease-in-out;
}
.sk-title { width: 50%; height: 16px; }
.sk-price { width: 70%; height: 26px; }
.sk-desc { width: 90%; height: 14px; }

@keyframes sk-pulse {
  0%, 100% { opacity: 0.6; }
  50% { opacity: 1; }
}

@media (max-width: 768px) {
  .recommend-cards-grid,
  .recommend-loading-grid {
    grid-template-columns: 1fr;
  }
  .apply-btn {
    height: 44px;
    font-size: 14px;
  }
}
</style>

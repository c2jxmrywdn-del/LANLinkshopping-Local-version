<template>
  <div class="credit-interest-calculator-root">
    <GlowBorderCard
      :glow-enabled="true"
      glow-color1="#0284c7"
      glow-color2="#6366f1"
      :glow-duration="8"
      custom-class="calculator-glow-card"
    >
      <!-- 计算器头部：标题、副标题与合规授信雷达徽章 -->
      <div class="calculator-header">
        <div class="header-main">
          <div class="calc-icon-box">
            <Calculator :size="20" class="text-sky-600" aria-hidden="true" />
          </div>
          <div>
            <h3 class="calc-title">企业账期利率与资金成本测算中枢</h3>
            <p class="calc-subtitle">
              7~15 天极速周转短融 · 执行基准日息与单利年化 APR 真实透明披露
            </p>
          </div>
        </div>
        <div class="header-seal">
          <RadarPulseBadge
            status="success"
            size="sm"
            text="第三方持牌合规授信"
            :icon="ShieldCheck"
          />
        </div>
      </div>

      <!-- Bento 网格布局主体 -->
      <div class="calculator-bento-layout">
        <!-- Bento 单元 1: 融资本金输入与大额快捷选项 -->
        <div class="bento-box principal-box">
          <label class="box-label">
            <CircleDollarSign :size="15" class="text-sky-600" aria-hidden="true" />
            <span>采购融资金额（元）</span>
          </label>
          <div class="input-wrapper">
            <span class="currency-prefix">¥</span>
            <input
              type="number"
              v-model.number="amount"
              min="1000"
              max="500000"
              step="1000"
              class="number-input"
              placeholder="请输入融资金额"
              @input="onInputChange"
            />
          </div>
          <div class="quick-amounts-grid">
            <button
              v-for="amt in [10000, 50000, 100000, 200000]"
              :key="amt"
              type="button"
              class="quick-amt-btn"
              :class="{ 'is-active': amount === amt }"
              @click="setAmount(amt)"
            >
              ¥{{ amt / 10000 }}万
            </button>
          </div>
        </div>

        <!-- Bento 单元 2: 7~15天期限切换 (FluidAnimatedTabs + TouchTermSlider 双重交互) -->
        <div class="bento-box term-box">
          <label class="box-label">
            <Calendar :size="15" class="text-indigo-600" aria-hidden="true" />
            <span>账期期限选择（7 ~ 15 天标准极速短融）</span>
          </label>
          <!-- 快捷档位药丸切换器 -->
          <FluidAnimatedTabs
            v-model="selectedTerm"
            :items="termTabs"
            variant="pill"
            custom-class="w-full mb-3"
            @change="onTermChange"
          />
          <!-- 21st.dev 移动端触控触感拖拽滑块 -->
          <TouchTermSlider
            v-model="selectedTerm"
            :min="7"
            :max="15"
            @change="onTermChange"
          />
        </div>

        <!-- Bento 单元 3: 执行利率与透明 APR 披露条 -->
        <div class="bento-box rate-box">
          <div class="rate-pills-row">
            <div class="rate-pill">
              <Percent :size="14" class="text-sky-600" aria-hidden="true" />
              <span>基准日息：<strong>{{ (dailyRate * 100).toFixed(4) }}%/天</strong></span>
            </div>
            <div class="rate-pill">
              <Clock :size="14" class="text-indigo-600" aria-hidden="true" />
              <span>单利年化：<strong>{{ (dailyRate * 360 * 100).toFixed(2) }}% APR</strong></span>
            </div>
            <div class="rate-pill">
              <ShieldCheck :size="14" class="text-emerald-600" aria-hidden="true" />
              <span>免息宽限：<strong>{{ graceDays }} 天</strong></span>
            </div>
            <button
              type="button"
              class="rate-sim-toggle"
              :class="{ 'is-active': showOverdueSim }"
              @click="showOverdueSim = !showOverdueSim"
            >
              <AlertCircle :size="14" class="text-amber-500 mr-1" aria-hidden="true" />
              <span>{{ showOverdueSim ? '收起逾期罚息模拟' : '展开逾期罚息模拟' }}</span>
            </button>
          </div>

          <!-- 可选逾期罚息模拟面板 -->
          <transition name="fade-slide">
            <div v-if="showOverdueSim" class="overdue-sim-drawer">
              <div class="sim-inner">
                <span class="sim-label">模拟逾期天数：</span>
                <div class="sim-stepper">
                  <button type="button" class="step-btn" @click="adjustOverdue(-1)" aria-label="减少1天">-</button>
                  <span class="step-val">{{ overdueDays }} 天</span>
                  <button type="button" class="step-btn" @click="adjustOverdue(1)" aria-label="增加1天">+</button>
                </div>
                <span class="sim-note">
                  逾期罚息按基准 1.5 倍执行（{{ (dailyRate * 1.5 * 100).toFixed(4) }}%/天，单利计提）
                </span>
              </div>
            </div>
          </transition>
        </div>

        <!-- Bento 单元 4: 试算结果核心看板 (Hero Payoff Card，毛玻璃拟态) -->
        <div class="bento-box result-hero-box">
          <div class="hero-main-stat">
            <span class="hero-label">到期应还总金额</span>
            <div class="hero-number-wrap">
              <span class="hero-curr">¥</span>
              <NumberTicker
                :value="trial.totalRepayment"
                :decimal-places="2"
                class="hero-value"
              />
            </div>
            <div class="hero-composition">
              融资本金 ¥{{ formatMoney(trial.principal) }} + 利息与罚息 ¥{{ formatMoney(trial.totalInterest) }}
            </div>
          </div>

          <div class="hero-metrics-subgrid">
            <div class="submetric-card">
              <span class="sub-label">正常账期利息</span>
              <span class="sub-val">¥{{ formatMoney(trial.normalInterest) }}</span>
              <span class="sub-desc">计息 {{ trial.interestBearingDays }} 天 (扣减 {{ trial.graceDays }} 天免息)</span>
            </div>
            <div class="submetric-card" :class="{ 'has-penalty': trial.penaltyInterest > 0 }">
              <span class="sub-label">逾期罚息金额</span>
              <span class="sub-val" :class="trial.penaltyInterest > 0 ? 'text-rose-600' : ''">
                ¥{{ formatMoney(trial.penaltyInterest) }}
              </span>
              <span class="sub-desc">{{ trial.overdueDays }} 天逾期产生</span>
            </div>
            <div class="submetric-card">
              <span class="sub-label">每日资金成本</span>
              <span class="sub-val text-sky-600">¥{{ formatMoney(trial.dailyCost) }}</span>
              <span class="sub-desc">基准单日占用成本</span>
            </div>
          </div>
        </div>

        <!-- Bento 单元 5: 7天 / 10天 / 15天 标准档位成本对比矩阵 -->
        <div class="bento-box tier-matrix-box">
          <div class="tier-matrix-header">
            <span class="matrix-title">标准短融档位成本横向对比</span>
            <span class="matrix-tip">点击卡片可直接一键切换至对应账期</span>
          </div>
          <div class="tier-cards-grid">
            <div
              v-for="tier in tierComparisons"
              :key="tier.termDays"
              class="tier-select-card"
              :class="{ 'is-selected': selectedTerm === tier.termDays }"
              @click="setTerm(tier.termDays)"
            >
              <div class="tier-card-top">
                <span class="tier-days-tag">{{ tier.termDays }} 天账期</span>
                <span v-if="selectedTerm === tier.termDays" class="active-pill">当前生效</span>
              </div>
              <div class="tier-card-body">
                <div class="tier-interest-row">
                  <span class="t-label">应计利息</span>
                  <span class="t-val">¥{{ formatMoney(tier.normalInterest) }}</span>
                </div>
                <div class="tier-total-row">
                  <span class="t-label">总偿还额</span>
                  <strong class="t-val-strong">¥{{ formatMoney(tier.totalRepayment) }}</strong>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 合规与征信零留存公示 -->
      <div class="compliance-footer">
        <Scale :size="16" class="text-emerald-600 flex-shrink-0" aria-hidden="true" />
        <p class="compliance-text">
          <strong>国家金融监管合规声明</strong>：本平台严格遵守国家金融监管红线，不自建征信库，严禁留存用户与企业原始征信底稿；所有授信与风控核验均由持牌第三方权威机构通过机密计算接口核定。利率严格以单利年化 APR 真实列示，拒绝套路收费与隐形开支。
        </p>
      </div>
    </GlowBorderCard>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import {
  AlertCircle,
  Calculator,
  Calendar,
  CircleDollarSign,
  Clock,
  Percent,
  Scale,
  ShieldCheck
} from 'lucide-vue-next'
import GlowBorderCard from '../ui/GlowBorderCard.vue'
import FluidAnimatedTabs from '../ui/FluidAnimatedTabs.vue'
import RadarPulseBadge from '../ui/RadarPulseBadge.vue'
import NumberTicker from '../ui/NumberTicker.vue'
import TouchTermSlider from './TouchTermSlider.vue'
import {
  calculateCreditTrial,
  clampTermDays,
  DEFAULT_DAILY_RATE,
  DEFAULT_GRACE_DAYS
} from '../../utils/creditCalculator'

const props = defineProps({
  initialAmount: {
    type: Number,
    default: 50000
  },
  initialTerm: {
    type: Number,
    default: 7
  }
})

const emit = defineEmits(['change'])

const amount = ref(props.initialAmount)
const selectedTerm = ref(clampTermDays(props.initialTerm))
const dailyRate = ref(DEFAULT_DAILY_RATE)
const graceDays = ref(DEFAULT_GRACE_DAYS)
const overdueDays = ref(0)
const showOverdueSim = ref(false)

const termTabs = [
  { label: '7天 (敏捷短融)', value: 7 },
  { label: '10天 (周转备货)', value: 10 },
  { label: '15天 (赊销上限)', value: 15 }
]

function setAmount(val) {
  amount.value = val
  notifyChange()
}

function setTerm(days) {
  selectedTerm.value = clampTermDays(days)
  notifyChange()
}

function onTermChange(val) {
  selectedTerm.value = clampTermDays(val)
  notifyChange()
}

function onInputChange() {
  selectedTerm.value = clampTermDays(selectedTerm.value)
  notifyChange()
}

function adjustOverdue(delta) {
  overdueDays.value = Math.max(0, Math.min(30, overdueDays.value + delta))
  notifyChange()
}

function notifyChange() {
  emit('change', {
    amount: amount.value,
    termDays: selectedTerm.value,
    trial: trial.value
  })
}

const trial = computed(() => {
  return calculateCreditTrial({
    amount: amount.value,
    termDays: selectedTerm.value,
    dailyRate: dailyRate.value,
    graceDays: graceDays.value,
    overdueDays: overdueDays.value
  })
})

const tierComparisons = computed(() => {
  return trial.value.tierComparisons
})

function formatMoney(n) {
  return (n || 0).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  })
}
</script>

<style scoped>
.credit-interest-calculator-root {
  width: 100%;
  max-width: 860px;
  margin: 0 auto;
}

.calculator-glow-card {
  background: rgba(255, 255, 255, 0.96);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border: 1px solid rgba(226, 232, 240, 0.9);
  border-radius: 16px;
  padding: 24px;
  box-shadow: 0 4px 24px -2px rgba(15, 23, 42, 0.06);
}

/* 头部 */
.calculator-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
  flex-wrap: wrap;
  gap: 12px;
}

.header-main {
  display: flex;
  align-items: center;
  gap: 12px;
}

.calc-icon-box {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: rgba(2, 132, 199, 0.1);
  flex-shrink: 0;
}

.calc-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  color: #0f172a;
}

.calc-subtitle {
  margin: 2px 0 0;
  font-size: 13px;
  color: #64748b;
}

/* Bento 网格布局 */
.calculator-bento-layout {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.bento-box {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 16px;
  transition: all 200ms ease;
}

.bento-box:hover {
  background: #ffffff;
  border-color: #cbd5e1;
}

.box-label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: #334155;
  margin-bottom: 12px;
}

/* 本金输入 */
.input-wrapper {
  display: flex;
  align-items: center;
  background: #ffffff;
  border: 1px solid #cbd5e1;
  border-radius: 10px;
  padding: 0 14px;
  margin-bottom: 12px;
  transition: all 160ms ease;
}

.input-wrapper:focus-within {
  border-color: #0284c7;
  box-shadow: 0 0 0 3px rgba(2, 132, 199, 0.15);
}

.currency-prefix {
  font-size: 18px;
  font-weight: 700;
  color: #0284c7;
  margin-right: 8px;
}

.number-input {
  width: 100%;
  height: 44px;
  border: none;
  background: transparent;
  font-size: 17px;
  font-weight: 700;
  color: #0f172a;
  outline: none;
}

.quick-amounts-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
}

.quick-amt-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 8px 12px;
  min-height: 44px; /* 人机工效 44px */
  border-radius: 8px;
  border: 1px solid #e2e8f0;
  background: #ffffff;
  color: #475569;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 160ms ease;
  touch-action: manipulation;
}

.quick-amt-btn:hover {
  border-color: #0284c7;
  color: #0284c7;
}

.quick-amt-btn.is-active {
  background: #0284c7;
  border-color: #0284c7;
  color: #ffffff;
  box-shadow: 0 2px 8px rgba(2, 132, 199, 0.3);
}

/* 利率条 */
.rate-pills-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
}

.rate-pill {
  display: flex;
  align-items: center;
  font-size: 12px;
  color: #475569;
  background: #ffffff;
  padding: 6px 12px;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
}

.rate-pill strong {
  color: #0f172a;
  margin-left: 2px;
}

.rate-sim-toggle {
  display: flex;
  align-items: center;
  padding: 6px 12px;
  min-height: 44px;
  border-radius: 8px;
  border: 1px dashed #cbd5e1;
  background: #ffffff;
  color: #475569;
  font-size: 12px;
  cursor: pointer;
  transition: all 160ms ease;
  touch-action: manipulation;
}

.rate-sim-toggle:hover,
.rate-sim-toggle.is-active {
  border-color: #f59e0b;
  color: #b45309;
  background: #fffbeb;
}

/* 逾期模拟抽屉 */
.overdue-sim-drawer {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #e2e8f0;
}

.sim-inner {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.sim-label {
  font-size: 12px;
  font-weight: 600;
  color: #475569;
}

.sim-stepper {
  display: flex;
  align-items: center;
  border: 1px solid #cbd5e1;
  border-radius: 8px;
  overflow: hidden;
  background: #ffffff;
}

.step-btn {
  width: 44px;
  height: 44px;
  border: none;
  background: #f1f5f9;
  font-size: 18px;
  font-weight: 600;
  color: #334155;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 150ms ease;
  touch-action: manipulation;
}

.step-btn:hover {
  background: #e2e8f0;
}

.step-val {
  padding: 0 12px;
  font-size: 13px;
  font-weight: 700;
  color: #0f172a;
  min-width: 50px;
  text-align: center;
}

.sim-note {
  font-size: 12px;
  color: #92400e;
}

/* Hero 结果看板 */
.result-hero-box {
  background: linear-gradient(135deg, rgba(2, 132, 199, 0.04) 0%, rgba(99, 102, 241, 0.06) 100%);
  border-color: rgba(2, 132, 199, 0.2);
}

.hero-main-stat {
  text-align: center;
  padding: 16px 0;
  border-bottom: 1px solid rgba(226, 232, 240, 0.8);
}

.hero-label {
  font-size: 13px;
  font-weight: 600;
  color: #475569;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.hero-number-wrap {
  display: flex;
  align-items: baseline;
  justify-content: center;
  gap: 4px;
  margin: 6px 0;
}

.hero-curr {
  font-size: 24px;
  font-weight: 700;
  color: #0284c7;
}

.hero-value {
  font-size: 38px;
  font-weight: 800;
  color: #0f172a;
  line-height: 1;
}

.hero-composition {
  font-size: 13px;
  color: #64748b;
}

.hero-metrics-subgrid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-top: 16px;
}

.submetric-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.submetric-card.has-penalty {
  background: #fff1f2;
  border-color: #fecdd3;
}

.sub-label {
  font-size: 11px;
  font-weight: 600;
  color: #64748b;
}

.sub-val {
  font-size: 16px;
  font-weight: 700;
  color: #0f172a;
}

.sub-desc {
  font-size: 10px;
  color: #94a3b8;
}

/* 档位横向矩阵 */
.tier-matrix-box {
  background: #ffffff;
}

.tier-matrix-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  flex-wrap: wrap;
  gap: 6px;
}

.matrix-title {
  font-size: 13px;
  font-weight: 700;
  color: #0f172a;
}

.matrix-tip {
  font-size: 11px;
  color: #94a3b8;
}

.tier-cards-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}

.tier-select-card {
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  padding: 12px;
  background: #f8fafc;
  cursor: pointer;
  transition: all 160ms ease;
  touch-action: manipulation;
}

.tier-select-card:hover {
  border-color: #0284c7;
  background: #ffffff;
  transform: translateY(-1px);
}

.tier-select-card.is-selected {
  border-color: #0284c7;
  background: #f0f9ff;
  box-shadow: 0 0 0 2px rgba(2, 132, 199, 0.2);
}

.tier-card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.tier-days-tag {
  font-size: 13px;
  font-weight: 700;
  color: #0f172a;
}

.active-pill {
  font-size: 10px;
  font-weight: 600;
  color: #0284c7;
  background: rgba(2, 132, 199, 0.12);
  padding: 1px 6px;
  border-radius: 9999px;
}

.tier-card-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.tier-interest-row,
.tier-total-row {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
}

.tier-interest-row .t-label,
.tier-total-row .t-label {
  color: #64748b;
}

.tier-interest-row .t-val {
  color: #475569;
}

.tier-total-row .t-val-strong {
  color: #0f172a;
  font-weight: 700;
}

/* 底部合规声明 */
.compliance-footer {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  border-radius: 10px;
  padding: 12px 14px;
  margin-top: 16px;
}

.compliance-text {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: #166534;
}

.compliance-text strong {
  color: #14532d;
}

/* 动效 */
.fade-slide-enter-active,
.fade-slide-leave-active {
  transition: all 200ms ease;
}

.fade-slide-enter-from,
.fade-slide-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

/* 移动端响应式断点 */
@media (max-width: 768px) {
  .hero-metrics-subgrid {
    grid-template-columns: 1fr;
  }
  .tier-cards-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .calculator-glow-card {
    padding: 16px;
  }
  .quick-amounts-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .hero-value {
    font-size: 30px;
  }
}
</style>

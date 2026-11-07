<template>
  <div class="vip-plan-block">
    <!-- 计费周期分段切换（D1 默认按年付费）：原生 radio 承载方向键换挡语义 -->
    <div class="vip-seg" role="radiogroup" aria-label="计费周期">
      <label class="vip-seg-item" :class="{ on: billingModel === 'month' }">
        <input class="vip-seg-radio" type="radio" name="vip-billing" value="month" v-model="billingModel" />
        <span>按月付费</span>
      </label>
      <label class="vip-seg-item" :class="{ on: billingModel === 'year' }">
        <input class="vip-seg-radio" type="radio" name="vip-billing" value="year" v-model="billingModel" />
        <span>按年付费</span>
      </label>
    </div>

    <!-- 档位卡组：卡片只是选择热区（radio 语义），提交由区块底部唯一 CTA 完成 -->
    <Transition name="vip-num" mode="out-in">
      <div v-if="visiblePlans.length" class="vip-plan-grid" :key="billingModel" role="radiogroup" aria-label="订阅档位" @keydown="onKeydown">
        <button v-for="p in visiblePlans" :key="p.planId" type="button" role="radio"
                class="plan-card ll-glass--day"
                :class="{ 'is-selected': isChecked(p), 'is-busy': isBusy(p) }"
                :aria-checked="isChecked(p)"
                :tabindex="isChecked(p) ? 0 : -1"
                @click="select(p)">
          <span class="plan-ring" aria-hidden="true"></span>
          <span class="plan-top">
            <span class="plan-name">{{ p.planName }}</span>
            <span class="plan-badges">
              <span v-if="isReco(p)" class="plan-reco">推荐档</span>
              <span v-if="p.discountLabel" class="plan-badge">{{ p.discountLabel }}</span>
            </span>
          </span>
          <!-- Apple 价格层级：划线对比价（年付卡，按月价×12）→ 大号现价 → 周期小字 -->
          <span v-if="isReco(p) && strikePrice" class="plan-strike">¥{{ strikePrice }}</span>
          <span class="plan-price">¥{{ formatPrice(p.priceCents) }}</span>
          <span class="plan-meta">{{ p.durationDays }} 天</span>
          <span v-if="isReco(p) && perMonthPrice" class="plan-equiv">折合 ¥{{ perMonthPrice }}/月</span>
          <!-- P1-4 扣款调度上线后恢复「自动续费」真实标注（连续包月方案 auto_renew=1，调度器按日扣款顺延） -->
          <span v-if="p.autoRenew" class="plan-note">自动续费</span>
        </button>
      </div>
      <p v-else class="plan-empty" key="empty">暂无数据</p>
    </Transition>
  </div>
</template>

<script setup>
import { computed } from 'vue'

// 价格为整数元（price_cents 存整数元，展示只加千分位，禁止除以 100 —— 口径同 MembershipCenter.formatPrice）
function formatPrice(n) { return Number(n || 0).toLocaleString('zh-CN') }

const props = defineProps({
  plans: { type: Array, default: () => [] },
  modelValue: { type: [String, Number], default: '' },
  busy: { type: [String, Number], default: '' },
  billing: { type: String, default: 'year' } // D1：默认「按年付费」
})
const emit = defineEmits(['update:billing', 'update:modelValue'])

const billingModel = computed({
  get: () => (props.billing === 'month' ? 'month' : 'year'),
  set: (v) => emit('update:billing', v)
})

// 分段归组：连续包月/月度 → 按月段；季度(90 天)/年度(365 天) → 按年段
function segmentOf(p) {
  const code = String(p?.planCode || '').toUpperCase()
  if (code.includes('MONTH') || code.includes('AUTO')) return 'month'
  if (p?.durationDays != null) return Number(p.durationDays) > 30 ? 'year' : 'month'
  return 'month'
}
const visiblePlans = computed(() => props.plans.filter(p => segmentOf(p) === billingModel.value))

// 推荐档 = 按年段中周期最长的档（对齐年度订阅；无年段时退化为全局周期最长档）
const recoPlanId = computed(() => {
  const pool = props.plans.filter(p => segmentOf(p) === 'year')
  const list = pool.length ? pool : props.plans
  if (!list.length) return null
  return list.reduce((best, p) => (Number(p.durationDays) > Number(best.durationDays) ? p : best))?.planId ?? null
})
function isReco(p) { return recoPlanId.value != null && String(p.planId) === String(recoPlanId.value) }

// 年付卡对比价 = 月度档价 × 12（无月度档时不展示划线价，不虚构对比基准）
const strikePrice = computed(() => {
  const base = props.plans.find(p => segmentOf(p) === 'month' && !/AUTO/i.test(String(p.planCode || '')))
  const reco = props.plans.find(p => isReco(p))
  return base && reco ? formatPrice(Number(base.priceCents) * 12) : ''
})
const perMonthPrice = computed(() => {
  const reco = props.plans.find(p => isReco(p))
  return reco ? formatPrice(Math.round(Number(reco.priceCents) / 12)) : ''
})

const isChecked = (p) => String(p.planId) === String(props.modelValue)
const isBusy = (p) => props.busy !== '' && String(p.planId) === String(props.busy)

function select(p) {
  if (isBusy(p)) return
  emit('update:modelValue', p.planId)
}

// radio 键盘换挡：方向键在档位间移动选中并移动焦点，空格/回车经原生 button click 已覆盖
function onKeydown(e) {
  if (!['ArrowRight', 'ArrowDown', 'ArrowLeft', 'ArrowUp'].includes(e.key)) return
  e.preventDefault()
  const list = visiblePlans.value
  if (!list.length) return
  const cur = list.findIndex(p => isChecked(p))
  const dir = e.key === 'ArrowRight' || e.key === 'ArrowDown' ? 1 : -1
  const next = cur < 0 ? 0 : (cur + dir + list.length) % list.length
  select(list[next])
}
</script>

<style scoped>
.vip-plan-block{margin-bottom:14px}
/* 分段切换：胶囊分段，native radio 视觉隐藏但可聚焦 */
.vip-seg{display:flex;gap:4px;margin:0 auto 14px;padding:4px;border-radius:999px;background:var(--ll-surface-inset);width:fit-content}
.vip-seg-item{position:relative;display:inline-flex;align-items:center;justify-content:center;min-width:120px;min-height:36px;padding:6px 18px;border-radius:999px;cursor:pointer;font-size:13px;font-weight:600;color:var(--ll-slate-muted);transition:background var(--ll-transition-fast) ease,color var(--ll-transition-fast) ease}
.vip-seg-item.on{background:var(--ll-card);color:var(--ll-primary);box-shadow:var(--ll-shadow-xs)}
.vip-seg-item:hover{color:var(--ll-primary)}
.vip-seg-radio{position:absolute;opacity:0;width:1px;height:1px;margin:0;pointer-events:none}
.vip-seg-item:focus-within{box-shadow:var(--ll-focus-ring)}
/* 档位卡组 */
.vip-plan-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}
.plan-card{position:relative;display:flex;flex-direction:column;align-items:flex-start;gap:4px;padding:18px 16px;border-radius:var(--ll-glass-day-radius);text-align:left;cursor:pointer;font:inherit;z-index:0}
.plan-card:hover{border-color:var(--ll-primary)}
.plan-card.is-busy{opacity:.72;pointer-events:none}
.plan-ring{position:absolute;inset:0;border-radius:inherit;pointer-events:none;opacity:0;z-index:2}
.plan-card.is-selected .plan-ring{opacity:1;box-shadow:var(--ll-vip-card-ring)}
.plan-card:focus-visible{outline:none}
.plan-card:focus-visible .plan-ring{opacity:1;box-shadow:var(--ll-focus-ring)}
.plan-top{display:flex;align-items:center;justify-content:space-between;gap:8px;width:100%}
.plan-name{font-size:14px;font-weight:700;color:var(--ll-ink-on-glass)}
.plan-badges{display:inline-flex;gap:6px;align-items:center}
/* 推荐档：品牌渐变实底胶囊 + 深字（对比口径同 .ll-add-cart） */
.plan-reco{font-size:11px;font-weight:700;color:var(--ll-vip-badge-ink);background:var(--ll-brand-gradient);border-radius:999px;padding:2px 9px;white-space:nowrap}
.plan-badge{font-size:11px;font-weight:700;color:var(--ll-text-inverse);background:var(--ll-primary);border-radius:999px;padding:2px 8px;white-space:nowrap}
.plan-strike{font-size:12px;color:var(--ll-muted);text-decoration:line-through;font-variant-numeric:tabular-nums}
.plan-price{font-size:var(--ll-text-2xl);font-weight:800;color:var(--ll-primary);line-height:1.2;font-variant-numeric:tabular-nums}
.plan-meta{font-size:var(--ll-text-xs);color:var(--ll-slate-muted);font-variant-numeric:tabular-nums}
.plan-equiv{font-size:12px;color:var(--ll-slate-muted);font-variant-numeric:tabular-nums}
.plan-note{font-size:11px;color:var(--ll-primary);background:var(--ll-surface-inset);border-radius:999px;padding:2px 8px}
.plan-empty{margin:0;text-align:center;color:var(--ll-muted);font-size:13px}
/* 价格/卡组数字过渡：读全局时长与曲线；reduced-motion 下静止 */
.vip-num-enter-active,.vip-num-leave-active{transition:opacity var(--ll-dur-base) var(--ll-ease-standard),transform var(--ll-dur-base) var(--ll-ease-standard)}
.vip-num-enter-from,.vip-num-leave-to{opacity:0;transform:translateY(6px)}
@media(prefers-reduced-motion:reduce){.vip-num-enter-active,.vip-num-leave-active{transition:none}.vip-num-enter-from,.vip-num-leave-to{transform:none}}
@media(max-width:720px){.vip-plan-grid{grid-template-columns:1fr}}
@media(max-width:480px){.vip-seg{width:100%}.vip-seg-item{flex:1;min-width:0}}
</style>

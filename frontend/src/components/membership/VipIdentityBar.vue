<template>
  <section class="vip-idbar ll-glass--day" role="status">
    <span class="idbar-crown" aria-hidden="true"><Crown :size="18" :stroke-width="2" /></span>
    <div class="idbar-copy">
      <!-- 未订阅：当前等级 + 等级折扣（computed 单文本节点，EN 走锚定模式翻译） -->
      <p v-if="state === 'none'" class="idbar-line">{{ identityLine }}</p>
      <!-- 生效中：当前方案 + 到期时间 + 续费态 chip（P1-4 调度上线后按订阅实际 auto_renew 渲染真实状态） -->
      <template v-else-if="state === 'active'">
        <p class="idbar-line"><span class="idbar-label">当前方案</span><b class="idbar-plan">{{ planName }}</b></p>
        <p class="idbar-meta"><span>到期时间</span> <b>{{ endDate }}</b><span class="idbar-renew-chip">{{ autoRenew ? '自动续费' : '到期可一键续费' }}</span></p>
      </template>
      <!-- 已取消/已过期：挽回态，恢复走续费同链路 -->
      <template v-else>
        <p class="idbar-line"><b class="idbar-status">{{ statusText }}</b></p>
        <p class="idbar-meta"><span>到期时间</span> <b>{{ endDate }}</b></p>
      </template>
      <!-- 双轨口径说明位：等级折扣（订阅+成长值轨）与 VIP 消费积分（身份轨）并置，防混称 -->
      <div class="idbar-dual">
        <span class="dual-chip"><BadgePercent :size="13" :stroke-width="2" aria-hidden="true" />{{ tierDiscountLine }}</span>
        <span class="dual-chip"><Coins :size="13" :stroke-width="2" aria-hidden="true" />{{ vipPointsLine }}</span>
      </div>
      <p v-if="state === 'none'" class="idbar-hint">需有效订阅方可升级</p>
    </div>
    <div v-if="state === 'active'" class="idbar-actions">
      <a-button size="small" type="primary" :loading="busy" @click="$emit('renew')">续费</a-button>
      <!-- D3 cancel 语义 = 撤销自动续费：auto_renew 已关时无操作可做，隐藏取消入口 -->
      <a-button v-if="autoRenew" size="small" danger @click="$emit('cancel')">取消订阅</a-button>
    </div>
    <div v-else-if="state === 'ended'" class="idbar-actions">
      <a-button size="small" type="primary" :loading="busy" @click="$emit('renew')">续费</a-button>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import { Crown, BadgePercent, Coins } from 'lucide-vue-next'

// 身份态三态：none 未订阅 / active 生效中 / ended 已取消或已过期（状态归一由父级 STATUS_MAP 完成）
const props = defineProps({
  state: { type: String, default: 'none' },
  levelName: { type: String, default: '' },
  rateText: { type: String, default: '' },
  planName: { type: String, default: '' },
  endDate: { type: String, default: '' },
  statusText: { type: String, default: '' },
  multiplier: { type: Number, default: 1 },
  // P1-4 调度上线后按订阅实际 auto_renew 渲染：true=「自动续费」chip+取消入口；false=「到期可一键续费」
  autoRenew: { type: [Boolean, Number], default: false },
  busy: { type: [Boolean, String, Number], default: false }
})
defineEmits(['renew', 'cancel'])

// 三个组合句一律单文本节点（含插值的句子不拆节点，防语序错乱与漏译）
const identityLine = computed(() => `当前等级：${props.levelName || '普通会员'} · 等级折扣 ${props.rateText || '无折扣'}`)
const tierDiscountLine = computed(() => `等级折扣 ${props.rateText || '无折扣'}`)
const vipPointsLine = computed(() => `VIP 消费享 ×${props.multiplier} 积分（按累计消费身份判定）`)
</script>

<style scoped>
.vip-idbar{display:flex;align-items:flex-start;gap:14px;padding:16px 18px;margin-bottom:14px;position:relative;z-index:0}
.idbar-crown{flex:0 0 auto;display:inline-flex;align-items:center;justify-content:center;width:38px;height:38px;border-radius:12px;background:var(--ll-brand-gradient);color:var(--ll-vip-badge-ink)}
.idbar-copy{flex:1 1 auto;min-width:0}
.idbar-line{margin:0;font-size:15px;font-weight:700;color:var(--ll-ink-on-glass);display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.idbar-label{font-size:12px;font-weight:400;color:var(--ll-slate-muted)}
.idbar-plan{color:var(--ll-ink-on-glass)}
.idbar-status{color:var(--ll-ink-on-glass)}
.idbar-meta{margin:4px 0 0;font-size:12px;color:var(--ll-slate-muted);display:flex;align-items:center;gap:6px;flex-wrap:wrap}
.idbar-meta b{color:var(--ll-ink-on-glass);font-weight:600}
.idbar-renew-chip{font-size:11px;color:var(--ll-primary);border:1px solid var(--ll-separator-strong);border-radius:999px;padding:1px 8px}
.idbar-dual{display:flex;gap:8px;flex-wrap:wrap;margin-top:10px}
.dual-chip{display:inline-flex;align-items:center;gap:5px;font-size:12px;color:var(--ll-slate-muted);background:var(--ll-surface-inset);border-radius:999px;padding:4px 10px}
.idbar-hint{margin:8px 0 0;font-size:12px;color:var(--ll-slate-muted)}
.idbar-actions{flex:0 0 auto;display:flex;gap:8px;align-items:center}
@media(max-width:480px){.vip-idbar{flex-direction:column;gap:10px}.idbar-actions{width:100%}.idbar-actions .ant-btn{flex:1}}
</style>

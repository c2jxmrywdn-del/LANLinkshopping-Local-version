<template>
  <section class="vip-compare ll-glass--day">
    <button type="button" class="cmp-toggle" :aria-expanded="open ? 'true' : 'false'" @click="open = !open">
      <span class="cmp-title">权益对比</span>
      <ChevronDown :size="15" :stroke-width="2.2" class="cmp-caret" :class="{ open }" aria-hidden="true" />
    </button>
    <div v-show="open" class="cmp-body">
      <!-- 两列特化：权益由「有效订阅 + 成长值档位」决定，不存在多档订阅权益差 -->
      <table class="cmp-table">
        <thead>
          <tr>
            <th scope="col" class="cmp-what">权益</th>
            <th scope="col">未订阅（免费身份）</th>
            <th scope="col" class="cmp-vip-col">已订阅（会员档）</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in ROWS" :key="row.label">
            <th scope="row" class="cmp-what">
              <span class="cmp-ico"><component :is="row.icon" :size="15" :stroke-width="2" aria-hidden="true" /></span>
              <span class="cmp-label">
                {{ row.label }}
                <small v-if="row.note">{{ row.note }}</small>
              </span>
            </th>
            <td>
              <span class="cmp-cell" :class="{ 'is-x': row.free.kind === 'x' }">
                <Check v-if="row.free.kind === 'check'" :size="14" :stroke-width="2.6" class="cmp-check" role="img" aria-label="已包含" />
                <X v-else :size="14" :stroke-width="2.6" class="cmp-x" role="img" aria-label="未包含" />
                <span v-if="row.free.text" class="cmp-cell-text">{{ row.free.text }}</span>
              </span>
            </td>
            <td class="cmp-vip-col">
              <span class="cmp-cell">
                <Check :size="14" :stroke-width="2.6" class="cmp-check" role="img" aria-label="已包含" />
                <span v-if="row.vipText" class="cmp-cell-text">{{ row.vipText }}</span>
              </span>
            </td>
          </tr>
        </tbody>
      </table>
      <!-- 双轨口径注（随表常驻输出给读屏，不 aria-hidden）：×2 与专属通道是身份轨权益，订阅解锁的是等级折扣资格 -->
      <p class="cmp-note">消费积分 ×2 为累计消费身份权益（累计已支付满 ¥5,000），不随订阅开通；订阅解锁的是等级折扣资格。</p>
      <p class="cmp-note">人工客服工单对所有登录用户开放。</p>
    </div>
  </section>
</template>

<script setup>
import { ref } from 'vue'
import { Check, X, ChevronDown, BadgePercent, Coins, TicketPercent, TrendingUp, Headset } from 'lucide-vue-next'

// 行内容只写真实存在的权益（与 MembershipBenefits 4 项静态权益对齐，不虚构 API 配额/SLA/席位）
const ROWS = [
  {
    icon: BadgePercent, label: '等级折扣',
    free: { kind: 'x', text: '普通会员 1.000，无折扣' },
    vipText: '解锁资格：随成长值六档 0.990~0.900（升级双条件之一）'
  },
  { icon: Coins, label: '消费积分', free: { kind: 'check', text: '1 元 = 1 积分' }, vipText: '1 元 = 1 积分' },
  { icon: TicketPercent, label: '积分抵现', note: '1000 积分 = 1 元，单笔 ≤ 应付 10%', free: { kind: 'check' } },
  { icon: TrendingUp, label: '成长值累积', free: { kind: 'check' } },
  { icon: Headset, label: '在线客服支持', free: { kind: 'check' } }
]

const open = ref(true)
</script>

<style scoped>
.vip-compare{padding:14px 18px;margin-bottom:14px;position:relative;z-index:0}
.cmp-toggle{display:flex;align-items:center;justify-content:space-between;width:100%;padding:2px 0;background:none;border:0;cursor:pointer;font:inherit}
.cmp-title{font-size:15px;font-weight:800;color:var(--ll-ink-on-glass)}
.cmp-caret{color:var(--ll-slate-muted);transition:transform var(--ll-transition-fast) ease}
.cmp-caret.open{transform:rotate(180deg)}
.cmp-body{margin-top:10px}
.cmp-table{width:100%;border-collapse:collapse;font-size:13px}
.cmp-table th,.cmp-table td{padding:10px 12px;border-top:1px solid var(--ll-separator);text-align:left;vertical-align:top}
.cmp-table thead th{border-top:0;font-size:12px;font-weight:600;color:var(--ll-slate-muted);white-space:nowrap}
.cmp-table tbody th{font-weight:600;color:var(--ll-ink-on-glass)}
.cmp-table tbody tr{transition:background var(--ll-transition-fast) ease}
.cmp-table tbody tr:hover{background:var(--ll-surface-inset)}
.cmp-what{width:34%}
.cmp-ico{display:inline-flex;align-items:center;justify-content:center;width:26px;height:26px;border-radius:8px;background:var(--ll-surface-inset);color:var(--ll-primary);flex:0 0 auto}
.cmp-label{display:inline-flex;flex-direction:column;gap:2px;vertical-align:middle}
.cmp-label small{font-size:11px;font-weight:400;color:var(--ll-slate-muted)}
.cmp-cell{display:inline-flex;align-items:flex-start;gap:8px}
.cmp-check{color:var(--ll-primary);flex:0 0 auto;margin-top:2px}
.cmp-x{color:var(--ll-muted);flex:0 0 auto;margin-top:2px}
.cmp-cell.is-x .cmp-cell-text{color:var(--ll-muted)}
.cmp-cell-text{color:var(--ll-ink-on-glass);font-variant-numeric:tabular-nums}
/* 已订阅列整列高亮：底色走 day-glass 深 tint token + 顶部 4px 品牌渐变条（非仅颜色区分，表头文字双通道） */
.cmp-vip-col{position:relative;background:var(--ll-glass-day-bg-deep)}
thead .cmp-vip-col::before{content:'';position:absolute;left:0;right:0;top:0;height:4px;background:var(--ll-vip-highlight-bar);border-radius:0 0 4px 4px}
thead .cmp-vip-col{color:var(--ll-primary);font-weight:700}
.cmp-note{margin:10px 0 0;font-size:12px;color:var(--ll-slate-muted);line-height:var(--ll-leading-normal)}
@media(max-width:720px){.cmp-what{width:30%}.cmp-table th,.cmp-table td{padding:8px 8px;font-size:12px}}
</style>

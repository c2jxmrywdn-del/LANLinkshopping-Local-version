<template>
  <div class="payment-method-selector">
    <div class="bento-grid">
      <div
        v-for="item in paymentChannels"
        :key="item.key"
        class="bento-tile"
        :class="{
          active: modelValue === item.key,
          disabled: item.disabled,
          recommended: item.recommended
        }"
        @click="selectChannel(item)"
      >
        <div v-if="item.recommended" class="recommended-badge">
          <ShieldCheck :size="12" class="mr-1" /> 推荐方式
        </div>

        <div class="tile-content">
          <div class="tile-icon-wrap" :style="{ color: item.iconColor }">
            <component :is="item.icon" :size="22" />
          </div>

          <div class="tile-meta">
            <div class="tile-title-row">
              <span class="tile-title">{{ item.title }}</span>
              <span v-if="item.badge" class="tile-badge" :class="item.badgeClass">
                {{ item.badge }}
              </span>
            </div>
            <p class="tile-desc">{{ item.desc }}</p>
          </div>

          <div class="tile-indicator">
            <div class="indicator-ring">
              <div class="indicator-dot"></div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import {
  CreditCard,
  QrCode,
  Wallet,
  Building2,
  Smartphone,
  CalendarCheck2,
  ShieldCheck
} from 'lucide-vue-next'

const props = defineProps({
  modelValue: {
    type: String,
    default: 'alipay'
  },
  walletBalance: {
    type: Number,
    default: 0
  },
  creditStatus: {
    type: String,
    default: 'NONE'
  },
  creditAvailable: {
    type: Number,
    default: 0
  },
  payableTotal: {
    type: [Number, String],
    default: 0
  }
})

const emit = defineEmits(['update:modelValue', 'change'])

const paymentChannels = computed(() => [
  {
    key: 'stripe',
    title: 'Stripe 国际卡',
    desc: 'Visa, Mastercard, Amex, 银联国际 (3DS2 强认证)',
    icon: CreditCard,
    iconColor: '#6366f1',
    badge: '全球外卡',
    badgeClass: 'badge-indigo',
    recommended: true
  },
  {
    key: 'apple_pay',
    title: 'Apple Pay (苹果支付)',
    desc: 'Safari / iOS 原生 Touch ID / Face ID 极速免密确认',
    icon: Smartphone,
    iconColor: '#0f172a',
    badge: 'iOS/Mac',
    badgeClass: 'badge-slate'
  },
  {
    key: 'google_pay',
    title: 'Google Pay (谷歌支付)',
    desc: 'Android / Chrome 浏览器绑定银行卡一键付款',
    icon: Smartphone,
    iconColor: '#2563eb',
    badge: 'Android',
    badgeClass: 'badge-blue'
  },
  {
    key: 'alipay',
    title: '支付宝 (Alipay)',
    desc: '电脑网站跳转收银台或手机支付宝 App 扫码',
    icon: QrCode,
    iconColor: '#1677ff',
    badge: '即时到账',
    badgeClass: 'badge-sky'
  },
  {
    key: 'wechat',
    title: '微信支付 (WeChat Pay)',
    desc: '微信原生 Native 动态扫码极速结算',
    icon: QrCode,
    iconColor: '#07c160',
    badge: '扫码付',
    badgeClass: 'badge-emerald'
  },
  {
    key: 'balance',
    title: '企业钱包 (Wallet)',
    desc: `可用余额 ¥${Number(props.walletBalance || 0).toFixed(2)}`,
    icon: Wallet,
    iconColor: '#f59e0b',
    disabled: Number(props.walletBalance || 0) < Number(props.payableTotal || 0) && Number(props.walletBalance || 0) <= 0,
    badge: '余额扣减',
    badgeClass: 'badge-amber'
  },
  {
    key: 'term',
    title: '企业账期 (Credit Term)',
    desc: props.creditStatus === 'ACTIVE'
      ? `可用授信额度 ¥${Number(props.creditAvailable || 0).toFixed(2)} · 7~15天免息周转`
      : '专属企业授信担保支付 · 需先开通账期',
    icon: CalendarCheck2,
    iconColor: '#0ea5e9',
    badge: '先货后款',
    badgeClass: 'badge-sky',
    disabled: props.creditStatus !== 'ACTIVE' || Number(props.creditAvailable || 0) < Number(props.payableTotal || 0)
  },
  {
    key: 'corporate',
    title: '对公转账 (Corporate Wire)',
    desc: '大宗工业品银行电汇核验 · 展示供货商认证对公账户',
    icon: Building2,
    iconColor: '#0284c7',
    badge: 'B2B大宗',
    badgeClass: 'badge-blue'
  }
])

function selectChannel(item) {
  if (item.disabled) return
  emit('update:modelValue', item.key)
  emit('change', item.key)
}
</script>

<style scoped>
.payment-method-selector {
  width: 100%;
}

.bento-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}

.bento-tile {
  position: relative;
  background: var(--mc-card, #ffffff);
  border: 1.5px solid var(--mc-border, #e2e8f0);
  border-radius: 14px;
  padding: 14px 16px;
  cursor: pointer;
  min-height: 72px;
  box-sizing: border-box;
  transition: all 0.22s cubic-bezier(0.16, 1, 0.3, 1);
  overflow: hidden;
  user-select: none;
}

.bento-tile:hover:not(.disabled) {
  border-color: var(--ll-primary, #1e6eb8);
  transform: translateY(-2px);
  box-shadow: 0 8px 20px -4px rgba(30, 110, 184, 0.12);
}

.bento-tile.active {
  border-color: var(--ll-primary, #1e6eb8);
  background: rgba(30, 110, 184, 0.03);
  box-shadow: 0 0 0 1px var(--ll-primary, #1e6eb8) inset, 0 6px 18px -4px rgba(30, 110, 184, 0.15);
}

.bento-tile.disabled {
  opacity: 0.5;
  cursor: not-allowed;
  filter: grayscale(0.4);
}

.recommended-badge {
  position: absolute;
  top: 0;
  right: 0;
  background: linear-gradient(135deg, #6366f1, #4f46e5);
  color: #ffffff;
  font-size: 10.5px;
  font-weight: 600;
  padding: 3px 10px;
  border-bottom-left-radius: 10px;
  display: flex;
  align-items: center;
  letter-spacing: 0.02em;
}

.tile-content {
  display: flex;
  align-items: center;
  gap: 14px;
  min-height: 44px;
}

.tile-icon-wrap {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  background: rgba(241, 245, 249, 0.85);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: transform 0.2s ease;
}

.bento-tile:hover .tile-icon-wrap {
  transform: scale(1.06);
}

.tile-meta {
  flex: 1;
  min-width: 0;
}

.tile-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tile-title {
  font-size: 14px;
  font-weight: 650;
  color: var(--mc-ink, #0f172a);
}

.tile-badge {
  font-size: 11px;
  font-weight: 500;
  padding: 1px 7px;
  border-radius: 999px;
  letter-spacing: 0.02em;
}

.badge-indigo { background: #e0e7ff; color: #4338ca; }
.badge-slate { background: #f1f5f9; color: #334155; }
.badge-blue { background: #dbeafe; color: #1e40af; }
.badge-sky { background: #e0f2fe; color: #0369a1; }
.badge-emerald { background: #d1fae5; color: #065f46; }
.badge-amber { background: #fef3c7; color: #92400e; }

.tile-desc {
  font-size: 12px;
  color: var(--mc-muted, #64748b);
  margin: 3px 0 0 0;
  line-height: 1.35;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tile-indicator {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.indicator-ring {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  border: 1.5px solid #cbd5e1;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s ease;
}

.bento-tile.active .indicator-ring {
  border-color: var(--ll-primary, #1e6eb8);
  background: var(--ll-primary, #1e6eb8);
}

.indicator-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #ffffff;
  opacity: 0;
  transform: scale(0.6);
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}

.bento-tile.active .indicator-dot {
  opacity: 1;
  transform: scale(1);
}

@media (max-width: 640px) {
  .bento-grid {
    grid-template-columns: 1fr;
  }
  .tile-content {
    min-height: 48px;
  }
}
</style>

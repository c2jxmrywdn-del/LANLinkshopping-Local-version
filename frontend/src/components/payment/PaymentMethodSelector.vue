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
          <div class="tile-icon-wrap" :class="item.media.kind === 'logo' ? 'is-logo' : 'is-icon'">
            <img
              v-if="item.media.kind === 'logo'"
              class="brand-logo"
              :class="item.media.logoClass"
              :src="item.media.src"
              :alt="item.media.alt"
              draggable="false"
            />
            <component :is="item.media.icon" v-else :size="22" />
          </div>

          <div class="tile-meta">
            <div class="tile-title-row">
              <span class="tile-title">{{ item.title }}</span>
              <span v-if="item.badge" class="tile-badge" :class="item.badgeClass">
                {{ item.badge }}
              </span>
            </div>
            <p class="tile-desc">{{ item.desc }}</p>
            <div v-if="item.networks" class="card-networks">
              <img
                v-for="n in item.networks"
                :key="n.alt"
                class="network-badge"
                :src="n.src"
                :alt="n.alt"
                draggable="false"
              />
            </div>
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
  Wallet,
  Building2,
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
  },
  /**
   * 兼容别名：订单详情/我的订单按 `order-amount` 传值。
   * 缺省会退化为 payableTotal=0，导致钱包与账期可用性判断失真（余额不足也显示可选）。
   */
  orderAmount: {
    type: [Number, String],
    default: null
  },
  /**
   * 需要隐藏的渠道 key 列表（如会员订阅不支持 Stripe 卡支付：排除 stripe/apple_pay/google_pay）。
   * 默认空 = 保持既有全渠道展示，不影响商品订单收银台。
   */
  excludeChannels: {
    type: Array,
    default: () => []
  },
  /**
   * 钱包渠道（Apple Pay / Google Pay）是否真实可用。
   *
   * <p>为什么需要这个开关：Stripe 要求站点域名完成 Apple Pay 域名验证（本地 IP 无法验证），
   * Google Pay 还需在后台单独开启。账户未就绪时，选钱包只会得到"不可用"，
   * 不如直接禁用并说明原因，避免用户选了才发现付不了。
   */
  walletReady: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['update:modelValue', 'change'])

/** 实际应付金额：优先 payableTotal，未传则回落到 orderAmount（订单详情/我的订单口径）。 */
const effectiveTotal = computed(() => {
  const primary = Number(props.payableTotal || 0)
  if (primary > 0) return primary
  return Number(props.orderAmount || 0)
})

const BRAND = '/payment-brands'

const paymentChannels = computed(() => {
  const channels = [
  {
    key: 'stripe',
    title: 'Stripe 国际卡',
    desc: 'Visa, Mastercard, Amex, 银联国际 (3DS2 强认证)',
    media: { kind: 'logo', src: `${BRAND}/stripe.svg`, alt: 'Stripe' },
    networks: [
      { src: `${BRAND}/visa.svg`, alt: 'Visa' },
      { src: `${BRAND}/mastercard.svg`, alt: 'Mastercard' },
      { src: `${BRAND}/americanexpress.svg`, alt: 'American Express' },
      { src: `${BRAND}/unionpay.svg`, alt: 'UnionPay 银联' }
    ],
    badge: '全球外卡',
    badgeClass: 'badge-accent',
    recommended: true
  },
  {
    key: 'apple_pay',
    title: 'Apple Pay (苹果支付)',
    desc: props.walletReady
      ? 'Safari / iOS 原生 Touch ID / Face ID 极速免密确认'
      : '需站点完成 Stripe Apple Pay 域名验证后启用（当前不可用）',
    media: {
      kind: 'logo',
      src: `${BRAND}/applepay.svg`,
      alt: 'Apple Pay',
      // 官方 glyph 纯黑 (#000000)：深色主题反白；未启用时叠加灰度弱化
      logoClass: props.walletReady ? 'invert-in-dark' : 'invert-in-dark logo-muted'
    },
    badge: props.walletReady ? 'iOS/Mac' : '未启用',
    badgeClass: 'badge-neutral',
    disabled: !props.walletReady
  },
  {
    key: 'google_pay',
    title: 'Google Pay (谷歌支付)',
    desc: props.walletReady
      ? 'Android / Chrome 浏览器绑定银行卡一键付款'
      : '需在 Stripe 后台开启 Google Pay 后启用（当前不可用）',
    media: {
      kind: 'logo',
      src: `${BRAND}/googlepay.svg`,
      alt: 'Google Pay',
      logoClass: props.walletReady ? '' : 'logo-muted'
    },
    badge: props.walletReady ? 'Android' : '未启用',
    badgeClass: 'badge-neutral',
    disabled: !props.walletReady
  },
  {
    key: 'alipay',
    title: '支付宝 (Alipay)',
    desc: '电脑网站跳转收银台或手机支付宝 App 扫码',
    media: { kind: 'logo', src: `${BRAND}/alipay.svg`, alt: '支付宝 Alipay' },
    badge: '即时到账',
    badgeClass: 'badge-neutral'
  },
  {
    key: 'wechat',
    title: '微信支付 (WeChat Pay)',
    desc: '微信原生 Native 动态扫码极速结算',
    media: { kind: 'logo', src: `${BRAND}/wechat.svg`, alt: '微信支付 WeChat Pay' },
    badge: '扫码付',
    badgeClass: 'badge-neutral'
  },
  {
    key: 'balance',
    title: '企业钱包 (Wallet)',
    desc: `可用余额 ¥${Number(props.walletBalance || 0).toFixed(2)}`,
    media: { kind: 'icon', icon: Wallet },
    disabled: Number(props.walletBalance || 0) < effectiveTotal.value && Number(props.walletBalance || 0) <= 0,
    badge: '余额扣减',
    badgeClass: 'badge-neutral'
  },
  {
    key: 'term',
    title: '企业账期 (Credit Term)',
    desc: props.creditStatus === 'ACTIVE'
      ? `可用授信额度 ¥${Number(props.creditAvailable || 0).toFixed(2)} · 7~15天免息周转`
      : '专属企业授信担保支付 · 需先开通账期',
    media: { kind: 'icon', icon: CalendarCheck2 },
    badge: '先货后款',
    badgeClass: 'badge-neutral',
    disabled: props.creditStatus !== 'ACTIVE' || Number(props.creditAvailable || 0) < effectiveTotal.value
  },
  {
    key: 'corporate',
    title: '对公转账 (Corporate Wire)',
    desc: '大宗工业品银行电汇核验 · 展示供货商认证对公账户',
    media: { kind: 'icon', icon: Building2 },
    badge: 'B2B大宗',
    badgeClass: 'badge-accent'
  }
  ]
  return channels.filter((item) => !props.excludeChannels.includes(item.key))
})

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
  background: var(--ll-card, #ffffff);
  border: 1.5px solid var(--ll-border, #e5e7eb);
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
  background: linear-gradient(135deg, var(--ll-primary, #1e6eb8), var(--ll-primary-active, #155b9e));
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
  background: var(--ll-surface-inset, rgba(15, 23, 42, 0.04));
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: transform 0.2s ease;
}

.bento-tile:hover .tile-icon-wrap {
  transform: scale(1.06);
}

/* 平台自有渠道（钱包/账期/对公）：lucide 线性图标统一中性色，不再各自彩色 */
.tile-icon-wrap.is-icon {
  color: var(--ll-muted, #64748b);
}

.brand-logo {
  height: 22px;
  width: auto;
  max-width: 44px;
  object-fit: contain;
  display: block;
}

/* 未启用的钱包渠道：官方 LOGO 灰度弱化 */
.brand-logo.logo-muted {
  filter: grayscale(1);
  opacity: 0.6;
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
  color: var(--ll-ink, #0f172a);
}

.tile-badge {
  font-size: 11px;
  font-weight: 500;
  padding: 1px 7px;
  border-radius: 999px;
  letter-spacing: 0.02em;
  white-space: nowrap;
}

/* 统一中性 pill：底 inset 面、次级字色（随主题翻转） */
.badge-neutral {
  background: var(--ll-surface-inset, rgba(15, 23, 42, 0.04));
  color: var(--ll-muted, #64748b);
}

/* 特殊状态（全球外卡 / B2B大宗）：品牌蓝强调 */
.badge-accent {
  background: rgba(30, 110, 184, 0.1);
  color: var(--ll-primary, #1e6eb8);
}

/* 深色主题：token 之外的官方品牌字形与强调色补暗色适配 */
[data-theme='dark'] .brand-logo.invert-in-dark {
  filter: invert(1) brightness(1.55);
}

[data-theme='dark'] .brand-logo.invert-in-dark.logo-muted {
  filter: invert(1) brightness(0.75);
  opacity: 0.55;
}

[data-theme='dark'] .badge-accent {
  color: var(--ll-primary-on-dark, #6fb1e4);
}

/* 深色卡面上垫白色小底，避免深色字标（Visa #1A1F71 等）隐入背景 */
[data-theme='dark'] .network-badge {
  background: rgba(255, 255, 255, 0.92);
  border-radius: 3px;
  padding: 1px 3px;
  box-sizing: content-box;
}

/* Stripe 格卡组织小徽章：默认灰度，悬停整格恢复官方彩色 */
.card-networks {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 5px;
}

.network-badge {
  height: 20px;
  width: auto;
  max-width: 40px;
  object-fit: contain;
  filter: grayscale(1);
  opacity: 0.55;
  transition: filter 0.2s ease, opacity 0.2s ease;
}

.bento-tile:hover .network-badge,
.bento-tile.active .network-badge {
  filter: none;
  opacity: 1;
}

.tile-desc {
  font-size: 12px;
  color: var(--ll-muted, #64748b);
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
  border: 1.5px solid var(--ll-border-strong, #d5dae2);
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

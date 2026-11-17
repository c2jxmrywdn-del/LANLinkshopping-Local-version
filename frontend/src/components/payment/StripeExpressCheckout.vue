<template>
  <!--
    Stripe Express Checkout（Apple Pay / Google Pay / Link 原生钱包按钮）。

    渲染时机：只有 Stripe 在**当前设备与浏览器**上确实支持钱包时，才会把按钮渲染进
    这个容器；不支持的设备容器保持为空（高度为 0），不会留下空白块或假按钮。

    安全边界：钱包认证在 Stripe 的跨域 iframe 内完成，卡号不经过本页面，
    平台只拿到 PaymentIntent 的最终状态（PCI-DSS SAQ-A）。
  -->
  <div class="stripe-express-wrapper" :class="{ 'is-hidden': !supported }">
    <div class="stripe-express-head">
      <Smartphone :size="16" aria-hidden="true" />
      <span class="stripe-express-title">{{ title }}</span>
      <span class="stripe-express-badge">
        <ShieldCheck :size="12" aria-hidden="true" />
        Stripe 安全托管
      </span>
    </div>

    <div
      ref="mountEl"
      class="stripe-express-mount"
      role="group"
      :aria-label="ariaLabel"
    ></div>

    <div v-if="!supported && !loadError" class="stripe-express-skeleton" aria-hidden="true"></div>

    <p v-if="loadError" class="stripe-express-msg stripe-express-msg--error" role="alert">{{ loadError }}</p>
    <p v-else-if="!supported" class="stripe-express-msg">{{ unsupportedHint }}</p>
    <p v-else class="stripe-express-msg stripe-express-msg--ok">
      钱包认证在 Stripe 加密环境内完成，平台不接触卡号与生物识别数据。
    </p>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ShieldCheck, Smartphone } from 'lucide-vue-next'
import { getStripeElements } from '../../utils/stripeLoader'

/**
 * Apple Pay / Google Pay 原生钱包按钮（基于 Stripe Express Checkout Element）。
 *
 * <p>为什么用 ExpressCheckoutElement 而不是自己画按钮：
 * 钱包按钮的品牌规范、可用性检测、生物识别弹窗全部由 Stripe 托管；
 * 自己画按钮无法触发 Apple Pay 的原生支付表（Safari 要求按钮由 Apple 校验的
 * 脚本渲染），也无法拿到 Google Pay 的 token。
 */
const props = defineProps({
  /** 后端 /payment/create 返回的 payInfo.publishableKey */
  publishableKey: { type: String, default: '' },
  /** PaymentIntent 的 clientSecret（决定金额与币种，必须来自后端） */
  clientSecret: { type: String, default: '' },
  /** 当前选中的方式：apple_pay / google_pay / stripe（用于文案与可用性过滤） */
  channel: { type: String, default: 'stripe' },
  /** 支付进行中：禁用按钮避免重复扣款 */
  disabled: { type: Boolean, default: false }
})

const emit = defineEmits(['ready', 'processing', 'succeeded', 'failed', 'unsupported'])

const mountEl = ref(null)
const supported = ref(false)
const loadError = ref('')

let stripeInstance = null
let expressElement = null
let initPromise = null

const isApple = computed(() => props.channel === 'apple_pay')

const title = computed(() => (isApple.value ? 'Apple Pay 极速支付' : 'Google Pay 极速支付'))
const ariaLabel = computed(() => `${title.value}：由 Stripe 安全托管`)
const unsupportedHint = computed(() =>
  isApple.value
    ? 'Apple Pay 当前不可用：需 Safari / iOS / macOS 且已添加卡片，且站点域名已在 Stripe 完成 Apple Pay 域名验证（本地 127.0.0.1 无法验证）。请改用银行卡支付。'
    : 'Google Pay 当前不可用：需 Chrome 且已登录 Google 账号，且已在 Stripe 后台开启 Google Pay。请改用银行卡支付。'
)

/** 钱包方式与 Stripe 的 paymentMethodType 对应关系 */
const WALLET_TYPES = {
  apple_pay: ['apple_pay'],
  google_pay: ['google_pay']
}

function destroyExpress() {
  const el = expressElement
  expressElement = null
  supported.value = false
  if (el) {
    try {
      el.destroy()
    } catch (e) {
      // 已卸载：忽略，避免销毁期抛错打断路由切换
    }
  }
}

/**
 * 挂载 Express Checkout 元素（幂等）。
 *
 * <p>必须等 clientSecret 就绪才挂：金额/币种来自 PaymentIntent，
 * 没有它 Stripe 无法在钱包弹窗里展示正确的应付金额。
 */
function ensureExpressElement() {
  if (expressElement) return Promise.resolve(expressElement)
  if (initPromise) return initPromise
  if (!props.clientSecret) return Promise.resolve(null)

  initPromise = (async () => {
    try {
      const shared = await getStripeElements(props.publishableKey)
      stripeInstance = shared.stripe
      const elements = shared.elements

      // paymentMethodTypes 限定为当前渠道：选 Apple Pay 时不会冒出 Google Pay 按钮
      const express = elements.create('expressCheckout', {
        paymentMethodTypes: WALLET_TYPES[props.channel] || undefined,
        buttonType: { applePay: 'buy', googlePay: 'buy' },
        buttonHeight: 44
      })
      expressElement = express

      express.on('ready', (event) => {
        // availablePaymentMethods 为 null/空 表示该设备没有任何可用钱包
        const available = event?.availablePaymentMethods
        const hasWallet = !!(available && (available.applePay || available.googlePay))
        supported.value = hasWallet
        if (!hasWallet) emit('unsupported', props.channel)
        else emit('ready', props.channel)
      })

      express.on('confirm', async () => {
        if (props.disabled) return
        emit('processing')
        try {
          // confirmPayment 会用 Express Checkout 采集到的钱包信息完成 PaymentIntent
          const { error, paymentIntent } = await stripeInstance.confirmPayment({
            elements,
            clientSecret: props.clientSecret,
            confirmParams: { return_url: window.location.href },
            redirect: 'if_required'
          })
          if (error) {
            emit('failed', error.message || '钱包支付失败，请更换支付方式')
            return
          }
          emit('succeeded', paymentIntent?.status || 'unknown')
        } catch (e) {
          emit('failed', e?.message || '钱包支付异常，请稍后重试')
        }
      })

      express.on('cancel', () => {
        // 用户在钱包弹窗里取消：不算失败，不弹错误，只恢复按钮可用
        emit('failed', '')
      })

      if (mountEl.value) express.mount(mountEl.value)
      return express
    } catch (e) {
      loadError.value = e?.message || 'Stripe 钱包组件加载失败'
      emit('failed', loadError.value)
      return null
    } finally {
      initPromise = null
    }
  })()
  return initPromise
}

onMounted(() => {
  ensureExpressElement()
})

onBeforeUnmount(() => {
  destroyExpress()
})

watch(
  () => [props.publishableKey, props.clientSecret, props.channel],
  () => {
    destroyExpress()
    loadError.value = ''
    ensureExpressElement()
  }
)
</script>

<style scoped>
.stripe-express-wrapper {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px 16px;
  border: 1px solid var(--ll-border, #e2e8f0);
  border-radius: 12px;
  background: var(--ll-card, #fff);
}
.stripe-express-wrapper.is-hidden {
  padding: 10px 16px;
}
.stripe-express-head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ll-ink, #0f172a);
}
.stripe-express-title {
  flex: 1;
}
.stripe-express-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  font-weight: 500;
  color: #0284c7;
}
.stripe-express-mount {
  min-height: 0;
}
.stripe-express-skeleton {
  height: 44px;
  border-radius: 8px;
  background: linear-gradient(90deg, var(--ll-surface-inset, rgba(15, 23, 42, 0.04)) 25%, var(--ll-border, #e2e8f0) 37%, var(--ll-surface-inset, rgba(15, 23, 42, 0.04)) 63%);
  background-size: 400% 100%;
  animation: ll-express-shimmer 1.4s ease infinite;
}
@keyframes ll-express-shimmer {
  0% { background-position: 100% 50%; }
  100% { background-position: 0 50%; }
}
.stripe-express-msg {
  margin: 0;
  font-size: 11px;
  line-height: 1.5;
  color: var(--ll-text-tertiary, #94a3b8);
}
.stripe-express-msg--ok {
  color: var(--ll-muted, #64748b);
}
.stripe-express-msg--error {
  color: #e4393c;
}
</style>

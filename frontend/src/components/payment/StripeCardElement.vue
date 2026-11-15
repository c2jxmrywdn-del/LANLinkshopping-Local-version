<template>
  <div class="stripe-card-wrapper">
    <div class="stripe-card-head">
      <CreditCard :size="16" aria-hidden="true" />
      <span class="stripe-card-title">银行卡信息</span>
      <span class="stripe-card-secure">
        <Lock :size="12" aria-hidden="true" />
        Stripe 安全托管
      </span>
    </div>

    <!--
      Stripe Elements 挂载点：卡号/有效期/安全码由 Stripe 的跨域加密 iframe 渲染，
      平台页面不渲染任何原生卡输入框，也不读取/回传原始卡数据（PCI-DSS SAQ-A）。
    -->
    <div
      ref="mountEl"
      class="stripe-card-mount"
      :class="{ 'is-ready': ready, 'is-invalid': !!changeError, 'is-disabled': disabled }"
      role="group"
      aria-label="银行卡号、有效期与安全码（由 Stripe 安全采集）"
    ></div>

    <div v-if="!ready && !loadError" class="stripe-card-skeleton" aria-hidden="true"></div>

    <p v-if="loadError" class="stripe-card-msg stripe-card-msg--error" role="alert">{{ loadError }}</p>
    <p v-else-if="changeError" class="stripe-card-msg stripe-card-msg--error" role="alert">{{ changeError }}</p>
    <p v-else-if="!ready" class="stripe-card-msg">{{ pendingHint }}</p>
    <p v-else class="stripe-card-msg stripe-card-msg--ok">
      <ShieldCheck :size="13" aria-hidden="true" />
      卡号、有效期、安全码仅在 Stripe 加密 iframe 内采集，平台服务器不接触、不存储原始卡数据。
    </p>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { CreditCard, Lock, ShieldCheck } from 'lucide-vue-next'
import { getStripeElements } from '../../utils/stripeLoader'

const props = defineProps({
  /** 后端 /payment/create 返回的 payInfo.publishableKey（权威来源，可后置传入） */
  publishableKey: { type: String, default: '' },
  /** 支付提交中：禁用卡控件，避免重复输入/重复确认 */
  disabled: { type: Boolean, default: false }
})

const emit = defineEmits(['ready', 'change', 'error'])

/** 卡品牌/是否填写完整（供父组件做按钮可用性判断） */
const complete = ref(false)
const brand = ref('')

const mountEl = ref(null)
const ready = ref(false)
const loadError = ref('')
const changeError = ref('')
const cardElement = shallowRef(null)

let stripeInstance = null
let initPromise = null

/** 卡控件视觉：与站点表单 token 对齐（Stripe 只接受其支持的样式子集） */
const CARD_STYLE = {
  base: {
    fontSize: '15px',
    color: '#0f172a',
    fontFamily: 'system-ui, -apple-system, "Segoe UI", Roboto, "PingFang SC", "Microsoft YaHei", sans-serif',
    '::placeholder': { color: '#94a3b8' },
    iconColor: '#1e6eb8'
  },
  invalid: { color: '#e4393c', iconColor: '#e4393c' }
}

const pendingHint = computed(() => {
  if (props.publishableKey) return '正在加载安全卡输入框…'
  return '点击「立即支付」后在此录入银行卡信息（由 Stripe 安全采集）。'
})

/** 当前是否已具备可用的 publishable key（prop 优先，其次构建期 env） */
function hasPublishableKey() {
  if (props.publishableKey && props.publishableKey.trim()) return true
  try {
    return !!String(import.meta.env?.VITE_STRIPE_PUBLISHABLE_KEY || '').trim()
  } catch (e) {
    return false
  }
}

function destroyCardElement() {
  const el = cardElement.value
  cardElement.value = null
  ready.value = false
  complete.value = false
  brand.value = ''
  changeError.value = ''
  if (el) {
    try {
      el.unmount()
    } catch (e) {
      // Stripe 已卸载或 iframe 已移除：无需处理，避免销毁期抛错打断路由切换
    }
  }
}

/**
 * 惰性创建 Elements + CardElement（幂等）：
 * - 首次调用加载 Stripe.js（单例）、创建 elements、create('card') 并 mount 到容器；
 * - 并发调用复用同一个 promise，避免重复 create/mount；
 * - 失败时记录可展示错误并允许下次重试。
 * @returns {Promise<object>} Stripe CardElement 实例
 */
function ensureCardElement() {
  if (cardElement.value) return Promise.resolve(cardElement.value)
  if (initPromise) return initPromise
  initPromise = (async () => {
    // 1) Stripe 实例 + Elements 分组均按 key 单例（弹窗反复开关不重建分组）
    const shared = await getStripeElements(props.publishableKey)
    stripeInstance = shared.stripe
    if (!stripeInstance) throw new Error('Stripe.js 加载失败（返回空实例）')
    // 2) Card Element（postal code 由收货地址提供，隐藏以少一列）
    const el = shared.elements.create('card', {
      hidePostalCode: true,
      style: CARD_STYLE,
      disabled: !!props.disabled
    })
    await nextTick()
    if (!mountEl.value) throw new Error('Stripe 卡输入容器尚未挂载')
    el.mount(mountEl.value)
    // 3) 事件桥接：change 里带 error/complete/brand，父组件据此提示与置灰按钮
    el.on('ready', () => {
      ready.value = true
      emit('ready', el)
    })
    el.on('change', (event) => {
      complete.value = !!event.complete
      brand.value = event.brand || ''
      changeError.value = event.error ? event.error.message : ''
      emit('change', { complete: complete.value, brand: brand.value, error: event.error || null })
      if (event.error) emit('error', event.error)
    })
    cardElement.value = el
    ready.value = true
    loadError.value = ''
    emit('ready', el)
    return el
  })().catch((error) => {
    initPromise = null
    loadError.value = error?.message || 'Stripe 卡控件加载失败，请刷新重试'
    emit('error', error)
    throw error
  })
  return initPromise
}

/**
 * 卡数据 Token 化（平台仍可用它校验/绑定支付方式）：
 * 注意：真正的 PaymentIntent 确认由父组件用 stripe.confirmCardPayment 完成，
 * 卡号始终停留在 Stripe iframe 内，这里只拿到 paymentMethod id。
 * @param {object} [billingDetails] 账单姓名/电话（可选）
 */
async function createPaymentMethod(billingDetails = {}) {
  const el = await ensureCardElement()
  if (!stripeInstance) throw new Error('Stripe 尚未初始化')
  return stripeInstance.createPaymentMethod({
    type: 'card',
    card: el,
    billing_details: billingDetails
  })
}

/** 供父组件拿 Stripe 实例做 confirmCardPayment（与卡控件共用同一 loadStripe 单例） */
function getStripe() {
  return stripeInstance
}

function isComplete() {
  return complete.value
}

onMounted(() => {
  // 只有 key 已就绪时才在挂载期初始化；key 通常在下单后由父组件回填（watch 接管），
  // 提前初始化会因缺少 key 误报配置错误。
  if (hasPublishableKey()) ensureCardElement().catch(() => {})
})

onBeforeUnmount(() => {
  destroyCardElement()
  stripeInstance = null
  initPromise = null
})

watch(
  () => props.publishableKey,
  (val, old) => {
    if (!val || val === old) return
    // key 后置到达或切换（测试/沙箱）：重建 elements，确保用新 key 渲染
    const hadInit = !!initPromise || !!cardElement.value
    destroyCardElement()
    if (hadInit || hasPublishableKey()) ensureCardElement().catch(() => {})
  }
)

watch(
  () => props.disabled,
  (val) => {
    if (cardElement.value) {
      try {
        cardElement.value.update({ disabled: !!val })
      } catch (e) {
        // 更新失败不阻断支付流程
      }
    }
  }
)

defineExpose({ cardElement, ensureCardElement, createPaymentMethod, getStripe, isComplete, brand, complete })
</script>

<style scoped>
.stripe-card-wrapper {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 100%;
}

.stripe-card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--mc-ink, #1e293b);
}

.stripe-card-title {
  font-size: 13px;
  font-weight: 600;
}

.stripe-card-secure {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11.5px;
  font-weight: 600;
  color: #1e6eb8;
  background: rgba(30, 110, 184, 0.08);
  border: 1px solid rgba(30, 110, 184, 0.18);
  border-radius: 999px;
  padding: 2px 9px;
}

/* Stripe iframe 容器：高度由 Card Element 决定（单行卡号 + 一行有效期/CVC = ~44px），
   仅提供边框/圆角/内边距与聚焦态，内部样式由 Stripe 的 style 控制 */
.stripe-card-mount {
  min-height: 44px;
  padding: 11px 12px;
  border: 1.5px solid var(--ll-border, #e5e7eb);
  border-radius: 10px;
  background: #fff;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.stripe-card-mount.is-ready {
  border-color: var(--ll-skyblue-border, #5b8fb5);
}

.stripe-card-mount.is-ready:focus-within {
  border-color: var(--ll-primary, #1e6eb8);
  box-shadow: 0 0 0 3px rgba(30, 110, 184, 0.12);
}

.stripe-card-mount.is-invalid {
  border-color: #e4393c;
}

.stripe-card-mount.is-disabled {
  opacity: 0.6;
  pointer-events: none;
}

.stripe-card-skeleton {
  height: 8px;
  border-radius: 6px;
  background: linear-gradient(90deg, #eef2f7 25%, #f8fafc 37%, #eef2f7 63%);
  background-size: 400% 100%;
  animation: stripeCardShimmer 1.3s ease-in-out infinite;
}

@keyframes stripeCardShimmer {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}

.stripe-card-msg {
  display: flex;
  align-items: flex-start;
  gap: 5px;
  margin: 0;
  font-size: 12px;
  line-height: 1.55;
  color: var(--ll-muted, #64748b);
}

.stripe-card-msg--ok {
  color: #0f766e;
}

.stripe-card-msg--error {
  color: #e4393c;
  font-weight: 500;
}

@media (prefers-reduced-motion: reduce) {
  .stripe-card-skeleton {
    animation: none;
  }
}
</style>

<template>
  <div>
    <div class="eyebrow">ORDER CENTER</div><h2>我的订单</h2><p class="sub">查看订单状态、支付进度与履约信息。</p>
    <!-- 商户身份互通提示：本页是「我买入的」采购订单，店铺销售订单在商户中心 -->
    <a-alert v-if="isMerchant" type="info" show-icon class="merchant-bridge"
             message="本页展示您作为买家的采购订单"
             description="您店铺的销售订单（买家向您下单）在「商户中心 → 订单管理」中处理：发货、跟踪履约与退款。">
      <template #action>
        <a-button size="small" type="primary" @click="$router.push('/merchant/center/orders')">查看店铺销售订单</a-button>
      </template>
    </a-alert>
    <div class="order-tabs">
      <FluidAnimatedTabs v-model="filter" :items="orderTabs" variant="pill" />
    </div>
    <a-table :data-source="filteredOrders" :columns="cols" row-key="orderNo" :loading="loading" :scroll="{ x: 1020 }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'total'">
          ¥{{ record.totalAmount }}
          <!-- 优惠构成回显：促销/会员取优快照 + 券抵扣单列（promoTitle/promoSource 由下单落库） -->
          <div v-if="Number(record.promoDiscount) > 0" class="disc-line">
            {{ record.promoSource === 'member' ? '会员折扣' : (record.promoTitle || '促销优惠') }}
            <em>-¥{{ record.promoDiscount }}</em>
          </div>
          <div v-if="Number(record.couponDiscount) > 0" class="disc-line">券抵扣 <em>-¥{{ record.couponDiscount }}</em></div>
        </template>
        <template v-else-if="column.key === 'payType'">{{ payTypeText[record.payType] || record.payType || '—' }}</template>
        <template v-else-if="column.key === 'pay'">
          <a-tag :color="record.payStatus === 1 ? 'green' : (record.payStatus === 2 ? 'purple' : 'orange')">
            {{ record.payStatus === 1 ? '已支付' : (record.payStatus === 2 ? '已退款' : '未支付') }}
          </a-tag>
          <div v-if="record.payChannel" class="ch">{{ channelText[record.payChannel] || record.payChannel }}</div>
        </template>
        <template v-else-if="column.key === 'status'">
          <a-tag>{{ statusMap[record.orderStatus] || record.orderStatus }}</a-tag>
          <div v-if="record.refundStatus === 'applied'" class="rf rf-wait">退款申请中 · 待商家初审</div>
          <div v-else-if="record.refundStatus === 'rejected'" class="rf rf-rej">退款申请被驳回：{{ record.refundRejectReason || '不符合退款条件' }}</div>
          <div v-else-if="record.refundStatus === 'partial'" class="rf">已部分退款 ¥{{ record.refundAmount }}，剩余金额可再次申请</div>
          <div v-else-if="record.refundStatus === 'success'" class="rf">已全额退款 ¥{{ record.refundAmount }}</div>
        </template>
        <template v-else-if="column.key === 'op'">
          <a-space wrap>
            <a @click="$router.push('/orders/' + record.orderNo)">详情</a>
            <a v-if="record.payStatus === 0 && record.orderStatus === 0 && record.payType !== 'term'" @click="openPay(record)">支付</a>
            <a v-if="Number(record.payStatus) === 1 && record.orderStatus === 1" @click="confirmReceipt(record)">确认收货</a>
            <a v-if="canReview(record)" @click="openOrderReview(record)">评价</a>
            <a v-if="canRefund(record)" @click="openRefund(record)">申请退款</a>
            <a v-if="record.orderStatus === 0 && (record.payStatus === 0 || record.payType === 'term')" @click="cancel(record)" style="color:#999">取消</a>
          </a-space>
        </template>
      </template>
    </a-table>
    <a-empty v-if="!loading && orders.length === 0" description="还没有订单">
      <a-space direction="vertical" :size="8">
        <a-button type="primary" @click="$router.push('/mall')">去下单</a-button>
        <a-button type="link" @click="$router.back()">返回</a-button>
      </a-space>
    </a-empty>

    <!-- 支付渠道选择弹窗 (21st.dev Bento Grid 多渠道收银台) -->
    <a-modal
      v-model:open="payOpen"
      :title="`收银结算 · 订单号 ${current?.orderNo}`"
      width="680px"
      :footer="null"
      destroy-on-close
    >
      <div class="pay-modal-body">
        <div class="pay-amount-banner">
          <div class="banner-left">
            <span class="banner-label">待支付订单实付金额</span>
            <div class="banner-amt">¥<b>{{ current?.totalAmount }}</b></div>
          </div>
          <div class="banner-right">
            <span class="banner-badge">
              <ShieldCheck :size="13" class="inline mr-1" /> 金融级 SSL 传输防护
            </span>
          </div>
        </div>

        <!-- 21st.dev 风格支付方式多通道选择器 -->
        <div class="selector-container">
          <div class="selector-title">选择支付方式（支持 8 种主流与国际结算）</div>
          <PaymentMethodSelector
            v-model="selectedPayMethod"
            :wallet-balance="walletBalance"
            :order-amount="Number(current?.totalAmount || 0)"
            :wallet-ready="walletReady"
          />
        </div>

        <!--
          钱包渠道（Apple Pay / Google Pay）：渲染 Stripe Express Checkout 原生按钮。
          按钮由 Stripe 按设备能力决定是否显示，不支持的设备会给出"请改用银行卡"的说明，
          不再像以前那样把钱包选项伪装成卡表单。
        -->
        <div
          v-if="['apple_pay','google_pay'].includes(selectedPayMethod)"
          class="extra-panel"
        >
          <StripeExpressCheckout
            v-if="stripeClientSecret"
            :publishable-key="stripePublishableKey"
            :client-secret="stripeClientSecret"
            :channel="selectedPayMethod"
            :disabled="paying"
            @processing="paying = true"
            @succeeded="onWalletSucceeded"
            @failed="onWalletFailed"
          />
          <p v-else class="wallet-prepare-hint">点击下方「立即支付」后将唤起 {{ payMethodDisplayName(selectedPayMethod) }} 钱包。</p>
        </div>

        <!-- Stripe 卡槽（纯银行卡：Visa / Mastercard / Amex / 银联国际，含 3DS2） -->
        <div v-else-if="selectedPayMethod === 'stripe'" class="extra-panel">
          <StripeCardElement ref="stripeCardRef" :publishable-key="stripePublishableKey" :disabled="paying" />
        </div>

        <!-- 对公收款账号提示 -->
        <div v-else-if="selectedPayMethod === 'corporate'" class="extra-panel">
          <!-- 账户信息只能来自后端配置；无配置时组件会明确提示，不展示任何示例账号 -->
          <MerchantCorporateBankCard :bank-info="corporateBank" />
        </div>

        <!-- 支付宝/微信说明 -->
        <div v-else-if="selectedPayMethod === 'alipay' || selectedPayMethod === 'wechat'" class="gateway-preview-panel">
          <QrCode :size="20" class="text-sky-500 mr-2 flex-shrink-0" />
          <div class="gateway-tip">
            <strong>{{ selectedPayMethod === 'alipay' ? '支付宝' : '微信支付' }}安全网关直连</strong>
            <p>点击确认支付后将唤起{{ selectedPayMethod === 'alipay' ? '支付宝收银台' : '微信扫码二维码' }}完成交易核验。</p>
          </div>
        </div>

        <!-- 钱包渠道链路：选中 apple_pay/google_pay 时仅预下单拿 clientSecret，
             Express Checkout 原生按钮在上方 extra-panel 挂载（Stripe 托管 Touch ID / Face ID），
             真正扣款由用户点钱包按钮触发，结果回调 onWalletSucceeded / onWalletFailed。 -->

        <!-- 确认支付大按钮 -->
        <div class="pay-submit-bar">
          <button
            type="button"
            class="pay-confirm-btn"
            :disabled="paying || (selectedPayMethod === 'balance' && walletInsufficient)"
            @click="payWith(selectedPayMethod)"
          >
            <span v-if="paying">正在建立安全加密支付通道...</span>
            <span v-else-if="selectedPayMethod === 'balance' && walletInsufficient">钱包余额不足，请更换支付方式</span>
            <span v-else>立即使用 {{ payMethodDisplayName(selectedPayMethod) }} 支付 ¥{{ current?.totalAmount }}</span>
          </button>
        </div>
      </div>
    </a-modal>

    <!-- 申请退款弹窗 -->
    <a-modal v-model:open="refundOpen" :title="`${t('申请退款')} · ${refundTarget?.orderNo}`" :ok-text="t('提交申请')" :cancel-text="t('取消')"
             :confirm-loading="refunding" @ok="doRefund">
      <a-form layout="vertical">
        <a-form-item :label="t('订单金额')"><b>¥{{ refundTarget?.totalAmount }}</b>
          <span v-if="refundTarget?.refundAmount" class="refund-refunded-hint">（{{ t('已退金额') }} ¥{{ refundTarget.refundAmount }}）</span>
        </a-form-item>
        <a-form-item :label="t('退款原因（必填，将提交平台审核）')" required>
          <a-textarea v-model:value="refundReason" :rows="3" maxlength="200" show-count
                      :placeholder="t('例如：商品缺货 / 与描述不符 / 沟通后协商退款')" />
        </a-form-item>
        <a-form-item :label="t('退款凭证（至少上传 1 项）')" required>
          <RefundEvidenceUpload ref="evidenceUploadRef" v-model:evidences="refundEvidences" @status="onEvidenceStatus" />
          <div class="refund-evidence-hints">
            <p>{{ t('若已与商家沟通协商，请上传聊天记录截图或协商说明文件') }}</p>
            <p>{{ t('图片 JPG/PNG/WEBP/GIF 单张 ≤8MB；文件 PDF ≤20MB、TXT/CSV ≤2MB') }}</p>
            <p>{{ t('Word/压缩包等将以 .bin 附件提交，审核端可下载查看') }}</p>
            <p>{{ t('视频 MP4/MOV 单个 ≤20MB') }}</p>
          </div>
        </a-form-item>
        <a-alert type="info" show-icon :message="t('申请后由平台审核处理')"
                 :description="t('申请按可退余额全额提交，平台审核通过后资金原路退回；若被驳回会展示驳回原因，你可再次申请。')" />
      </a-form>
    </a-modal>

    <!-- 订单评价弹窗：列表页按单快捷入口（行级明细评价在订单详情页，同一 t_order_review 链路） -->
    <a-modal v-model:open="reviewOpen" :title="reviewTitle" :footer="null" width="520px">
      <div v-if="!reviewLoaded" class="rev-loading"><a-spin /></div>
      <template v-else>
        <div v-for="it in reviewItems" :key="it.itemId" class="rev-item">
          <div class="rev-item-head">
            <span class="rev-item-name">{{ it.prodName }}</span>
            <a-tag v-if="reviewedIds.has(it.itemId)" color="green">{{ locale === 'en-US' ? 'Reviewed' : '已评价' }}</a-tag>
          </div>
          <a-button v-if="!reviewedIds.has(it.itemId)" size="small" type="primary" class="rev-go"
                    @click="goItemReview(it)">
            <Star :size="13" class="inline-block mr-1 align-middle" /> {{ locale === 'en-US' ? 'Review now' : '去评价' }}
          </a-button>
        </div>
        <p v-if="!reviewItems.length" class="rev-empty">{{ locale === 'en-US' ? 'No items to review' : '该订单没有可评价的商品' }}</p>
        <p class="rev-hint">{{ locale === 'en-US'
          ? 'Review each item with photos and anonymity on the order detail page.'
          : '按商品行逐条评价；星级、晒图与匿名开关在订单详情页的评价弹窗中提供。' }}</p>
      </template>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, h, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import {
  Wallet, FlaskConical, Smartphone, Globe, ShieldCheck,
  QrCode, CreditCard, Building2, CheckCircle2, Star
} from 'lucide-vue-next'
import { orderApi, paymentApi, walletApi, trafficApi, reviewApi } from '../api'
import { t, locale } from '../i18n'
import { serializeEvidences } from '../utils/refundEvidence'
import { trackEvent } from '../utils/analytics'
import { submitPayForm } from '../utils/payForm'
import { buildPayQrPayload, renderQrToDataUrl } from '../utils/qrCode'
// 对公收款账户：只来自后端配置，未配置时组件提示"平台暂未配置"
const corporateBank = ref({})
import RefundEvidenceUpload from '../components/refund/RefundEvidenceUpload.vue'
import { useUserStore } from '../store/user'
import PaymentMethodSelector from '../components/payment/PaymentMethodSelector.vue'
import MerchantCorporateBankCard from '../components/payment/MerchantCorporateBankCard.vue'
import StripeCardElement from '../components/payment/StripeCardElement.vue'
import StripeExpressCheckout from '../components/payment/StripeExpressCheckout.vue'
import { confirmStripeCardPayment, describeStripePaymentResult } from '../components/payment/stripeConfirm'
import { waitForOrderPaid } from '../utils/waitForOrderPaid'
import FluidAnimatedTabs from '../components/ui/FluidAnimatedTabs.vue'

const user = useUserStore()
const $router = useRouter()
// 仅真实商户显示（admin 的 admin:all 是通配权限，会误命中，需排除）
const isMerchant = computed(() => !user.isAdmin &&
  (user.hasPerm('merchant:manage') || user.hasPerm('product:publish')))
const orders = ref([])
const loading = ref(false)
const filter = ref('all')
const orderTabs = [
  { value: 'all', label: '全部' },
  { value: 'unpaid', label: '待支付' },
  { value: 'paid', label: '已支付' },
  { value: 'done', label: '已完成' },
  { value: 'closed', label: '已关闭' }
]
const filteredOrders = computed(() => orders.value.filter(o => {
  if (filter.value === 'all') return true
  if (filter.value === 'unpaid') return Number(o.payStatus) !== 1 && Number(o.payStatus) !== 2 && Number(o.orderStatus) !== 3 && Number(o.orderStatus) !== 5
  if (filter.value === 'paid') return Number(o.payStatus) === 1 && Number(o.orderStatus) !== 2 && Number(o.orderStatus) !== 3 && Number(o.orderStatus) !== 5
  if (filter.value === 'done') return Number(o.orderStatus) === 2
  return Number(o.orderStatus) === 3 || Number(o.orderStatus) === 5 || Number(o.payStatus) === 2
}))
const statusMap = { 0: '待发货', 1: '已发货', 2: '已完成', 3: '已取消', 4: '部分发货', 5: '已关闭' }
const channelText = {
  wallet: '钱包',
  mock: '模拟',
  wechat: '微信',
  alipay: '支付宝',
  stripe: 'Stripe 国际卡',
  apple_pay: 'Apple Pay',
  google_pay: 'Google Pay',
  corporate: '企业对公'
}
const payTypeText = {
  corporate: '对公转账',
  balance: '企业钱包',
  term: '企业账期',
  alipay: '支付宝',
  wechat: '微信支付',
  stripe: 'Stripe 国际卡',
  apple_pay: 'Apple Pay',
  google_pay: 'Google Pay'
}
const cols = [
  { title: '订单号', dataIndex: 'orderNo', key: 'orderNo' },
  { title: '金额', key: 'total', width: 120 },
  { title: '支付方式', dataIndex: 'payType', key: 'payType', width: 120 },
  { title: '支付状态', key: 'pay', width: 110 },
  { title: '订单状态', key: 'status', width: 130 },
  { title: '操作', key: 'op', width: 220 }
]

// ===== 支付弹窗 =====
const payOpen = ref(false)
const paying = ref(false)
const current = ref(null)
const selectedPayMethod = ref('stripe')
// Stripe 真实卡支付：publishableKey 由 /payment/create 返回，卡控件在收银台弹窗内挂载
const stripeCardRef = ref(null)
const stripePublishableKey = ref('')
// 钱包渠道（Apple Pay / Google Pay）需要 clientSecret 才能挂 Express Checkout 按钮
const stripeClientSecret = ref('')
// 钱包是否真的可用（由后端探测 Stripe 账户激活状态 + Apple Pay 域名验证）
const walletReady = ref(false)
const walletBalance = ref(0)
const walletInsufficient = computed(() =>
  !current.value || walletBalance.value < Number(current.value.totalAmount || 0))

function payMethodDisplayName(k) {
  const map = {
    stripe: 'Stripe 国际卡',
    apple_pay: 'Apple Pay',
    google_pay: 'Google Pay',
    alipay: '支付宝',
    wechat: '微信支付',
    balance: '企业钱包',
    corporate: '企业对公转账',
    mock: '模拟网关'
  }
  return map[k] || '推荐渠道'
}

async function load() {
  loading.value = true
  try { orders.value = await orderApi.my() }
  finally { loading.value = false }
}

async function openPay(record) {
  current.value = record
  selectedPayMethod.value = record.payType && record.payType !== 'term' ? record.payType : 'stripe'
  // 每次开收银台都清空上一次的 clientSecret：PaymentIntent 与订单一一对应，
  // 复用旧 secret 会把钱付到上一笔订单上
  stripeClientSecret.value = ''
  payOpen.value = true
  try {
    const w = await walletApi.my()
    walletBalance.value = Number(w?.balance || 0)
  } catch (e) { walletBalance.value = 0 }
  // 钱包能力探测：失败按"不可用"处理，只展示银行卡，不影响正常支付
  try {
    const cap = await paymentApi.walletCapability()
    walletReady.value = !!cap?.walletReady
    // 若当前选中的就是不可用的钱包渠道，回落到银行卡
    if (!walletReady.value && ['apple_pay', 'google_pay'].includes(selectedPayMethod.value)) {
      selectedPayMethod.value = 'stripe'
    }
  } catch (e) { walletReady.value = false }
}

async function payWith(channel) {
  if (channel === 'balance' && walletInsufficient.value) {
    message.warning('钱包余额不足，请先充值')
    return
  }
  paying.value = true
  try {
    if (channel === 'balance' || channel === 'wallet') {
      const vo = await paymentApi.create(current.value.orderNo, 'wallet')
      if (vo?.payInfo?.paid) {
        message.success(`支付成功，钱包余额 ¥${Number(vo.payInfo.balance).toFixed(2)}`)
        trackPay()
      }
    } else if (['apple_pay', 'google_pay'].includes(channel)) {
      /*
       * 钱包渠道：这一步只做「预下单 + 挂按钮」。
       *
       * Express Checkout 的按钮必须在拿到 clientSecret 之后才能渲染（金额来自 PaymentIntent），
       * 所以第一次点「立即支付」只是准备阶段；真正的扣款由用户点钱包按钮触发，
       * 结果回调走 onWalletSucceeded / onWalletFailed。
       */
      const vo = await paymentApi.create(current.value.orderNo, 'stripe')
      if (vo?.mock) {
        await paymentApi.mockConfirm(current.value.orderNo)
        message.success('Stripe 支付成功（模拟环境）')
      } else {
        stripePublishableKey.value = String(vo?.payInfo?.publishableKey || '')
        stripeClientSecret.value = String(vo?.payInfo?.clientSecret || '')
        if (!stripeClientSecret.value) {
          throw new Error('Stripe 预下单未返回 clientSecret，无法唤起钱包')
        }
        message.info(`已就绪，请点击上方 ${payMethodDisplayName(channel)} 按钮完成支付`)
      }
      trackPay()
    } else if (channel === 'stripe') {
      const vo = await paymentApi.create(current.value.orderNo, 'stripe')
      if (vo?.mock) {
        // 开发/演示模式：仅当后端显式返回 mock 标记时才允许模拟确认
        await paymentApi.mockConfirm(current.value.orderNo)
        message.success('Stripe 支付成功（模拟环境）')
      } else {
        // 真实链路：clientSecret + Stripe Elements 卡控件 → confirmCardPayment（含 3DS）
        stripePublishableKey.value = String(vo?.payInfo?.publishableKey || '')
        await nextTick()
        const { status } = await confirmStripeCardPayment(stripeCardRef.value, vo?.payInfo, {
          name: current.value?.receiver,
          phone: current.value?.phone
        })
        const result = describeStripePaymentResult(status)
        if (result.ok) {
          /*
           * Stripe 扣款成功不等于平台订单已结算：唯一事实源是 webhook。
           * 原来这里直接 message.success，webhook 未到时用户看到"支付成功"，
           * 回到列表却是"未支付"。改为先等服务端确认，再给出准确提示。
           */
          const confirmed = await waitForOrderPaid(current.value.orderNo, (no) => paymentApi.query(no))
          if (confirmed) {
            message.success(result.message)
          } else {
            message.warning('银行已扣款，但平台尚未确认到账（状态同步中）。请稍后刷新，勿重复支付。')
          }
        } else if (result.processing) {
          message.info(result.message)
        } else {
          throw new Error(result.message)
        }
      }
      trackPay()
    } else if (channel === 'alipay') {
      const vo = await paymentApi.create(current.value.orderNo, 'alipay')
      if (vo?.mock) {
        await paymentApi.mockConfirm(current.value.orderNo)
        message.success('支付宝支付成功（模拟环境）')
      } else if (vo?.payInfo?.form) {
        submitPayForm(vo.payInfo.form)
        return
      }
      trackPay()
    } else if (channel === 'wechat') {
      const vo = await paymentApi.create(current.value.orderNo, 'wechat')
      if (vo?.mock) {
        await paymentApi.mockConfirm(current.value.orderNo)
        message.success('微信支付成功（模拟环境）')
      } else if (vo?.payInfo?.codeUrl) {
        // 后端契约：二维码串在 payInfo.codeUrl
        const src = await renderQrToDataUrl(
          buildPayQrPayload({ codeUrl: vo.payInfo.codeUrl, orderNo: current.value.orderNo })
        )
        Modal.info({
          title: '请使用微信扫码支付',
          width: 320,
          content: h('div', { style: 'text-align:center' }, [
            h('img', { src, alt: '微信支付二维码', style: 'width:220px;height:220px' }),
            h('p', { style: 'margin-top:8px;color:#64748b;font-size:12px' },
              `订单号 ${current.value.orderNo}`)
          ])
        })
      } else {
        message.warning('微信支付通道未配置，请选择其他支付方式')
      }
      trackPay()
    } else if (channel === 'corporate') {
      message.info(`对公电汇订单已就绪，请向平台对公账户转账并备注订单号 ${current.value.orderNo}`)
    } else {
      const vo = await paymentApi.create(current.value.orderNo, 'mock')
      if (vo?.mock) await paymentApi.mockConfirm(current.value.orderNo)
      message.success('支付成功')
      trackPay()
    }
    payOpen.value = false
    user.fetchMe()
    await load()
  } catch (e) {
    // 仅本页 Stripe 链路/未完成状态抛出的错误需要再提示；后端业务错误已由 request.js 拦截器提示
    if (e?.__llStripe || e?.message?.startsWith('支付未完成')) message.error(e.message)
  } finally {
    paying.value = false
  }
}

/** 支付成功埋点：订单列表无商户归属信息，PAY 作为平台级事件上报（后端以 0 兜底），失败静默 */
function trackPay() {
  trafficApi.trackPage({ eventType: 'PAY', sourceType: 'order_list' }).catch(() => {})
}

/**
 * 钱包（Apple Pay / Google Pay）支付成功回调。
 *
 * <p>与卡支付同一口径：Stripe 侧 succeeded 只是渠道确认，平台订单是否已结算
 * 仍以 webhook 为准，因此先等服务端 payStatus=1 再给成功提示。
 */
async function onWalletSucceeded(status) {
  try {
    const result = describeStripePaymentResult(status)
    if (result.ok) {
      const confirmed = await waitForOrderPaid(current.value?.orderNo, (no) => paymentApi.query(no))
      if (confirmed) {
        message.success(result.message)
        trackPay()
        payOpen.value = false
        load()
      } else {
        message.warning('钱包已扣款，但平台尚未确认到账（状态同步中）。请稍后刷新，勿重复支付。')
      }
    } else if (result.processing) {
      message.info(result.message)
    } else {
      message.error(result.message)
    }
  } finally {
    paying.value = false
  }
}

/** 钱包支付失败/取消：空字符串表示用户主动取消，不打扰；其余为可展示错误 */
function onWalletFailed(msg) {
  paying.value = false
  if (msg) message.error(msg)
}

function realChannelTip(name) {
  message.info(`${name}支付需在服务端配置商户凭证后启用，当前演示环境默认走模拟渠道`)
}

// 取消不可逆：账期单还会释放授信额度，必须二次确认 + 防重复点击
const cancelling = ref(false)
function cancel(r) {
  const isTerm = String(r?.payType || '').toLowerCase() === 'term'
  Modal.confirm({
    title: '确认取消该订单？',
    content: `订单 ${r.orderNo} 取消后不可恢复`
      + (isTerm ? '；该订单为账期支付，取消将同步释放占用的授信额度。' : '。'),
    okText: '确认取消',
    okType: 'danger',
    cancelText: '再想想',
    onOk: async () => {
      if (cancelling.value) return
      cancelling.value = true
      try {
        await orderApi.cancel(r.orderNo)
        message.success('已取消')
        load()
      } catch (e) {
        /* 拦截器已提示 */
      } finally {
        cancelling.value = false
      }
    }
  })
}

// ===== 确认收货（订单完成闭环） =====
function confirmReceipt(r) {
  Modal.confirm({
    title: '确认已收到货？',
    content: `订单 ${r.orderNo} 将被标记为「已完成」，在途运单同步签收。请确认商品已验收无误。`,
    okText: '确认收货', cancelText: '再等等',
    onOk: async () => {
      try {
        await orderApi.confirmReceipt(r.orderNo)
        message.success('确认收货成功，交易完成')
        load()
      } catch (e) { /* 拦截器已提示 */ }
    }
  })
}

// ===== 退款申请 =====
const refundOpen = ref(false)
const refunding = ref(false)
const refundTarget = ref(null)
const refundReason = ref('')
const refundEvidences = ref([])
const evidenceUploadRef = ref(null)
const evidenceStatus = ref({ uploading: 0, error: 0, done: 0 })
function onEvidenceStatus(s) { evidenceStatus.value = s }
/** 可申请退款：已支付、订单未结束，且无进行中/已完成退款 */
function canRefund(r) {
  return Number(r.payStatus) === 1
    && ![3, 5].includes(Number(r.orderStatus))
    && !['applied', 'merchant_approved', 'processing', 'success'].includes(r.refundStatus || 'none')
}
function openRefund(r) {
  refundTarget.value = r
  refundReason.value = ''
  refundEvidences.value = []
  evidenceUploadRef.value?.clear?.()
  refundOpen.value = true
}
async function doRefund() {
  if (!refundReason.value.trim()) { message.warning(t('请填写退款原因')); return }
  // 证据 ≥1 必填（体验层，与后端 RefundEvidenceValidator 双层拦截）
  if (!refundEvidences.value.length) { message.warning(t('请至少上传 1 项退款凭证')); return }
  if (evidenceStatus.value.uploading > 0) { message.warning(t('凭证上传中，请稍候')); return }
  if (evidenceStatus.value.error > 0) { message.warning(t('凭证上传失败，请重试')); return }
  refunding.value = true
  try {
    await orderApi.applyRefund(refundTarget.value.orderNo, refundReason.value.trim(), serializeEvidences(refundEvidences.value))
    trackEvent('refund_apply', { order_no: refundTarget.value.orderNo })
    message.success('退款申请已提交，请等待平台处理')
    refundOpen.value = false
    await load()
  } catch (e) { /* 拦截器已提示 */ }
  finally { refunding.value = false }
}

// ===== 订单评价入口（已完成单）：列表弹窗展示各商品行评价状态，行级评价在详情页完成 =====
/** 可评价：已完成且在完成后 30 天窗口内（与后端 OrderReviewService 同口径，基准取 updateTime） */
const REVIEW_WINDOW_MS = 30 * 24 * 3600 * 1000
function canReview(r) {
  if (Number(r.payStatus) !== 1 || Number(r.orderStatus) !== 2) return false
  const doneAt = new Date(String(r.updateTime || r.createTime || '').replace(' ', 'T')).getTime()
  return !Number.isFinite(doneAt) || Date.now() - doneAt <= REVIEW_WINDOW_MS
}
const reviewOpen = ref(false)
const reviewLoaded = ref(false)
const reviewTitle = ref('')
const reviewItems = ref([])
const reviewedIds = ref(new Set())
async function openOrderReview(record) {
  reviewTitle.value = locale.value === 'en-US' ? `Review · ${record.orderNo}` : `评价 · ${record.orderNo}`
  reviewItems.value = []
  reviewedIds.value = new Set()
  reviewLoaded.value = false
  reviewOpen.value = true
  try {
    const detail = await orderApi.detail(record.orderNo)
    reviewItems.value = (detail?.items || []).map(it => ({ ...it, rating: null, content: '' }))
    const ids = await reviewApi.orderReviewedItems(record.orderNo)
    reviewedIds.value = new Set((ids || []).map(Number))
  } catch (e) {
    /* 拦截器已提示；弹窗保持空态提示 */
  } finally {
    reviewLoaded.value = true
  }
}
function goItemReview(it) {
  reviewOpen.value = false
  // 行级评价（星级+晒图+匿名）在订单详情页完成
  $router.push('/orders/' + it.orderNo)
}

onMounted(() => {
  load()
  // 收款账户为可选配置：失败时保持空对象，组件会提示"平台暂未配置"
  paymentApi.corporateBank().then((vo) => { corporateBank.value = vo || {} }).catch(() => {})
})
</script>

<style scoped>
.eyebrow{font-size:11px;letter-spacing:.16em;color:var(--ll-primary)}.sub{margin:-10px 0 12px;color:var(--ll-muted)}.merchant-bridge{margin:0 0 14px;border-radius:12px}.order-tabs{margin-bottom:16px}.order-tabs :deep(.ant-radio-button-wrapper){font-size:12px}
.ch { font-size: 12px; color: var(--ll-muted, #64748b); margin-top: 2px; }
/* 优惠构成回显行：金额列下方的小字明细 */
.disc-line { font-size: 12px; color: #067647; margin-top: 2px; }
.disc-line em { font-style: normal; font-weight: 600; }
.rf { font-size: 12px; color: #7c3aed; margin-top: 2px; }
.rf-wait { color: var(--ll-amber-strong, #b45309); }
.rf-rej { color: var(--ll-price, #d93025); }
/* 订单评价弹窗：行卡片 + 星级回显（已评）+ 去评价按钮 */
.rev-loading { display: flex; justify-content: center; padding: 24px 0; }
.rev-item { border: 1px solid #f0f0f0; border-radius: 10px; padding: 10px 12px; margin-bottom: 10px; }
.rev-item-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.rev-item-name { font-weight: 600; font-size: 13px; }
.rev-item-rate { display: flex; align-items: center; gap: 10px; margin-top: 4px; }
.rev-item-content { color: #6b7280; font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rev-go { margin-top: 8px; }
.rev-empty { text-align: center; color: #9ca3af; font-size: 13px; padding: 16px 0; }
.rev-hint { margin: 8px 0 0; color: var(--ll-text-secondary, #64748b); font-size: 11.5px; }
/* 退款凭证区：说明文案小字 + 已退金额回显（颜色走令牌，零硬编码） */
.refund-evidence-hints { margin-top: 6px; display: flex; flex-direction: column; gap: 2px; }
.refund-evidence-hints p { margin: 0; font-size: 11.5px; line-height: 1.55; color: var(--ll-text-secondary); }
.refund-refunded-hint { color: var(--ll-text-tertiary); font-size: 12px; }
.pay-modal-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 4px 0;
}
.pay-amount-banner {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: linear-gradient(135deg, #0f172a 0%, #1e293b 100%);
  padding: 16px 20px;
  border-radius: 14px;
  color: #fff;
  box-shadow: 0 4px 16px rgba(15, 23, 42, 0.12);
}
.banner-label {
  display: block;
  font-size: 11.5px;
  color: #94a3b8;
  letter-spacing: .02em;
}
.banner-amt {
  font-size: 18px;
  font-weight: 500;
  color: #38bdf8;
  margin-top: 2px;
}
.banner-amt b {
  font-size: 26px;
  font-weight: 700;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  margin-left: 3px;
  letter-spacing: -.02em;
}
.banner-badge {
  font-size: 11px;
  padding: 4px 10px;
  border-radius: 9999px;
  background: rgba(56, 189, 248, 0.12);
  color: #38bdf8;
  border: 1px solid rgba(56, 189, 248, 0.25);
}
.selector-container {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.selector-title {
  font-size: 13px;
  font-weight: 600;
  color: #1e293b;
}
.extra-panel {
  animation: fadeIn .25s ease-out;
}
.wallet-prepare-hint {
  margin: 0;
  padding: 12px 14px;
  border-radius: 12px;
  background: var(--ll-card-soft, #fbfcfe);
  border: 1px dashed var(--ll-border-strong, #d5dae2);
  font-size: 12px;
  color: var(--ll-muted, #64748b);
}
.gateway-preview-panel {
  display: flex;
  align-items: center;
  padding: 12px 14px;
  border-radius: 12px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  animation: fadeIn .25s ease-out;
}
.gateway-tip strong {
  display: block;
  font-size: 13px;
  color: #0f172a;
}
.gateway-tip p {
  margin: 3px 0 0;
  font-size: 12px;
  color: #64748b;
  line-height: 1.4;
}
.pay-submit-bar {
  margin-top: 8px;
}
.pay-confirm-btn {
  width: 100%;
  min-height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14.5px;
  font-weight: 600;
  color: #ffffff;
  background: linear-gradient(135deg, #0284c7 0%, #0369a1 100%);
  border: none;
  border-radius: 12px;
  cursor: pointer;
  box-shadow: 0 4px 14px rgba(2, 132, 199, 0.3);
  transition: all .2s ease;
}
.pay-confirm-btn:hover:not(:disabled) {
  background: linear-gradient(135deg, #0369a1 0%, #075985 100%);
  transform: translateY(-1px);
  box-shadow: 0 6px 18px rgba(2, 132, 199, 0.4);
}
.pay-confirm-btn:disabled {
  opacity: .5;
  cursor: not-allowed;
  transform: none;
}
@keyframes fadeIn {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: translateY(0); }
}
</style>

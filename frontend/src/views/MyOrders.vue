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
          <div v-if="record.refundStatus === 'applied'" class="rf rf-wait">退款申请中 · 待平台处理</div>
          <div v-else-if="record.refundStatus === 'rejected'" class="rf rf-rej">退款申请被驳回：{{ record.refundRejectReason || '不符合退款条件' }}</div>
          <div v-else-if="record.refundStatus === 'partial'" class="rf">已部分退款 ¥{{ record.refundAmount }}，剩余金额可再次申请</div>
          <div v-else-if="record.refundStatus === 'success'" class="rf">已全额退款 ¥{{ record.refundAmount }}</div>
        </template>
        <template v-else-if="column.key === 'op'">
          <a-space wrap>
            <a @click="$router.push('/orders/' + record.orderNo)">详情</a>
            <a v-if="record.payStatus === 0 && record.orderStatus === 0 && record.payType !== 'term'" @click="openPay(record)">支付</a>
            <a v-if="Number(record.payStatus) === 1 && record.orderStatus === 1" @click="confirmReceipt(record)">确认收货</a>
            <a v-if="canRefund(record)" @click="openRefund(record)">申请退款</a>
            <a v-if="record.orderStatus === 0 && (record.payStatus === 0 || record.payType === 'term')" @click="cancel(record)" style="color:#999">取消</a>
          </a-space>
        </template>
      </template>
    </a-table>
    <a-empty v-if="!loading && orders.length === 0" description="还没有订单">
      <a-button type="primary" @click="$router.push('/mall')">去下单</a-button>
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
          <div class="selector-title">选择支付方式（支持 7 种主流与国际结算）</div>
          <PaymentMethodSelector
            v-model="selectedPayMethod"
            :wallet-balance="walletBalance"
            :order-amount="Number(current?.totalAmount || 0)"
          />
        </div>

        <!-- Stripe 虚拟卡槽 -->
        <div v-if="selectedPayMethod === 'stripe'" class="extra-panel">
          <StripeCardElement />
        </div>

        <!-- 对公收款账号提示 -->
        <div v-else-if="selectedPayMethod === 'corporate'" class="extra-panel">
          <MerchantCorporateBankCard
            :bank-info="{
              bankName: '中国工商银行股份有限公司深圳科技园支行',
              bankAccountMasked: '6212 26****** 8899',
              legalPersonMasked: '深圳市蓝链工业互联股份有限公司',
              taxRegNo: '91440300MA5XXXXXX',
              verified: true
            }"
          />
        </div>

        <!-- 支付宝/微信说明 -->
        <div v-else-if="selectedPayMethod === 'alipay' || selectedPayMethod === 'wechat'" class="gateway-preview-panel">
          <QrCode :size="20" class="text-sky-500 mr-2 flex-shrink-0" />
          <div class="gateway-tip">
            <strong>{{ selectedPayMethod === 'alipay' ? '支付宝' : '微信支付' }}安全网关直连</strong>
            <p>点击确认支付后将唤起{{ selectedPayMethod === 'alipay' ? '支付宝收银台' : '微信扫码二维码' }}完成交易核验。</p>
          </div>
        </div>

        <!-- 苹果/谷歌支付说明 -->
        <div v-else-if="selectedPayMethod === 'apple_pay' || selectedPayMethod === 'google_pay'" class="gateway-preview-panel">
          <Smartphone :size="20" class="text-indigo-500 mr-2 flex-shrink-0" />
          <div class="gateway-tip">
            <strong>{{ selectedPayMethod === 'apple_pay' ? 'Apple Pay 极速支付' : 'Google Pay 极速支付' }}</strong>
            <p>由 Stripe Express Checkout 引擎驱动，支持原生生物识别（Touch ID / Face ID）秒级完成扣款。</p>
          </div>
        </div>

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
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  Wallet, FlaskConical, Smartphone, Globe, ShieldCheck,
  QrCode, CreditCard, Building2, CheckCircle2
} from 'lucide-vue-next'
import { orderApi, paymentApi, walletApi, trafficApi } from '../api'
import { t } from '../i18n'
import { serializeEvidences } from '../utils/refundEvidence'
import RefundEvidenceUpload from '../components/refund/RefundEvidenceUpload.vue'
import { useUserStore } from '../store/user'
import PaymentMethodSelector from '../components/payment/PaymentMethodSelector.vue'
import MerchantCorporateBankCard from '../components/payment/MerchantCorporateBankCard.vue'
import StripeCardElement from '../components/payment/StripeCardElement.vue'
import FluidAnimatedTabs from '../components/ui/FluidAnimatedTabs.vue'

const user = useUserStore()
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
  payOpen.value = true
  try {
    const w = await walletApi.my()
    walletBalance.value = Number(w?.balance || 0)
  } catch (e) { walletBalance.value = 0 }
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
    } else if (['stripe', 'apple_pay', 'google_pay'].includes(channel)) {
      const vo = await paymentApi.create(current.value.orderNo, 'stripe')
      if (vo?.mock) {
        await paymentApi.mockConfirm(current.value.orderNo)
        message.success('Stripe 支付成功（模拟环境）')
      } else {
        message.success('Stripe 支付预授权已建立')
      }
      trackPay()
    } else if (channel === 'alipay') {
      const vo = await paymentApi.create(current.value.orderNo, 'alipay')
      if (vo?.mock) {
        await paymentApi.mockConfirm(current.value.orderNo)
        message.success('支付宝支付成功（模拟环境）')
      } else if (vo?.payInfo?.form) {
        const div = document.createElement('div')
        div.innerHTML = vo.payInfo.form
        document.body.appendChild(div)
        document.forms[0]?.submit()
        return
      }
      trackPay()
    } else if (channel === 'wechat') {
      const vo = await paymentApi.create(current.value.orderNo, 'wechat')
      if (vo?.mock) {
        await paymentApi.mockConfirm(current.value.orderNo)
        message.success('微信支付成功（模拟环境）')
      } else {
        message.success('请使用微信扫码完成支付')
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
    /* 拦截器已提示 */
  } finally {
    paying.value = false
  }
}

/** 支付成功埋点：订单列表无商户归属信息，PAY 作为平台级事件上报（后端以 0 兜底），失败静默 */
function trackPay() {
  trafficApi.trackPage({ eventType: 'PAY', sourceType: 'order_list' }).catch(() => {})
}

function realChannelTip(name) {
  message.info(`${name}支付需在服务端配置商户凭证后启用，当前演示环境默认走模拟渠道`)
}

async function cancel(r) { await orderApi.cancel(r.orderNo); message.success('已取消'); load() }

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
    message.success('退款申请已提交，请等待平台处理')
    refundOpen.value = false
    await load()
  } catch (e) { /* 拦截器已提示 */ }
  finally { refunding.value = false }
}

onMounted(load)
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

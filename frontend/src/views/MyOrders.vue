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
    <div class="order-tabs"><a-radio-group v-model:value="filter" button-style="solid"><a-radio-button value="all">全部</a-radio-button><a-radio-button value="unpaid">待支付</a-radio-button><a-radio-button value="paid">已支付</a-radio-button><a-radio-button value="done">已完成</a-radio-button><a-radio-button value="closed">已关闭</a-radio-button></a-radio-group></div>
    <a-table :data-source="filteredOrders" :columns="cols" row-key="orderNo" :loading="loading" :scroll="{ x: 1020 }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'total'">¥{{ record.totalAmount }}</template>
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
          <div v-else-if="record.refundStatus === 'success'" class="rf">已全额退款 ¥{{ record.refundAmount }}</div>
        </template>
        <template v-else-if="column.key === 'op'">
          <a-space wrap>
            <a @click="$router.push('/orders/' + record.orderNo)">详情</a>
            <a v-if="record.payStatus === 0 && record.orderStatus === 0 && record.payType !== 'term'" @click="openPay(record)">支付</a>
            <a v-if="canRefund(record)" @click="openRefund(record)">申请退款</a>
            <a v-if="record.orderStatus === 0 && (record.payStatus === 0 || record.payType === 'term')" @click="cancel(record)" style="color:#999">取消</a>
          </a-space>
        </template>
      </template>
    </a-table>
    <a-empty v-if="!loading && orders.length === 0" description="还没有订单">
      <a-button type="primary" @click="$router.push('/mall')">去下单</a-button>
    </a-empty>

    <!-- 支付渠道选择弹窗 -->
    <a-modal v-model:open="payOpen" :title="`订单支付 · ${current?.orderNo}`" :footer="null">
      <div class="pay-amount">应付 <b>¥{{ current?.totalAmount }}</b></div>
      <div class="pay-list">
        <!-- 钱包余额支付 -->
        <div class="pay-item" :class="{ disabled: walletInsufficient }" @click="payWith('wallet')">
          <div class="pay-main">
            <div class="pay-name">💰 钱包余额</div>
            <div class="pay-sub">可用 ¥{{ walletBalance.toFixed(2) }}
              <span v-if="walletInsufficient" class="pay-warn">（余额不足，<a @click.stop="$router.push('/wallet'); payOpen = false">去充值</a>）</span>
            </div>
          </div>
          <a-tag v-if="walletInsufficient" color="red">不足</a-tag>
          <a-tag v-else color="blue">推荐</a-tag>
        </div>
        <!-- 模拟渠道（mock 模式默认） -->
        <div class="pay-item" @click="payWith('mock')">
          <div class="pay-main">
            <div class="pay-name">🧪 模拟网关</div>
            <div class="pay-sub">演示环境模拟支付，即时到账</div>
          </div>
        </div>
        <!-- 真实渠道（未配置凭证时禁用提示） -->
        <div class="pay-item disabled" @click="realChannelTip('微信')">
          <div class="pay-main">
            <div class="pay-name">💚 微信支付</div>
            <div class="pay-sub">扫码支付（需配置商户凭证后启用）</div>
          </div>
          <a-tag>未启用</a-tag>
        </div>
        <div class="pay-item disabled" @click="realChannelTip('支付宝')">
          <div class="pay-main">
            <div class="pay-name">💙 支付宝</div>
            <div class="pay-sub">电脑网站支付（需配置应用凭证后启用）</div>
          </div>
          <a-tag>未启用</a-tag>
        </div>
      </div>
      <div class="pay-note">支付遇到问题？可前往「我的钱包」充值后使用余额支付。</div>
    </a-modal>

    <!-- 申请退款弹窗 -->
    <a-modal v-model:open="refundOpen" :title="`申请退款 · ${refundTarget?.orderNo}`" ok-text="提交申请" cancel-text="取消"
             :confirm-loading="refunding" @ok="doRefund">
      <a-form layout="vertical">
        <a-form-item label="订单金额"><b>¥{{ refundTarget?.totalAmount }}</b>
          <span v-if="refundTarget?.refundAmount" style="color:#999">（已退 ¥{{ refundTarget.refundAmount }}）</span>
        </a-form-item>
        <a-form-item label="退款原因（必填，将提交平台审核）" required>
          <a-textarea v-model:value="refundReason" :rows="3" maxlength="200" show-count
                      placeholder="例如：商品缺货 / 与描述不符 / 沟通后协商退款" />
        </a-form-item>
        <a-alert type="info" show-icon message="申请后由平台审核处理"
                 description="申请按可退余额全额提交，平台审核通过后资金原路退回；若被驳回会展示驳回原因，你可再次申请。" />
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { orderApi, paymentApi, walletApi } from '../api'
import { useUserStore } from '../store/user'

const user = useUserStore()
// 仅真实商户显示（admin 的 admin:all 是通配权限，会误命中，需排除）
const isMerchant = computed(() => !user.isAdmin &&
  (user.hasPerm('merchant:manage') || user.hasPerm('product:publish')))
const orders = ref([])
const loading = ref(false)
const filter = ref('all')
const filteredOrders = computed(() => orders.value.filter(o => filter.value === 'all' ? true : filter.value === 'unpaid' ? Number(o.payStatus) !== 1 && Number(o.payStatus) !== 2 && Number(o.orderStatus) !== 3 : filter.value === 'paid' ? Number(o.payStatus) === 1 && Number(o.orderStatus) !== 2 && Number(o.orderStatus) !== 3 && Number(o.orderStatus) !== 4 : filter.value === 'done' ? Number(o.orderStatus) === 2 : Number(o.orderStatus) === 3 || Number(o.orderStatus) === 4))
const statusMap = { 0: '待发货', 1: '已发货', 2: '已完成', 3: '已取消', 4: '已退款' }
const channelText = { wallet: '钱包', mock: '模拟', wechat: '微信', alipay: '支付宝' }
const payTypeText = { corporate: '对公转账', balance: '企业钱包', term: '企业账期' }
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
const current = ref(null)
const walletBalance = ref(0)
const walletInsufficient = computed(() =>
  !current.value || walletBalance.value < Number(current.value.totalAmount || 0))

async function load() {
  loading.value = true
  try { orders.value = await orderApi.my() }
  finally { loading.value = false }
}

async function openPay(record) {
  current.value = record
  payOpen.value = true
  try {
    const w = await walletApi.my()
    walletBalance.value = Number(w?.balance || 0)
  } catch (e) { walletBalance.value = 0 }
}

async function payWith(channel) {
  if (channel === 'wallet' && walletInsufficient.value) {
    message.warning('钱包余额不足，请先充值'); return
  }
  try {
    if (channel === 'wallet') {
      const vo = await paymentApi.create(current.value.orderNo, 'wallet')
      if (vo?.payInfo?.paid) message.success(`支付成功，钱包余额 ¥${Number(vo.payInfo.balance).toFixed(2)}`)
    } else {
      // mock 渠道：create 生成模拟令牌后确认支付
      const vo = await paymentApi.create(current.value.orderNo, 'mock')
      if (vo?.mock) await paymentApi.mockConfirm(current.value.orderNo)
      message.success('支付成功')
    }
    payOpen.value = false
    user.fetchMe()
    await load()
  } catch (e) { /* 拦截器已提示 */ }
}

function realChannelTip(name) {
  message.info(`${name}支付需在服务端配置商户凭证后启用，当前演示环境默认走模拟渠道`)
}

async function cancel(r) { await orderApi.cancel(r.orderNo); message.success('已取消'); load() }

// ===== 退款申请 =====
const refundOpen = ref(false)
const refunding = ref(false)
const refundTarget = ref(null)
const refundReason = ref('')
/** 可申请退款：已支付、非账期、订单未结束，且无进行中/已完成退款 */
function canRefund(r) {
  return Number(r.payStatus) === 1 && r.payType !== 'term'
    && ![3, 4].includes(Number(r.orderStatus))
    && !['applied', 'processing', 'success'].includes(r.refundStatus || 'none')
}
function openRefund(r) { refundTarget.value = r; refundReason.value = ''; refundOpen.value = true }
async function doRefund() {
  if (!refundReason.value.trim()) { message.warning('请填写退款原因'); return }
  refunding.value = true
  try {
    await orderApi.applyRefund(refundTarget.value.orderNo, refundReason.value.trim())
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
.rf { font-size: 12px; color: #7c3aed; margin-top: 2px; }
.rf-wait { color: var(--ll-amber-strong, #b45309); }
.rf-rej { color: var(--ll-price, #d93025); }
.pay-amount { font-size: 15px; margin-bottom: 14px; color: var(--ll-gray, #4b5563); }
.pay-amount b { color: var(--ll-price, #e4393c); font-size: 22px; margin-left: 6px; letter-spacing: -.02em; }
.pay-list { display: flex; flex-direction: column; gap: 10px; }
.pay-item { display: flex; align-items: center; justify-content: space-between; gap: 12px;
            border: 1.5px solid var(--ll-border, #e5e7eb); border-radius: var(--ll-radius-md, 10px); padding: 12px 14px; cursor: pointer;
            transition: border-color .2s ease, background-color .2s ease, transform .2s var(--ll-ease-out, ease); }
.pay-item:hover:not(.disabled) { border-color: var(--ll-primary, #1e6eb8); background: rgba(30, 110, 184, .04); transform: translateY(-1px); }
.pay-item:active:not(.disabled) { transform: scale(0.99); }
.pay-item.disabled { opacity: .55; cursor: not-allowed; }
.pay-name { font-weight: 600; }
.pay-sub { font-size: 12px; color: var(--ll-muted, #64748b); margin-top: 2px; }
.pay-warn a { color: var(--ll-primary, #1e6eb8); }
.pay-note { margin-top: 14px; font-size: 12px; color: var(--ll-muted, #64748b); }
</style>

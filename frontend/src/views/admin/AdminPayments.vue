<template>
  <a-card title="交易管理">
    <template #extra>
      <a-radio-group v-model:value="payStatus" button-style="solid" @change="load(1)">
        <a-radio-button :value="null">全部</a-radio-button>
        <a-radio-button :value="0">未支付</a-radio-button>
        <a-radio-button :value="1">已支付</a-radio-button>
        <a-radio-button :value="2">已退款</a-radio-button>
      </a-radio-group>
    </template>
    <a-table :data-source="rows" :columns="cols" row-key="orderNo" :loading="loading"
             :scroll="{ x: 1280 }" :pagination="pager" @change="onPage">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'total'">¥{{ record.totalAmount }}</template>
        <template v-else-if="column.key === 'pay'">
          <a-tag :color="payColor[record.payStatus]">{{ payText[record.payStatus] || record.payStatus }}</a-tag>
        </template>
        <template v-else-if="column.key === 'status'">
          <a-tag>{{ orderText[record.orderStatus] || record.orderStatus }}</a-tag>
        </template>
        <template v-else-if="column.key === 'channel'">
          <a-tag v-if="record.payChannel" :color="channelColor[record.payChannel] || 'default'">
            {{ channelText[record.payChannel] || record.payChannel }}
          </a-tag>
          <span v-else style="color:#999">—</span>
        </template>
        <template v-else-if="column.key === 'refund'">
          <!-- 退款状态：包含 P0-C 新增平台复核中间态，避免显示"无退款"误导运营 -->
          <a-tooltip :title="refundTooltip(record)">
            <a-tag :color="refundTagColor(record.refundStatus)">{{ refundStatusText(record.refundStatus) }}</a-tag>
          </a-tooltip>
          <span v-if="record.refundAmount" class="refund-amt">¥{{ record.refundAmount }}</span>
        </template>
        <template v-else-if="column.key === 'time'">{{ (record.payTime || record.createTime || '').replace('T', ' ') }}</template>
        <template v-else-if="column.key === 'op'">
          <a-space wrap>
            <a-button size="small" @click="$router.push('/admin/payments/' + record.orderNo)">详情</a-button>
            <!-- P0-C：旧的一步式「退款」按钮已移除（直接调 /payment/refund 会被出款守卫 403/409 拦截）。
                 可出款状态统一走「平台复核 → 通过并出款 / 驳回」。 -->
            <a-button v-if="isPlatformReviewable(record)" type="primary" size="small" danger
                      @click="openReview(record)">平台复核</a-button>
            <a-button v-if="record.refundStatus === 'applied'" size="small" @click="openReject(record)">驳回申请</a-button>
          </a-space>
        </template>
      </template>
    </a-table>

    <!-- P0-C 平台退款复核弹窗：链A（订单）/ 链B（RMA 售后单）双人留痕 + 复核金额锁定 -->
    <a-modal
      v-model:open="reviewOpen"
      :title="reviewTitle"
      :width="620"
      :mask-closable="false"
      :confirm-loading="reviewing"
      @cancel="reviewOpen = false"
    >
      <div class="review-body">
        <a-alert
          v-if="!reviewTargetCanReview"
          type="warning"
          show-icon
          class="review-alert"
          message="当前退款状态不可平台复核"
          description="请先完成商户初审（或退货验货）后再复核；若已被驳回，需买家提交售后工单申诉、由平台「重开复核」。"
        />
        <a-alert
          v-else
          type="warning"
          show-icon
          class="review-alert"
          message="平台复核通过将立即原路出款（不可撤回）"
          :description="reviewTarget?.currentRmaNo ? `本单为售后单（RMA ${reviewTarget.currentRmaNo}），通过后由 RMA 复核链出款并结案。` : '本单为普通订单退款（链A）。出款前请核对退款原因、凭证与审批历史。'"
        />

        <a-descriptions :column="2" size="small" bordered class="review-desc">
          <a-descriptions-item label="订单号">{{ reviewTarget?.orderNo }}</a-descriptions-item>
          <a-descriptions-item label="退款状态">{{ refundStatusText(reviewTarget?.refundStatus) }}</a-descriptions-item>
          <a-descriptions-item label="订单金额">¥{{ reviewTarget?.totalAmount }}</a-descriptions-item>
          <a-descriptions-item label="已退金额">¥{{ reviewTarget?.refundAmount || 0 }}</a-descriptions-item>
          <a-descriptions-item label="可退余额" :span="2"><b class="remain">¥{{ remainRefundable }}</b></a-descriptions-item>
          <a-descriptions-item v-if="reviewTarget?.refundReason" label="买家申请原因" :span="2">{{ reviewTarget.refundReason }}</a-descriptions-item>
          <a-descriptions-item v-if="reviewTarget?.currentRmaNo" label="售后单号" :span="2">{{ reviewTarget.currentRmaNo }}</a-descriptions-item>
        </a-descriptions>

        <!-- 退款凭证（链A t_order.refund_evidences）：平台复核必须能核对凭证 -->
        <RefundEvidenceGallery v-if="reviewEvidenceJson" :evidences="reviewEvidenceJson" class="review-evidence" />
        <p v-else class="review-no-evidence">该单未上传退款凭证（链B 凭证请到售后单详情核对）。</p>

        <!-- 双人留痕审批历史：MERCHANT 初审 + PLATFORM 复核/驳回/重开 -->
        <div class="review-history">
          <div class="review-history-head">
            <span>审批留痕</span>
            <a-spin v-if="historyLoading" size="small" />
          </div>
          <a-empty v-if="!historyLoading && !history.length" description="暂无审批记录" :image="false" />
          <ol v-else class="review-history-list">
            <li v-for="h in history" :key="h.id">
              <span class="hist-dot" :class="h.action === 'APPROVE' ? 'is-ok' : h.action === 'REJECT' ? 'is-reject' : 'is-reopen'"></span>
              <span class="hist-text">{{ approvalActionText(h) }}</span>
              <span class="hist-time">{{ fmt(h.createTime) }}</span>
            </li>
          </ol>
        </div>

        <a-form layout="vertical" class="review-form">
          <a-form-item label="本次复核金额（元，留空=按剩余可退金额全额复核）">
            <a-input-number v-model:value="reviewAmount" :min="0.01" :max="Number(remainRefundable)"
                            :precision="2" style="width: 100%" :placeholder="`最多 ¥${remainRefundable}`" />
          </a-form-item>
          <a-form-item :label="reviewTargetCanReview ? '复核意见（通过时展示给买家，选填）' : '驳回原因（必填）'">
            <a-textarea v-model:value="reviewRemark" :rows="3" maxlength="200" show-count
                        :placeholder="reviewTargetCanReview ? '如：凭证齐全，同意原路退款' : '如：凭证不完整 / 商品已签收超出时限'" />
          </a-form-item>
        </a-form>
      </div>

      <template #footer>
        <a-button @click="reviewOpen = false">取消</a-button>
        <a-button danger :loading="reviewing" @click="rejectReview">驳回复核</a-button>
        <a-button type="primary" danger :loading="reviewing" @click="approveReview">通过并出款</a-button>
      </template>
    </a-modal>

    <!-- 驳回退款申请（尚未完成商户初审的 applied 状态；非出款动作，走旧的非出款驳回入口） -->
    <a-modal v-model:open="rejectOpen" :title="`驳回退款申请 · ${rejectTarget?.orderNo}`" ok-text="确认驳回" cancel-text="取消"
             :confirm-loading="rejecting" @ok="doReject">
      <a-form layout="vertical">
        <a-form-item v-if="rejectTarget?.refundReason">
          <a-alert type="info" show-icon :message="'用户申请原因：' + rejectTarget.refundReason" />
        </a-form-item>
        <a-form-item label="驳回原因（必填，将展示给用户）" required>
          <a-textarea v-model:value="rejectReason" :rows="3" maxlength="200" show-count
                      placeholder="如：商品已发货且无质量问题 / 超出可退款时限 / 与用户协商后取消申请" />
        </a-form-item>
      </a-form>
    </a-modal>
  </a-card>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { orderApi, refundReviewApi } from '../../api'
import { t } from '../../i18n'
import { parseEvidences } from '../../utils/refundEvidence'
import RefundEvidenceGallery from '../../components/refund/RefundEvidenceGallery.vue'
import {
  isPlatformReviewable,
  resolveRefundReviewTarget,
  buildReviewPayload,
  explainRefundReviewError,
  refundStatusText,
  approvalActionText
} from './refundReview'

const rows = ref([])
const loading = ref(false)
const payStatus = ref(null)
const page = ref(1)
const total = ref(0)
const pageSize = 10

const payText = { 0: '未支付', 1: '已支付', 2: '已退款' }
const payColor = { 0: 'orange', 1: 'green', 2: 'purple' }
const orderText = { 0: '待发货', 1: '已发货', 2: '已完成', 3: '已取消', 4: '已退款' }
const channelText = { wallet: '钱包', mock: '模拟', wechat: '微信', alipay: '支付宝', stripe: 'Stripe', corporate: '对公转账' }
const channelColor = { wallet: 'blue', mock: 'default', wechat: 'green', alipay: 'blue', stripe: 'geekblue', corporate: 'cyan' }

// 链A退款凭证摘要计数（t_order.refund_evidences；链B（RMA）凭证在详情页经 rmaApi 取）
function evidenceCount(record) {
  return parseEvidences(record?.refundEvidences).length
}

function refundTagColor(status) {
  return {
    success: 'green',
    partial: 'gold',
    processing: 'orange',
    applied: 'blue',
    merchant_approved: 'purple',
    shipping_back: 'cyan',
    received: 'cyan',
    dispute: 'volcano',
    rejected: 'default'
  }[status] || 'default'
}

function refundTooltip(record) {
  const parts = [refundStatusText(record.refundStatus)]
  if (record.refundReason) parts.push('申请原因：' + record.refundReason)
  if (record.refundApplyTime) parts.push('申请时间：' + String(record.refundApplyTime).replace('T', ' '))
  if (evidenceCount(record) > 0) parts.push(t('买家提交的退款凭证') + ' ' + evidenceCount(record))
  if (record.refundRejectReason) parts.push('驳回原因：' + record.refundRejectReason)
  return parts.join(' ｜ ')
}

const cols = [
  { title: '订单号', dataIndex: 'orderNo', key: 'orderNo', width: 190 },
  { title: '用户ID', dataIndex: 'userId', width: 80 },
  { title: '金额', key: 'total', width: 110 },
  { title: '支付渠道', key: 'channel', width: 100 },
  { title: '支付状态', key: 'pay', width: 100 },
  { title: '订单状态', key: 'status', width: 100 },
  { title: '退款', key: 'refund', width: 210 },
  { title: '支付/下单时间', key: 'time', width: 165 },
  { title: '操作', key: 'op', width: 240 }
]

const pager = computed(() => ({
  current: page.value, pageSize, total: total.value, showTotal: (t) => `共 ${t} 单`
}))

async function load(p = 1) {
  loading.value = true
  page.value = p
  try {
    const params = { page: p, size: pageSize }
    if (payStatus.value !== null) params.payStatus = payStatus.value
    const d = await orderApi.adminList(params)
    rows.value = d?.records || []
    total.value = Number(d?.total || 0)
  } catch (e) { /* 拦截器已提示 */ }
  finally { loading.value = false }
}

function onPage(p) { load(p.current) }

// ===== P0-C 平台退款复核（唯一出款入口） =====
const reviewOpen = ref(false)
const reviewing = ref(false)
const reviewTarget = ref(null)
const reviewAmount = ref(null)
const reviewRemark = ref('')
const reviewHistory = ref([])
const historyLoading = ref(false)

const reviewTargetCanReview = computed(() => isPlatformReviewable(reviewTarget.value))
const reviewTitle = computed(() => {
  const r = reviewTarget.value
  if (!r) return '平台退款复核'
  return `平台退款复核 · ${r.orderNo}${r.currentRmaNo ? '（售后单 ' + r.currentRmaNo + '）' : ''}`
})
const remainRefundable = computed(() => {
  const r = reviewTarget.value
  if (!r) return '0.00'
  const remain = Number(r.totalAmount || 0) - Number(r.refundAmount || 0)
  return Math.max(0, remain).toFixed(2)
})
const reviewEvidenceJson = computed(() => reviewTarget.value?.refundEvidences || '')
function fmt(v) { return v ? String(v).replace('T', ' ').slice(0, 19) : '—' }

async function loadHistory(orderNo) {
  historyLoading.value = true
  try {
    const list = await refundReviewApi.history(orderNo)
    reviewHistory.value = Array.isArray(list) ? list : []
  } catch (e) {
    // 历史属只读辅助信息，失败不阻塞复核（服务端仍会写留痕）
    reviewHistory.value = []
  } finally {
    historyLoading.value = false
  }
}

async function openReview(record) {
  reviewTarget.value = record
  reviewAmount.value = null
  reviewRemark.value = ''
  reviewHistory.value = []
  reviewOpen.value = true
  await loadHistory(record.orderNo)
}

/** 复核目标解析：currentRmaNo 存在走 RMA 复核，否则走订单复核（与服务端两条链同源） */
function targetFor(record) {
  return resolveRefundReviewTarget(record)
}

async function approveReview() {
  const target = targetFor(reviewTarget.value)
  if (!reviewing.value) reviewing.value = true
  try {
    const payload = buildReviewPayload({ action: 'approve', amount: reviewAmount.value, remark: reviewRemark.value })
    if (target.scope === 'rma') await refundReviewApi.approveRma(target.rmaNo, payload)
    else await refundReviewApi.approveOrder(target.orderNo, payload)
    message.success('平台复核通过，退款已提交原路出款')
    reviewOpen.value = false
    await load(page.value)
  } catch (e) {
    // 403/409 翻译成「走复核 / 刷新重新复核」等可行动提示，绝不显示通用网络错误
    message.error(explainRefundReviewError(e, 'approve'))
  } finally {
    reviewing.value = false
  }
}

async function rejectReview() {
  const remark = String(reviewRemark.value || '').trim()
  if (!remark) { message.warning('请填写驳回原因（将展示给买家）'); return }
  const target = targetFor(reviewTarget.value)
  if (!reviewing.value) reviewing.value = true
  try {
    const payload = buildReviewPayload({ action: 'reject', remark })
    if (target.scope === 'rma') await refundReviewApi.rejectRma(target.rmaNo, payload)
    else await refundReviewApi.rejectOrder(target.orderNo, payload)
    message.success('平台复核已驳回，原因将展示给买家')
    reviewOpen.value = false
    await load(page.value)
  } catch (e) {
    message.error(explainRefundReviewError(e, 'reject'))
  } finally {
    reviewing.value = false
  }
}

// ===== 驳回退款申请（applied：尚未完成商户初审，非出款动作） =====
const rejectOpen = ref(false)
const rejecting = ref(false)
const rejectTarget = ref(null)
const rejectReason = ref('')

function openReject(record) {
  rejectTarget.value = record
  rejectReason.value = ''
  rejectOpen.value = true
}

async function doReject() {
  if (!rejectReason.value.trim()) { message.warning('请填写驳回原因'); return }
  rejecting.value = true
  try {
    await orderApi.adminRejectRefund(rejectTarget.value.orderNo, rejectReason.value.trim())
    message.success('已驳回退款申请，原因将展示给用户')
    rejectOpen.value = false
    await load(page.value)
  } catch (e) {
    // 复用同一套 403/409 文案映射（旧入口在 P0-C 后也可能命中状态冲突）
    message.error(explainRefundReviewError(e, 'reject'))
  } finally { rejecting.value = false }
}

onMounted(() => load(1))
</script>

<style scoped>
:deep(.ant-table-thead > tr > th) { white-space: nowrap; }
.refund-amt { margin-left: 6px; color: var(--ll-price, #e4393c); font-weight: 600; font-size: 12px; }
.review-body { display: flex; flex-direction: column; gap: 12px; }
.review-alert { margin: 0; }
.review-desc :deep(.ant-descriptions-item-content) { word-break: break-all; }
.remain { color: var(--ll-price, #e4393c); font-size: 15px; }
.review-evidence { border: 1px solid var(--ll-border, #e5e7eb); border-radius: 12px; padding: 10px; background: var(--ll-card-soft, #fbfcfe); }
.review-no-evidence { margin: 0; color: var(--ll-muted, #64748b); font-size: 12px; }
.review-history { border: 1px dashed var(--ll-border, #e5e7eb); border-radius: 12px; padding: 10px 12px; }
.review-history-head { display: flex; justify-content: space-between; align-items: center; font-size: 13px; font-weight: 600; color: var(--ll-ink, #0f172a); margin-bottom: 6px; }
.review-history-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; max-height: 160px; overflow: auto; }
.review-history-list li { display: flex; align-items: center; gap: 8px; font-size: 12.5px; }
.hist-dot { width: 7px; height: 7px; border-radius: 50%; background: #94a3b8; flex-shrink: 0; }
.hist-dot.is-ok { background: #10b981; }
.hist-dot.is-reject { background: #e4393c; }
.hist-dot.is-reopen { background: #f59e0b; }
.hist-text { flex: 1; color: var(--ll-gray, #4b5563); word-break: break-all; }
.hist-time { color: var(--ll-muted, #94a3b8); flex-shrink: 0; }
.review-form { margin-top: 2px; }
</style>

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
             :pagination="pager" @change="onPage">
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
          <a-tag v-if="record.refundStatus === 'success'" color="green">已全额退款 ¥{{ record.refundAmount }}</a-tag>
          <a-tag v-else-if="record.refundStatus === 'processing'" color="orange">退款处理中</a-tag>
          <span v-else style="color:#999">—</span>
        </template>
        <template v-else-if="column.key === 'time'">{{ (record.payTime || record.createTime || '').replace('T', ' ') }}</template>
        <template v-else-if="column.key === 'op'">
          <a-button size="small" @click="$router.push('/admin/payments/' + record.orderNo)">详情</a-button>
          <a-button v-if="record.payStatus === 1 && record.refundStatus !== 'success'"
                    size="small" danger @click="openRefund(record)">退款</a-button>
          <span v-else style="color:#999">—</span>
        </template>
      </template>
    </a-table>

    <!-- 退款弹窗：全额 / 部分 -->
    <a-modal v-model:open="refundOpen" :title="`订单退款 · ${current?.orderNo}`" @ok="doRefund" :confirm-loading="refunding">
      <a-form layout="vertical">
        <a-form-item label="订单金额">
          <b>¥{{ current?.totalAmount }}</b>
          <span v-if="current?.refundAmount" style="color:#999">（已退 ¥{{ current.refundAmount }}）</span>
        </a-form-item>
        <a-form-item label="退款金额（元，留空为全额退款）">
          <a-input-number v-model:value="refundAmount" :min="0.01"
                          :max="maxRefundable" :precision="2" style="width: 100%"
                          :placeholder="`最多 ¥${maxRefundable}`" />
        </a-form-item>
        <a-form-item label="退款原因">
          <a-input v-model:value="refundReason" :maxlength="100" placeholder="例如：商品缺货、客户协商" />
        </a-form-item>
        <a-alert type="warning" show-icon
                 message="全额退款将自动恢复商品库存、扣回支付所得积分并返还积分抵现；退款金额原路退回用户支付账户。" />
      </a-form>
    </a-modal>
  </a-card>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { orderApi, paymentApi } from '../../api'

const rows = ref([])
const loading = ref(false)
const payStatus = ref(null)
const page = ref(1)
const total = ref(0)
const pageSize = 10

const refundOpen = ref(false)
const refunding = ref(false)
const current = ref(null)
const refundAmount = ref(null)
const refundReason = ref('')

const payText = { 0: '未支付', 1: '已支付', 2: '已退款' }
const payColor = { 0: 'orange', 1: 'green', 2: 'purple' }
const orderText = { 0: '待发货', 1: '已发货', 2: '已完成', 3: '已取消', 4: '已退款' }
const channelText = { wallet: '钱包', mock: '模拟', wechat: '微信', alipay: '支付宝' }
const channelColor = { wallet: 'blue', mock: 'default', wechat: 'green', alipay: 'blue' }

const cols = [
  { title: '订单号', dataIndex: 'orderNo', key: 'orderNo', width: 190 },
  { title: '用户ID', dataIndex: 'userId', width: 80 },
  { title: '金额', key: 'total', width: 110 },
  { title: '支付渠道', key: 'channel', width: 100 },
  { title: '支付状态', key: 'pay', width: 100 },
  { title: '订单状态', key: 'status', width: 100 },
  { title: '退款', key: 'refund', width: 150 },
  { title: '支付/下单时间', key: 'time', width: 165 },
  { title: '操作', key: 'op', width: 90 }
]

const pager = computed(() => ({
  current: page.value, pageSize, total: total.value, showTotal: (t) => `共 ${t} 单`
}))

const maxRefundable = computed(() => {
  if (!current.value) return 0
  const remain = Number(current.value.totalAmount || 0) - Number(current.value.refundAmount || 0)
  return remain.toFixed(2)
})

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

function openRefund(record) {
  current.value = record
  refundAmount.value = null
  refundReason.value = ''
  refundOpen.value = true
}

async function doRefund() {
  if (refundAmount.value !== null && refundAmount.value <= 0) {
    message.warning('退款金额需大于 0'); return
  }
  refunding.value = true
  try {
    await paymentApi.refund(current.value.orderNo, refundAmount.value || undefined, refundReason.value || undefined)
    message.success('退款成功')
    refundOpen.value = false
    await load(page.value)
  } catch (e) { /* 拦截器已提示 */ }
  finally { refunding.value = false }
}

onMounted(() => load(1))
</script>

<style scoped>
:deep(.ant-table-thead > tr > th) { white-space: nowrap; }
</style>

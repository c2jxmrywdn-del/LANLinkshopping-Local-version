<template><div class="sys">
  <div class="hero"><div><div class="eyebrow membership-eyebrow"><MembershipIcon :size="30" label="会员图标" /><span>SUBSCRIPTION ORDERS</span></div><h1>订阅订单</h1><p>订阅购买与续费流水，对账/开票依据（金额为整数元口径）。</p></div><div class="actions"><a-button type="primary" @click="$router.push('/membership')">会员中心</a-button></div></div>
  <a-card :bordered="false" class="orders-card">
    <a-table
      :data-source="records"
      :columns="columns"
      :loading="loading"
      :pagination="pagination"
      row-key="orderNo"
      @change="onTableChange">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'orderNo'"><span class="mono">{{ record.orderNo }}</span></template>
        <template v-else-if="column.key === 'amount'">¥{{ formatPrice(record.amountCents) }}</template>
        <template v-else-if="column.key === 'payStatus'">
          <a-tag :color="payColor(record.payStatus)">{{ payText(record.payStatus) }}</a-tag>
        </template>
        <template v-else-if="column.key === 'channel'">{{ channelText(record.channel) }}</template>
        <template v-else-if="column.key === 'paidAt'">{{ formatTime(record.paidAt) }}</template>
        <template v-else-if="column.key === 'autoRenew'">
          <a-tag v-if="Number(record.autoRenew) === 1" color="blue">自动续费</a-tag>
          <a-tag v-else>单次</a-tag>
        </template>
      </template>
    </a-table>
    <p class="unit-note">金额单位：整数元（8888 = ¥8,888），与订阅方案定价同一口径。</p>
  </a-card>
</div></template>
<script setup>
import { ref, reactive, onMounted } from 'vue'
import { subscriptionApi } from '../api'
import MembershipIcon from '../components/ui/MembershipIcon.vue'

// 订阅订单列表（P1-8）：本人购买/续费流水分页；pay_status 0待支付/1已支付/2已取消
// 时间格式化：后端 LocalDateTime ISO 串（无时区，+08 本地语义）→ "YYYY-MM-DD HH:mm"
// 刻意不做 Date 解析（无时区标注的 ISO 走 new Date 会被浏览器按本地时区二次偏移）
function formatTime(v) {
  if (!v) return '—'
  const m = String(v).match(/^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})/)
  return m ? `${m[1]} ${m[2]}` : String(v)
}
const records = ref([])
const loading = ref(false)
const pagination = reactive({ current: 1, pageSize: 10, total: 0, showSizeChanger: false })
const columns = [
  { title: '订阅单号', key: 'orderNo', dataIndex: 'orderNo' },
  { title: '方案', key: 'planName', dataIndex: 'planName' },
  { title: '金额', key: 'amount', dataIndex: 'amountCents', align: 'right' },
  { title: '有效期(天)', key: 'durationDays', dataIndex: 'durationDays' },
  { title: '支付状态', key: 'payStatus', dataIndex: 'payStatus' },
  { title: '支付渠道', key: 'channel', dataIndex: 'channel' },
  { title: '支付时间', key: 'paidAt', dataIndex: 'paidAt' },
  { title: '续费方式', key: 'autoRenew', dataIndex: 'autoRenew' }
]

// 价格为整数元（只加千分位，禁止除以 100）
function formatPrice(n) { return n == null ? '—' : Number(n).toLocaleString('zh-CN') }
function payText(s) { return Number(s) === 1 ? '已支付' : Number(s) === 2 ? '已取消' : '待支付' }
function payColor(s) { return Number(s) === 1 ? 'green' : Number(s) === 2 ? 'default' : 'orange' }
function channelText(c) {
  return { wallet: '企业钱包', mock: '模拟支付', wechat: '微信支付', alipay: '支付宝', stripe: 'Stripe' }[c] || c || '—'
}
function onTableChange(p) { pagination.current = p.current; load() }
async function load() {
  loading.value = true
  try {
    const r = await subscriptionApi.orders(pagination.current, pagination.pageSize)
    records.value = r?.records || []
    pagination.total = Number(r?.total || 0)
  } catch (e) { /* 拦截器已提示 */ } finally { loading.value = false }
}
onMounted(load)
</script>
<style scoped>
.sys{max-width:1040px;margin:0 auto}
.hero{display:flex;justify-content:space-between;gap:20px;align-items:flex-start;margin-bottom:20px}
.eyebrow{font-size:11px;letter-spacing:.18em;color:var(--ll-primary)}
.membership-eyebrow{display:flex;align-items:center;gap:9px}
h1{margin:5px 0;font-size:30px}
.hero p{margin:0;color:var(--ll-muted)}
.actions{display:flex;gap:8px}
.orders-card{border-radius:18px}
.mono{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:12px}
.unit-note{margin:12px 0 0;font-size:12px;color:var(--ll-muted)}
</style>

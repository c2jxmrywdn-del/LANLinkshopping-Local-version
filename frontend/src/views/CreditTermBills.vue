<template>
  <div class="sys">
    <div class="hero">
      <div>
        <div class="eyebrow">CREDIT BILLS</div>
        <h1>账单中心</h1>
        <p>按到期日管理应付账单与还款计划。</p>
      </div>
      <div class="hero-actions">
        <a-button @click="$router.push('/credit-term')">返回账期中心</a-button>
        <a-button :loading="loading" @click="load">刷新</a-button>
      </div>
    </div>

    <a-alert
      v-if="loadError"
      type="error"
      show-icon
      message="账单加载失败"
      description="请检查网络连接后重试，或稍后再访问。"
      class="notice"
    >
      <template #action><a-button size="small" @click="load">重试</a-button></template>
    </a-alert>

    <template v-else>
      <div class="stat-grid">
        <div class="stat">
          <span>待还总额</span>
          <b>¥{{ money(summary.outstanding) }}</b>
          <small>{{ counts.unpaid + counts.overdue }} 笔未结清</small>
        </div>
        <div class="stat danger">
          <span>已逾期</span>
          <b>¥{{ money(summary.overdueAmount) }}</b>
          <small>{{ summary.overdueCount || 0 }} 笔需优先处理</small>
        </div>
        <div class="stat warn">
          <span>7 天内到期</span>
          <b>¥{{ money(summary.dueSoonAmount) }}</b>
          <small>{{ summary.dueSoonCount || 0 }} 笔请提前安排</small>
        </div>
        <div class="stat">
          <span>待确认转账</span>
          <b>¥{{ money(summary.pendingTransferAmount) }}</b>
          <small>财务核销后自动入账</small>
        </div>
      </div>

      <a-card class="filter-card" :bordered="false">
        <a-tabs v-model:activeKey="activeStatus" class="status-tabs" @change="resetPage">
          <a-tab-pane v-for="tab in tabs" :key="tab.key" :tab="`${tab.label} (${counts[tab.key]})`" />
        </a-tabs>
        <div class="toolbar">
          <a-input-search
            v-model:value="keyword"
            allow-clear
            placeholder="搜索账单号 / 订单号"
            class="search"
            @search="resetPage"
          />
          <a-select v-model:value="sort" class="sort">
            <a-select-option value="due">到期日升序</a-select-option>
            <a-select-option value="dueDesc">到期日降序</a-select-option>
            <a-select-option value="created">最近出账</a-select-option>
          </a-select>
        </div>
      </a-card>

      <a-card class="card" :bordered="false">
        <a-table
          v-if="sortedBills.length"
          :data-source="sortedBills"
          :loading="loading"
          row-key="id"
          :scroll="{ x: 1080 }"
          :pagination="{
            current: page,
            pageSize: 10,
            total: sortedBills.length,
            showSizeChanger: false,
            showTotal: (total) => `共 ${total} 条`
          }"
          @change="onTableChange"
        >
          <a-table-column title="账单号" data-index="id" key="id" width="90" />
          <a-table-column title="订单号" key="orderNo" width="200">
            <template #default="{ record }">
              <a class="order-link" @click="goOrder(record.orderNo)">{{ record.orderNo }}</a>
            </template>
          </a-table-column>
          <a-table-column title="账单金额" key="amount" width="120">
            <template #default="{ record }">¥{{ money(record.amount) }}</template>
          </a-table-column>
          <a-table-column title="已还" key="paidAmount" width="110">
            <template #default="{ record }">¥{{ money(record.paidAmount) }}</template>
          </a-table-column>
          <a-table-column title="待还" key="outstandingAmount" width="120">
            <template #default="{ record }"><b>¥{{ money(record.outstandingAmount) }}</b></template>
          </a-table-column>
          <a-table-column title="待确认" key="pending" width="110">
            <template #default="{ record }">
              <span :class="{ pending: pendingFor(record) > 0 }">
                {{ pendingFor(record) > 0 ? `¥${money(pendingFor(record))}` : '—' }}
              </span>
            </template>
          </a-table-column>
          <a-table-column title="到期日" data-index="dueDate" key="dueDate" width="110" />
          <a-table-column title="剩余" key="remaining" width="100">
            <template #default="{ record }">{{ remainingText(record) }}</template>
          </a-table-column>
          <a-table-column title="状态" key="status" width="90">
            <template #default="{ record }">
              <a-tag :color="billColor(record)">{{ billLabel(record) }}</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="操作" key="op" width="150">
            <template #default="{ record }">
              <a-space :size="4" wrap>
                <a-button type="link" size="small" @click="goDetail(record.id)">查看</a-button>
                <a-button v-if="canRepay(record)" type="link" size="small" @click="goDetail(record.id)">去还款</a-button>
                <a-tag v-else-if="pendingFor(record) > 0 && !['PAID', 'CANCELLED'].includes(record.status)" color="orange">
                  待核销
                </a-tag>
              </a-space>
            </template>
          </a-table-column>
        </a-table>

        <a-empty v-else :description="bills.length ? '没有符合条件的账单' : emptyDescription">
          <a-button v-if="bills.length" @click="resetFilters">清空筛选</a-button>
          <a-button v-else type="primary" @click="emptyAction">{{ emptyActionText }}</a-button>
        </a-empty>
      </a-card>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { creditTermApi } from '../api'
import {
  billCategory,
  billColor,
  billLabel,
  money,
  remainingText
} from '../utils/creditTerm'

const route = useRoute()
const router = useRouter()
const loading = ref(true)
const loadError = ref(false)
const data = ref(null)
const keyword = ref('')
const page = ref(1)
const allowedStatus = ['all', 'unpaid', 'overdue', 'paid', 'cancelled']
const activeStatus = ref(allowedStatus.includes(route.query.status) ? route.query.status : 'all')
const allowedSort = ['due', 'dueDesc', 'created']
const sort = ref(allowedSort.includes(route.query.sort) ? route.query.sort : 'due')

const tabs = [
  { key: 'all', label: '全部' },
  { key: 'unpaid', label: '待还' },
  { key: 'overdue', label: '已逾期' },
  { key: 'paid', label: '已结清' },
  { key: 'cancelled', label: '已取消' }
]

const bills = computed(() => data.value?.bills || [])
const summary = computed(() => data.value?.summary || {})
const accountStatus = computed(() => data.value?.status || 'NOT_APPLIED')

const counts = computed(() => {
  const result = { all: bills.value.length, unpaid: 0, overdue: 0, paid: 0, cancelled: 0 }
  for (const bill of bills.value) result[billCategory(bill)]++
  return result
})

const sortedBills = computed(() => {
  const key = keyword.value.trim().toLowerCase()
  const list = bills.value.filter((bill) => {
    const matchStatus = activeStatus.value === 'all' || billCategory(bill) === activeStatus.value
    const matchKeyword = !key
      || String(bill.id).includes(key)
      || String(bill.orderNo || '').toLowerCase().includes(key)
    return matchStatus && matchKeyword
  })
  const time = (value) => (value ? new Date(String(value).slice(0, 10)).getTime() : 0)
  return list.slice().sort((a, b) => {
    if (sort.value === 'created') {
      return new Date(b.createTime || 0).getTime() - new Date(a.createTime || 0).getTime()
    }
    const diff = time(a.dueDate) - time(b.dueDate)
    return sort.value === 'dueDesc' ? -diff : diff
  })
})

const emptyDescription = computed(() =>
  accountStatus.value === 'ACTIVE' ? '暂无账期账单，使用账期结算后将自动生成' : '尚未开通企业账期'
)
const emptyActionText = computed(() => (accountStatus.value === 'ACTIVE' ? '返回账期中心' : '去申请授信'))

function pendingFor(bill) {
  const map = summary.value.pendingByBill || {}
  return Number(map[bill.id] || 0)
}
function payableFor(bill) {
  return Number(bill.outstandingAmount || 0) - pendingFor(bill)
}
function canRepay(bill) {
  return !['PAID', 'CANCELLED'].includes(bill.status) && payableFor(bill) > 0
}
function resetPage() {
  page.value = 1
}
function resetFilters() {
  activeStatus.value = 'all'
  keyword.value = ''
  sort.value = 'due'
  resetPage()
}
function goDetail(id) {
  router.push('/credit-term/bills/' + id)
}
function goOrder(orderNo) {
  if (orderNo) router.push('/orders/' + orderNo)
}
function emptyAction() {
  router.push(accountStatus.value === 'ACTIVE' ? '/credit-term' : '/credit-term/apply')
}
function onTableChange(pagination) {
  page.value = pagination?.current || 1
}

watch([activeStatus, keyword, sort], resetPage)

async function load() {
  loading.value = true
  loadError.value = false
  try {
    data.value = await creditTermApi.overview()
  } catch (e) {
    loadError.value = true
    data.value = null
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.sys{max-width:1100px;margin:0 auto}
.hero{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:18px;flex-wrap:wrap}
.eyebrow{font-size:11px;letter-spacing:.18em;color:var(--ll-primary)}
h1{margin:5px 0}
.hero p{margin:0;color:var(--ll-muted)}
.hero-actions{display:flex;gap:10px;flex-wrap:wrap}
.notice{margin-bottom:16px;border-radius:14px}
.stat-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin-bottom:16px}
.stat{padding:16px;border-radius:18px;background:rgba(255,255,255,.78);box-shadow:0 12px 32px rgba(19,35,58,.06);border-top:3px solid #c8d2e0;min-width:0}
.stat span{display:block;color:var(--ll-muted);font-size:12px}
.stat b{display:block;font-size:22px;margin:6px 0 2px;color:var(--ll-ink);overflow-wrap:anywhere}
.stat small{color:#98a2b3;font-size:12px}
.stat.danger{border-top-color:#d45645}
.stat.warn{border-top-color:#d79a35}
.filter-card,.card{border-radius:18px;margin-bottom:16px}
.status-tabs :deep(.ant-tabs-nav){margin-bottom:12px}
.toolbar{display:flex;gap:10px;flex-wrap:wrap;align-items:center}
.search{max-width:300px}
.sort{width:170px}
.order-link{color:var(--ll-primary);cursor:pointer;text-decoration:none;border-bottom:1px dashed rgba(22,119,255,.35)}
.order-link:hover{text-decoration:none;border-bottom-style:solid}
.pending{color:#b45309;font-weight:600}
.card :deep(.ant-table-tbody > tr > td){white-space:nowrap}
@media(max-width:900px){
  .stat-grid{grid-template-columns:repeat(2,1fr)}
}
@media(max-width:700px){
  .hero{align-items:flex-start;flex-direction:column;gap:10px}
  .hero-actions{width:100%}
  .stat-grid{grid-template-columns:1fr 1fr;gap:10px}
  .stat b{font-size:18px}
  .toolbar{align-items:stretch}
  .search,.sort{max-width:none;width:100%}
}
@media(max-width:420px){
  .stat-grid{grid-template-columns:1fr}
}
</style>

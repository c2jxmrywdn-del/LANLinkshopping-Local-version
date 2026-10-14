<template>
  <div class="sys">
    <div class="hero">
      <div class="hero-copy">
        <div class="eyebrow">ENTERPRISE CREDIT</div>
        <h1>账期管理</h1>
        <p>授信额度、账单到期、还款与对账统一管理。</p>
      </div>
      <div class="hero-actions">
        <a-button
          v-if="canApply && accountStatus !== 'ACTIVE'"
          type="primary"
          @click="$router.push('/credit-term/apply')"
        >
          {{ accountStatus === 'PENDING' ? '查看申请' : '申请账期' }}
        </a-button>
        <a-button @click="$router.push('/credit-term/bills')">账单中心</a-button>
      </div>
    </div>

    <a-alert
      v-if="accountStatus === 'NOT_APPLIED'"
      type="info"
      show-icon
      message="尚未开通企业账期"
      description="提交授信额度与账期申请，平台审核通过后即可在订单结算页使用。"
      class="notice"
    />
    <a-alert
      v-else-if="accountStatus === 'PENDING'"
      type="warning"
      show-icon
      message="授信审核中"
      description="平台正在核验企业信息与采购需求，审核通过后额度会自动生效。"
      class="notice"
    />
    <a-alert
      v-else-if="accountStatus === 'REJECTED'"
      type="error"
      show-icon
      message="本次申请未通过"
      :description="account?.reviewRemark || '可重新提交新的账期申请。'"
      class="notice"
    />

    <a-card v-if="accountStatus === 'ACTIVE'" class="credit-card" :bordered="false">
      <div class="credit-top">
        <div>
          <span>可用授信</span>
          <strong>¥{{ money(account.availableLimit) }}</strong>
          <small>总额度 ¥{{ money(account.creditLimit) }}</small>
        </div>
        <a-tag color="green">已生效</a-tag>
      </div>

      <div class="usage">
        <div class="usage-bar">
          <i :style="{ width: usagePercent + '%' }"></i>
        </div>
        <span>已用 ¥{{ money(account.usedLimit) }} · {{ usagePercent }}%</span>
      </div>

      <div class="metrics">
        <div><span>账期</span><b>{{ account.termDays }} 天</b></div>
        <div><span>待还金额</span><b>¥{{ money(outstanding) }}</b></div>
        <div><span>风险等级</span><b>{{ account.riskLevel || '标准' }}</b></div>
      </div>
    </a-card>

    <div v-if="accountStatus === 'ACTIVE'" class="grid">
      <a-card class="nav-card" @click="$router.push('/credit-term/bills')">
        <div class="num">01</div>
        <h3>应付账单</h3>
        <p>查看未结清、逾期及已结清账单</p>
        <span>进入账单中心 →</span>
      </a-card>
      <a-card class="nav-card" @click="$router.push('/credit-term/bills')">
        <div class="num">02</div>
        <h3>到期管理</h3>
        <p>按到期日排序，提前安排资金与还款</p>
        <span>查看到期计划 →</span>
      </a-card>
      <a-card class="nav-card" @click="$router.push('/credit-term/apply')">
        <div class="num">03</div>
        <h3>授信调整</h3>
        <p>业务规模变化后重新申请更匹配的额度</p>
        <span>管理授信申请 →</span>
      </a-card>
    </div>

    <a-card title="最近账单" class="logs" :bordered="false">
      <a-table
        :data-source="bills.slice(0, 6)"
        :pagination="false"
        row-key="id"
        :scroll="{ x: 720 }"
      >
        <a-table-column title="账单" data-index="id" key="id"></a-table-column>
        <a-table-column title="订单号" data-index="orderNo" key="orderNo"></a-table-column>
        <a-table-column title="账单金额" key="amount">
          <template #default="{ record }">
            ¥{{ money(record.amount) }}
          </template>
        </a-table-column>
        <a-table-column title="待还" key="outstandingAmount">
          <template #default="{ record }">
            ¥{{ money(record.outstandingAmount) }}
          </template>
        </a-table-column>
        <a-table-column title="到期日" data-index="dueDate" key="dueDate"></a-table-column>
        <a-table-column title="状态" key="status">
          <template #default="{ record }">
            <a-tag :color="billColor(record)">{{ billLabel(record) }}</a-tag>
          </template>
        </a-table-column>
        <a-table-column title="操作" key="op">
          <template #default="{ record }">
            <a-button
              type="link"
              @click="$router.push('/credit-term/bills/' + record.id)"
            >
              查看
            </a-button>
          </template>
        </a-table-column>
      </a-table>
      <a-empty v-if="!loading && !bills.length" description="暂无账期账单"></a-empty>
    </a-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { creditTermApi } from '../api'
import { useUserStore } from '../store/user'

const user = useUserStore()
const loading = ref(true)
const account = ref(null)
const accountStatus = ref('NOT_APPLIED')
const bills = ref([])
const outstanding = ref(0)

const canApply = computed(() => user.hasPerm('credit:apply'))

const usagePercent = computed(() => {
  const total = Number(account.value?.creditLimit || 0)
  const used = Number(account.value?.usedLimit || 0)
  return total ? Math.min(100, Math.round((used / total) * 100)) : 0
})

const money = (value) => Number(value || 0).toFixed(2)

const billLabel = (bill) => {
  if (bill.status === 'PAID') return '已结清'
  if (bill.status === 'CANCELLED') return '已取消'
  if (
    bill.status === 'OVERDUE' ||
    (bill.dueDate && bill.dueDate < new Date().toISOString().slice(0, 10))
  ) return '已逾期'
  return '待还'
}

const billColor = (bill) => {
  const label = billLabel(bill)
  return label === '已结清' ? 'green' : label === '已逾期' ? 'red' : 'orange'
}

onMounted(async () => {
  try {
    const data = await creditTermApi.overview()
    account.value = data?.account || null
    accountStatus.value = data?.status || 'NOT_APPLIED'
    bills.value = data?.bills || []
    outstanding.value = Number(data?.outstanding || 0)
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.sys{max-width:1040px;margin:0 auto}
.hero{display:flex;justify-content:space-between;gap:24px;align-items:center;margin-bottom:18px}
.hero-copy h1{margin:5px 0;font-size:30px}
.eyebrow{font-size:11px;letter-spacing:.18em;color:var(--ll-primary)}
.hero p{margin:0;color:var(--ll-muted)}
.hero-actions{display:flex;gap:10px}
.notice{margin-bottom:16px;border-radius:14px}
.credit-card{border-radius:22px;background:var(--ll-brand-hero-gradient);color:#fff;box-shadow:var(--ll-glass-shadow);margin-bottom:16px}
.credit-top{display:flex;justify-content:space-between;align-items:flex-start}
.credit-top span,.credit-top small{display:block;opacity:.72}
.credit-top strong{display:block;font-size:42px;margin:4px 0}
.usage{margin-top:20px}
.usage-bar{height:8px;border-radius:8px;background:rgba(255,255,255,.2);overflow:hidden}
.usage-bar i{display:block;height:100%;background:#f6d38a;border-radius:8px}
.usage>span{display:block;margin-top:6px;font-size:12px;opacity:.72}
.metrics{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin-top:18px}
.metrics div{padding:12px;border:1px solid rgba(255,255,255,.12);border-radius:12px;background:rgba(255,255,255,.06)}
.metrics span{display:block;opacity:.62;font-size:12px}
.metrics b{display:block;margin-top:4px}
.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:14px;margin-bottom:16px}
.nav-card,.logs{border-radius:18px}
.nav-card{cursor:pointer;transition:transform .2s,box-shadow .2s}
.nav-card:hover{transform:translateY(-3px);box-shadow:0 16px 40px rgba(19,35,58,.08)}
.num{font-size:11px;color:#9b2d20;letter-spacing:.14em}
.nav-card h3{margin:8px 0 4px}
.nav-card p,.nav-card span{font-size:12px;color:var(--ll-muted)}
.nav-card span{color:var(--ll-primary)}
@media(max-width:700px){
  .hero{align-items:flex-start;flex-direction:column}
  .grid{grid-template-columns:1fr}
  .metrics{grid-template-columns:1fr 1fr}
  .credit-top strong{font-size:34px}
}
</style>
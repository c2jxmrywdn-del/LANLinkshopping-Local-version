<template>
  <div class="sys">
    <div class="hero">
      <div class="hero-copy">
        <div class="eyebrow">ENTERPRISE CREDIT</div>
        <h1>账期管理</h1>
        <p>授信额度、账单到期、还款与对账统一管理。</p>
      </div>
      <div class="hero-actions">
        <a-tag v-if="status === 'ACTIVE'" color="green" class="state-tag">已生效</a-tag>
        <a-button v-if="status !== 'ACTIVE'" type="primary" @click="goApply">
          {{ status === 'PENDING' ? '查看申请' : status === 'REJECTED' ? '重新申请' : '申请授信' }}
        </a-button>
        <a-button v-if="status === 'ACTIVE'" type="primary" @click="$router.push('/credit-term/adjust')">
          调额申请
        </a-button>
        <a-button @click="$router.push('/credit-term/bills')">账单中心</a-button>
      </div>
    </div>

    <a-alert
      v-if="loadError"
      type="error"
      show-icon
      message="账期信息加载失败"
      description="请检查网络连接后重试，或稍后再访问。"
      class="notice"
    >
      <template #action><a-button size="small" @click="load">重试</a-button></template>
    </a-alert>
    <a-skeleton v-else-if="loading" active :paragraph="{ rows: 6 }" class="skeleton" />

    <template v-else>
      <template v-if="status === 'ACTIVE'">
        <a-card class="credit-card" :bordered="false">
          <div class="credit-top">
            <div>
              <span>可用授信</span>
              <strong>¥{{ money(account?.availableLimit) }}</strong>
              <small>总额度 ¥{{ money(account?.creditLimit) }} · 已用 ¥{{ money(account?.usedLimit) }}</small>
            </div>
            <div class="credit-side">
              <div class="term-pill">账期 {{ account?.termDays || 0 }} 天</div>
              <a-tag color="green">正常使用中</a-tag>
            </div>
          </div>

          <div class="usage">
            <div class="usage-bar">
              <i :style="{ width: usagePercent + '%' }"></i>
            </div>
            <span>额度使用率 {{ usagePercent }}% · 风险等级 {{ account?.riskLevel || '标准' }}</span>
          </div>

          <div class="metrics">
            <div><span>待还金额</span><b>¥{{ money(summary.outstanding) }}</b></div>
            <div><span>已逾期</span><b>¥{{ money(summary.overdueAmount) }}</b></div>
            <div><span>7 天内到期</span><b>¥{{ money(summary.dueSoonAmount) }}</b></div>
            <div><span>待确认转账</span><b>¥{{ money(summary.pendingTransferAmount) }}</b></div>
          </div>
        </a-card>

        <a-alert
          v-if="summary.pendingTransferAmount > 0"
          type="warning"
          show-icon
          class="notice"
          message="有对公转账登记待财务核销"
          :description="`待确认金额 ¥${money(summary.pendingTransferAmount)}，核销后自动冲减对应账单并恢复授信额度。`"
        >
          <template #action>
            <a-button size="small" @click="$router.push('/credit-term/bills?status=unpaid')">查看账单</a-button>
          </template>
        </a-alert>

        <a-alert
          v-if="pendingAdjustment"
          type="info"
          show-icon
          class="notice"
          message="调额申请审核中"
          :description="`申请额度 ¥${money(pendingAdjustment.requestedLimit)} / ${pendingAdjustment.termDays} 天，审核期间原额度继续可用。`"
        >
          <template #action>
            <a-button size="small" @click="$router.push('/credit-term/adjust')">查看申请</a-button>
          </template>
        </a-alert>

        <div class="stat-grid">
          <div class="stat">
            <span>待还总额</span>
            <b>¥{{ money(summary.outstanding) }}</b>
            <small>{{ unpaidCount }} 笔未结清</small>
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
            <span>累计已还</span>
            <b>¥{{ money(summary.paidTotal) }}</b>
            <small>共 {{ summary.billCount || 0 }} 笔账单</small>
          </div>
        </div>

        <div class="grid">
          <a-card class="nav-card" @click="$router.push('/credit-term/bills')">
            <div class="num">01</div>
            <h3>应付账单</h3>
            <p>查看未结清、逾期及已结清账单</p>
            <span>进入账单中心 →</span>
          </a-card>
          <a-card class="nav-card" @click="$router.push('/credit-term/bills?status=unpaid&sort=due')">
            <div class="num">02</div>
            <h3>到期管理</h3>
            <p>按到期日排序，提前安排资金与还款</p>
            <span>查看到期计划 →</span>
          </a-card>
          <a-card class="nav-card" @click="$router.push('/credit-term/adjust')">
            <div class="num">03</div>
            <h3>授信调整</h3>
            <p>提交调额申请，审核期间额度继续可用</p>
            <span>管理调额申请 →</span>
          </a-card>
          <a-card class="nav-card" @click="scrollToRules">
            <div class="num">04</div>
            <h3>账期规则</h3>
            <p>了解申请、使用与还款的完整规则</p>
            <span>查看规则 →</span>
          </a-card>
        </div>

        <a-card class="logs" :bordered="false">
          <template #title>最近账单</template>
          <template #extra>
            <a-button type="link" size="small" @click="$router.push('/credit-term/bills')">查看全部</a-button>
          </template>
          <a-table :data-source="bills.slice(0, 6)" :pagination="false" row-key="id" :scroll="{ x: 880 }">
            <a-table-column title="账单" data-index="id" key="id" width="90" />
            <a-table-column title="订单号" data-index="orderNo" key="orderNo" width="170" />
            <a-table-column title="账单金额" key="amount" width="120">
              <template #default="{ record }">¥{{ money(record.amount) }}</template>
            </a-table-column>
            <a-table-column title="待还" key="outstandingAmount" width="120">
              <template #default="{ record }">¥{{ money(record.outstandingAmount) }}</template>
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
            <a-table-column title="操作" key="op" width="90">
              <template #default="{ record }">
                <a-button type="link" size="small" @click="$router.push('/credit-term/bills/' + record.id)">查看</a-button>
              </template>
            </a-table-column>
          </a-table>
          <a-empty v-if="!bills.length" description="暂无账期账单">
            <template #description><span>暂无账期账单，使用账期结算后将自动生成</span></template>
          </a-empty>
        </a-card>
      </template>

      <template v-else>
        <a-alert
          v-if="status === 'NOT_APPLIED'"
          type="info"
          show-icon
          message="尚未开通企业账期"
          description="提交授信额度与账期申请，平台审核通过后即可在订单结算页使用。"
          class="notice"
        />
        <a-alert
          v-else-if="status === 'PENDING'"
          type="warning"
          show-icon
          message="授信审核中"
          description="平台正在核验企业信息与采购需求，审核通过后额度会自动生效。"
          class="notice"
        />
        <a-alert
          v-else-if="status === 'REJECTED'"
          type="error"
          show-icon
          message="本次申请未通过"
          :description="account?.reviewRemark || '可重新提交新的账期申请。'"
          class="notice"
        />

        <a-card v-if="account" class="apply-card" :bordered="false" title="申请信息">
          <a-descriptions bordered :column="{ xs: 1, sm: 2, md: 2 }">
            <a-descriptions-item label="申请额度">¥{{ money(account.requestedLimit) }}</a-descriptions-item>
            <a-descriptions-item label="申请账期">{{ account.termDays }} 天</a-descriptions-item>
            <a-descriptions-item label="提交时间">{{ prettyTime(account.applyTime) }}</a-descriptions-item>
            <a-descriptions-item label="审核状态">{{ statusLabel(account.status) }}</a-descriptions-item>
            <a-descriptions-item label="主要用途" :span="2">{{ account.purpose || '—' }}</a-descriptions-item>
            <a-descriptions-item v-if="account.reviewRemark" label="审核备注" :span="2">
              {{ account.reviewRemark }}
            </a-descriptions-item>
          </a-descriptions>
          <div class="apply-actions">
            <a-button @click="$router.push('/wallet')">返回钱包</a-button>
            <a-button type="primary" @click="goApply">
              {{ status === 'PENDING' ? '查看 / 重新提交' : '重新申请' }}
            </a-button>
          </div>
        </a-card>

        <a-card class="steps-card" :bordered="false" title="如何开通企业账期">
          <a-steps :current="status === 'PENDING' ? 1 : 0" :items="openSteps" />
        </a-card>
      </template>

      <a-card id="credit-rules" class="rules-card" :bordered="false" title="账期规则">
        <div class="rule-grid">
          <div>
            <h4>申请与审核</h4>
            <p>按采购规模提交额度与账期，平台综合企业资料、采购记录与履约情况审核。</p>
          </div>
          <div>
            <h4>使用额度</h4>
            <p>审核通过后，结算页选择「账期/尾款」即可占用授信，账单按账期自动生成。</p>
          </div>
          <div>
            <h4>到期还款</h4>
            <p>支持企业钱包实时还款与对公转账登记；转账需平台财务核销后入账。</p>
          </div>
          <div>
            <h4>额度恢复</h4>
            <p>还款入账后自动恢复可用授信；订单取消或退款会释放未使用额度。</p>
          </div>
          <div>
            <h4>额度调整</h4>
            <p>已生效账户可提交调额申请，审核期间原额度继续可用；审核通过后按新额度执行。</p>
          </div>
          <div>
            <h4>逾期提醒</h4>
            <p>请在到期日前完成还款，逾期账单会标记风险并影响后续授信评估。</p>
          </div>
        </div>
      </a-card>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { creditTermApi } from '../api'
import { billColor, billLabel, money, prettyTime, remainingText } from '../utils/creditTerm'

const router = useRouter()
const loading = ref(true)
const loadError = ref(false)
const data = ref(null)

const account = computed(() => data.value?.account || null)
const status = computed(() => data.value?.status || 'NOT_APPLIED')
const bills = computed(() => data.value?.bills || [])
const summary = computed(() => data.value?.summary || {})
const pendingAdjustment = computed(() => data.value?.pendingAdjustment || null)

const usagePercent = computed(() => {
  const total = Number(account.value?.creditLimit || 0)
  const used = Number(account.value?.usedLimit || 0)
  return total ? Math.min(100, Math.round((used / total) * 100)) : 0
})

const unpaidCount = computed(() => bills.value.filter((b) => !['PAID', 'CANCELLED'].includes(b.status)).length)

const openSteps = [
  { title: '提交申请', description: '填写额度、账期与用途' },
  { title: '平台审核', description: '核验企业资料与采购需求' },
  { title: '生效使用', description: '结算页选择账期支付' },
  { title: '到期还款', description: '钱包还款或转账核销' }
]

const statusLabel = (value) =>
  value === 'ACTIVE' ? '已生效' : value === 'PENDING' ? '待审核' : value === 'REJECTED' ? '已驳回' : '未申请'

function goApply() {
  router.push('/credit-term/apply')
}
function scrollToRules() {
  const el = document.getElementById('credit-rules')
  if (el) el.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

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
.sys{max-width:1040px;margin:0 auto}
.hero{display:flex;justify-content:space-between;gap:24px;align-items:center;margin-bottom:18px}
.hero-copy h1{margin:5px 0;font-size:30px}
.eyebrow{font-size:11px;letter-spacing:.18em;color:var(--ll-primary)}
.hero p{margin:0;color:var(--ll-muted)}
.hero-actions{display:flex;gap:10px;align-items:center;flex-wrap:wrap}
.state-tag{margin-right:2px}
.notice{margin-bottom:16px;border-radius:14px}
.skeleton{margin-top:10px}
.credit-card{border-radius:22px;background:var(--ll-brand-hero-gradient);color:#fff;box-shadow:var(--ll-glass-shadow);margin-bottom:16px}
.credit-top{display:flex;justify-content:space-between;align-items:flex-start;gap:16px}
.credit-top span,.credit-top small{display:block;opacity:.72}
.credit-top strong{display:block;font-size:42px;margin:4px 0}
.credit-side{display:flex;flex-direction:column;align-items:flex-end;gap:10px}
.term-pill{padding:6px 12px;border:1px solid rgba(255,255,255,.22);border-radius:999px;font-size:12px;background:rgba(255,255,255,.08)}
.usage{margin-top:20px}
.usage-bar{height:8px;border-radius:8px;background:rgba(255,255,255,.2);overflow:hidden}
.usage-bar i{display:block;height:100%;background:#f6d38a;border-radius:8px;transition:width .4s ease}
.usage>span{display:block;margin-top:6px;font-size:12px;opacity:.72}
.metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-top:18px}
.metrics div{padding:12px;border:1px solid rgba(255,255,255,.12);border-radius:12px;background:rgba(255,255,255,.06);min-width:0}
.metrics span{display:block;opacity:.62;font-size:12px}
.metrics b{display:block;margin-top:4px;font-size:16px}
.stat-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin-bottom:16px}
.stat{padding:16px;border-radius:18px;background:rgba(255,255,255,.78);box-shadow:0 12px 32px rgba(19,35,58,.06);border-top:3px solid #c8d2e0}
.stat span{display:block;color:var(--ll-muted);font-size:12px}
.stat b{display:block;font-size:22px;margin:6px 0 2px;color:var(--ll-ink);overflow-wrap:anywhere}
.stat small{color:#98a2b3;font-size:12px}
.stat.danger{border-top-color:#d45645}
.stat.warn{border-top-color:#d79a35}
.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin-bottom:16px}
.nav-card,.logs,.apply-card,.steps-card,.rules-card{border-radius:18px}
.nav-card{cursor:pointer;transition:transform .2s,box-shadow .2s}
.nav-card:hover{transform:translateY(-3px);box-shadow:0 16px 40px rgba(19,35,58,.08)}
.num{font-size:11px;color:#9b2d20;letter-spacing:.14em}
.nav-card h3{margin:8px 0 4px}
.nav-card p,.nav-card span{font-size:12px;color:var(--ll-muted)}
.nav-card span{color:var(--ll-primary)}
.apply-card,.steps-card,.rules-card{margin-bottom:16px}
.apply-actions{display:flex;justify-content:flex-end;gap:10px;margin-top:18px}
.rule-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:16px}
.rule-grid h4{margin:0 0 6px;font-size:14px}
.rule-grid p{margin:0;color:var(--ll-muted);font-size:12px;line-height:1.7}
@media(max-width:900px){
  .grid,.stat-grid{grid-template-columns:repeat(2,1fr)}
  .rule-grid{grid-template-columns:repeat(2,1fr)}
}
@media(max-width:700px){
  .hero{align-items:flex-start;flex-direction:column;gap:12px}
  .hero-actions{width:100%}
  .grid,.stat-grid,.rule-grid{grid-template-columns:1fr 1fr}
  .metrics{grid-template-columns:1fr 1fr}
  .credit-top{flex-direction:column}
  .credit-side{align-items:flex-start;flex-direction:row}
  .credit-top strong{font-size:32px}
  .stat b{font-size:18px}
  .hero-actions :deep(.ant-btn){flex:1 1 0;min-width:0}
}
@media(max-width:480px){
  .state-tag{flex:1 1 100%;width:max-content}
}
@media(max-width:480px){
  .grid,.stat-grid,.rule-grid{grid-template-columns:1fr}
  .credit-top strong{font-size:28px}
}
</style>

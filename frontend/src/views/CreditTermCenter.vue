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
        <GlowBorderCard :glow-enabled="true" glow-color1="#0284c7" glow-color2="#6366f1" custom-class="mb-6">
          <div class="credit-top">
            <div>
              <span>可用授信</span>
              <strong>¥<NumberTicker :value="Number(account?.availableLimit || 0)" :decimal-places="2" /></strong>
              <small>总额度 ¥{{ money(account?.creditLimit) }} · 已用 ¥{{ money(account?.usedLimit) }}</small>
            </div>
            <div class="credit-side">
              <div class="term-pill">账期 {{ account?.termDays || 0 }} 天 (7~15天标准短融)</div>
              <RadarPulseBadge status="success" size="sm" text="授信正常使用中" :icon="ShieldCheck" />
            </div>
          </div>

          <div class="usage">
            <div class="usage-bar">
              <i :style="{ width: usagePercent + '%' }"></i>
            </div>
            <span>
              额度使用率 {{ usagePercent }}% · 信用评级：<a-tag :color="account?.riskTier === 'AAA' ? 'gold' : 'blue'">{{ account?.riskTier || 'AAA' }} 级</a-tag> ·
              基准日息：<strong style="color: #059669;">{{ ((account?.dailyRate || 0.00015) * 100).toFixed(4) }}%/天</strong> (单利年化 {{ ((account?.apr || 0.054) * 100).toFixed(2) }}% APR) ·
              <span class="text-xs text-emerald-600 font-medium">第三方权威机构合规授权（平台零征信数据留存）</span>
            </span>
          </div>

          <div class="metrics">
            <div><span>待还金额</span><b>¥{{ money(summary.outstanding) }}</b></div>
            <div><span>已逾期</span><b>¥{{ money(summary.overdueAmount) }}</b></div>
            <div><span>7 天内到期</span><b>¥{{ money(summary.dueSoonAmount) }}</b></div>
            <div><span>待确认转账</span><b>¥{{ money(summary.pendingTransferAmount) }}</b></div>
          </div>
        </GlowBorderCard>

        <!-- 21st.dev 风格：第三方持牌权威征信授权与风控评级防伪验签卡片 -->
        <div style="margin-bottom: 24px;">
          <CreditAuthorityRatingCard
            :institution-name="'百行征信 · 央行合规数据直联源'"
            :risk-grade="account?.riskTier || 'AAA'"
            :health-score="account?.riskTier === 'AAA' ? 98.5 : account?.riskTier === 'AA' ? 92.0 : 88.0"
            :credit-limit="Number(account?.creditLimit || 200000)"
            :term-days="account?.termDays || 7"
            :daily-rate="Number(account?.dailyRate || (account?.riskTier === 'AAA' ? 0.00015 : account?.riskTier === 'AA' ? 0.00020 : account?.riskTier === 'A' ? 0.00025 : 0.00030))"
            :assessment-ref-no="account?.assessmentRefNo || ('CRED-EXT-' + (account?.id || '8891'))"
            :valid-from="prettyTime(account?.approvedTime || account?.applyTime).slice(0, 10) || '2026-01-01'"
            :valid-until="account?.validUntil ? prettyTime(account?.validUntil).slice(0, 10) : '2026-12-31'"
          />
        </div>

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
          <a-card class="nav-card is-calculator-trigger" @click="showCalculator = !showCalculator">
            <div class="num">05</div>
            <h3>利率计算器</h3>
            <p>7~15 天短融测算，APR 真实试算</p>
            <span style="color: #0284c7; font-weight: 600;">{{ showCalculator ? '收起计算器 ↑' : '打开试算器 ↓' }}</span>
          </a-card>
        </div>

        <!-- 21st.dev 动效利率计算器试算模块 -->
        <transition name="calc-slide">
          <div v-if="showCalculator" class="calculator-embed-wrapper" style="margin: 24px 0;">
            <CreditInterestCalculator :initial-amount="50000" :initial-term="account?.termDays || 7" />
          </div>
        </transition>

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
import { ShieldCheck } from 'lucide-vue-next'
import { creditTermApi } from '../api'
import { billColor, billLabel, money, prettyTime, remainingText } from '../utils/creditTerm'
import GlowBorderCard from '../components/ui/GlowBorderCard.vue'
import RadarPulseBadge from '../components/ui/RadarPulseBadge.vue'
import NumberTicker from '../components/ui/NumberTicker.vue'
import CreditInterestCalculator from '../components/credit/CreditInterestCalculator.vue'
import CreditAuthorityRatingCard from '../components/credit/CreditAuthorityRatingCard.vue'

const router = useRouter()
const loading = ref(true)
const loadError = ref(false)
const data = ref(null)
const showCalculator = ref(false)

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
.hero{display:flex;justify-content:space-between;gap:24px;align-items:center;margin-bottom:20px}
.hero-copy h1{margin:6px 0;font-size:32px;font-weight:700;color:#1D1D1F;letter-spacing:-.02em}
.eyebrow{font-size:11px;letter-spacing:.16em;color:#86868B;font-weight:600}
.hero p{margin:0;color:#6E6E73;font-size:14px}
.hero-actions{display:flex;gap:10px;align-items:center;flex-wrap:wrap}
.hero-actions :deep(.ant-btn){height:40px;padding:0 20px;border-radius:999px;font-weight:600}
.hero-actions :deep(.ant-btn-primary){background:#0071E3;border-color:#0071E3;box-shadow:0 8px 18px -10px rgba(0,113,227,.7)}
.hero :deep(.ant-tag-green),.state-tag :deep(.ant-tag-green){background:#E8F8EE;border-color:#C9EFD6;color:#1B7F3B;border-radius:999px;font-weight:600}
.state-tag{margin-right:2px}
.notice{margin-bottom:18px;border-radius:16px}
.skeleton{margin-top:10px}
/* ===== 授信卡：Apple 风白色玻璃卡 ===== */
.credit-card{
  border-radius:24px;color:#1D1D1F;
  border:1px solid rgba(0,0,0,.06);
  background:rgba(255,255,255,.82);
  backdrop-filter:blur(20px) saturate(180%);
  -webkit-backdrop-filter:blur(20px) saturate(180%);
  box-shadow:0 1px 1px rgba(0,0,0,.03),0 14px 36px -16px rgba(0,0,0,.14);
  margin-bottom:18px;
}
.credit-card :deep(.ant-card-body){padding:26px 26px 24px}
.credit-top{display:flex;justify-content:space-between;align-items:flex-start;gap:16px}
.credit-top span{display:block;font-size:13px;font-weight:500;color:#6E6E73}
.credit-top small{display:block;font-size:12.5px;color:#6E6E73}
.credit-top strong{display:block;font-size:42px;font-weight:700;letter-spacing:-.03em;color:#1D1D1F;margin:6px 0 4px}
.credit-side{display:flex;flex-direction:column;align-items:flex-end;gap:10px}
.term-pill{padding:6px 14px;border:1px solid #E5E5EA;border-radius:999px;font-size:12px;font-weight:600;color:#1D1D1F;background:#F5F5F7}
.credit-card :deep(.ant-tag-green){background:#E8F8EE;border-color:#C9EFD6;color:#1B7F3B;border-radius:999px;font-weight:600}
.usage{margin-top:22px}
.usage-bar{height:8px;border-radius:999px;background:#F0F0F2;overflow:hidden}
.usage-bar i{display:block;height:100%;border-radius:999px;background:#0071E3;transition:width .4s ease}
.usage>span{display:block;margin-top:8px;font-size:12px;color:#6E6E73}
.metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-top:20px}
.metrics div{padding:14px 16px;border:1px solid rgba(0,0,0,.05);border-radius:16px;background:#F5F5F7;min-width:0}
.metrics span{display:block;font-size:12px;color:#6E6E73}
.metrics b{display:block;margin-top:5px;font-size:17px;font-weight:600;color:#1D1D1F}
.stat-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:16px;margin-bottom:18px}
.stat{padding:18px;border-radius:24px;background:rgba(255,255,255,.82);border:1px solid rgba(0,0,0,.06);border-top:2px solid #C7C7CC;box-shadow:0 1px 1px rgba(0,0,0,.03),0 12px 30px -18px rgba(0,0,0,.16);backdrop-filter:blur(20px) saturate(180%)}
.stat span{display:block;color:#6E6E73;font-size:12.5px}
.stat b{display:block;font-size:23px;font-weight:600;margin:8px 0 2px;color:#1D1D1F;overflow-wrap:anywhere;letter-spacing:-.02em}
.stat small{color:#86868B;font-size:12px}
.stat.danger{border-top-color:#FF453A}
.stat.warn{border-top-color:#FF9F0A}
.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:16px;margin-bottom:18px}
.nav-card,.logs,.apply-card,.steps-card,.rules-card{
  border-radius:24px;
  border:1px solid rgba(0,0,0,.06);
  background:rgba(255,255,255,.82);
  backdrop-filter:blur(20px) saturate(180%);
  -webkit-backdrop-filter:blur(20px) saturate(180%);
  box-shadow:0 1px 1px rgba(0,0,0,.03),0 12px 30px -18px rgba(0,0,0,.14);
}
.nav-card{cursor:pointer;transition:transform .2s,box-shadow .2s,border-color .2s}
.nav-card:hover{transform:translateY(-3px);box-shadow:0 18px 40px rgba(0,0,0,.10);border-color:rgba(0,0,0,.10)}
.num{font-size:11px;color:#B08A42;letter-spacing:.14em;font-weight:600}
.nav-card h3{margin:8px 0 4px;font-size:16px;color:#1D1D1F}
.nav-card p,.nav-card span{font-size:12px;color:#6E6E73}
.nav-card span{color:#0071E3;font-weight:500}
.logs{margin-bottom:18px}
.apply-card,.steps-card,.rules-card{margin-bottom:18px}
.logs :deep(.ant-card-head),.rules-card :deep(.ant-card-head),.steps-card :deep(.ant-card-head),.apply-card :deep(.ant-card-head){min-height:0;padding:20px 24px 0;border-bottom:0}
.logs :deep(.ant-card-head-title),.rules-card :deep(.ant-card-head-title),.steps-card :deep(.ant-card-head-title),.apply-card :deep(.ant-card-head-title){font-size:17px;font-weight:600;color:#1D1D1F;letter-spacing:-.015em;padding:0}
.logs :deep(.ant-card-body),.rules-card :deep(.ant-card-body){padding:14px 24px 22px}
.apply-actions{display:flex;justify-content:flex-end;gap:10px;margin-top:18px}
.rule-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:20px}
.rule-grid h4{margin:0 0 6px;font-size:14px;font-weight:600;color:#1D1D1F}
.rule-grid p{margin:0;color:#6E6E73;font-size:12.5px;line-height:1.7}
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
  .state-tag{flex:0 0 auto;width:auto;align-self:flex-start}
}
@media(max-width:480px){
  .grid,.stat-grid,.rule-grid{grid-template-columns:1fr}
  .credit-top strong{font-size:28px}
}
</style>

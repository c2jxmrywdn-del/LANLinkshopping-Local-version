<template>
  <div class="fin-page">
    <section class="hero">
      <div class="hero-copy">
        <div class="eyebrow"><span class="dot"></span> 商户中心</div>
        <h1>资金结算</h1>
        <p>本店商品的成交收入、退款与结算明细，全部透明可查。</p>
      </div>
      <div class="hero-actions">
        <a-button size="large" class="pill" @click="load"><RefreshCw :size="14" class="inline mr-1" /> 刷新数据</a-button>
        <a-button size="large" type="primary" class="pill" @click="showWithdrawNotice">
          <ArrowDownRight :size="15" class="inline mr-1" /> 申请提现
        </a-button>
      </div>
    </section>

    <!-- 资金总览 (21st.dev NumberTicker 弹簧数字动效) -->
    <section class="kpi-grid">
      <div class="kpi-card main">
        <div class="kpi-top"><span>累计收入</span><span class="kpi-ico"><Wallet :size="15" class="text-amber-500" /></span></div>
        <div class="kpi-value">¥<NumberTicker :value="Number(f.totalIncome) || 0" :decimal-places="2" /></div>
        <div class="kpi-foot">历史已支付订单的本店金额</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-top"><span>本月收入</span><span class="kpi-ico"><TrendingUp :size="15" class="text-emerald-500" /></span></div>
        <div class="kpi-value">¥<NumberTicker :value="Number(f.monthIncome) || 0" :decimal-places="2" /></div>
        <div class="kpi-foot">近 30 天 · {{ f.monthOrders || 0 }} 笔订单</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-top"><span>累计退款</span><span class="kpi-ico"><RotateCcw :size="15" class="text-rose-500" /></span></div>
        <div class="kpi-value refund">¥<NumberTicker :value="Number(f.totalRefund) || 0" :decimal-places="2" /></div>
        <div class="kpi-foot">已全额退款订单的本店金额</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-top"><span>净收入</span><span class="kpi-ico"><Coins :size="15" class="text-indigo-500" /></span></div>
        <div class="kpi-value">¥<NumberTicker :value="Number(f.netIncome) || 0" :decimal-places="2" /></div>
        <div class="kpi-foot">累计收入 − 累计退款</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-top"><span>待结算</span><span class="kpi-ico"><Clock :size="15" class="text-sky-500" /></span></div>
        <div class="kpi-value">¥<NumberTicker :value="Number(f.pendingSettle) || 0" :decimal-places="2" /></div>
        <div class="kpi-foot">已支付、履约中的订单金额</div>
      </div>
    </section>

    <section class="grid-main">
      <!-- 收入趋势 -->
      <a-card :bordered="false" class="panel">
        <template #title>
          <div class="panel-title">
            <div><strong>收入趋势</strong><small>近 30 天按日结算金额</small></div>
            <span class="period">30D</span>
          </div>
        </template>
        <div v-if="hasTrend" class="chart">
          <div class="grid-line l1"></div><div class="grid-line l2"></div><div class="grid-line l3"></div>
          <div class="bars">
            <div v-for="d in f.trend" :key="d.date" class="bar-wrap"
                 :title="`${d.date}  ${money(d.amount)} 元 / ${d.orders} 单`">
              <div class="bar" :style="{ height: barHeight(d) }"></div>
            </div>
          </div>
        </div>
        <a-empty v-else :image="aEmpty.PRESENTED_IMAGE_SIMPLE" description="近 30 天暂无收入" />
        <div class="axis"><span>{{ f.trend?.[0]?.date || '—' }}</span><span>{{ f.trend?.[f.trend.length - 1]?.date || '—' }}</span></div>
      </a-card>

      <!-- 结算说明 -->
      <a-card :bordered="false" class="panel">
        <template #title>
          <div class="panel-title"><div><strong>结算规则</strong><small>平台统一结算口径</small></div></div>
        </template>
        <div class="rule-list">
          <div class="rule-row"><span class="rule-ico">1</span><div><b>结算口径</b><p>按订单明细中本店商品的小计金额结算，不含平台运费与优惠分摊。</p></div></div>
          <div class="rule-row"><span class="rule-ico">2</span><div><b>结算时点</b><p>订单完成（买家签收）后计入可结算余额；退款订单自动扣减。</p></div></div>
          <div class="rule-row"><span class="rule-ico">3</span><div><b>对账凭证</b><p>每笔支付均有支付渠道流水号，可在订单详情中核对。</p></div></div>
          <div class="rule-row"><span class="rule-ico">4</span><div><b>税务上报</b><p>月度完税证明请在 <a class="bridge-link" @click="$router.push('/merchant/center/store')">店铺设置 → 资质证照</a> 中上传。</p></div></div>
        </div>
      </a-card>
    </section>

    <!-- 企业对公收款账户看板 -->
    <a-card :bordered="false" class="panel bank-account-panel">
      <template #title>
        <div class="panel-title">
          <div>
            <strong>企业对公收款账户</strong>
            <small>用于接收买家对公转账电汇款项及平台资金周期结算</small>
          </div>
          <a-button type="default" size="small" class="edit-bank-btn" @click="openEditBank">
            <Edit3 :size="13" class="mr-1" /> 更新对公账户
          </a-button>
        </div>
      </template>
      <MerchantCorporateBankCard :bank-info="f.receivingAccount || {}" />
    </a-card>

    <!-- 对公账户编辑模态框 -->
    <a-modal
      v-model:open="editModalVisible"
      title="更新企业对公收款账户"
      :confirm-loading="submittingBank"
      ok-text="确认更新"
      cancel-text="取消"
      @ok="handleUpdateBank"
    >
      <div class="bank-edit-form">
        <p class="edit-tip">对公账户信息经 AES-256-GCM 工业级加密存储，更新后将作为买家对公电汇及平台结算汇款凭据。</p>
        <a-form layout="vertical">
          <a-form-item label="开户银行全称（含支行）" required>
            <a-input
              v-model:value="bankForm.bankName"
              placeholder="如：中国工商银行股份有限公司深圳科技园支行"
            />
          </a-form-item>
          <a-form-item label="对公银行账号" required>
            <a-input
              v-model:value="bankForm.bankAccount"
              placeholder="请输入 8~35 位数字或字母银行账号"
            />
          </a-form-item>
        </a-form>
      </div>
    </a-modal>

    <!-- 结算明细 -->
    <a-card :bordered="false" class="panel">
      <template #title>
        <div class="panel-title">
          <div><strong>结算明细</strong><small>最近已支付 / 已退款订单</small></div>
          <a-button size="small" class="pill export-btn" @click="exportSettlementsCsv" :disabled="!f.settlements || f.settlements.length === 0">
            <Download :size="13" class="mr-1 inline" /> 导出对账单 (CSV)
          </a-button>
        </div>
      </template>
      <a-table :data-source="f.settlements || []" :columns="cols" row-key="orderNo" :loading="loading"
               :scroll="{ x: 920 }"
               :pagination="{ pageSize: 10, showSizeChanger: false }" class="mc-table">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'orderNo'">
            <a class="mono" @click="$router.push(`/merchant/center/orders/${record.orderNo}`)">{{ record.orderNo }}</a>
          </template>
          <template v-else-if="column.key === 'myAmount'">¥{{ money(record.myAmount) }}</template>
          <template v-else-if="column.key === 'channel'">{{ PAY_CHANNEL[record.payChannel] || record.payChannel || '—' }}</template>
          <template v-else-if="column.key === 'status'">
            <a-tag v-if="Number(record.orderStatus) === 2" color="green">已完成</a-tag>
            <a-tag v-else-if="Number(record.orderStatus) === 4" color="cyan">部分发货</a-tag>
            <a-tag v-else-if="Number(record.orderStatus) === 5 || record.refundStatus === 'success' || Number(record.payStatus) === 2" color="purple">已退款</a-tag>
            <a-tag v-else color="blue">履约中</a-tag>
          </template>
          <template v-else-if="column.key === 'payTime'">{{ shortTime(record.payTime) }}</template>
        </template>
      </a-table>
    </a-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Empty, message } from 'ant-design-vue'
import {
  Edit3,
  Wallet,
  TrendingUp,
  RotateCcw,
  Coins,
  Clock,
  RefreshCw,
  ArrowDownRight,
  Download
} from 'lucide-vue-next'
import { merchantCenterApi } from '../../api'
import NumberTicker from '../../components/ui/NumberTicker.vue'
import MerchantCorporateBankCard from '../../components/payment/MerchantCorporateBankCard.vue'
import { PAY_CHANNEL, fmtMoney } from '../../utils/merchantStatus'

const aEmpty = Empty
const loading = ref(true)
const f = ref({})

const editModalVisible = ref(false)
const bankForm = ref({ bankName: '', bankAccount: '' })
const submittingBank = ref(false)

// 提现功能未上线：真实提现端点待定前，入口只做诚实告知，不做任何资金操作
function showWithdrawNotice() {
  message.info('提现功能即将开放，请联系您的客户经理协助处理资金结算')
}

function exportSettlementsCsv() {
  const list = f.value.settlements || []
  if (!list.length) {
    message.warning('暂无结算明细可导出')
    return
  }
  const headers = ['订单号', '本店金额(元)', '商品种数', '支付渠道', '结算状态', '支付时间']
  const statusMap = { '0': '待发货', '1': '已发货', '2': '已完成', '3': '已取消', '4': '部分发货', '5': '已退款' }
  const rows = list.map(r => {
    const statusText = (r.refundStatus === 'success' || Number(r.payStatus) === 2 || Number(r.orderStatus) === 5)
      ? '已退款'
      : (statusMap[String(r.orderStatus)] || '履约中')
    const channelText = PAY_CHANNEL[r.payChannel] || r.payChannel || '—'
    return [
      `\t${r.orderNo}`,
      fmtMoney(r.myAmount),
      r.myKinds ?? 1,
      channelText,
      statusText,
      r.payTime ? r.payTime.replace('T', ' ').slice(0, 19) : ''
    ]
  })

  const csvContent = '\uFEFF' + [headers.join(','), ...rows.map(e => e.map(val => `"${String(val).replace(/"/g, '""')}"`).join(','))].join('\r\n')
  const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.setAttribute('href', url)
  link.setAttribute('download', `商户资金结算对账单_${new Date().toISOString().slice(0, 10)}.csv`)
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
  message.success('对账单 CSV 文件已成功导出')
}

function openEditBank() {
  bankForm.value = {
    bankName: f.value.receivingAccount?.bankName || '',
    bankAccount: ''
  }
  editModalVisible.value = true
}

async function handleUpdateBank() {
  if (!bankForm.value.bankName || bankForm.value.bankName.trim().length < 4) {
    message.warning('开户银行全称需至少 4 个字符')
    return
  }
  if (!bankForm.value.bankAccount || bankForm.value.bankAccount.trim().length < 8) {
    message.warning('对公银行账号需至少 8 位数字或字符')
    return
  }
  submittingBank.value = true
  try {
    await merchantCenterApi.updateBankAccount({
      bankName: bankForm.value.bankName.trim(),
      bankAccount: bankForm.value.bankAccount.trim()
    })
    message.success('企业对公收款账户已更新')
    editModalVisible.value = false
    await load()
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    submittingBank.value = false
  }
}

const money = fmtMoney
function shortTime(t) { return (t || '').replace('T', ' ').slice(0, 16) }

const hasTrend = computed(() => (f.value.trend || []).some(d => Number(d.amount || 0) > 0))
const cols = [
  { title: '订单号', key: 'orderNo', width: 178 },
  { title: '本店金额', key: 'myAmount', width: 120 },
  { title: '商品种数', dataIndex: 'myKinds', width: 96 },
  { title: '支付渠道', key: 'channel', width: 104 },
  { title: '结算状态', key: 'status', width: 100 },
  { title: '支付时间', key: 'payTime', width: 150 }
]

function barHeight(d) {
  const max = Math.max(...(f.value.trend || []).map(x => Number(x.amount || 0)), 0.01)
  return Math.max(5, (Number(d.amount || 0) / max) * 100) + '%'
}

async function load() {
  loading.value = true
  try { f.value = (await merchantCenterApi.finance()) || {} } catch (e) { /* 拦截器已提示 */ }
  loading.value = false
}
onMounted(load)
</script>

<style scoped>
.fin-page { display: flex; flex-direction: column; gap: 16px; }
.hero { display: flex; justify-content: space-between; align-items: flex-end; gap: 14px; padding: 6px 2px 2px; }
.eyebrow { font-size: 12px; color: var(--mc-muted, #6e6e73); margin-bottom: 7px; letter-spacing: .04em; }
.dot { display: inline-block; width: 7px; height: 7px; border-radius: 50%; background: var(--mc-chart-solid, #0ea5e9); margin-right: 7px; }
.hero h1 { margin: 0; font-size: 28px; font-weight: 750; letter-spacing: -.03em; color: var(--mc-ink, #1d1d1f); }
.hero p { margin: 8px 0 0; color: var(--mc-muted, #6e6e73); font-size: 14px; }
.pill { border-radius: 999px; height: 40px; padding-inline: 18px; }

.kpi-grid { display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; }
.kpi-card { background: var(--mc-card, #fff); border: 1px solid var(--mc-border, #e8e6e1); border-radius: 16px; padding: 16px 17px; min-height: 106px; position: relative; overflow: hidden; box-shadow: 0 2px 14px rgba(20, 24, 34, .04); }
.kpi-card:after { content: ""; position: absolute; right: -20px; bottom: -24px; width: 66px; height: 66px; border-radius: 50%; background: var(--mc-decor, #f5f0e8); }
/* 主卡：柔和天蓝浅底 + 常规深色文字（低对比、Apple 卡片风），避免高饱和渐变 + 白字的生硬对比 */
.kpi-card.main { background: linear-gradient(135deg, #e0f2fe, #bae6fd); border-color: rgba(14, 165, 233, .22); }
.kpi-card.main:after { background: rgba(255, 255, 255, .5); }
.kpi-card.main .kpi-ico { color: #0284c7; }
[data-theme='dark'] .kpi-card.main { background: linear-gradient(135deg, rgba(14, 165, 233, .20), rgba(2, 132, 199, .30)); border-color: rgba(56, 189, 248, .35); }
[data-theme='dark'] .kpi-card.main:after { background: rgba(255, 255, 255, .06); }
.kpi-top { display: flex; justify-content: space-between; align-items: center; font-size: 12px; color: var(--mc-muted, #6e6e73); }
.kpi-ico { color: var(--mc-accent, #9b2d20); font-size: 15px; }
.kpi-value { font-size: 22px; font-weight: 750; letter-spacing: -.02em; color: var(--mc-ink, #1d1d1f); margin: 10px 0 6px; }
.kpi-value.refund { color: #8e44ad; }
.kpi-foot { font-size: 11px; color: var(--mc-muted2, #86868b); }

.panel { border-radius: 16px; border: 1px solid var(--mc-border, #e8e6e1); box-shadow: 0 2px 14px rgba(20, 24, 34, .04); }
.panel :deep(.ant-card-head) { border-bottom: 1px solid var(--mc-border-soft, #f0eee9); padding: 0 20px; min-height: 60px; }
.panel :deep(.ant-card-body) { padding: 18px 20px; }
.panel-title { display: flex; align-items: center; justify-content: space-between; width: 100%; }
.panel-title strong { display: block; font-size: 15px; color: var(--mc-ink, #1d1d1f); letter-spacing: -.01em; }
.panel-title small { display: block; color: var(--mc-faint, #a1a1a6); font-size: 11px; margin-top: 3px; }
.period { font-size: 11px; color: var(--mc-accent, #9b2d20); background: var(--mc-tint, #fbf4ef); border-radius: 20px; padding: 4px 8px; }

.grid-main { display: grid; grid-template-columns: 1.7fr 1fr; gap: 16px; }

.chart { height: 190px; position: relative; padding: 0 2px; }
.grid-line { position: absolute; left: 0; right: 0; border-top: 1px dashed var(--mc-border-soft, #ebe9e5); }
.l1 { top: 25%; } .l2 { top: 50%; } .l3 { top: 75%; }
.bars { height: 100%; display: flex; align-items: flex-end; gap: 4px; position: relative; z-index: 1; }
.bar-wrap { flex: 1; height: 100%; display: flex; align-items: flex-end; min-width: 2px; }
.bar { width: 100%; min-height: 4px; background: var(--mc-chart-grad, linear-gradient(180deg, #4fc3f7, #0ea5e9)); border-radius: 3px 3px 0 0; opacity: .92; transition: height .3s ease; }
.axis { display: flex; justify-content: space-between; color: var(--mc-faint, #a1a1a6); font-size: 10px; margin-top: 8px; }

.rule-list { display: flex; flex-direction: column; }
.rule-row { display: flex; gap: 12px; padding: 10px 0; border-top: 1px solid var(--mc-border-soft, #f1efeb); }
.rule-row:first-child { border-top: 0; }
.rule-ico { width: 24px; height: 24px; border-radius: 8px; background: var(--mc-decor, #f5f0e8); color: var(--mc-accent, #9b2d20); font-size: 12px; font-weight: 700; display: grid; place-items: center; flex-shrink: 0; }
.rule-row b { font-size: 12.5px; color: var(--mc-ink, #1d1d1f); }
.rule-row p { margin: 3px 0 0; font-size: 11.5px; color: var(--mc-muted2, #86868b); line-height: 1.6; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12.5px; }

.bank-account-panel {
  margin-top: 4px;
}
.edit-bank-btn {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  font-size: 12px;
}
.bank-edit-form {
  padding-top: 8px;
}
.edit-tip {
  font-size: 12.5px;
  color: #64748b;
  line-height: 1.5;
  margin-bottom: 16px;
  background: #f8fafc;
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
}

.export-btn {
  font-size: 12px;
  display: inline-flex;
  align-items: center;
}
.withdraw-modal-body {
  padding-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.withdraw-balance-card {
  background: linear-gradient(135deg, #f0fdf4, #dcfce7);
  border: 1px solid #bbf7d0;
  border-radius: 12px;
  padding: 14px 16px;
}
.wb-label {
  font-size: 12px;
  color: #166534;
  font-weight: 600;
}
.wb-amount {
  font-size: 26px;
  font-weight: 800;
  color: #15803d;
  letter-spacing: -0.02em;
  margin: 4px 0 2px;
}
.wb-sub {
  font-size: 11px;
  color: #166534;
  opacity: 0.85;
}
.withdraw-warning {
  display: flex;
  align-items: center;
  background: #fffbeb;
  border: 1px solid #fef3c7;
  color: #b45309;
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 12px;
}
.withdraw-dest-card {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  padding: 12px 14px;
}
.dest-title {
  font-size: 12px;
  color: #64748b;
  margin-bottom: 6px;
}
.dest-bank {
  font-size: 13.5px;
  font-weight: 600;
  color: #1e293b;
}
.dest-acc {
  font-size: 13px;
  color: #0284c7;
  font-weight: 600;
  margin: 3px 0;
}
.dest-meta {
  font-size: 11px;
  color: #94a3b8;
}
.withdraw-form {
  margin-top: 4px;
}
.withdraw-tip {
  font-size: 11px;
  color: #94a3b8;
  line-height: 1.5;
  margin: 0;
}

.bridge-link {
  color: #1e6eb8;
  cursor: pointer;
  text-decoration: underline;
  transition: color .15s;
}
.bridge-link:hover {
  color: #104d80;
}

@media (max-width: 1050px) {
  .kpi-grid { grid-template-columns: repeat(3, 1fr); }
  .grid-main { grid-template-columns: 1fr; }
}
@media (max-width: 700px) {
  .hero { flex-direction: column; align-items: flex-start; }
  .hero h1 { font-size: 22px; }
  .kpi-grid { grid-template-columns: repeat(2, 1fr); gap: 10px; }
  .kpi-card { padding: 13px; min-height: 92px; }
  .kpi-value { font-size: 18px; }
  .panel :deep(.ant-card-body) { padding: 14px; }
}
</style>

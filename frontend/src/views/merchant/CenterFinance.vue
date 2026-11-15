<template>
  <div class="fin-page">
    <section class="hero">
      <div class="hero-copy">
        <div class="eyebrow"><span class="dot"></span> {{ txt(L.hub) }}</div>
        <h1>{{ txt(L.title) }}</h1>
        <p>{{ txt(L.sub) }}</p>
      </div>
      <div class="hero-actions">
        <a-button size="large" class="pill" @click="load"><RefreshCw :size="14" class="inline mr-1" /> {{ txt(L.refresh) }}</a-button>
        <a-button size="large" type="primary" class="pill" @click="openWithdraw">
          <ArrowDownRight :size="15" class="inline mr-1" /> {{ txt(L.withdraw) }}
        </a-button>
      </div>
    </section>

    <!-- Order-derived KPIs (legacy /merchant/center/finance kept intact) -->
    <section class="kpi-grid">
      <div class="kpi-card main">
        <div class="kpi-top"><span>{{ txt(L.totalIncome) }}</span><span class="kpi-ico"><Wallet :size="15" class="text-amber-500" /></span></div>
        <div class="kpi-value">¥<NumberTicker :value="Number(f.totalIncome) || 0" :decimal-places="2" /></div>
        <div class="kpi-foot">{{ txt(L.totalIncomeFoot) }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-top"><span>{{ txt(L.monthIncome) }}</span><span class="kpi-ico"><TrendingUp :size="15" class="text-emerald-500" /></span></div>
        <div class="kpi-value">¥<NumberTicker :value="Number(f.monthIncome) || 0" :decimal-places="2" /></div>
        <div class="kpi-foot">{{ txt(L.last30) }} · {{ f.monthOrders || 0 }} {{ txt(L.ordersUnit) }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-top"><span>{{ txt(L.totalRefund) }}</span><span class="kpi-ico"><RotateCcw :size="15" class="text-rose-500" /></span></div>
        <div class="kpi-value refund">¥<NumberTicker :value="Number(f.totalRefund) || 0" :decimal-places="2" /></div>
        <div class="kpi-foot">{{ txt(L.totalRefundFoot) }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-top"><span>{{ txt(L.netIncome) }}</span><span class="kpi-ico"><Coins :size="15" class="text-indigo-500" /></span></div>
        <div class="kpi-value">¥<NumberTicker :value="Number(f.netIncome) || 0" :decimal-places="2" /></div>
        <div class="kpi-foot">{{ txt(L.netIncomeFoot) }}</div>
      </div>
    </section>

    <!-- Settlement four pools (ledger-based, settlementApi.summary; shown separately from order-derived KPIs) -->
    <section class="kpi-grid">
      <div class="kpi-card">
        <div class="kpi-top"><span>{{ txt(L.pending) }}</span><span class="kpi-ico"><Clock :size="15" class="text-sky-500" /></span></div>
        <div class="kpi-value">¥<NumberTicker :value="Number(s.pending) || 0" :decimal-places="2" /></div>
        <div class="kpi-foot">{{ txt(L.pendingFoot) }}</div>
      </div>
      <div class="kpi-card available">
        <div class="kpi-top"><span>{{ txt(L.available) }}</span><span class="kpi-ico"><BadgeCheck :size="15" /></span></div>
        <div class="kpi-value">¥<NumberTicker :value="Number(s.available) || 0" :decimal-places="2" /></div>
        <div class="kpi-foot">{{ txt(L.availableFoot) }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-top"><span>{{ txt(L.settled) }}</span><span class="kpi-ico"><Landmark :size="15" class="text-teal-600" /></span></div>
        <div class="kpi-value">¥<NumberTicker :value="Number(s.settled) || 0" :decimal-places="2" /></div>
        <div class="kpi-foot">{{ txt(L.settledFoot) }}</div>
      </div>
      <div class="kpi-card">
        <div class="kpi-top"><span>{{ txt(L.commission) }}</span><span class="kpi-ico"><Percent :size="15" class="text-amber-600" /></span></div>
        <div class="kpi-value">¥<NumberTicker :value="Number(s.totalCommission) || 0" :decimal-places="2" /></div>
        <div class="kpi-foot">{{ txt(L.commissionFoot) }}</div>
      </div>
    </section>

    <section class="grid-main">
      <!-- Revenue trend -->
      <a-card :bordered="false" class="panel">
        <template #title>
          <div class="panel-title">
            <div><strong>{{ txt(L.trend) }}</strong><small>{{ txt(L.trendSub) }}</small></div>
            <span class="period">30D</span>
          </div>
        </template>
        <div v-if="hasTrend" class="chart">
          <div class="grid-line l1"></div><div class="grid-line l2"></div><div class="grid-line l3"></div>
          <div class="bars">
            <div v-for="d in f.trend" :key="d.date" class="bar-wrap"
                 :title="`${d.date}  ${money(d.amount)} ${txt(L.yuan)} / ${d.orders} ${txt(L.orderUnit)}`">
              <div class="bar" :style="{ height: barHeight(d) }"></div>
            </div>
          </div>
        </div>
        <a-empty v-else :image="aEmpty.PRESENTED_IMAGE_SIMPLE" :description="txt(L.trendEmpty)" />
        <div class="axis"><span>{{ f.trend?.[0]?.date || '—' }}</span><span>{{ f.trend?.[f.trend.length - 1]?.date || '—' }}</span></div>
      </a-card>

      <!-- Settlement rules -->
      <a-card :bordered="false" class="panel">
        <template #title>
          <div class="panel-title"><div><strong>{{ txt(L.rules) }}</strong><small>{{ txt(L.rulesSub) }}</small></div></div>
        </template>
        <div class="rule-list">
          <div class="rule-row"><span class="rule-ico">1</span><div><b>{{ txt(L.rule1Title) }}</b><p>{{ txt(L.rule1Body) }}</p></div></div>
          <div class="rule-row"><span class="rule-ico">2</span><div><b>{{ txt(L.rule2Title) }}</b><p>{{ txt(L.rule2Body) }}</p></div></div>
          <div class="rule-row"><span class="rule-ico">3</span><div><b>{{ txt(L.rule3Title) }}</b><p>{{ txt(L.rule3Body) }}</p></div></div>
          <div class="rule-row"><span class="rule-ico">4</span><div><b>{{ txt(L.rule4Title) }}</b><p>{{ txt(L.rule4Pre) }} <a class="bridge-link" @click="$router.push('/merchant/center/store')">{{ txt(L.rule4Link) }} → {{ txt(L.rule4Link2) }}</a> {{ txt(L.rule4Post) }}</p></div></div>
        </div>
      </a-card>
    </section>

    <!-- Earnings ledger: EARN green / REVERSE red (settlementApi.earnings) -->
    <a-card :bordered="false" class="panel">
      <template #title>
        <div class="panel-title">
          <div><strong>{{ txt(L.ledger) }}</strong><small>{{ txt(L.ledgerSub) }}</small></div>
          <a-select v-model:value="earningStatus" style="width: 150px" allow-clear
                    :placeholder="txt(L.allStatus)" @change="reloadEarnings(1)">
            <a-select-option value="PENDING">{{ txt(L.pending) }}</a-select-option>
            <a-select-option value="AVAILABLE">{{ txt(L.available) }}</a-select-option>
            <a-select-option value="SETTLED">{{ txt(L.settled) }}</a-select-option>
            <a-select-option value="REVERSED">{{ txt(L.reversed) }}</a-select-option>
          </a-select>
        </div>
      </template>
      <a-table :data-source="earnings" :columns="earningCols" row-key="earningId"
               :loading="earningLoading" :scroll="{ x: 1080 }" class="mc-table"
               :pagination="earningPagination">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'orderNo'"><span class="mono">{{ record.orderNo || '—' }}</span></template>
          <template v-else-if="column.key === 'type'">
            <a-tag :color="record.type === 'REVERSE' ? 'red' : 'green'">
              {{ record.type === 'REVERSE' ? txt(L.reverse) : txt(L.earn) }}
            </a-tag>
          </template>
          <template v-else-if="column.key === 'grossAmount'">
            <span :class="signedClass(record)">{{ signedMoney(record, record.grossAmount) }}</span>
          </template>
          <template v-else-if="column.key === 'commissionAmount'">
            <span :class="signedClass(record)">{{ signedMoney(record, record.commissionAmount) }}</span>
          </template>
          <template v-else-if="column.key === 'netAmount'">
            <span :class="signedClass(record)">{{ signedMoney(record, record.netAmount) }}</span>
          </template>
          <template v-else-if="column.key === 'status'">
            <a-tag :color="EARN_STATUS_COLOR[record.status] || 'default'">{{ earningStatusText(record.status) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'availableAt'">{{ shortTime(record.availableAt) || '—' }}</template>
          <template v-else-if="column.key === 'createTime'">{{ shortTime(record.createTime) || '—' }}</template>
        </template>
      </a-table>
    </a-card>

    <!-- Settlement batches (settlementApi.batches) -->
    <a-card :bordered="false" class="panel">
      <template #title>
        <div class="panel-title">
          <div><strong>{{ txt(L.batches) }}</strong><small>{{ txt(L.batchesSub) }}</small></div>
        </div>
      </template>
      <a-table :data-source="batches" :columns="batchCols" row-key="settleId"
               :loading="batchLoading" :scroll="{ x: 1100 }" class="mc-table"
               :pagination="batchPagination">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'settleNo'"><span class="mono">{{ record.settleNo }}</span></template>
          <template v-else-if="column.key === 'period'">{{ periodText(record) }}</template>
          <template v-else-if="column.key === 'totalNet'">¥{{ money(record.totalNet) }}</template>
          <template v-else-if="column.key === 'commissionTotal'">¥{{ money(record.commissionTotal) }}</template>
          <template v-else-if="column.key === 'status'">
            <a-tag :color="BATCH_STATUS_COLOR[record.status] || 'default'">{{ batchStatusText(record.status) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'payVoucher'"><span class="mono">{{ record.payVoucher || '—' }}</span></template>
          <template v-else-if="column.key === 'createTime'">{{ shortTime(record.createTime) || '—' }}</template>
        </template>
      </a-table>
    </a-card>

    <!-- Withdrawal: available balance + records -->
    <a-card :bordered="false" class="panel">
      <template #title>
        <div class="panel-title">
          <div><strong>{{ txt(L.wdTitle) }}</strong><small>{{ txt(L.wdSub) }}</small></div>
          <a-space size="small">
            <span class="wd-balance">{{ txt(L.wdAvailable) }} <b>¥{{ money(wd.balance?.available) }}</b></span>
            <a-button size="small" type="primary" @click="openWithdraw">{{ txt(L.withdraw) }}</a-button>
          </a-space>
        </div>
      </template>
      <a-table :data-source="withdrawals" :columns="withdrawCols" row-key="id"
               :loading="withdrawLoading" :scroll="{ x: 1080 }" class="mc-table"
               :pagination="withdrawPagination">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'withdrawNo'"><span class="mono">{{ record.withdrawNo }}</span></template>
          <template v-else-if="column.key === 'amount'">¥{{ money(record.amount) }}</template>
          <template v-else-if="column.key === 'bankName'">
            <span class="wd-bank">{{ record.bankName }}<br /><span class="mono">{{ record.bankAccountNoMasked }}</span></span>
          </template>
          <template v-else-if="column.key === 'status'">
            <a-tag :color="WD_STATUS_COLOR[record.status] || 'default'">{{ wdStatusText(record.status) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'voucherUrl'">
            <a v-if="record.voucherUrl" :href="`/api/merchant/finance/withdrawals/${record.id}/voucher`" target="_blank" rel="noopener">{{ txt(L.wdViewVoucher) }}</a>
            <span v-else>—</span>
          </template>
          <template v-else-if="column.key === 'rejectReason'">{{ record.rejectReason || '—' }}</template>
          <template v-else-if="column.key === 'createTime'">{{ shortTime(record.createTime) || '—' }}</template>
          <template v-else-if="column.key === 'op'">
            <a v-if="Number(record.status) === 0" @click="confirmCancel(record)">{{ txt(L.wdCancel) }}</a>
            <span v-else>—</span>
          </template>
        </template>
      </a-table>
    </a-card>

    <!-- Finance ledger (every balance movement) -->
    <a-card :bordered="false" class="panel">
      <template #title>
        <div class="panel-title">
          <div><strong>{{ txt(L.ledgerTitle) }}</strong><small>{{ txt(L.ledgerSub2) }}</small></div>
        </div>
      </template>
      <a-table :data-source="ledgerRows" :columns="ledgerCols" row-key="id"
               :loading="ledgerLoading" :scroll="{ x: 900 }" class="mc-table"
               :pagination="ledgerPagination">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'bizNo'"><span class="mono">{{ record.bizNo }}</span></template>
          <template v-else-if="column.key === 'bizType'">
            <a-tag :color="LEDGER_TYPE_COLOR[record.bizType] || 'default'">{{ ledgerTypeText(record.bizType) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'amount'">
            <span :class="Number(record.amount) >= 0 ? 'amt-earn' : 'amt-reverse'">
              {{ Number(record.amount) >= 0 ? '+' : '−' }}¥{{ money(Math.abs(Number(record.amount || 0))) }}
            </span>
          </template>
          <template v-else-if="column.key === 'balanceAfter'">¥{{ money(record.balanceAfter) }}</template>
          <template v-else-if="column.key === 'createTime'">{{ shortTime(record.createTime) || '—' }}</template>
        </template>
      </a-table>
    </a-card>

    <!-- Withdrawal apply modal -->
    <a-modal
      v-model:open="withdrawOpen"
      :title="txt(L.withdraw)"
      :confirm-loading="submittingWithdraw"
      :ok-text="txt(L.wdSubmit)"
      :cancel-text="txt(L.bankEditCancel)"
      @ok="submitWithdraw"
    >
      <div class="bank-edit-form">
        <p class="edit-tip">{{ txt(L.wdTip) }}</p>
        <a-alert class="wd-modal-balance" type="info" show-icon
                 :message="`${txt(L.wdAvailable)}：¥${money(wd.balance?.available)}`" />
        <a-form layout="vertical">
          <a-form-item :label="txt(L.wdAmount)" required>
            <a-input-number v-model:value="withdrawForm.amount" :min="1" :precision="2"
                            style="width: 100%" :placeholder="txt(L.wdAmountPh)" />
          </a-form-item>
          <a-form-item :label="txt(L.wdAccName)" required>
            <a-input v-model:value="withdrawForm.bankAccountName" :placeholder="txt(L.wdAccNamePh)" />
          </a-form-item>
          <a-form-item :label="txt(L.wdAccNo)" required>
            <a-input v-model:value="withdrawForm.bankAccountNo" :placeholder="txt(L.bankAccountPh)" />
          </a-form-item>
          <a-form-item :label="txt(L.bankName)" required>
            <a-input v-model:value="withdrawForm.bankName" :placeholder="txt(L.bankNamePh)" />
          </a-form-item>
          <a-form-item :label="txt(L.wdNote)">
            <a-textarea v-model:value="withdrawForm.applyNote" :rows="2" :maxlength="200" />
          </a-form-item>
        </a-form>
        <p class="wd-mask-tip">{{ txt(L.wdMaskTip) }}</p>
      </div>
    </a-modal>

    <!-- Corporate receiving account -->
    <a-card :bordered="false" class="panel bank-account-panel">
      <template #title>
        <div class="panel-title">
          <div>
            <strong>{{ txt(L.bankTitle) }}</strong>
            <small>{{ txt(L.bankSub) }}</small>
          </div>
          <a-button type="default" size="small" class="edit-bank-btn" @click="openEditBank">
            <Edit3 :size="13" class="mr-1" /> {{ txt(L.bankEdit) }}
          </a-button>
        </div>
      </template>
      <MerchantCorporateBankCard :bank-info="f.receivingAccount || {}" />
    </a-card>

    <!-- Corporate account edit modal -->
    <a-modal
      v-model:open="editModalVisible"
      :title="txt(L.bankEditTitle)"
      :confirm-loading="submittingBank"
      :ok-text="txt(L.bankEditOk)"
      :cancel-text="txt(L.bankEditCancel)"
      @ok="handleUpdateBank"
    >
      <div class="bank-edit-form">
        <p class="edit-tip">{{ txt(L.bankEditTip) }}</p>
        <a-form layout="vertical">
          <a-form-item :label="txt(L.bankName)" required>
            <a-input
              v-model:value="bankForm.bankName"
              :placeholder="txt(L.bankNamePh)"
            />
          </a-form-item>
          <a-form-item :label="txt(L.bankAccount)" required>
            <a-input
              v-model:value="bankForm.bankAccount"
              :placeholder="txt(L.bankAccountPh)"
            />
          </a-form-item>
        </a-form>
      </div>
    </a-modal>

    <!-- Settlement details (order-derived, legacy finance payload) -->
    <a-card :bordered="false" class="panel">
      <template #title>
        <div class="panel-title">
          <div><strong>{{ txt(L.details) }}</strong><small>{{ txt(L.detailsSub) }}</small></div>
          <a-button size="small" class="pill export-btn" @click="exportSettlementsCsv" :disabled="!f.settlements || f.settlements.length === 0">
            <Download :size="13" class="mr-1 inline" /> {{ txt(L.export) }}
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
            <a-tag v-if="Number(record.orderStatus) === 2" color="green">{{ txt(L.stDone) }}</a-tag>
            <a-tag v-else-if="Number(record.orderStatus) === 4" color="cyan">{{ txt(L.stPartial) }}</a-tag>
            <a-tag v-else-if="Number(record.orderStatus) === 5 || record.refundStatus === 'success' || Number(record.payStatus) === 2" color="purple">{{ txt(L.stRefunded) }}</a-tag>
            <a-tag v-else color="blue">{{ txt(L.stFulfilling) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'payTime'">{{ shortTime(record.payTime) }}</template>
        </template>
      </a-table>
    </a-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Empty, message, Modal } from 'ant-design-vue'
import {
  Edit3,
  Wallet,
  TrendingUp,
  RotateCcw,
  Coins,
  Clock,
  BadgeCheck,
  Landmark,
  Percent,
  RefreshCw,
  ArrowDownRight,
  Download
} from 'lucide-vue-next'
import { merchantCenterApi, settlementApi, merchantFinanceApi } from '../../api'
import { locale } from '../../i18n'
import { UI_ZH_EN_EXTRA } from '../../i18n/zh-en-extra'
import NumberTicker from '../../components/ui/NumberTicker.vue'
import MerchantCorporateBankCard from '../../components/payment/MerchantCorporateBankCard.vue'
import { PAY_CHANNEL, fmtMoney } from '../../utils/merchantStatus'

const aEmpty = Empty
const isEn = computed(() => String(locale.value || '').toLowerCase().startsWith('en'))
// 模板 CJK=0：所有 UI 串经 txt() 查补充词典（缺失回退原文），与 DOM 整句翻译共用同一份词表
const txt = (zh) => (isEn.value ? (UI_ZH_EN_EXTRA[zh] || zh) : zh)

// 文案表：模板零中文（CJK=0），所有 UI 串经 txt() 查补充词典（缺失回退中文原文）
const L = {
  hub: '商户中心',
  title: '资金结算',
  sub: '本店商品的成交收入、退款与结算明细，全部透明可查。',
  refresh: '刷新数据',
  withdraw: '申请提现',
  totalIncome: '累计收入',
  totalIncomeFoot: '历史已支付订单的本店金额',
  monthIncome: '本月收入',
  last30: '近 30 天',
  ordersUnit: '笔订单',
  totalRefund: '累计退款',
  totalRefundFoot: '已全额退款订单的本店金额',
  netIncome: '净收入',
  netIncomeFoot: '累计收入减累计退款',
  pending: '待结算',
  pendingFoot: '已记账但仍在持有期内的收益',
  available: '可结算',
  availableFoot: '持有期满、待纳入结算批次的收益',
  settled: '已结算',
  settledFoot: '已纳入结算批次的净额',
  commission: '平台累计佣金',
  commissionFoot: '按佣金率累计扣佣，含退款冲正回冲',
  trend: '收入趋势',
  trendSub: '近 30 天按日结算金额',
  yuan: '元',
  orderUnit: '单',
  trendEmpty: '近 30 天暂无收入',
  rules: '结算规则',
  rulesSub: '平台统一结算口径',
  rule1Title: '结算口径',
  rule1Body: '按订单明细中本店商品的小计金额结算，不含平台运费与优惠分摊。',
  rule2Title: '结算时点',
  rule2Body: '订单完成（买家签收）后记入待结算，持有期满转可结算；退款自动冲正。',
  rule3Title: '对账凭证',
  rule3Body: '每笔支付均有支付渠道流水号，可在订单详情中核对。',
  rule4Title: '税务上报',
  rule4Pre: '月度完税证明请在',
  rule4Link: '店铺设置',
  rule4Link2: '资质证照',
  rule4Post: '中上传。',
  ledger: '收益台账',
  ledgerSub: '每笔成交与退款冲正的记账明细',
  allStatus: '全部状态',
  reversed: '已冲正',
  reverse: '冲正',
  earn: '计入',
  batches: '结算批次',
  batchesSub: '平台按账期生成的结算批次与线下打款凭证',
  bankTitle: '企业对公收款账户',
  bankSub: '用于接收买家对公转账电汇款项及平台资金周期结算',
  bankEdit: '更新对公账户',
  bankEditTitle: '更新企业对公收款账户',
  bankEditOk: '确认更新',
  bankEditCancel: '取消',
  bankEditTip: '对公账户信息经 AES-256-GCM 工业级加密存储，更新后将作为买家对公电汇及平台结算汇款凭据。',
  bankName: '开户银行全称（含支行）',
  bankNamePh: '如：中国工商银行股份有限公司深圳科技园支行',
  bankAccount: '对公银行账号',
  bankAccountPh: '请输入 8~35 位数字或字母银行账号',
  details: '结算明细',
  detailsSub: '最近已支付或已退款订单',
  export: '导出对账单 (CSV)',
  stDone: '已完成',
  stPartial: '部分发货',
  stRefunded: '已退款',
  stFulfilling: '履约中',
  colOrderNo: '订单号',
  colMyAmount: '本店金额',
  colKinds: '商品种数',
  colChannel: '支付渠道',
  colSettleStatus: '结算状态',
  colPayTime: '支付时间',
  colType: '类型',
  colGross: '毛额',
  colCommission: '佣金',
  colNet: '净额',
  colStatus: '状态',
  colAvailableAt: '可用时间',
  colCreateTime: '记录时间',
  colSettleNo: '批次号',
  colPeriod: '账期',
  colTotalNet: '应结净额',
  colCommissionTotal: '佣金合计',
  colItemCount: '明细数',
  colPayVoucher: '打款凭证',
  colWithdrawNo: '提现单号',
  colAmount: '金额',
  colBankInfo: '开户行 / 账号',
  colRejectReason: '驳回原因',
  colBizNo: '业务单号',
  colBalanceAfter: '变动后余额',
  colRemark: '备注',
  colOp: '操作',
  unitRow: '条',
  stDraft: '草稿',
  stApproved: '已审核',
  stPaid: '已打款',
  noExport: '暂无结算明细可导出',
  exported: '对账单 CSV 文件已成功导出',
  confirm: '确认',
  bankNameTooShort: '开户银行全称需至少 4 个字符',
  bankAccountTooShort: '对公银行账号需至少 8 位数字或字符',
  bankUpdated: '企业对公收款账户已更新',
  csvName: '商户资金结算对账单',
  wdTitle: '提现记录',
  wdSub: '可提余额 = 已结算净额 − 已打款 − 审核中冻结；申请后资金即冻结，等待平台审核',
  wdAvailable: '可提余额',
  wdCancel: '撤回',
  wdViewVoucher: '查看打款凭证',
  wdAmount: '提现金额（元）',
  wdAmountPh: '最低 1.00 元',
  wdAccName: '收款账户户名（对公）',
  wdAccNamePh: '须与企业名称一致',
  wdAccNo: '银行账号',
  wdNote: '备注（选填）',
  wdTip: '打款将汇入下方填写的对公账户；银行账号仅保存脱敏掩码（前4后4），全号不落库。',
  wdMaskTip: '账号信息仅本次传输用于生成掩码与打款核对，不会以明文存储。',
  wdSubmit: '提交申请并冻结',
  wdRequired: '请完整填写提现金额与收款账户信息',
  wdApplied: '提现申请已提交，资金已冻结',
  wdCanceled: '提现申请已撤回，资金已解冻',
  wdCancelConfirm: '确认撤回该提现申请？冻结金额将解冻回可提余额。',
  stWdPending: '审核中',
  stWdPaid: '已打款',
  stWdRejected: '已驳回',
  stWdWithdrawn: '已撤回',
  ledgerTitle: '财务台账',
  ledgerSub2: '每笔资金变动的出入账明细（正入负出）',
  ltFreeze: '提现冻结',
  ltPaid: '提现打款',
  ltUnfreeze: '解冻',
  ltEarn: '收益入账',
  ltSettle: '结算',
  stPendingShip: '待发货',
  stShipped: '已发货',
  stCancelled: '已取消',
}


const loading = ref(true)
const f = ref({})
// 结算四池（收益台账口径）：与订单派生口径的 KPI 分开展示，两套数字互不覆盖
const s = ref({})

const editModalVisible = ref(false)
const bankForm = ref({ bankName: '', bankAccount: '' })
const submittingBank = ref(false)

// 收益台账分页状态
const earnings = ref([])
const earningLoading = ref(false)
const earningStatus = ref(undefined)
const earningPage = ref({ current: 1, size: 10, total: 0 })

// 结算批次分页状态
const batches = ref([])
const batchLoading = ref(false)
const batchPage = ref({ current: 1, size: 10, total: 0 })

const EARN_STATUS_COLOR = { PENDING: 'blue', AVAILABLE: 'cyan', SETTLED: 'green', REVERSED: 'red' }
const BATCH_STATUS_COLOR = { DRAFT: 'default', APPROVED: 'blue', PAID: 'green' }
const earningStatusText = (v) => {
  const map = { PENDING: L.pending, AVAILABLE: L.available, SETTLED: L.settled, REVERSED: L.reversed }
  return txt(map[v] || v || '—')
}
const batchStatusText = (v) => {
  const map = { DRAFT: L.stDraft, APPROVED: L.stApproved, PAID: L.stPaid }
  return txt(map[v] || v || '—')
}

// ===== 提现：余额概览 / 记录分页 / 申请弹窗 / 撤回 =====
const wd = ref({ balance: {} })
const withdrawals = ref([])
const withdrawLoading = ref(false)
const withdrawPage = ref({ current: 1, size: 10, total: 0 })
const withdrawOpen = ref(false)
const submittingWithdraw = ref(false)
const withdrawForm = ref({ amount: undefined, bankAccountName: '', bankAccountNo: '', bankName: '', applyNote: '' })

const WD_STATUS_COLOR = { 0: 'blue', 1: 'green', 2: 'red', 3: 'default' }
const wdStatusText = (v) => {
  const map = { 0: L.stWdPending, 1: L.stWdPaid, 2: L.stWdRejected, 3: L.stWdWithdrawn }
  return txt(map[Number(v)] || String(v ?? '—'))
}

const LEDGER_TYPE_COLOR = { withdraw_freeze: 'orange', withdraw_paid: 'green', withdraw_reject_unrefreeze: 'cyan', earn: 'green', settle: 'blue' }
const ledgerTypeText = (v) => {
  const map = { withdraw_freeze: L.ltFreeze, withdraw_paid: L.ltPaid, withdraw_reject_unrefreeze: L.ltUnfreeze, earn: L.ltEarn, settle: L.ltSettle }
  return txt(map[v] || v || '—')
}

async function reloadBalance() {
  try { wd.value = (await merchantFinanceApi.balance()) || {} } catch (e) { /* 拦截器已提示 */ }
}

async function reloadWithdrawals(page = 1) {
  withdrawLoading.value = true
  try {
    const res = await merchantFinanceApi.withdrawals({ current: page, size: withdrawPage.value.size })
    withdrawals.value = res?.records || []
    withdrawPage.value = {
      current: Number(res?.current) || page,
      size: Number(res?.size) || withdrawPage.value.size,
      total: Number(res?.total) || 0
    }
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    withdrawLoading.value = false
  }
}

function openWithdraw() {
  withdrawForm.value = { amount: undefined, bankAccountName: '', bankAccountNo: '', bankName: '', applyNote: '' }
  withdrawOpen.value = true
}

async function submitWithdraw() {
  const f = withdrawForm.value
  if (!f.amount || !f.bankAccountName?.trim() || !f.bankAccountNo?.trim() || !f.bankName?.trim()) {
    message.warning(txt(L.wdRequired))
    return
  }
  submittingWithdraw.value = true
  try {
    await merchantFinanceApi.applyWithdrawal({
      amount: f.amount,
      bankAccountName: f.bankAccountName.trim(),
      bankAccountNo: f.bankAccountNo.trim(),
      bankName: f.bankName.trim(),
      applyNote: f.applyNote?.trim() || undefined
    })
    message.success(txt(L.wdApplied))
    withdrawOpen.value = false
    await Promise.all([reloadBalance(), reloadWithdrawals(1), reloadLedger(1)])
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    submittingWithdraw.value = false
  }
}

function confirmCancel(record) {
  Modal.confirm({
    title: txt(L.wdCancelConfirm),
    okText: txt(L.confirm),
    cancelText: txt(L.bankEditCancel),
    onOk: async () => {
      try {
        await merchantFinanceApi.cancelWithdrawal(record.id)
        message.success(txt(L.wdCanceled))
        await Promise.all([reloadBalance(), reloadWithdrawals(withdrawPage.value.current), reloadLedger(1)])
      } catch (e) {
        /* 拦截器已提示 */
      }
    }
  })
}

// ===== 财务台账 =====
const ledgerRows = ref([])
const ledgerLoading = ref(false)
const ledgerPage = ref({ current: 1, size: 10, total: 0 })

async function reloadLedger(page = 1) {
  ledgerLoading.value = true
  try {
    const res = await merchantFinanceApi.ledger({ current: page, size: ledgerPage.value.size })
    ledgerRows.value = res?.records || []
    ledgerPage.value = {
      current: Number(res?.current) || page,
      size: Number(res?.size) || ledgerPage.value.size,
      total: Number(res?.total) || 0
    }
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    ledgerLoading.value = false
  }
}

// REVERSE 行本身净额为负：展示加显式负号 + 红色，避免与 EARN 混淆（负号/标签为主，颜色为辅）
function signedMoney(record, v) {
  const n = Number(v || 0)
  const sign = record && record.type === 'REVERSE' ? '−' : ''
  return `${sign}¥${money(Math.abs(n))}`
}
function signedClass(record) {
  return record && record.type === 'REVERSE' ? 'amt-reverse' : 'amt-earn'
}

function periodText(record) {
  return `${shortTime(record?.periodStart) || '—'} ~ ${shortTime(record?.periodEnd) || '—'}`
}

function exportSettlementsCsv() {
  const list = f.value.settlements || []
  if (!list.length) {
    message.warning(txt(L.noExport))
    return
  }
  const headers = [txt(L.colOrderNo), txt(L.colMyAmount), txt(L.colKinds), txt(L.colChannel), txt(L.colSettleStatus), txt(L.colPayTime)]
  const statusMap = {
    0: txt(L.stPendingShip), 1: txt(L.stShipped), 2: txt(L.stDone),
    3: txt(L.stCancelled), 4: txt(L.stPartial), 5: txt(L.stRefunded)
  }
  const rows = list.map(r => {
    const statusText = (r.refundStatus === 'success' || Number(r.payStatus) === 2 || Number(r.orderStatus) === 5)
      ? txt(L.stRefunded)
      : (statusMap[Number(r.orderStatus)] || txt(L.stFulfilling))
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
  link.setAttribute('download', `${txt(L.csvName)}_${new Date().toISOString().slice(0, 10)}.csv`)
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(url)
  message.success(txt(L.exported))
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
    message.warning(txt(L.bankNameTooShort))
    return
  }
  if (!bankForm.value.bankAccount || bankForm.value.bankAccount.trim().length < 8) {
    message.warning(txt(L.bankAccountTooShort))
    return
  }
  submittingBank.value = true
  try {
    await merchantCenterApi.updateBankAccount({
      bankName: bankForm.value.bankName.trim(),
      bankAccount: bankForm.value.bankAccount.trim()
    })
    message.success(txt(L.bankUpdated))
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
const cols = computed(() => [
  { title: txt(L.colOrderNo), key: 'orderNo', width: 178 },
  { title: txt(L.colMyAmount), key: 'myAmount', width: 120 },
  { title: txt(L.colKinds), dataIndex: 'myKinds', width: 96 },
  { title: txt(L.colChannel), key: 'channel', width: 104 },
  { title: txt(L.colSettleStatus), key: 'status', width: 100 },
  { title: txt(L.colPayTime), key: 'payTime', width: 150 }
])

const earningCols = computed(() => [
  { title: txt(L.colOrderNo), key: 'orderNo', width: 178 },
  { title: txt(L.colType), key: 'type', width: 88 },
  { title: txt(L.colGross), key: 'grossAmount', width: 120 },
  { title: txt(L.colCommission), key: 'commissionAmount', width: 112 },
  { title: txt(L.colNet), key: 'netAmount', width: 120 },
  { title: txt(L.colStatus), key: 'status', width: 96 },
  { title: txt(L.colAvailableAt), key: 'availableAt', width: 148 },
  { title: txt(L.colCreateTime), key: 'createTime', width: 148 }
])

const batchCols = computed(() => [
  { title: txt(L.colSettleNo), key: 'settleNo', width: 200 },
  { title: txt(L.colPeriod), key: 'period', width: 320 },
  { title: txt(L.colTotalNet), key: 'totalNet', width: 122 },
  { title: txt(L.colCommissionTotal), key: 'commissionTotal', width: 120 },
  { title: txt(L.colItemCount), dataIndex: 'itemCount', width: 88 },
  { title: txt(L.colStatus), key: 'status', width: 96 },
  { title: txt(L.colPayVoucher), key: 'payVoucher', width: 150 },
  { title: txt(L.colCreateTime), key: 'createTime', width: 148 }
])

const withdrawCols = computed(() => [
  { title: txt(L.colWithdrawNo), key: 'withdrawNo', width: 180 },
  { title: txt(L.colAmount), key: 'amount', width: 110 },
  { title: txt(L.wdAccName), dataIndex: 'bankAccountName', width: 130 },
  { title: txt(L.colBankInfo), key: 'bankName', width: 230 },
  { title: txt(L.colStatus), key: 'status', width: 92 },
  { title: txt(L.colPayVoucher), key: 'voucherUrl', width: 120 },
  { title: txt(L.colRejectReason), key: 'rejectReason', width: 150 },
  { title: txt(L.colCreateTime), key: 'createTime', width: 148 },
  { title: txt(L.colOp), key: 'op', width: 80, fixed: 'right' }
])

const ledgerCols = computed(() => [
  { title: txt(L.colCreateTime), key: 'createTime', width: 148 },
  { title: txt(L.colType), key: 'bizType', width: 110 },
  { title: txt(L.colBizNo), key: 'bizNo', width: 180 },
  { title: txt(L.colAmount), key: 'amount', width: 120 },
  { title: txt(L.colBalanceAfter), key: 'balanceAfter', width: 130 },
  { title: txt(L.colRemark), dataIndex: 'remark', width: 220 }
])

const withdrawPagination = computed(() => ({
  current: withdrawPage.value.current,
  pageSize: withdrawPage.value.size,
  total: withdrawPage.value.total,
  showSizeChanger: false,
  showTotal: (t) => `${t} ${txt(L.unitRow)}`,
  onChange: (page) => reloadWithdrawals(page)
}))

const ledgerPagination = computed(() => ({
  current: ledgerPage.value.current,
  pageSize: ledgerPage.value.size,
  total: ledgerPage.value.total,
  showSizeChanger: false,
  showTotal: (t) => `${t} ${txt(L.unitRow)}`,
  onChange: (page) => reloadLedger(page)
}))

const earningPagination = computed(() => ({
  current: earningPage.value.current,
  pageSize: earningPage.value.size,
  total: earningPage.value.total,
  showSizeChanger: false,
  showTotal: (t) => `${t} ${txt(L.unitRow)}`,
  onChange: (page) => reloadEarnings(page)
}))

const batchPagination = computed(() => ({
  current: batchPage.value.current,
  pageSize: batchPage.value.size,
  total: batchPage.value.total,
  showSizeChanger: false,
  showTotal: (t) => `${t} ${txt(L.unitRow)}`,
  onChange: (page) => reloadBatches(page)
}))

function barHeight(d) {
  const max = Math.max(...(f.value.trend || []).map(x => Number(x.amount || 0)), 0.01)
  return Math.max(5, (Number(d.amount || 0) / max) * 100) + '%'
}

async function reloadEarnings(page = 1) {
  earningLoading.value = true
  try {
    const res = await settlementApi.earnings({
      current: page,
      size: earningPage.value.size,
      status: earningStatus.value || undefined
    })
    earnings.value = res?.records || []
    earningPage.value = {
      current: Number(res?.current) || page,
      size: Number(res?.size) || earningPage.value.size,
      total: Number(res?.total) || 0
    }
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    earningLoading.value = false
  }
}

async function reloadBatches(page = 1) {
  batchLoading.value = true
  try {
    const res = await settlementApi.batches({ current: page, size: batchPage.value.size })
    batches.value = res?.records || []
    batchPage.value = {
      current: Number(res?.current) || page,
      size: Number(res?.size) || batchPage.value.size,
      total: Number(res?.total) || 0
    }
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    batchLoading.value = false
  }
}

async function load() {
  loading.value = true
  // 旧 finance 接口保留不动（订单派生口径），与新结算台账并行展示
  try { f.value = (await merchantCenterApi.finance()) || {} } catch (e) { /* 拦截器已提示 */ }
  loading.value = false
  try { s.value = (await settlementApi.summary()) || {} } catch (e) { /* 未入驻等由拦截器提示 */ }
  reloadBalance()
  await Promise.all([reloadEarnings(1), reloadBatches(1), reloadWithdrawals(1), reloadLedger(1)])
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

.kpi-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.kpi-card { background: var(--mc-card, #fff); border: 1px solid var(--mc-border, #e8e6e1); border-radius: 16px; padding: 16px 17px; min-height: 106px; position: relative; overflow: hidden; box-shadow: 0 2px 14px rgba(20, 24, 34, .04); }
.kpi-card:after { content: ""; position: absolute; right: -20px; bottom: -24px; width: 66px; height: 66px; border-radius: 50%; background: var(--mc-decor, #f5f0e8); }
/* 主卡：柔和天蓝浅底 + 常规深色文字（低对比、Apple 卡片风），避免高饱和渐变 + 白字的生硬对比 */
.kpi-card.main { background: linear-gradient(135deg, #e0f2fe, #bae6fd); border-color: rgba(14, 165, 233, .22); }
.kpi-card.main:after { background: rgba(255, 255, 255, .5); }
.kpi-card.main .kpi-ico { color: #0284c7; }
/* 可结算池：浅绿底，与「待结算」中性卡区分（颜色仅作辅助，标题文字已区分语义） */
.kpi-card.available { background: linear-gradient(135deg, #ecfdf5, #d1fae5); border-color: rgba(16, 185, 129, .22); }
.kpi-card.available:after { background: rgba(255, 255, 255, .5); }
.kpi-card.available .kpi-ico { color: #047857; }
[data-theme='dark'] .kpi-card.main { background: linear-gradient(135deg, rgba(14, 165, 233, .20), rgba(2, 132, 199, .30)); border-color: rgba(56, 189, 248, .35); }
[data-theme='dark'] .kpi-card.main:after { background: rgba(255, 255, 255, .06); }
[data-theme='dark'] .kpi-card.available { background: linear-gradient(135deg, rgba(16, 185, 129, .18), rgba(5, 150, 105, .28)); border-color: rgba(52, 211, 153, .35); }
[data-theme='dark'] .kpi-card.available:after { background: rgba(255, 255, 255, .06); }
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
/* 台账分色：EARN 绿 / REVERSE 红（红绿仅作辅助，负号与类型标签同样区分，色弱可读） */
.amt-earn { color: #15803d; font-weight: 600; font-variant-numeric: tabular-nums; }
.amt-reverse { color: #b91c1c; font-weight: 600; font-variant-numeric: tabular-nums; }

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

.wd-balance { font-size: 12px; color: var(--mc-muted, #6e6e73); }
.wd-balance b { color: var(--mc-ink, #1d1d1f); font-size: 14px; }
.wd-bank { font-size: 12px; line-height: 1.5; }
.wd-modal-balance { margin-bottom: 12px; border-radius: 8px; }
.wd-mask-tip { font-size: 11px; color: var(--mc-faint, #a1a1a6); margin-top: 8px; }

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
  .kpi-grid { grid-template-columns: repeat(2, 1fr); }
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

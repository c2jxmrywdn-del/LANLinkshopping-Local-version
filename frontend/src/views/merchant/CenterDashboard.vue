<template>
  <div class="dash-page">
    <section class="hero">
      <div class="hero-copy">
        <div class="eyebrow"><span class="dot"></span> 商户中心 · 工作台</div>
        <h1>{{ greeting }}，{{ storeName }}</h1>
        <p>今天有 {{ kpis.pendingShip || 0 }} 笔订单待发货，近 7 日销售额 {{ money(kpis.weekAmount) }} 元。</p>
      </div>
      <div class="hero-actions">
        <a-button size="large" class="pill" @click="$router.push('/merchant/center/orders')"><FileText :size="15" class="inline-block mr-1 align-middle" /> 处理订单</a-button>
        <a-button size="large" type="primary" class="pill" @click="publishOpen = true">＋ 发布商品</a-button>
      </div>
    </section>

    <!-- KPI 指标卡 -->
    <section class="kpi-grid">
      <div class="kpi-card" @click="$router.push('/merchant/center/finance')">
        <div class="kpi-top"><span>近 7 日销售额</span><span class="kpi-ico"><Wallet :size="18" /></span></div>
        <div class="kpi-value">{{ money(kpis.weekAmount) }}</div>
        <div class="kpi-foot">{{ kpis.weekOrders || 0 }} 笔已支付订单</div>
      </div>
      <div class="kpi-card" @click="$router.push('/merchant/center/products')">
        <div class="kpi-top"><span>在售商品</span><span class="kpi-ico"><LayoutGrid :size="18" /></span></div>
        <div class="kpi-value">{{ kpis.onSaleProducts || 0 }}<em>/ {{ kpis.totalProducts || 0 }}</em></div>
        <div class="kpi-foot">{{ kpis.reviewingProducts ? `另有 ${kpis.reviewingProducts} 件审核中` : '全部商品经营正常' }}</div>
      </div>
      <div class="kpi-card warn" @click="$router.push('/merchant/center/orders?tab=paid')">
        <div class="kpi-top"><span>待发货订单</span><span class="kpi-ico"><Truck :size="18" /></span></div>
        <div class="kpi-value">{{ kpis.pendingShip || 0 }}</div>
        <div class="kpi-foot">{{ kpis.pendingShip ? '点击立即处理' : '暂无待发货' }}</div>
      </div>
      <div class="kpi-card" :class="{ warn: kpis.refundPending }" @click="$router.push('/merchant/center/orders?tab=refund')">
        <div class="kpi-top"><span>退款申请</span><span class="kpi-ico"><RotateCcw :size="18" /></span></div>
        <div class="kpi-value">{{ kpis.refundPending || 0 }}</div>
        <div class="kpi-foot">{{ kpis.refundPending ? '待平台处理，可查看详情' : '暂无退款申请' }}</div>
      </div>
      <div class="kpi-card" @click="$router.push('/merchant/center/traffic')">
        <div class="kpi-top"><span>在途订单</span><span class="kpi-ico"><Compass :size="18" /></span></div>
        <div class="kpi-value">{{ kpis.shipping || 0 }}</div>
        <div class="kpi-foot">已发货运输中</div>
      </div>
    </section>

    <section class="grid-main">
      <!-- 销售趋势 -->
      <a-card :bordered="false" class="panel">
        <template #title>
          <div class="panel-title">
            <div><strong>销售趋势</strong><small>近 7 日支付金额与订单走势</small></div>
            <a-button type="link" @click="$router.push('/merchant/center/traffic')">流量管理 →</a-button>
          </div>
        </template>
        <div v-if="hasTrend" class="chart">
          <div class="grid-line l1"></div><div class="grid-line l2"></div><div class="grid-line l3"></div>
          <div class="bars">
            <div v-for="d in trend" :key="d.date" class="bar-wrap"
                 :title="`${d.date}  ${money(d.amount)} 元 / ${d.orders} 单`">
              <div class="bar" :style="{ height: barHeight(d) }"></div>
            </div>
          </div>
        </div>
        <a-empty v-else :image="aEmpty.PRESENTED_IMAGE_SIMPLE" description="近 7 日暂无成交" />
        <div class="axis"><span>{{ trend[0]?.date || '—' }}</span><span>{{ trend[trend.length - 1]?.date || '—' }}</span></div>
      </a-card>

      <!-- 待办清单 -->
      <a-card :bordered="false" class="panel todo-panel">
        <template #title>
          <div class="panel-title"><div><strong>待办事项</strong><small>需要您关注的经营事项</small></div></div>
        </template>
        <template v-if="todos.length">
          <button v-for="t in todos" :key="t.key" type="button" class="todo-row" @click="$router.push(t.link)">
            <span class="todo-ico" :class="t.key"><component :is="todoIco(t.key)" :size="16" /></span>
            <span class="todo-label">{{ t.label }}</span>
            <b class="todo-count">{{ t.count }}</b>
            <span class="todo-go">→</span>
          </button>
        </template>
        <div v-else class="todo-empty"><CheckCircle2 :size="24" class="text-emerald-500 inline-block align-middle" /><div><b>一切井然有序</b><p>暂无待处理事项，继续保持。</p></div></div>
      </a-card>
    </section>

    <!-- 最新订单 -->
    <a-card :bordered="false" class="panel order-panel">
      <template #title>
        <div class="panel-title">
          <div><strong>最新订单</strong><small>包含您商品的最近订单</small></div>
          <a-button type="link" @click="$router.push('/merchant/center/orders')">全部订单 →</a-button>
        </div>
      </template>
      <a-table :data-source="recentOrders" :columns="orderCols" row-key="orderNo" :loading="loading"
               :scroll="{ x: 900 }" :pagination="false" class="mc-table">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'orderNo'">
            <a class="mono" @click="$router.push(`/merchant/center/orders/${record.order.orderNo}`)">{{ record.order.orderNo }}</a>
          </template>
          <template v-else-if="column.key === 'goods'">
            <div class="goods-cell">
              <img v-if="record.myItems?.[0]?.coverUrl" :src="record.myItems[0].coverUrl" alt="" loading="lazy" />
              <span v-else class="goods-fallback">{{ (record.myItems?.[0]?.prodName || '商').slice(0, 1) }}</span>
              <div>
                <b>{{ record.myItems?.[0]?.prodName || '—' }}</b>
                <small v-if="(record.myItems?.length || 0) > 1">等 {{ record.myItems.length }} 种商品</small>
              </div>
            </div>
          </template>
          <template v-else-if="column.key === 'myAmount'">¥{{ money(record.myAmount) }}</template>
          <template v-else-if="column.key === 'pay'">
            <a-tag :color="PAY_STATUS[record.order.payStatus]?.color">{{ PAY_STATUS[record.order.payStatus]?.text }}</a-tag>
          </template>
          <template v-else-if="column.key === 'status'">
            <a-tag :color="ORDER_STATUS[record.order.orderStatus]?.color">{{ ORDER_STATUS[record.order.orderStatus]?.text }}</a-tag>
          </template>
          <template v-else-if="column.key === 'op'">
            <a-button type="link" size="small"
                      @click="$router.push(`/merchant/center/orders/${record.order.orderNo}`)">
              {{ record.order.orderStatus === 0 && Number(record.order.payStatus) === 1 ? '去发货' : '查看详情' }}
            </a-button>
          </template>
        </template>
      </a-table>
    </a-card>

    <ProductPublishForm v-model:open="publishOpen" @published="load" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref, markRaw } from 'vue'
import { Empty } from 'ant-design-vue'
import {
  FileText,
  Truck,
  Compass,
  RotateCcw,
  Package,
  Search,
  AlertTriangle,
  CheckCircle2,
  Wallet,
  LayoutGrid
} from 'lucide-vue-next'
import { merchantCenterApi } from '../../api'
import { ORDER_STATUS, PAY_STATUS, fmtMoney } from '../../utils/merchantStatus'
import ProductPublishForm from '../../components/merchant/ProductPublishForm.vue'

const aEmpty = Empty
const loading = ref(true)
const publishOpen = ref(false)
const summary = ref({})

const kpis = computed(() => summary.value.kpis || {})
const trend = computed(() => summary.value.trend || [])
const todos = computed(() => summary.value.todos || [])
const recentOrders = computed(() => summary.value.recentOrders || [])
const hasTrend = computed(() => trend.value.some(d => Number(d.amount || 0) > 0))
const storeName = computed(() => summary.value.store?.entName || '商户')

const greeting = computed(() => {
  const h = new Date().getHours()
  return h < 6 ? '夜深了' : h < 12 ? '早上好' : h < 18 ? '下午好' : '晚上好'
})

const orderCols = [
  { title: '订单号', key: 'orderNo', width: 170 },
  { title: '商品', key: 'goods', ellipsis: true },
  { title: '我的金额', key: 'myAmount', width: 110 },
  { title: '支付', key: 'pay', width: 90 },
  { title: '状态', key: 'status', width: 90 },
  { title: '', key: 'op', width: 90 }
]

const money = fmtMoney
const TODO_MAP = {
  ship: markRaw(Package),
  refund: markRaw(RotateCcw),
  review: markRaw(Search),
  reject: markRaw(AlertTriangle)
}
function todoIco(key) {
  return TODO_MAP[key] || markRaw(Package)
}
function barHeight(d) {
  const max = Math.max(...trend.value.map(x => Number(x.amount || 0)), 0.01)
  return Math.max(5, (Number(d.amount || 0) / max) * 100) + '%'
}

async function load() {
  loading.value = true
  try { summary.value = (await merchantCenterApi.summary()) || {} } catch (e) { /* 拦截器已提示 */ }
  loading.value = false
}
onMounted(load)
</script>

<style scoped>
.dash-page { display: flex; flex-direction: column; gap: 16px; }

/* 头部 */
.hero { display: flex; justify-content: space-between; align-items: flex-end; gap: 14px; padding: 6px 2px 2px; }
.eyebrow { font-size: 12px; color: var(--mc-muted, #6e6e73); margin-bottom: 7px; letter-spacing: .04em; }
.dot { display: inline-block; width: 7px; height: 7px; border-radius: 50%; background: var(--mc-chart-solid, #0ea5e9); margin-right: 7px; }
.hero h1 { margin: 0; font-size: 28px; font-weight: 750; letter-spacing: -.03em; color: var(--mc-ink, #1d1d1f); line-height: 1.15; }
.hero p { margin: 8px 0 0; color: var(--mc-muted, #6e6e73); font-size: 14px; }
.hero-actions { display: flex; gap: 10px; flex-shrink: 0; }
.pill { border-radius: 999px; height: 40px; padding-inline: 18px; }

/* KPI */
.kpi-grid { display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; }
.kpi-card { background: var(--mc-card, #fff); border: 1px solid var(--mc-border, #e8e6e1); border-radius: 16px; padding: 16px 17px; min-height: 106px; cursor: pointer; transition: transform .2s ease, box-shadow .2s ease; position: relative; overflow: hidden; box-shadow: 0 2px 14px rgba(20, 24, 34, .04); }
.kpi-card:hover { transform: translateY(-2px); box-shadow: 0 10px 26px rgba(20, 24, 34, .08); }
.kpi-card:after { content: ""; position: absolute; right: -20px; bottom: -24px; width: 66px; height: 66px; border-radius: 50%; background: var(--mc-decor, #f5f0e8); }
.kpi-card.warn:after { background: #fbeee0; }
.kpi-top { display: flex; justify-content: space-between; align-items: center; font-size: 12px; color: var(--mc-muted, #6e6e73); }
.kpi-ico { color: var(--mc-accent, #9b2d20); font-size: 15px; }
.kpi-value { font-size: 24px; font-weight: 750; letter-spacing: -.02em; color: var(--mc-ink, #1d1d1f); margin: 10px 0 6px; }
.kpi-value em { font-style: normal; font-size: 13px; color: var(--mc-faint, #a1a1a6); font-weight: 500; margin-left: 3px; }
.kpi-foot { font-size: 11px; color: var(--mc-muted2, #86868b); }

/* 面板 */
.panel { border-radius: 16px; border: 1px solid var(--mc-border, #e8e6e1); box-shadow: 0 2px 14px rgba(20, 24, 34, .04); }
.panel :deep(.ant-card-head) { border-bottom: 1px solid var(--mc-border-soft, #f0eee9); padding: 0 20px; min-height: 60px; }
.panel :deep(.ant-card-body) { padding: 18px 20px; }
.panel-title { display: flex; align-items: center; justify-content: space-between; width: 100%; }
.panel-title strong { display: block; font-size: 15px; color: var(--mc-ink, #1d1d1f); letter-spacing: -.01em; }
.panel-title small { display: block; color: var(--mc-faint, #a1a1a6); font-size: 11px; font-weight: 400; margin-top: 3px; }

.grid-main { display: grid; grid-template-columns: 1.7fr 1fr; gap: 16px; }

/* 图表 */
.chart { height: 190px; position: relative; padding: 0 2px; }
.grid-line { position: absolute; left: 0; right: 0; border-top: 1px dashed var(--mc-border-soft, #ebe9e5); }
.l1 { top: 25%; } .l2 { top: 50%; } .l3 { top: 75%; }
.bars { height: 100%; display: flex; align-items: flex-end; gap: 5px; position: relative; z-index: 1; }
.bar-wrap { flex: 1; height: 100%; display: flex; align-items: flex-end; min-width: 2px; }
.bar { width: 100%; min-height: 4px; background: var(--mc-chart-grad, linear-gradient(180deg, #4fc3f7, #0ea5e9)); border-radius: 4px 4px 0 0; opacity: .92; transition: height .3s ease, opacity .2s ease; }
.bar-wrap:hover .bar { opacity: 1; }
.axis { display: flex; justify-content: space-between; color: var(--mc-faint, #a1a1a6); font-size: 10px; margin-top: 8px; }

/* 待办 */
.todo-panel :deep(.ant-card-body) { padding: 10px 12px; }
.todo-row { display: flex; align-items: center; gap: 11px; width: 100%; border: 0; background: transparent; font: inherit; text-align: left; padding: 12px 10px; border-radius: 12px; cursor: pointer; transition: background .18s ease; }
.todo-row:hover { background: var(--mc-soft, #f5f5f7); }
.todo-ico { width: 34px; height: 34px; border-radius: 10px; background: var(--mc-decor, #f5f0e8); display: grid; place-items: center; font-size: 15px; }
.todo-ico.refund, .todo-ico.reject { background: #fdeee6; }
.todo-label { font-size: 13.5px; color: var(--mc-ink, #1d1d1f); }
.todo-count { margin-left: auto; font-size: 15px; color: var(--mc-accent, #9b2d20); }
.todo-go { color: var(--mc-faint, #c7c7cc); font-size: 13px; }
.todo-empty { display: flex; gap: 12px; align-items: flex-start; padding: 22px 12px; }
.todo-empty span { width: 34px; height: 34px; border-radius: 50%; background: #e8f5ee; color: #16805a; display: grid; place-items: center; font-size: 15px; flex-shrink: 0; }
.todo-empty b { font-size: 13.5px; color: var(--mc-ink, #1d1d1f); }
.todo-empty p { margin: 3px 0 0; font-size: 12px; color: var(--mc-muted2, #86868b); }

/* 订单表 */
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12.5px; }
.goods-cell { display: flex; align-items: center; gap: 9px; min-width: 0; }
.goods-cell img { width: 34px; height: 34px; border-radius: 8px; object-fit: cover; background: var(--mc-soft, #f5f5f7); flex-shrink: 0; }
.goods-fallback { width: 34px; height: 34px; border-radius: 8px; background: var(--mc-soft, #f5f5f7); color: var(--mc-faint, #b9c3d4); display: grid; place-items: center; font-size: 14px; flex-shrink: 0; }
.goods-cell b { display: block; font-size: 12.5px; font-weight: 500; color: var(--mc-ink, #1d1d1f); max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.goods-cell small { color: var(--mc-faint, #a1a1a6); font-size: 11px; }

/* 响应式（平板 / 手机浏览器） */
@media (max-width: 1050px) {
  .kpi-grid { grid-template-columns: repeat(3, 1fr); }
  .grid-main { grid-template-columns: 1fr; }
}
@media (max-width: 700px) {
  .hero { flex-direction: column; align-items: flex-start; }
  .hero h1 { font-size: 22px; }
  .hero-actions { width: 100%; }
  .hero-actions .pill { flex: 1; }
  .kpi-grid { grid-template-columns: repeat(2, 1fr); gap: 10px; }
  .kpi-card { padding: 13px; min-height: 92px; }
  .kpi-value { font-size: 19px; }
  .panel :deep(.ant-card-body) { padding: 14px; }
}
</style>

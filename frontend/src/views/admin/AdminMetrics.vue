<template>
  <div class="metrics-page">
    <a-page-header
      title="平台经营指标"
      sub-title="行为漏斗 / 分来源 CTR / 交易护栏 / 广告投放 — 通用评估体系统一口径"
    />

    <div class="metrics-toolbar">
      <a-radio-group v-model:value="days" button-style="solid" @change="load">
        <a-radio-button :value="7">近 7 天</a-radio-button>
        <a-radio-button :value="14">近 14 天</a-radio-button>
        <a-radio-button :value="30">近 30 天</a-radio-button>
      </a-radio-group>
      <a-button :loading="loading" @click="load">刷新</a-button>
    </div>

    <a-spin :spinning="loading">
      <!-- 一、行为漏斗：访问→浏览→点击→加购→支付（t_traffic_event） -->
      <section class="block">
        <h3 class="block-title">行为漏斗（近 {{ data?.days || days }} 天）</h3>
        <div class="stat-row">
          <div class="stat-cell">
            <a-statistic title="访问会话" :value="funnel.visits" />
          </div>
          <div class="stat-cell">
            <a-statistic title="商品浏览" :value="funnel.views" />
          </div>
          <div class="stat-cell">
            <a-statistic title="商品点击" :value="funnel.clicks" />
          </div>
          <div class="stat-cell">
            <a-statistic title="加购" :value="funnel.carts" />
          </div>
          <div class="stat-cell">
            <a-statistic title="支付" :value="funnel.pays" />
          </div>
          <div class="stat-cell">
            <a-statistic title="浏览→点击 CTR" :value="funnel.ctr" :precision="2" suffix="%" />
          </div>
        </div>
        <a-table
          class="sub-table"
          :data-source="funnel.daily" row-key="date" :pagination="false" size="small"
        >
          <a-table-column title="日期" data-index="date" :width="120" />
          <a-table-column title="访问" data-index="visits" :width="80" />
          <a-table-column title="浏览" data-index="views" :width="80" />
          <a-table-column title="点击" data-index="clicks" :width="80" />
          <a-table-column title="加购" data-index="carts" :width="80" />
          <a-table-column title="支付" data-index="pays" :width="80" />
          <a-table-column title="当日 CTR（点击/浏览）" data-index="ctr" :width="160">
            <template #default="{ record }">{{ fmtPct(record.ctr) }}</template>
          </a-table-column>
        </a-table>
      </section>

      <!-- 二、分来源效率：推荐位 sourceType=recommend 与搜索/直访等来源对比 -->
      <section class="block">
        <h3 class="block-title">分来源效率（浏览 / 点击 / CTR）</h3>
        <a-empty v-if="!bySource.length" description="暂无来源数据" />
        <a-table
          v-else
          :data-source="bySource" row-key="source" :pagination="false" size="small"
        >
          <a-table-column title="来源" data-index="source" :width="140" />
          <a-table-column title="商品浏览" data-index="views" :width="110" />
          <a-table-column title="浏览占比" :width="220">
            <template #default="{ record }">
              <div class="bar-wrap">
                <div class="bar" :style="{ width: barWidth(record.viewSharePct) }"></div>
                <span class="bar-label">{{ fmtPct(record.viewSharePct) }}</span>
              </div>
            </template>
          </a-table-column>
          <a-table-column title="商品点击" data-index="clicks" :width="110" />
          <a-table-column title="CTR（点击/浏览）" data-index="ctr" :width="150">
            <template #default="{ record }">
              <span :class="{ 'ctr-strong': record.source === 'recommend' }">{{ fmtPct(record.ctr) }}</span>
            </template>
          </a-table-column>
        </a-table>
      </section>

      <!-- 三、交易护栏：GMV / 订单数 / AOV / 付款用户数（已支付订单） -->
      <section class="block">
        <h3 class="block-title">交易护栏（近 {{ data?.days || days }} 天，已支付订单）</h3>
        <div class="stat-row">
          <div class="stat-cell">
            <a-statistic title="GMV" :value="tradeTotal.gmv" :precision="2" prefix="¥" />
          </div>
          <div class="stat-cell">
            <a-statistic title="订单数" :value="tradeTotal.orders" />
          </div>
          <div class="stat-cell">
            <a-statistic title="客单价 AOV" :value="tradeTotal.aov" :precision="2" prefix="¥" />
          </div>
          <div class="stat-cell">
            <a-statistic title="付款用户数" :value="tradeTotal.payUsers" />
          </div>
        </div>
        <a-table
          class="sub-table"
          :data-source="dailyTrade" row-key="date" :pagination="false" size="small"
        >
          <a-table-column title="日期" data-index="date" :width="120" />
          <a-table-column title="GMV（元）" data-index="gmv" :width="130">
            <template #default="{ record }">{{ fmtMoney(record.gmv) }}</template>
          </a-table-column>
          <a-table-column title="订单数" data-index="orders" :width="100" />
          <a-table-column title="客单价 AOV" data-index="aov" :width="120">
            <template #default="{ record }">{{ fmtMoney(record.aov) }}</template>
          </a-table-column>
          <a-table-column title="付款用户数" data-index="payUsers" :width="110" />
        </a-table>
      </section>

      <!-- 四、广告推广汇总（t_ad_campaign） -->
      <section class="block">
        <h3 class="block-title">广告推广汇总（全量）</h3>
        <div class="stat-row">
          <div class="stat-cell">
            <a-statistic title="投放中计划" :value="ads.activeCampaigns" />
          </div>
          <div class="stat-cell">
            <a-statistic title="计划总数" :value="ads.campaigns" />
          </div>
          <div class="stat-cell">
            <a-statistic title="曝光" :value="ads.impressions" />
          </div>
          <div class="stat-cell">
            <a-statistic title="点击" :value="ads.clicks" />
          </div>
          <div class="stat-cell">
            <a-statistic title="点击率" :value="ads.ctr" :precision="2" suffix="%" />
          </div>
          <div class="stat-cell">
            <a-statistic title="已花费" :value="ads.spent" :precision="2" prefix="¥" />
          </div>
        </div>
      </section>
    </a-spin>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { adminApi } from '../../api'

const days = ref(14)
const loading = ref(false)
const data = ref(null)

const funnel = computed(() => {
  const f = data.value?.funnel || {}
  return {
    visits: f.visits || 0,
    views: f.views || 0,
    clicks: f.clicks || 0,
    carts: f.carts || 0,
    pays: f.pays || 0,
    ctr: f.ctr || 0,
    daily: f.daily || []
  }
})
const bySource = computed(() => data.value?.bySource || [])
const dailyTrade = computed(() => data.value?.dailyTrade || [])
const ads = computed(() => {
  const a = data.value?.ads || {}
  return {
    campaigns: a.campaigns || 0,
    activeCampaigns: a.activeCampaigns || 0,
    impressions: a.impressions || 0,
    clicks: a.clicks || 0,
    ctr: a.ctr || 0,
    spent: a.spent || 0
  }
})

/** 窗口合计：GMV/订单/AOV/付款用户由逐日行汇总（AOV=总GMV/总订单，与后端口径一致） */
const tradeTotal = computed(() => {
  let gmv = 0
  let orders = 0
  let payUsers = 0
  for (const r of dailyTrade.value) {
    gmv += Number(r.gmv) || 0
    orders += Number(r.orders) || 0
    payUsers += Number(r.payUsers) || 0
  }
  return {
    gmv,
    orders,
    payUsers,
    aov: orders > 0 ? gmv / orders : 0
  }
})

async function load() {
  loading.value = true
  try {
    data.value = await adminApi.metricsOverview(days.value)
  } catch (e) {
    message.error('经营指标加载失败')
  } finally {
    loading.value = false
  }
}

function fmtPct(v) {
  return (Number(v) || 0).toFixed(2) + '%'
}
function fmtMoney(v) {
  return '¥' + (Number(v) || 0).toFixed(2)
}
function barWidth(pct) {
  return Math.min(Math.max(Number(pct) || 0, 0), 100) + '%'
}

onMounted(load)
</script>

<style scoped>
.metrics-page { max-width: 1080px; margin: 0 auto; padding-bottom: 24px; }
.metrics-toolbar { display: flex; gap: 12px; margin: 4px 0 16px; }

.block {
  background: #fff;
  border: 1px solid #e6e9ef;
  border-radius: 12px;
  padding: 16px 18px;
  margin-bottom: 16px;
}
.block-title {
  margin: 0 0 14px;
  font-size: 14px;
  font-weight: 700;
  color: #13233A;
}

.stat-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 12px;
  margin-bottom: 14px;
}
.stat-cell { padding: 10px 12px; background: #F8FAFC; border-radius: 8px; }
.stat-cell :deep(.ant-statistic-title) { font-size: 12px; color: #64748B; margin-bottom: 4px; }
.stat-cell :deep(.ant-statistic-content) { font-size: 18px; color: #13233A; font-weight: 700; }

.sub-table { margin-top: 4px; }

.bar-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
}
.bar {
  height: 8px;
  border-radius: 4px;
  background: linear-gradient(90deg, #1F3864, #2563EB);
  min-width: 2px;
  transition: width .3s ease;
}
.bar-label { font-size: 11px; color: #64748B; white-space: nowrap; }

.ctr-strong { color: #C2410C; font-weight: 700; }

@media (max-width: 767px) {
  .stat-row { grid-template-columns: repeat(2, 1fr); }
}
</style>

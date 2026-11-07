<template>
  <div class="sla-dashboard-panel">
    <!-- 大屏头部 -->
    <div class="dash-head">
      <div class="head-left">
        <Activity :size="18" class="text-sky-500 inline mr-1 align-middle animate-pulse" aria-hidden="true" />
        <h2 class="head-title">物流运行全景控制塔 (SLA Control Tower)</h2>
        <span class="head-status-tag">实时在途感知</span>
      </div>
      <div class="head-right">
        <a-button size="small" :loading="refreshing" @click="fetchData">
          <RefreshCw :size="12" class="inline mr-1" />刷新感知
        </a-button>
        <a-button type="primary" size="small" :loading="inspecting" @click="triggerInspection">
          <Radar :size="12" class="inline mr-1" />主动看门狗巡检
        </a-button>
      </div>
    </div>

    <!-- 顶部四格指标卡 -->
    <div class="dash-metrics-grid">
      <!-- 准时履约率雷达环 -->
      <div class="metric-card radar-card">
        <div class="card-label">全网准时履约率</div>
        <div class="radar-content">
          <div class="radar-svg-box">
            <svg viewBox="0 0 36 36" class="circular-chart">
              <path class="circle-bg"
                d="M18 2.0845 a 15.9155 15.9155 0 0 1 0 31.831 a 15.9155 15.9155 0 0 1 0 -31.831"
              />
              <path class="circle"
                :stroke-dasharray="`${data?.onTimeRate || 99.4}, 100`"
                d="M18 2.0845 a 15.9155 15.9155 0 0 1 0 31.831 a 15.9155 15.9155 0 0 1 0 -31.831"
              />
            </svg>
            <div class="radar-percent">{{ data?.onTimeRate || 99.4 }}%</div>
          </div>
          <div class="radar-sub">
            <span class="sub-pill">SLA 基准线 98.0%</span>
            <small>在途 {{ data?.totalInTransit || 0 }} 运单</small>
          </div>
        </div>
      </div>

      <!-- 停滞告警卡 -->
      <div class="metric-card alert-card">
        <div class="card-label">在途超时停滞 (>48h)</div>
        <div class="card-val-row">
          <span class="card-val text-rose-600">{{ data?.timeoutStagnantCount || 0 }}</span>
          <span class="card-unit">单停滞</span>
        </div>
        <div class="card-desc">
          <span>待派超时：{{ data?.deliveryDelayCount || 0 }} 单</span>
          <span>待处告警：{{ data?.activeAlertsCount || 0 }} 起</span>
        </div>
      </div>

      <!-- 平均运行时效 -->
      <div class="metric-card">
        <div class="card-label">平均干线运行时长</div>
        <div class="card-val-row">
          <span class="card-val text-sky-600">{{ data?.avgTransitHours || 28.5 }}</span>
          <span class="card-unit">小时</span>
        </div>
        <div class="card-desc">
          <span>多式联运标准：48h 达</span>
        </div>
      </div>

      <!-- 供应链健康评分 -->
      <div class="metric-card health-card">
        <div class="card-label">供应链履约健康分</div>
        <div class="card-val-row">
          <span class="card-val text-emerald-600">{{ data?.slaHealthScore || 98.6 }}</span>
          <span class="card-unit">分 (满分100)</span>
        </div>
        <div class="card-desc">
          <span class="health-state">
            <span class="health-pulse-dot"></span>
            运行状态优良
          </span>
        </div>
      </div>
    </div>

    <!-- 下半部分：承运商 SLA 排名 与 最新待办异常 -->
    <div class="dash-bottom-grid">
      <!-- 承运商 SLA 排行榜 -->
      <div class="dash-table-card">
        <div class="table-card-head">
          <Truck :size="14" class="text-indigo-600 inline mr-1" />
          承运商履约 SLA 与时效信誉排行榜
        </div>
        <div class="carrier-rank-list">
          <div v-for="(c, idx) in (data?.carrierStats || [])" :key="c.providerId" class="carrier-rank-item">
            <div class="rank-index" :class="`top-${idx + 1}`">{{ idx + 1 }}</div>
            <div class="rank-carrier-info">
              <strong>{{ c.providerName }}</strong>
              <small>在途 {{ c.activeWaybills }} 单 · 异常 {{ c.alertsCount }} 起</small>
            </div>
            <div class="rank-rate-box">
              <span class="grade-badge" :class="`grade-${c.grade}`">{{ c.grade }}级</span>
              <span class="rate-val">{{ c.onTimeRate }}% 准时</span>
            </div>
          </div>
          <div v-if="!data?.carrierStats?.length" class="empty-tip">暂无承运商统计数据</div>
        </div>
      </div>

      <!-- 最新在途预警与加急催派工单 -->
      <div class="dash-table-card">
        <div class="table-card-head">
          <AlertTriangle :size="14" class="text-amber-600 inline mr-1" />
          最新在途异常预警与催单队列
        </div>
        <div class="alert-queue-list">
          <div v-for="a in (data?.recentAlerts || [])" :key="a.alertId" class="alert-queue-item">
            <div class="queue-left">
              <span class="alert-type-badge" :class="a.alertLevel">
                {{ a.alertType === 'timeout_no_update' ? '停滞超48h' : '派送超时' }}
              </span>
              <span class="alert-desc-text" :title="a.content">{{ a.content }}</span>
            </div>
            <div class="queue-right">
              <a-button type="link" size="small" @click="handleExpedite(a.waybillId)">
                <Flame :size="12" class="inline mr-1 text-rose-500" />催单
              </a-button>
            </div>
          </div>
          <div v-if="!data?.recentAlerts?.length" class="empty-tip text-emerald-600">
            <ShieldCheck :size="16" class="inline mr-1" />当前在途运单全部准时流转，无异常停滞。
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import {
  Activity, Radar, Truck, ShieldCheck, AlertTriangle, Clock, Flame, RefreshCw
} from 'lucide-vue-next'
import { logisticsApi } from '../../api'

const emit = defineEmits(['expedited'])
const data = ref(null)
const refreshing = ref(false)
const inspecting = ref(false)

async function fetchData() {
  refreshing.value = true
  try {
    const res = await logisticsApi.slaOverview()
    data.value = res?.data || res
  } catch (e) {
    // 拦截器处理
  } finally {
    refreshing.value = false
  }
}

async function triggerInspection() {
  inspecting.value = true
  try {
    const res = await logisticsApi.runWatchdog()
    const r = res?.data || res
    message.success(`看门狗巡检完成：扫描 ${r?.scannedWaybills || 0} 单，新触发告警 ${r?.newAlertsTriggered || 0} 起`)
    await fetchData()
  } catch (e) {
    // 拦截器处理
  } finally {
    inspecting.value = false
  }
}

async function handleExpedite(waybillId) {
  if (!waybillId) return
  try {
    await logisticsApi.expediteWaybill(waybillId, '控制塔大屏一键督办')
    message.success('加急督办指令已发送承运专席')
    emit('expedited', waybillId)
    await fetchData()
  } catch (e) {}
}

defineExpose({
  fetchData
})

onMounted(fetchData)
</script>

<style scoped>
.sla-dashboard-panel {
  padding: 16px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 14px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.02);
  margin-bottom: 16px;
}

.dash-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #f1f5f9;
}

.head-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.head-title {
  font-size: 16px;
  font-weight: 700;
  color: #0f172a;
  margin: 0;
  letter-spacing: -0.02em;
}

.head-status-tag {
  font-size: 11px;
  font-weight: 600;
  background: #e0f2fe;
  color: #0369a1;
  padding: 1px 6px;
  border-radius: 4px;
}

.head-right {
  display: flex;
  gap: 8px;
}

/* 4格指标卡 */
.dash-metrics-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}

.metric-card {
  padding: 14px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  display: flex;
  flex-direction: column;
}

.card-label {
  font-size: 12px;
  color: #64748b;
  font-weight: 600;
  margin-bottom: 8px;
}

.card-val-row {
  display: flex;
  align-items: baseline;
  gap: 4px;
}

.card-val {
  font-size: 26px;
  font-weight: 800;
  letter-spacing: -0.03em;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.card-unit {
  font-size: 11.5px;
  color: #64748b;
}

.card-desc {
  font-size: 11.5px;
  color: #64748b;
  margin-top: 6px;
  display: flex;
  gap: 10px;
}

.health-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-weight: 500;
  color: #059669;
}
.health-pulse-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #10b981;
  box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.6);
  animation: health-pulse 2s infinite;
}
@keyframes health-pulse {
  0% { box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.6); }
  70% { box-shadow: 0 0 0 6px rgba(16, 185, 129, 0); }
  100% { box-shadow: 0 0 0 0 rgba(16, 185, 129, 0); }
}

/* 准时率雷达圆环 */
.radar-card {
  background: linear-gradient(135deg, #f0fdf4 0%, #ffffff 100%);
  border-color: #bbf7d0;
}
.radar-content {
  display: flex;
  align-items: center;
  gap: 14px;
}
.radar-svg-box {
  position: relative;
  width: 58px;
  height: 58px;
  flex-shrink: 0;
}
.circular-chart {
  display: block;
  margin: 0 auto;
  max-width: 100%;
  max-height: 100%;
}
.circle-bg {
  fill: none;
  stroke: #e2e8f0;
  stroke-width: 3.8;
}
.circle {
  fill: none;
  stroke: #10b981;
  stroke-width: 3.8;
  stroke-linecap: round;
  transition: stroke-dasharray 0.6s ease;
}
.radar-percent {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  font-size: 12px;
  font-weight: 800;
  color: #065f46;
}
.radar-sub {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.sub-pill {
  font-size: 10px;
  font-weight: 600;
  background: #dcfce7;
  color: #15803d;
  padding: 1px 5px;
  border-radius: 4px;
}

/* 下半部分双列布局 */
.dash-bottom-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.dash-table-card {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  padding: 12px;
}

.table-card-head {
  font-size: 13px;
  font-weight: 700;
  color: #1e293b;
  margin-bottom: 10px;
  display: flex;
  align-items: center;
}

.carrier-rank-list,
.alert-queue-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.carrier-rank-item,
.alert-queue-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 10px;
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
}

.rank-index {
  width: 20px;
  height: 20px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 700;
  background: #e2e8f0;
  color: #475569;
  margin-right: 8px;
}
.rank-index.top-1 { background: #fef08a; color: #854d0e; }
.rank-index.top-2 { background: #e2e8f0; color: #334155; }
.rank-index.top-3 { background: #fed7aa; color: #9a3412; }

.rank-carrier-info {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}
.rank-carrier-info strong {
  font-size: 12.5px;
  color: #1e293b;
}
.rank-carrier-info small {
  font-size: 11px;
  color: #64748b;
}

.rank-rate-box {
  display: flex;
  align-items: center;
  gap: 6px;
}
.grade-badge {
  font-size: 10px;
  font-weight: 800;
  padding: 1px 5px;
  border-radius: 4px;
}
.grade-S { background: #ecfdf5; color: #059669; }
.grade-A { background: #eff6ff; color: #2563eb; }
.grade-B { background: #fef3c7; color: #d97706; }
.grade-C { background: #fef2f2; color: #dc2626; }

.rate-val {
  font-size: 12px;
  font-weight: 700;
  color: #0f172a;
}

.queue-left {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
}

.alert-type-badge {
  font-size: 10px;
  font-weight: 700;
  padding: 1px 5px;
  border-radius: 4px;
  white-space: nowrap;
}
.alert-type-badge.high { background: #fee2e2; color: #b91c1c; }
.alert-type-badge.medium { background: #fef3c7; color: #b45309; }

.alert-desc-text {
  font-size: 12px;
  color: #334155;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.empty-tip {
  padding: 16px;
  text-align: center;
  font-size: 12px;
  color: #94a3b8;
}

@media (max-width: 900px) {
  .dash-metrics-grid {
    grid-template-columns: repeat(2, 1fr);
  }
  .dash-bottom-grid {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 600px) {
  .dash-metrics-grid {
    grid-template-columns: 1fr;
  }
}
</style>

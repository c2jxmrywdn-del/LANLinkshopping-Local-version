<template>
  <a-drawer
    :open="open"
    :title="drawerTitle"
    :width="drawerWidth"
    placement="right"
    @close="$emit('update:open', false)"
  >
    <template v-if="waybill">
      <!-- 运单头部信息栏 -->
      <div class="wb-head">
        <div class="wb-no">
          <span>运单号</span>
          <b>{{ waybill.waybillNo }}</b>
        </div>
        <div class="wb-badges">
          <a-tag :color="WAYBILL_STATUS[waybill.status]?.color">
            {{ WAYBILL_STATUS[waybill.status]?.text || waybill.status }}
          </a-tag>
        </div>
      </div>

      <!-- 视图模式切换器 (21st.dev 工业科技风双模切换) -->
      <div class="view-mode-tabs">
        <button
          type="button"
          class="mode-tab-btn"
          :class="{ 'is-active': viewMode === 'map' }"
          @click="viewMode = 'map'"
        >
          <MapPin :size="13" class="inline mr-1 text-sky-500" />
          GIS 矢量地图动态浏览
        </button>
        <button
          type="button"
          class="mode-tab-btn"
          :class="{ 'is-active': viewMode === 'timeline' }"
          @click="viewMode = 'timeline'"
        >
          <ListTree :size="13" class="inline mr-1 text-indigo-500" />
          流转节点时间轴
        </button>
        <button
          type="button"
          class="mode-tab-btn"
          :class="{ 'is-active': viewMode === 'both' }"
          @click="viewMode = 'both'"
        >
          <Layers :size="13" class="inline mr-1 text-emerald-500" />
          全景分屏合览
        </button>
      </div>

      <!-- 运单概要属性折叠卡 -->
      <a-descriptions :column="{ xs: 1, sm: 2 }" size="small" class="wb-desc" bordered>
        <a-descriptions-item v-if="waybill.logisticsNo" label="快递单号">
          <span class="mono">{{ waybill.logisticsNo }}</span>
        </a-descriptions-item>
        <a-descriptions-item label="承运商">
          <strong>{{ waybill.providerName || '工业干线专线' }}</strong>
        </a-descriptions-item>
        <a-descriptions-item label="收货人">
          {{ waybill.receiverName }} {{ maskPhone(waybill.receiverPhone) }}
        </a-descriptions-item>
        <a-descriptions-item label="发货时间">
          {{ fmt(waybill.shipTime) }}
        </a-descriptions-item>
        <a-descriptions-item label="收货地址" :span="2">
          {{ waybill.receiverAddress }}
        </a-descriptions-item>
      </a-descriptions>

      <!-- 视图 1：GIS 矢量地图动态浏览与时空回放 -->
      <div v-show="viewMode === 'map' || viewMode === 'both'" class="gis-map-section">
        <div class="section-title">
          <Navigation :size="14" class="inline mr-1 text-sky-500" />
          全景地图动态轨迹与时空回放
        </div>
        <LogisticsDynamicMap
          :map-route="synthesizedMapRoute"
          :waybill-info="waybill"
          :interactive="true"
          :auto-play="false"
        />
      </div>

      <!-- 视图 2：时间轴明细 -->
      <div v-show="viewMode === 'timeline' || viewMode === 'both'" class="timeline-section">
        <div class="section-title">
          <ListTree :size="14" class="inline mr-1 text-indigo-500" />
          节点流转详情 ({{ traces.length }})
        </div>
        <a-spin :spinning="loading">
          <div v-if="traces.length" class="tl">
            <div v-for="(t, i) in traces" :key="i" class="tl-row" :class="{ latest: i === 0 }">
              <div class="tl-dot"></div>
              <div class="tl-body">
                <p class="tl-text">{{ t.description }}</p>
                <small>{{ t.location ? t.location + ' · ' : '' }}{{ fmt(t.traceTime) }}</small>
              </div>
            </div>
          </div>
          <a-empty v-else :image="aEmpty.PRESENTED_IMAGE_SIMPLE" description="暂无轨迹节点，车辆正在调拨中" />
        </a-spin>
      </div>
    </template>
  </a-drawer>
</template>

<script setup>
import { computed, ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { Empty } from 'ant-design-vue'
import { MapPin, ListTree, Layers, Navigation } from 'lucide-vue-next'
import { logisticsApi } from '../../api'
import { WAYBILL_STATUS } from '../../utils/merchantStatus'
import { synthesizeHighwayRoute, findCityGeo, parseCityFromAddress } from '../../utils/logisticsGis'
import LogisticsDynamicMap from '../logistics/LogisticsDynamicMap.vue'

const aEmpty = Empty
const props = defineProps({
  open: Boolean,
  waybill: { type: Object, default: null }
})
defineEmits(['update:open'])

const loading = ref(false)
const traces = ref([])
const viewMode = ref('both') // 'map' | 'timeline' | 'both'
const windowWidth = ref(window.innerWidth)

function onResize() {
  windowWidth.value = window.innerWidth
}
onMounted(() => window.addEventListener('resize', onResize))
onBeforeUnmount(() => window.removeEventListener('resize', onResize))

const isMobile = computed(() => windowWidth.value <= 768)
const drawerWidth = computed(() => {
  if (isMobile.value) return '100%'
  if (viewMode.value === 'both') return 820
  return 740
})

const drawerTitle = computed(() => {
  return props.waybill ? `运单动态追踪 · ${props.waybill.waybillNo}` : '物流轨迹'
})

function fmt(t) { return (t || '').replace('T', ' ').slice(0, 16) }
function maskPhone(p) {
  const s = String(p || '')
  return s.length === 11 ? s.slice(0, 3) + '****' + s.slice(7) : s
}

/**
 * 智能合成 GIS 矢量拓扑坐标节点：
 * 若运单已携带 mapRoute，直接使用；
 * 否则从运单地址与轨迹列表中识别主要城市（华南、华中、华北、西南枢纽）合成动态经纬度路线。
 */
const synthesizedMapRoute = computed(() => {
  if (props.waybill?.mapRoute && props.waybill.mapRoute.nodes?.length) {
    return props.waybill.mapRoute
  }

  // 根据收货地址智能提取目标城市（支持中英文拼音如 Chongqing / 重庆）
  const destCity = parseCityFromAddress(props.waybill?.receiverAddress)
  const originCity = parseCityFromAddress(props.waybill?.senderAddress) || '深圳'
  const isReverse = props.waybill?.waybillDirection === 'reverse' || (typeof props.waybill?.waybillNo === 'string' && props.waybill.waybillNo.startsWith('RW'))

  // 沿国家骨干物流走廊（如 G65、G4、G42 等）合成途经枢纽路线（支持逆向退运）
  const highwayPlan = synthesizeHighwayRoute(originCity, destCity, isReverse)

  return {
    corridorName: highwayPlan.corridorName,
    distanceKm: highwayPlan.distanceKm,
    origin: highwayPlan.nodes[0],
    destination: highwayPlan.nodes[highwayPlan.nodes.length - 1],
    nodes: highwayPlan.nodes,
    truckPosition: {
      progressFraction: props.waybill?.status === 'delivered' ? 1.0 : (props.waybill?.status === 'out_for_delivery' ? 0.85 : 0.45),
      headingAngle: 28
    }
  }
})

watch(() => props.open, async (v) => {
  if (!v || !props.waybill?.waybillId) return
  loading.value = true
  traces.value = []
  try {
    traces.value = (await logisticsApi.traces(props.waybill.waybillId)) || []
  } catch (e) {
    // 拦截器处理
  }
  loading.value = false
})
</script>

<style scoped>
.wb-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.wb-no span {
  display: block;
  font-size: 11px;
  color: var(--mc-muted2, #86868b);
}
.wb-no b {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 14px;
  color: var(--mc-ink, #1d1d1f);
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

/* 21st.dev 双模切换按钮栏 */
.view-mode-tabs {
  display: flex;
  gap: 8px;
  background: #f1f5f9;
  border-radius: 8px;
  padding: 4px;
  margin-bottom: 14px;
}
.mode-tab-btn {
  flex: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 6px 10px;
  font-size: 12px;
  font-weight: 500;
  border-radius: 6px;
  border: none;
  background: transparent;
  color: #64748b;
  cursor: pointer;
  transition: all 0.2s ease;
  touch-action: manipulation;
}
.mode-tab-btn:hover {
  color: #0f172a;
}
.mode-tab-btn.is-active {
  background: #ffffff;
  color: #0284c7;
  font-weight: 600;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

.wb-desc {
  margin-bottom: 16px;
  border-radius: 8px;
  overflow: hidden;
}

.section-title {
  display: flex;
  align-items: center;
  font-size: 13px;
  font-weight: 600;
  color: #1e293b;
  margin-bottom: 8px;
}

.gis-map-section {
  margin-bottom: 18px;
}

.timeline-section {
  padding-top: 6px;
}

/* 轨迹时间轴 */
.tl {
  display: flex;
  flex-direction: column;
  margin-top: 10px;
}
.tl-row {
  position: relative;
  padding: 0 0 18px 22px;
  border-left: 2px solid var(--mc-border-soft, #eceae5);
  margin-left: 5px;
}
.tl-row:last-child {
  border-left-color: transparent;
  padding-bottom: 2px;
}
.tl-dot {
  position: absolute;
  left: -6px;
  top: 2px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #cbd5e1;
}
.tl-row.latest .tl-dot {
  background: #0284c7;
  box-shadow: 0 0 0 4px rgba(2, 132, 199, 0.2);
}
.tl-text {
  margin: 0;
  font-size: 13px;
  color: var(--mc-text2, #3a3a3c);
  line-height: 1.5;
}
.tl-row.latest .tl-text {
  color: var(--mc-ink, #1d1d1f);
  font-weight: 600;
}
.tl-body small {
  color: var(--mc-faint, #a1a1a6);
  font-size: 11px;
}
</style>

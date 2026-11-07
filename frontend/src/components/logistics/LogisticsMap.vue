<template>
  <div class="ll-route-map">
    <div class="rm-head">
      <span class="rm-title">
        <RouteIcon :size="14" />
        <span>轨迹地图</span>
      </span>
      <span class="rm-meta">
        <span class="rm-count">{{ '共 ' + nodeCount + ' 个节点' }}</span>
        <span class="rm-mode" :class="{ 'is-amap': mode === 'amap' }">
          {{ mode === 'amap' ? '高德图源' : '城市级示意' }}
        </span>
      </span>
    </div>

    <div ref="amapContainerRef" class="rm-canvas" :style="{ height: canvasHeight }">
      <!-- AMap 加载遮罩 -->
      <div v-if="mode === 'amap' && initializing" class="rm-mask">
        <span class="rm-spinner" aria-hidden="true"></span>
        <span>正在加载高德图源…</span>
      </div>

      <!-- 城市级占位（无经纬度坐标 或 AMap 不可用时的降级展示） -->
      <div v-if="mode === 'city'" class="rm-city">
        <template v-if="cityRoute.length">
          <div class="rm-city-route">
            <template v-for="(c, i) in cityRoute" :key="c.name + '-' + i">
              <span class="rm-city-chip" :class="{ 'is-abnormal': c.abnormal }">
                <span class="rm-city-dot" aria-hidden="true"></span>
                <span class="rm-city-name">{{ c.name }}</span>
              </span>
              <MoveRight v-if="i < cityRoute.length - 1" :size="14" class="rm-city-arrow" />
            </template>
          </div>
          <div v-if="cityStartEnd" class="rm-city-se">
            <span><MapPinned :size="12" /> 始发：{{ cityStartEnd.start }}</span>
            <span><Flag :size="12" /> 目的地：{{ cityStartEnd.end }}</span>
          </div>
        </template>
        <div v-else class="rm-empty">
          <MapPinned :size="18" />
          <span>暂无轨迹</span>
        </div>
        <div v-if="degradeReason" class="rm-degrade">{{ degradeReason }}</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import AMapLoader from '@amap/amap-jsapi-loader'
import request from '../../api/request'
import { Route as RouteIcon, MoveRight, MapPinned, Flag } from 'lucide-vue-next'

const props = defineProps({
  /** GET /logistics/trace/{waybillId} 原始返回：LogisticsTrace[]（后端按 traceTime 升序） */
  traces: { type: Array, default: () => [] },
  /** 画布高度（px 数字或任意 CSS 长度） */
  height: { type: [Number, String], default: 340 }
})

const ABNORMAL_STATUSES = new Set(['exception', 'returned', 'lost'])

/* ===== 模式仲裁：有经纬度走 AMap；无坐标或 AMap 不可用降级城市级占位 ===== */
const mode = ref('amap') // 'amap' | 'city'
const initializing = ref(false)
const degradeReason = ref('')
const amapContainerRef = ref(null)
const canvasHeight = computed(() =>
  typeof props.height === 'number' ? props.height + 'px' : String(props.height || '340px'))
const nodeCount = computed(() => (props.traces || []).length)

/* 带坐标的节点（相邻完全重合的节点去重，避免零长折线段） */
const geoNodes = computed(() => {
  const out = []
  for (const n of (props.traces || [])) {
    if (!n) continue
    const lng = Number(n.longitude)
    const lat = Number(n.latitude)
    if (!Number.isFinite(lng) || !Number.isFinite(lat) || (!lng && !lat)) continue
    const name = String(n.location || n.city || '')
    const prev = out[out.length - 1]
    if (prev && prev.name === name && Math.abs(prev.lng - lng) < 1e-6 && Math.abs(prev.lat - lat) < 1e-6) continue
    out.push({
      lng, lat, name,
      abnormal: Number(n.isAbnormal) === 1 || ABNORMAL_STATUSES.has(String(n.status || ''))
    })
  }
  return out
})

/* 城市级占位路线：city 优先、location 兜底（截断展示），相邻同名合并 */
const cityRoute = computed(() => {
  const out = []
  for (const n of (props.traces || [])) {
    if (!n) continue
    let name = String(n.city || n.location || '').trim()
    if (!name) continue
    if (name.length > 10) name = name.slice(0, 10) + '…'
    const abnormal = Number(n.isAbnormal) === 1 || ABNORMAL_STATUSES.has(String(n.status || ''))
    const prev = out[out.length - 1]
    if (prev && prev.name === name) { prev.abnormal = prev.abnormal || abnormal; continue }
    out.push({ name, abnormal })
  }
  return out
})

const cityStartEnd = computed(() => {
  const r = cityRoute.value
  if (r.length < 2) return null
  return { start: r[0].name, end: r[r.length - 1].name }
})

/* ===== AMap 初始化（凭据链与 LogisticsDynamicMap 一致：key/securityJsCode 由后端 /logistics/map-config 代理下发） ===== */
let amapInstance = null
let amapPolyline = null
const amapMarkers = []
let mapConfigPromise = null
let lastLoadFailed = false

function fetchMapConfig() {
  if (!mapConfigPromise) {
    mapConfigPromise = request.get('/logistics/map-config', { silent: true }).catch((e) => {
      mapConfigPromise = null
      throw e
    })
  }
  return mapConfigPromise
}

function degradeToCity(reason) {
  mode.value = 'city'
  initializing.value = false
  degradeReason.value = reason || ''
  destroyAMap()
}

/* canvas/AMap 选项不认 CSS 变量：运行时读语义令牌，读不到用兜底色 */
function tokenColor(name, fallback) {
  try {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback
  } catch (e) {
    return fallback
  }
}

function escapeHtml(s) {
  return String(s)
    .replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;').replaceAll("'", '&#39;')
}

function markerHtml(n, isLatest) {
  return `<div class="ll-amap-node${n.abnormal ? ' is-abnormal' : ''}${isLatest ? ' is-latest' : ''}">`
    + '<span class="dot"></span>'
    + `<span class="txt">${escapeHtml(n.name)}</span>`
    + '</div>'
}

async function initAMap() {
  initializing.value = true
  degradeReason.value = ''
  let cfg = null
  try {
    cfg = await Promise.race([
      fetchMapConfig(),
      new Promise((_, reject) => setTimeout(() => reject(new Error('map-config timeout')), 6000))
    ])
  } catch (e) {
    degradeToCity('地图凭据服务暂不可用，已按城市级示意展示')
    return
  }
  const key = cfg?.key || ''
  const sec = cfg?.securityJsCode || ''
  if (!key) {
    degradeToCity('地图凭据未配置，已按城市级示意展示')
    return
  }
  try {
    // 安全密钥必须在 AMapLoader.load 之前挂到 window（官方 JSAPI 范式）
    if (sec) window._AMapSecurityConfig = { securityJsCode: sec }
    // loader 会缓存失败态（后续 load 直接 reject），重试前必须复位
    if (lastLoadFailed) {
      try { AMapLoader.reset?.() } catch (e) { /* 旧版本 loader 无 reset 时忽略 */ }
    }
    const AMap = await Promise.race([
      AMapLoader.load({ key, version: '2.0', plugins: [] }),
      new Promise((_, reject) => setTimeout(() => reject(new Error('amap load timeout')), 8000))
    ])
    // 卸载/降级竞态：容器已不在 amap 模式时不再建图
    if (!amapContainerRef.value || mode.value !== 'amap') return
    if (amapInstance) destroyAMap()
    amapInstance = new AMap.Map(amapContainerRef.value, {
      zoom: 5,
      center: [105.5, 36.5],
      mapStyle: 'amap://styles/normal',
      viewMode: '2D'
    })
    initializing.value = false
    drawRoute()
  } catch (e) {
    lastLoadFailed = true
    degradeToCity('高德图源加载失败，已按城市级示意展示')
  }
}

function clearOverlays() {
  const overlays = [amapPolyline, ...amapMarkers].filter(Boolean)
  if (amapInstance && overlays.length) {
    try { amapInstance.remove(overlays) } catch (e) { /* 地图已销毁时忽略 */ }
  }
  amapPolyline = null
  amapMarkers.length = 0
}

function destroyAMap() {
  clearOverlays()
  if (amapInstance) {
    try { amapInstance.destroy() } catch (e) { /* 已销毁时忽略 */ }
    amapInstance = null
  }
}

function drawRoute() {
  const AMap = window.AMap
  if (!AMap || !amapInstance) return
  clearOverlays()
  const nodes = geoNodes.value
  if (!nodes.length) return

  if (nodes.length >= 2) {
    amapPolyline = new AMap.Polyline({
      path: nodes.map(n => [n.lng, n.lat]),
      strokeColor: tokenColor('--ll-map-track-todo', '#0284c7'),
      strokeWeight: 4,
      strokeOpacity: 0.85,
      showDir: true,
      lineJoin: 'round',
      lineCap: 'round',
      zIndex: 50
    })
    amapInstance.add(amapPolyline)
  }
  nodes.forEach((n, i) => {
    const marker = new AMap.Marker({
      position: [n.lng, n.lat],
      content: markerHtml(n, i === nodes.length - 1),
      anchor: 'bottom-center',
      offset: [0, -6],
      zIndex: i === nodes.length - 1 ? 120 : 80
    })
    amapInstance.add(marker)
    amapMarkers.push(marker)
  })
  try {
    amapInstance.setFitView(amapPolyline ? [amapPolyline] : amapMarkers, false, [60, 60, 60, 60])
  } catch (e) { /* 视口适配失败不阻断 */ }
}

/* 有坐标 → AMap 优先；无坐标 → 城市级占位。挂载与轨迹更新都会走这里：
 * 初次挂载时 mode 已是 'amap' 且无实例，必须落到 initAMap 分支；init 进行中不重复发起 */
async function ensureMode() {
  if (geoNodes.value.length) {
    if (mode.value !== 'amap') {
      mode.value = 'amap'
      degradeReason.value = ''
      await nextTick()
    }
    if (amapInstance) {
      drawRoute()
    } else if (!initializing.value) {
      initAMap()
    }
  } else {
    degradeToCity('暂无经纬度坐标，已按城市级示意展示')
  }
}

watch(() => geoNodes.value.map(n => `${n.lng},${n.lat}`).join('|'), () => {
  ensureMode()
})

onMounted(() => {
  ensureMode()
})

onBeforeUnmount(() => {
  destroyAMap()
})
</script>

<style scoped>
.ll-route-map {
  width: 100%;
  border-radius: 12px;
  overflow: hidden;
  border: 1px solid var(--ll-border, #e2e8f0);
  background: var(--ll-card, #fff);
}

.rm-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px;
  background: var(--ll-surface-soft, #f8fafc);
  border-bottom: 1px solid var(--ll-border, #eef2f7);
}
.rm-title {
  display: inline-flex; align-items: center; gap: 6px;
  font-size: 12.5px; font-weight: 700;
  color: var(--ll-ink, #0f172a);
}
.rm-title :svg { color: #0284c7; }
.rm-meta { display: inline-flex; align-items: center; gap: 8px; }
.rm-count { font-size: 11.5px; color: var(--ll-muted, #64748b); font-variant-numeric: tabular-nums; }
.rm-mode {
  font-size: 10.5px; font-weight: 700;
  padding: 1px 8px; border-radius: 999px;
  color: #64748b;
  background: rgba(100, 116, 139, .12);
}
.rm-mode.is-amap { color: #0369a1; background: rgba(14, 165, 233, .14); }

.rm-canvas { position: relative; width: 100%; background: #f1f5f9; }

/* AMap 加载遮罩 */
.rm-mask {
  position: absolute; inset: 0; z-index: 5;
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px;
  background: rgba(248, 250, 252, .9);
  color: var(--ll-muted, #64748b);
  font-size: 12px;
}
.rm-spinner {
  width: 18px; height: 18px;
  border-radius: 50%;
  border: 2px solid rgba(2, 132, 199, .25);
  border-top-color: #0284c7;
  animation: rmSpin .8s linear infinite;
}
@keyframes rmSpin { to { transform: rotate(360deg); } }

/* 城市级占位 */
.rm-city {
  height: 100%;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 14px;
  padding: 18px 16px;
  background:
    radial-gradient(circle at 50% 0%, rgba(2, 132, 199, .06) 0%, transparent 60%),
    var(--ll-surface-soft, #f8fafc);
}
.rm-city-route {
  display: flex; align-items: center; justify-content: center;
  flex-wrap: wrap;
  gap: 6px;
  max-width: 100%;
}
.rm-city-chip {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 5px 12px;
  border-radius: 999px;
  background: var(--ll-card, #fff);
  border: 1px solid rgba(2, 132, 199, .35);
  color: var(--ll-ink, #0f172a);
  font-size: 12px; font-weight: 600;
  box-shadow: 0 2px 8px rgba(15, 23, 42, .06);
}
.rm-city-chip .rm-city-dot {
  width: 7px; height: 7px; border-radius: 50%;
  background: #0284c7;
  flex: none;
}
.rm-city-chip.is-abnormal {
  border-color: rgba(239, 68, 68, .6);
  background: rgba(254, 242, 242, .8);
}
.rm-city-chip.is-abnormal .rm-city-dot { background: #ef4444; }
.rm-city-arrow { color: #94a3b8; flex: none; }
.rm-city-se {
  display: flex; align-items: center; gap: 16px; flex-wrap: wrap;
  font-size: 11.5px;
  color: var(--ll-muted, #64748b);
}
.rm-city-se span { display: inline-flex; align-items: center; gap: 4px; }

.rm-empty {
  display: flex; align-items: center; gap: 8px;
  color: var(--ll-muted, #94a3b8);
  font-size: 13px;
}
.rm-degrade {
  font-size: 11.5px;
  color: #b45309;
  background: rgba(245, 158, 11, .10);
  border: 1px solid rgba(245, 158, 11, .3);
  border-radius: 8px;
  padding: 4px 10px;
}

/* AMap 注入 DOM 不带 scoped 属性，需 :deep 穿透（节点小名牌） */
.rm-canvas :deep(.ll-amap-node) {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 3px 9px;
  border-radius: 12px;
  background: rgba(255, 255, 255, .96);
  border: 1px solid rgba(2, 132, 199, .45);
  color: #0f172a;
  font-size: 11px;
  white-space: nowrap;
  box-shadow: 0 2px 8px rgba(15, 23, 42, .18);
}
.rm-canvas :deep(.ll-amap-node .dot) {
  width: 7px; height: 7px; border-radius: 50%;
  background: #0284c7;
  flex: none;
}
.rm-canvas :deep(.ll-amap-node.is-abnormal) { border-color: rgba(239, 68, 68, .65); }
.rm-canvas :deep(.ll-amap-node.is-abnormal .dot) { background: #ef4444; }
.rm-canvas :deep(.ll-amap-node.is-latest) {
  background: #0284c7;
  border-color: #0284c7;
  color: #fff;
}
.rm-canvas :deep(.ll-amap-node.is-latest .dot) { background: #fff; }
</style>






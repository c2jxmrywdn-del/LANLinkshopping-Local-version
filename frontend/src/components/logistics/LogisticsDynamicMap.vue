<template>
  <div class="logistics-dynamic-map-wrapper" ref="wrapperRef">
    <!-- 头部监控状态栏 HUD -->
    <div class="map-hud-bar">
      <div class="hud-left">
        <span class="hud-badge pulse-dot"></span>
        <span class="hud-title">{{ isMobile ? (activeEngine === 'amap' ? '工业在途实景' : '工业在途 GIS') : hudTitleText }}</span>
        <span class="hud-sub" v-if="waybillInfo">
          【{{ isReverseWaybill ? 'RMA售后退货' : (waybillInfo.packageNo || '主包裹') }}】{{ waybillInfo.providerName || '工业干线专线' }}
          <span class="mode-tag" :class="{ 'is-rma': isReverseWaybill }">
            {{ isReverseWaybill ? 'RMA 逆向返厂' : transportModeText(waybillInfo.transportMode) }}
          </span>
        </span>
      </div>
      <div class="hud-right">
        <span class="hud-metric hud-metric-dist">路线里程：<strong>{{ currentRouteDistance }} km</strong></span>
        <span class="hud-metric" v-if="!isMobile">
          航向：<strong>{{ Math.round(displayHeading) }}°</strong>
        </span>
        <span class="hud-metric">
          状态：<span :class="['status-pill', statusPillClass]">{{ statusText }}</span>
        </span>
        <!-- 底图状态微标（只读指示，不提供引擎/风格切换入口） -->
        <span class="engine-indicator-pill" :class="{ 'is-amap': activeEngine === 'amap' }">
          <Layers :size="11" class="inline mr-1" />
          {{ activeEngine === 'amap' ? '高德商业图源' : '内置矢量应急' }}
        </span>
        <!-- 遥测舱展开切换按钮 -->
        <button
          type="button"
          class="hud-toggle-btn"
          :class="{ 'is-active': showTelemetryHud }"
          :title="showTelemetryHud ? '收起全息车况遥测舱' : '展开全息车况遥测舱'"
          @click="showTelemetryHud = !showTelemetryHud"
        >
          <Activity :size="13" class="inline mr-1" />遥测舱
        </button>
      </div>
    </div>

    <!-- 动态地图画布与操作区 -->
    <div
      class="map-canvas-container"
      :class="{ 'is-high-contrast': highContrast, 'is-gesture-unlocked': gestureUnlocked }"
      ref="containerRef"
      @wheel="onWheelZoom"
      @pointerdown="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="onPointerUp"
    >
      <!-- PC Ctrl + 滚轮缩放友好提示 -->
      <transition name="fade">
        <div v-if="showCtrlWheelHint" class="ctrl-wheel-hint">
          按住 Ctrl + 滚轮缩放地图
        </div>
      </transition>

      <!-- 移动端双指操作提示 -->
      <transition name="fade">
        <div v-if="showMobileGestureHint" class="mobile-gesture-hint">
          双指移动/缩放地图，或点击右下角解锁交互
        </div>
      </transition>

      <!-- 移动端防误触手势锁定/解锁药丸按钮 -->
      <button
        v-if="isMobile"
        type="button"
        class="mobile-gesture-pill"
        :class="{ 'is-unlocked': gestureUnlocked }"
        @click.stop="toggleGestureLock"
      >
        <Lock v-if="!gestureUnlocked" :size="12" class="inline mr-1" />
        <Unlock v-else :size="12" class="inline mr-1" />
        {{ gestureUnlocked ? '交互已启用' : '手势锁定·轻滑滚动' }}
      </button>

      <!-- 移动端紧凑遥测胶囊 (收起HUD时显示) -->
      <div
        v-if="isMobile && !showTelemetryHud"
        class="mobile-telemetry-capsule"
        @click="showTelemetryHud = true"
      >
        <Activity :size="12" class="inline mr-1 text-sky-400" />
        <span>{{ activeSpeedText }} · {{ activePlateNo }} · 余{{ remainingDistanceKm }}km</span>
        <span class="capsule-more">展开遥测 ↗</span>
      </div>

      <!-- 遥测全息悬浮舱 (Telemetry Bento HUD) -->
      <transition name="hud-fade">
        <div v-if="showTelemetryHud" class="telemetry-bento-hud">
          <div class="hud-bento-head">
            <span class="bento-title">
              <Activity :size="13" class="inline mr-1 text-sky-400" />
              {{ isReverseWaybill ? 'RMA 车况遥测与退运路网' : '车况遥测与国家路网走廊' }} (IIoT Telematics)
            </span>
            <span class="bento-tag">{{ isReverseWaybill ? '逆向在途' : (hasRealTelemetry ? '实时同步' : '仿真推演') }}</span>
          </div>
          <div class="hud-bento-grid">
            <div class="bento-cell">
              <span class="cell-label"><Gauge :size="11" class="inline mr-1 text-sky-400" />动态车速</span>
              <span class="cell-val">{{ activeSpeedText }}</span>
            </div>
            <div class="bento-cell">
              <span class="cell-label"><Truck :size="11" class="inline mr-1 text-indigo-400" />承运车牌</span>
              <span class="cell-val text-amber-300">{{ activePlateNo }}</span>
            </div>
            <div class="bento-cell">
              <span class="cell-label"><Navigation :size="11" class="inline mr-1 text-cyan-400" />交通走廊</span>
              <span class="cell-val text-sky-300">{{ activeCorridorLabel }}</span>
            </div>
            <div class="bento-cell">
              <span class="cell-label"><Thermometer :size="11" class="inline mr-1 text-emerald-400" />厢内温湿度</span>
              <span class="cell-val text-emerald-300">{{ activeTempText }} · {{ activeHumidityText }}</span>
            </div>
          </div>
          <div class="hud-bento-foot">
            <span class="foot-item">剩余里程：<strong>{{ remainingDistanceKm }} km</strong></span>
            <span class="foot-item">预计到达：<strong>{{ estimatedArrivalText }}</strong></span>
            <span class="foot-item text-slate-400 font-mono text-xs">坐标：{{ activeCoordsText }}</span>
          </div>
        </div>
      </transition>

      <!-- 地图视角缩放、底图模式与复位工具栏 -->
      <div class="map-view-controls">
        <button type="button" class="view-btn" title="放大地图" @click="zoomIn">
          <ZoomIn :size="14" />
        </button>
        <button type="button" class="view-btn" title="缩小地图" @click="zoomOut">
          <ZoomOut :size="14" />
        </button>
        <button
          v-if="activeEngine === 'vector'"
          type="button"
          class="view-btn"
          :class="{ 'is-active': highContrast }"
          :title="highContrast ? '切换为默认夜航科技底图' : '切换为高对比度清晰底图'"
          @click="highContrast = !highContrast"
        >
          <SunDim v-if="highContrast" :size="14" />
          <Moon v-else :size="14" />
        </button>
        <button
          type="button"
          class="view-btn"
          :class="{ 'is-active': isOverviewMode }"
          :title="isOverviewMode ? '聚焦当前运单沿线' : '切换全国全景宏观视角'"
          @click="toggleOverview"
        >
          <Globe :size="14" />
        </button>
        <button type="button" class="view-btn" title="重置视角居中" @click="resetView">
          <Maximize2 :size="14" />
        </button>
      </div>

      <!-- 高德商业图源底图容器 (AMap JS API 2.0 · 引擎与风格固定，不可切换) -->
      <div
        v-show="activeEngine === 'amap'"
        ref="amapContainerRef"
        class="amap-container"
      >
        <div v-if="amapLoading" class="amap-loading-mask">
          <span class="pulse-dot"></span>
          <span>正在连接高德商业图源与真实路网服务...</span>
        </div>
        <!-- 失败蒙层（设计 A5：四出口统一 failAmap 收口后可达）：重试 + 应急降级双出口 -->
        <div v-if="amapError" class="amap-error-mask">
          <AlertTriangle :size="18" class="amap-error-icon" />
          <span class="amap-error-text">{{ amapError }}</span>
          <span class="amap-error-hint">可重试连接；或切换内置中国矢量底图应急使用（配置修复后刷新页面恢复高德图源）。</span>
          <div class="amap-error-actions">
            <button type="button" class="fallback-btn" @click="retryAmap">重试连接</button>
            <button type="button" class="fallback-btn is-ghost" @click="fallbackToVectorEngine('已切换内置中国矢量底图应急使用')">降级使用内置中国矢量底图</button>
          </div>
        </div>
      </div>

      <!-- 内置高精中国全国地理矢量底图（静默应急降级·AMap 不可用时自动回退·无切换入口） -->
      <svg
        v-show="activeEngine === 'vector'"
        class="map-svg"
        :viewBox="currentViewBox"
        preserveAspectRatio="xMidYMid meet"
        @click="activeNode = null"
      >
        <defs>
          <!-- 科技背景暗黑网格 -->
          <pattern id="gisGrid" width="40" height="40" patternUnits="userSpaceOnUse">
            <path d="M 40 0 L 0 0 0 40" fill="none" stroke="rgba(255,255,255,0.035)" stroke-width="1" />
          </pattern>
          <!-- 正向干线渐变流光 (青蓝) -->
          <linearGradient id="routeGrad" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stop-color="#00f2fe" />
            <stop offset="50%" stop-color="#38bdf8" />
            <stop offset="100%" stop-color="#0284c7" />
          </linearGradient>
          <!-- 逆向 RMA 专属琥珀紫罗兰流光 -->
          <linearGradient id="rmaRouteGrad" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stop-color="#f59e0b" />
            <stop offset="50%" stop-color="#d946ef" />
            <stop offset="100%" stop-color="#a855f7" />
          </linearGradient>
          <!-- 滤镜发光 -->
          <filter id="glow" x="-20%" y="-20%" width="140%" height="140%">
            <feGaussianBlur stdDeviation="3" result="glow" />
            <feComposite in="SourceGraphic" in2="glow" operator="over" />
          </filter>
        </defs>

        <!-- 背景底色与坐标网格 -->
        <rect x="-1000" y="-1000" width="3000" height="3000" fill="#070c14" />
        <rect x="-1000" y="-1000" width="3000" height="3000" fill="url(#gisGrid)" />

        <!-- 1. 全国 34 个省级行政区矢量轮廓 -->
        <g class="provinces-layer">
          <path
            v-for="prov in PROVINCES_DATA"
            :key="prov.id"
            :d="prov.path"
            :class="['province-polygon', { 'is-active-corridor': isProvActiveInRoute(prov) }]"
          />
          <!-- 省份名称标注 -->
          <text
            v-for="prov in PROVINCES_DATA"
            :key="'lbl-' + prov.id"
            :x="prov.center.x"
            :y="prov.center.y"
            class="province-name-label"
            text-anchor="middle"
          >
            {{ prov.name }}
          </text>
        </g>

        <!-- 2. 国家水运大通道：长江与黄河 -->
        <g class="rivers-layer">
          <path
            v-for="river in MAJOR_RIVERS"
            :key="river.id"
            :d="river.path"
            class="river-flow-path"
          />
        </g>

        <!-- 3. 南海十段线合规标注 (Ten-Dash Line) -->
        <g class="ten-dash-layer">
          <path
            v-for="(seg, idx) in TEN_DASH_LINES"
            :key="'dash-' + idx"
            :d="seg"
            class="ten-dash-line"
          />
          <text x="640" y="580" class="ten-dash-text" text-anchor="middle">南海诸岛</text>
        </g>

        <!-- 4. 国家骨干高速公路背景网络 (G4, G2, G42, G30) -->
        <g class="highways-layer">
          <path
            v-for="(hw, key) in HIGHWAY_CORRIDORS"
            :key="key"
            :d="hw.path"
            class="highway-corridor-path"
          />
        </g>

        <!-- 5. 全国 42 个工业枢纽城市点位常驻标注 -->
        <g class="major-cities-layer">
          <g
            v-for="city in MAJOR_CITIES"
            :key="'city-' + city.name"
            class="major-city-point"
            :class="{ 'is-hub': city.isHub }"
          >
            <circle :cx="city.x" :cy="city.y" :r="city.isHub ? 3.5 : 2" class="city-dot" />
            <text :x="city.x" :y="city.y - 6" class="city-name" text-anchor="middle">
              {{ city.name }}
            </text>
          </g>
        </g>

        <!-- 6. 当前运单的动态在途高速走廊轨迹路线 -->
        <path :d="routePathD" class="route-track-bg" :class="{ 'is-rma': isReverseWaybill }" />
        <path
          :d="routePathD"
          class="route-track-flow"
          :class="{ 'is-rma': isReverseWaybill }"
          filter="url(#glow)"
        />

        <!-- 7. 沿途实际途经枢纽节点 (带雷达脉冲光环) -->
        <g
          v-for="(node, idx) in activeHighwayNodes"
          :key="'node-' + idx"
          class="map-node-group"
          @click.stop="toggleNode(node)"
          @mouseenter="activeNode = node"
          @mouseleave="activeNode = null"
        >
          <circle :cx="node.x" :cy="node.y" r="14" class="radar-pulse" :class="nodeTypeClass(node.hubType)" />
          <circle :cx="node.x" :cy="node.y" r="6" class="node-core" :class="nodeTypeClass(node.hubType)" />
          <text :x="node.x" :y="node.y - 12" class="node-label" text-anchor="middle">
            {{ node.name || '枢纽' }}
          </text>
        </g>

        <!-- 8. 真实车头切线航向与动态探照光束 (卡车模型) -->
        <g
          v-if="interpolatedTruck"
          class="truck-marker-group"
          :class="{ 'is-rma': isReverseWaybill }"
          :transform="`translate(${interpolatedTruck.x}, ${interpolatedTruck.y})`"
        >
          <circle cx="0" cy="0" r="18" class="truck-beacon-pulse" />
          <g :transform="`rotate(${interpolatedTruck.headingAngle || 0})`">
            <polygon points="-6,-14 -14,-36 14,-36 6,-14" class="truck-headlight" />
            <rect x="-8" y="-14" width="16" height="28" rx="4" class="truck-body" />
            <rect x="-6" y="-12" width="12" height="7" rx="2" class="truck-cab" />
          </g>
          <text x="0" y="24" class="truck-hud-text" text-anchor="middle">
            {{ interpolatedTruck.locationName || '在途行驶中' }}
          </text>
        </g>

        <!-- 9. 节点自适应 SVG 内部气泡 Tooltip (绝对自同步，不脱靶飞屏) -->
        <g
          v-if="activeNode"
          class="svg-node-popover"
          :transform="`translate(${activeNode.x}, ${activeNode.y - 18})`"
          pointer-events="none"
        >
          <rect x="-90" y="-48" width="180" height="42" rx="7" class="svg-popover-bg" />
          <polygon points="-6,-6 0,0 6,-6" class="svg-popover-arrow" />
          <text x="0" y="-30" text-anchor="middle" class="svg-popover-title">
            {{ isReverseWaybill ? rmaHubTypeName(activeNode.hubType) : hubTypeName(activeNode.hubType) }} · {{ activeNode.name }}
          </text>
          <text x="0" y="-14" text-anchor="middle" class="svg-popover-coords">
            {{ activeNode.lng?.toFixed(2) }}°E, {{ activeNode.lat?.toFixed(2) }}°N
          </text>
        </g>
      </svg>
    </div>

    <!-- 时空轨迹回放控制条 (Interactive Playback Scrubber) -->
    <LogisticsPlaybackControl
      v-if="interactive"
      :progress="playbackProgress"
      :playing="isPlaying"
      :speed="playbackSpeed"
      :nodes="activeHighwayNodes"
      @update:progress="onProgressUpdate"
      @update:playing="onPlayingUpdate"
      @update:speed="onSpeedUpdate"
      @seek="onSeek"
    />

    <!-- 底部包裹及装载 SKU 列表明细（可折叠） -->
    <div class="map-footer-panel" v-if="packageItems && packageItems.length">
      <div class="panel-header">
        <Package :size="16" class="header-icon-svg text-sky-400" aria-hidden="true" />
        <span class="header-text">本运单实发装载货物明细（{{ packageItems.length }} 种工业品）</span>
      </div>
      <div class="sku-chips-row">
        <div v-for="item in packageItems" :key="item.id || item.orderItemId" class="sku-chip">
          <span class="chip-name">{{ item.prodName }}</span>
          <span class="chip-qty">× {{ item.shippedQty }} 件</span>
        </div>
      </div>
    </div>

  </div>
</template>

<script setup>
import { computed, ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { message } from 'ant-design-vue'
import AMapLoader from '@amap/amap-jsapi-loader'
import request from '../../api/request'
import {
  Package, Truck, Gauge, Thermometer, AlertTriangle,
  ZoomIn, ZoomOut, Maximize2, Activity, Navigation,
  Globe, Layers, SunDim, Moon,
  Lock, Unlock
} from 'lucide-vue-next'
import LogisticsPlaybackControl from './LogisticsPlaybackControl.vue'
import { sliceRouteAtFraction } from '../../utils/logisticsRoute'
import {
  interpolateTruckPosition,
  synthesizeHighwayRoute,
  haversineDistance,
  projectChinaGeoToSvg,
  parseCityFromAddress,
  PROVINCES_DATA,
  MAJOR_RIVERS,
  TEN_DASH_LINES,
  HIGHWAY_CORRIDORS,
  MAJOR_CITIES
} from '../../utils/logisticsGis'

const props = defineProps({
  mapRoute: { type: Object, default: () => ({}) },
  waybillInfo: { type: Object, default: () => ({}) },
  packageItems: { type: Array, default: () => [] },
  telemetry: { type: Object, default: () => null },
  interactive: { type: Boolean, default: true },
  autoPlay: { type: Boolean, default: false }
})

const viewWidth = 960
const viewHeight = 640
const activeNode = ref(null)
const showTelemetryHud = ref(true)
const isOverviewMode = ref(false)
const highContrast = ref(false)

/* 移动端与交互手势仲裁 */
const isMobile = ref(false)
const gestureUnlocked = ref(false)
const showCtrlWheelHint = ref(false)
const showMobileGestureHint = ref(false)
let ctrlWheelHintTimer = null
let mobileGestureHintTimer = null

function triggerCtrlWheelHint() {
  showCtrlWheelHint.value = true
  if (ctrlWheelHintTimer) clearTimeout(ctrlWheelHintTimer)
  ctrlWheelHintTimer = setTimeout(() => {
    showCtrlWheelHint.value = false
    ctrlWheelHintTimer = null
  }, 1500)
}

function triggerMobileGestureHint() {
  showMobileGestureHint.value = true
  if (mobileGestureHintTimer) clearTimeout(mobileGestureHintTimer)
  mobileGestureHintTimer = setTimeout(() => {
    showMobileGestureHint.value = false
    mobileGestureHintTimer = null
  }, 1800)
}

function toggleGestureLock() {
  gestureUnlocked.value = !gestureUnlocked.value
  if (gestureUnlocked.value) {
    message.info('已开启地图手势交互（支持拖拽与缩放）')
  } else {
    message.info('已锁定地图手势（恢复页面顺畅滑动浏览）')
  }
}

function toggleNode(node) {
  activeNode.value = activeNode.value === node ? null : node
}

/* 底图引擎：固定高德商业图源·标准明亮矢量 (amap://styles/normal)；内置矢量仅作为加载失败时的静默应急降级，无 UI 切换入口 */
const activeEngine = ref('amap')
const AMAP_BASE_STYLE = 'amap://styles/normal'

/* 高德地图状态 */
const amapContainerRef = ref(null)
const amapLoading = ref(false)
const amapError = ref('')
let amapInstance = null
let amapPolyline = null
let amapPassedPolyline = null
let amapStartMarker = null
let amapEndMarker = null
let amapTruckMarker = null
let drivingPlanner = null
let drivingPlanToken = 0

/* 主题令牌运行时读取（canvas/AMap 选项不认 CSS 变量；读值缓存随主题切换失效） */
const llTokenCache = new Map()
function readLlToken(name) {
  if (typeof document === 'undefined') return ''
  if (!llTokenCache.has(name)) {
    llTokenCache.set(name, getComputedStyle(document.documentElement).getPropertyValue(name).trim())
  }
  return llTokenCache.get(name)
}
let themeObserver = null

/* 真实路网轨迹规划状态 */
const routePlanState = ref('idle') // idle | planning | ready | fallback
const plannedRouteDistanceKm = ref(null)
const currentHeading = ref(0)
const currentLngLat = ref(null)
let drivingPath = []
let drivingPathCumKm = []
let drivingPathTotalKm = 0

/* 缩放与平移状态 (模式 A SVG) */
const zoomLevel = ref(1.0)
const panX = ref(0)
const panY = ref(0)

// 减去 panX / panY，使拖拽与视觉内容位移 100% 同向自然！
const currentViewBox = computed(() => {
  const w = viewWidth / zoomLevel.value
  const h = viewHeight / zoomLevel.value
  const x = (viewWidth - w) / 2 - panX.value
  const y = (viewHeight - h) / 2 - panY.value
  return `${x} ${y} ${w} ${h}`
})

/* 指针与拖拽交互状态池（基于标准 PointerEvents 与防误触协作手势） */
let isDragging = false
let lastPointerX = 0
let lastPointerY = 0
const activePointers = new Map()
let initialPinchDistance = 0
let initialPinchZoom = 1.0
const containerRef = ref(null)

function onPointerDown(e) {
  if (e.target.closest('.telemetry-bento-hud') || e.target.closest('.map-view-controls') || e.target.closest('.mobile-gesture-pill') || e.target.closest('.mobile-telemetry-capsule')) return
  if (e.button !== undefined && e.button !== 0) return

  activePointers.set(e.pointerId, { clientX: e.clientX, clientY: e.clientY })

  // 移动端防误触：在未显式解锁手势时，单指触摸完全不阻断原生页面滚动！
  if (e.pointerType === 'touch' && !gestureUnlocked.value) {
    if (activePointers.size === 1) {
      isDragging = false
      return
    }
  }

  try {
    e.target.setPointerCapture?.(e.pointerId)
  } catch (_) {}

  if (activePointers.size === 1) {
    isDragging = true
    lastPointerX = e.clientX
    lastPointerY = e.clientY
  } else if (activePointers.size === 2) {
    isDragging = false
    const pts = Array.from(activePointers.values())
    initialPinchDistance = Math.hypot(pts[1].clientX - pts[0].clientX, pts[1].clientY - pts[0].clientY)
    initialPinchZoom = zoomLevel.value
  }
}

function onPointerMove(e) {
  if (!activePointers.has(e.pointerId)) return
  activePointers.set(e.pointerId, { clientX: e.clientX, clientY: e.clientY })

  // 双指捏合缩放（全平台通用）
  if (activePointers.size === 2 && initialPinchDistance > 0) {
    const pts = Array.from(activePointers.values())
    const curDist = Math.hypot(pts[1].clientX - pts[0].clientX, pts[1].clientY - pts[0].clientY)
    const factor = curDist / initialPinchDistance
    zoomLevel.value = Math.max(0.7, Math.min(3.8, +(initialPinchZoom * factor).toFixed(2)))
    return
  }

  // 移动端触摸未解锁且仅单指时：忽略地图拖拽，允许页面正常上下滚动
  if (e.pointerType === 'touch' && !gestureUnlocked.value) {
    return
  }

  if (!isDragging) return
  const rect = containerRef.value?.getBoundingClientRect()
  const containerW = rect?.width || viewWidth
  const containerH = rect?.height || viewHeight
  const scaleX = (viewWidth / zoomLevel.value) / containerW
  const scaleY = (viewHeight / zoomLevel.value) / containerH

  const deltaX = e.clientX - lastPointerX
  const deltaY = e.clientY - lastPointerY
  lastPointerX = e.clientX
  lastPointerY = e.clientY

  panX.value = +(panX.value + deltaX * scaleX).toFixed(1)
  panY.value = +(panY.value + deltaY * scaleY).toFixed(1)
}

function onPointerUp(e) {
  activePointers.delete(e.pointerId)
  try {
    if (e.target?.hasPointerCapture?.(e.pointerId)) {
      e.target.releasePointerCapture(e.pointerId)
    }
  } catch (_) {}
  if (activePointers.size === 0) {
    isDragging = false
    initialPinchDistance = 0
  } else if (activePointers.size === 1) {
    if (gestureUnlocked.value || e.pointerType !== 'touch') {
      const remaining = Array.from(activePointers.values())[0]
      lastPointerX = remaining.clientX
      lastPointerY = remaining.clientY
      isDragging = true
    }
  }
}

/* 鼠标滚轮缩放：仅在按下 Ctrl / ⌘ 时触发缩放，其余情况允许页面顺畅滚动 */
function onWheelZoom(e) {
  if (e.ctrlKey || e.metaKey) {
    e.preventDefault()
    const delta = e.deltaY < 0 ? 0.15 : -0.15
    zoomLevel.value = Math.max(0.7, Math.min(3.8, +(zoomLevel.value + delta).toFixed(2)))
  } else {
    triggerCtrlWheelHint()
  }
}

function zoomIn() {
  if (activeEngine.value === 'amap' && amapInstance) { amapInstance.zoomIn(); return }
  zoomLevel.value = Math.min(3.8, +(zoomLevel.value + 0.25).toFixed(2))
}
function zoomOut() {
  if (activeEngine.value === 'amap' && amapInstance) { amapInstance.zoomOut(); return }
  zoomLevel.value = Math.max(0.7, +(zoomLevel.value - 0.25).toFixed(2))
}
function resetView() {
  if (activeEngine.value === 'amap' && amapInstance) {
    if (amapPolyline) amapInstance.setFitView([amapPolyline], false, [70, 70, 70, 70])
    else amapInstance.setZoomAndCenter(6, [114.05, 28.5])
    return
  }
  zoomLevel.value = 1.0
  panX.value = 0
  panY.value = 0
  isOverviewMode.value = false
}
function toggleOverview() {
  if (activeEngine.value === 'amap' && amapInstance) {
    isOverviewMode.value = !isOverviewMode.value
    if (isOverviewMode.value) amapInstance.setZoomAndCenter(4.2, [105.5, 36.5])
    else resetView()
    return
  }
  isOverviewMode.value = !isOverviewMode.value
  if (isOverviewMode.value) {
    zoomLevel.value = 0.95
    panX.value = 0
    panY.value = 0
  } else {
    zoomLevel.value = 1.55
    panX.value = 120
    panY.value = 80
  }
}

/* 逆向 RMA 物流判断 */
const isReverseWaybill = computed(() => {
  const wb = props.waybillInfo || {}
  return wb.waybillDirection === 'reverse' || (typeof wb.waybillNo === 'string' && wb.waybillNo.startsWith('RW'))
})

/* 路线与途经枢纽合成（依据真实中国高速走廊与中英拼音地名解析，支持逆向RMA） */
const activeRouteInfo = computed(() => {
  const waybill = props.waybillInfo || {}
  const addr = waybill.receiverAddress || waybill.receiverCity || waybill.receiverProvince || ''
  const dest = parseCityFromAddress(addr)
  const origin = parseCityFromAddress(waybill.senderAddress) || '深圳'
  return synthesizeHighwayRoute(origin, dest, isReverseWaybill.value)
})

const activeHighwayNodes = computed(() => {
  const route = props.mapRoute || {}
  const pts = []
  const addPt = (lng, lat, name, hubType) => {
    const L = Number(lng)
    const A = Number(lat)
    if (!Number.isFinite(L) || !Number.isFinite(A)) return
    const prev = pts[pts.length - 1]
    // 相邻重合节点去重：揽收轨迹与始发仓同坐标（后端按城市级字典落点），零长段会让路网规划失败
    if (prev && Math.abs(prev.lng - L) < 1e-4 && Math.abs(prev.lat - A) < 1e-4) return
    const proj = projectChinaGeoToSvg(L, A)
    pts.push({ name, lng: L, lat: A, hubType: hubType || 'hub', x: proj.x, y: proj.y })
  }
  // 规划链必须以 origin 打头、destination 收尾：未送达运单的 nodes 只覆盖始发侧已发段，
  // 终点坐标仅存在于 mapRoute.destination，只取 nodes 首尾会坍缩成单点而无法规划
  addPt(route.origin?.lng, route.origin?.lat, route.origin?.name, route.origin?.hubType || 'warehouse')
  for (const n of (route.nodes || [])) addPt(n.lng, n.lat, n.name, n.hubType)
  addPt(route.destination?.lng, route.destination?.lat, route.destination?.name, route.destination?.hubType || 'client')
  if (pts.length >= 2) {
    // 起终点近重合（同址演示数据）会让路网规划退化成零长：换合成走廊
    const f = pts[0]
    const l = pts[pts.length - 1]
    const sameSpot = Math.abs(f.lng - l.lng) < 1e-3 && Math.abs(f.lat - l.lat) < 1e-3
    if (!sameSpot) return pts
  }
  // 后端端点缺失或全部重合：退回按始发/收货城市合成的骨干走廊节点（SVG 应急引擎与规划兜底共用）
  const synthesized = activeRouteInfo.value.nodes || []
  const sPts = []
  for (const n of synthesized) {
    const L = Number(n.lng)
    const A = Number(n.lat)
    if (!Number.isFinite(L) || !Number.isFinite(A)) continue
    const prev = sPts[sPts.length - 1]
    if (prev && Math.abs(prev.lng - L) < 1e-4 && Math.abs(prev.lat - A) < 1e-4) continue
    sPts.push({ name: n.name, lng: L, lat: A, hubType: n.hubType || 'hub' })
  }
  const sFirst = sPts[0]
  const sLast = sPts[sPts.length - 1]
  const sOk = sPts.length >= 2 && sFirst && sLast && !(Math.abs(sFirst.lng - sLast.lng) < 1e-3 && Math.abs(sFirst.lat - sLast.lat) < 1e-3)
  if (sOk) return sPts
  // 兜底的兜底：确定性演示走廊（重庆→北京 骨干链），保证任何数据形态都有可规划路线
  return synthesizeHighwayRoute('重庆', '北京', isReverseWaybill.value).nodes
})

const currentRouteDistance = computed(() => {
  // 优先采用真实路网规划里程，其次后端 mapRoute，最后合成走廊兜底
  const planned = plannedRouteDistanceKm.value
  if (planned != null && planned > 0) return planned
  return Math.round(props.mapRoute?.distanceKm || activeRouteInfo.value.distanceKm || 2150)
})

const hudTitleText = computed(() => {
  return activeEngine.value === 'amap'
    ? '中国全境工业在途 · 高德实景全景感知'
    : '中国全境工业在途 GIS 矢量全景感知'
})

const activeCorridorLabel = computed(() => {
  return activeRouteInfo.value.corridorName || 'G4 京港澳高速走廊'
})

/* 全国核心城市至省份映射表，用于动态高亮实际走廊途经省份 */
const CITY_TO_PROVINCE_MAP = {
  '深圳': '广东', '广州': '广东', '韶关': '广东', '东莞': '广东', '佛山': '广东',
  '怀化': '湖南', '长沙': '湖南', '株洲': '湖南', '衡阳': '湖南',
  '武汉': '湖北', '襄阳': '湖北', '宜昌': '湖北',
  '郑州': '河南', '洛阳': '河南', '许昌': '河南',
  '石家庄': '河北', '保定': '河北', '邯郸': '河北',
  '北京': '北京', '天津': '天津',
  '上海': '上海', '苏州': '江苏', '昆山': '江苏', '南京': '江苏', '无锡': '江苏',
  '杭州': '浙江', '宁波': '浙江', '温州': '浙江',
  '合肥': '安徽', '芜湖': '安徽',
  '重庆': '重庆', '黔江': '重庆',
  '成都': '四川', '绵阳': '四川',
  '贵阳': '贵州', '遵义': '贵州',
  '西安': '陕西', '宝鸡': '陕西',
  '济南': '山东', '青岛': '山东',
  '沈阳': '辽宁', '大连': '辽宁',
  '哈尔滨': '黑龙江', '长春': '吉林',
  '南昌': '江西', '九江': '江西',
  '福州': '福建', '厦门': '福建',
  '南宁': '广西', '柳州': '广西', '桂林': '广西',
  '昆明': '云南', '太原': '山西', '呼和浩特': '内蒙古',
  '海口': '海南', '三亚': '海南', '兰州': '甘肃',
  '西宁': '青海', '银川': '宁夏', '乌鲁木齐': '新疆', '拉萨': '西藏'
}

const activeProvinceNames = computed(() => {
  const set = new Set()
  const nodes = activeHighwayNodes.value || []
  for (const n of nodes) {
    // 节点名可能是完整地址（后端 origin/destination 直接以收发货地址命名），需包含匹配而非全等
    const label = String(n.name || '')
    for (const [city, prov] of Object.entries(CITY_TO_PROVINCE_MAP)) {
      if (label.includes(city)) { set.add(prov); break }
    }
    if (n.province) set.add(n.province)
  }
  return set
})

/* 依据当前实际生成的骨干走廊动态高亮途经省份 */
function isProvActiveInRoute(prov) {
  return activeProvinceNames.value.has(prov.name)
}

/* 贝塞尔平滑干线轨迹 Path */
const routePathD = computed(() => {
  const nodes = activeHighwayNodes.value
  if (!nodes || nodes.length < 2) return ''
  let d = `M ${nodes[0].x} ${nodes[0].y}`
  for (let i = 0; i < nodes.length - 1; i++) {
    const curr = nodes[i]
    const next = nodes[i + 1]
    const mx = (curr.x + next.x) / 2
    const my = (curr.y + next.y) / 2
    const ctrlY = my - Math.abs(next.x - curr.x) * 0.08
    d += ` Q ${mx} ${ctrlY} ${next.x} ${next.y}`
  }
  return d
})

/* 回放进度与时钟 */
const playbackProgress = ref(0)
const isPlaying = ref(false)
const playbackSpeed = ref(1.0)
let animTimer = 0

function onProgressUpdate(val) { playbackProgress.value = val }
function onPlayingUpdate(val) {
  isPlaying.value = val
  if (val) startAnimation()
  else stopAnimation()
}
function onSpeedUpdate(val) { playbackSpeed.value = val }
function onSeek(val) { playbackProgress.value = val }

function startAnimation() {
  stopAnimation()
  if (playbackProgress.value >= 100) playbackProgress.value = 0
  animTimer = setInterval(() => {
    const step = 0.42 * playbackSpeed.value
    if (playbackProgress.value + step >= 100) {
      playbackProgress.value = 100
      isPlaying.value = false
      stopAnimation()
    } else {
      playbackProgress.value = +(playbackProgress.value + step).toFixed(2)
    }
  }, 50)
}

function stopAnimation() {
  if (animTimer) {
    clearInterval(animTimer)
    animTimer = 0
  }
}

const remainingDistanceKm = computed(() => {
  const tot = currentRouteDistance.value
  const p = playbackProgress.value / 100
  return Math.max(0, Math.round(tot * (1 - p)))
})

/* HUD 遥测取值：真实遥测（telemetry prop）优先；缺失时按回放进度做确定性仿真推演——
 * 同进度同值、可复现，四项指标（车速/车牌/温湿度）绝不出现空值占位；
 * 面板角标据实标注「仿真推演」而非「实时同步」 */
const hasRealTelemetry = computed(() => !!(props.telemetry && (props.telemetry.speedKmh || props.telemetry.plateNo || props.telemetry.temperature)))

const syntheticSpeedKmh = computed(() => {
  const p = playbackProgress.value
  if (!isPlaying.value && (p <= 0 || p >= 100)) return 0
  const v = 70 + 14 * Math.sin(0.09 * p) + 7 * Math.sin(0.23 * p + 1.3)
  return Math.max(16, Math.round(v))
})

/* 「—」等占位串视为无遥测数据，回退进度驱动的仿真值 */
const hasTelemetryVal = (v) => v != null && v !== '' && !/^[—–-]/.test(String(v).trim())

const activeSpeedText = computed(() => {
  if (hasTelemetryVal(props.telemetry?.speedKmh)) return `${Math.round(props.telemetry.speedKmh)} km/h`
  const v = syntheticSpeedKmh.value
  return v <= 0 ? '0 km/h (静止)' : `${v} km/h`
})

/* 车牌仿真：始发城市牌头 + 运单号散列出确定性假牌（同运单恒定，不随时间跳动） */
const PLATE_PREFIX_BY_CITY = {
  '重庆': '渝A', '成都': '川A', '上海': '沪A', '北京': '京A', '广州': '粤A', '深圳': '粤B',
  '东莞': '粤S', '佛山': '粤E', '中山': '粤T', '武汉': '鄂A', '长沙': '湘A', '郑州': '豫A',
  '合肥': '皖A', '南京': '苏A', '苏州': '苏E', '昆山': '苏E', '无锡': '苏B', '常州': '苏D',
  '杭州': '浙A', '宁波': '浙B', '西安': '陕A', '济南': '鲁A', '青岛': '鲁B', '天津': '津A',
  '沈阳': '辽A', '大连': '辽B', '石家庄': '冀A'
}
const activePlateNo = computed(() => {
  if (hasTelemetryVal(props.telemetry?.plateNo)) return props.telemetry.plateNo
  const wb = props.waybillInfo || {}
  const seed = String(wb.waybillNo || wb.packageNo || 'LANLINK')
  let h = 0
  for (let i = 0; i < seed.length; i++) h = (h * 33 + seed.charCodeAt(i)) >>> 0
  const city = parseCityFromAddress(wb.senderAddress || '')
  const prefix = PLATE_PREFIX_BY_CITY[city] || '苏A'
  const letters = 'ABCDEFGHJK'
  return `${prefix}·${letters[h % letters.length]}${String(h % 100000).padStart(5, '0')}`
})

const activeTempText = computed(() => {
  if (hasTelemetryVal(props.telemetry?.temperature)) return props.telemetry.temperature
  const t = 4.2 + 1.4 * Math.sin(0.07 * playbackProgress.value + 0.8)
  return `${t.toFixed(1)}°C`
})

const activeHumidityText = computed(() => {
  if (hasTelemetryVal(props.telemetry?.humidity)) return props.telemetry.humidity
  const h = 66 + 9 * Math.sin(0.05 * playbackProgress.value + 2.1)
  return `${Math.round(h)}%RH`
})

const estimatedArrivalText = computed(() => {
  const rem = remainingDistanceKm.value
  if (rem <= 0) return isReverseWaybill.value ? '已送达售后仓' : '已送达现场'
  const hours = (rem / 75).toFixed(1)
  return `约 ${hours} 小时后`
})

const interpolatedTruck = computed(() => {
  return interpolateTruckPosition(playbackProgress.value, activeHighwayNodes.value)
})

const displayHeading = computed(() => {
  if (activeEngine.value === 'amap') return currentHeading.value || interpolatedTruck.value?.headingAngle || 0
  return interpolatedTruck.value?.headingAngle || 0
})

const activeCoordsText = computed(() => {
  // 优先：真实路网采样坐标（AMap 规划就绪）
  if (activeEngine.value === 'amap' && currentLngLat.value) {
    return `${currentLngLat.value.lng.toFixed(2)}°E, ${currentLngLat.value.lat.toFixed(2)}°N · 航向 ${Math.round(displayHeading.value)}°`
  }
  // 次选：字典节点链按进度割分插值——真实经纬度，双引擎统一口径（不再视口反算）
  const nodes = activeHighwayNodes.value || []
  if (nodes.length >= 2) {
    const { head } = sliceRouteAtFraction(nodes, playbackProgress.value)
    if (head) {
      return `${head.lng.toFixed(2)}°E, ${head.lat.toFixed(2)}°N · 航向 ${Math.round(head.headingAngle)}°`
    }
  }
  // 完全无坐标：视口反算兜底（补纬度）
  const tr = interpolatedTruck.value
  if (!tr) return '—'
  const lng = 73.5 + (tr.x / viewWidth) * (135.0 - 73.5)
  const lat = 54.0 - (tr.y / viewHeight) * (54.0 - 18.0)
  return `${lng.toFixed(2)}°E, ${lat.toFixed(2)}°N`
})

function transportModeText(mode) {
  const map = { express: '标准快递', ltl: '零担精准快运', ftl: '整车专线直发', direct: '工厂直供', self: '现场自提' }
  return map[mode] || '自营干线专线'
}

const statusText = computed(() => {
  const s = props.waybillInfo?.status || ''
  if (isReverseWaybill.value) {
    const rmaMap = {
      pending_ship: '待买家寄出',
      picked_up: '退货已揽收',
      in_transit: '逆向在途返厂',
      out_for_delivery: '派送售后仓',
      delivered: '售后仓已签收入库',
      exception: '退运异常'
    }
    return rmaMap[s] || '逆向退货流转中'
  }
  const map = {
    pending_ship: '待发货',
    picked_up: '已揽收装车',
    in_transit: '干线在途运输',
    out_for_delivery: '末端派送',
    delivered: '客户已签收',
    exception: '运输异常'
  }
  return map[s] || '正常运转中'
})

const statusPillClass = computed(() => {
  const s = props.waybillInfo?.status || ''
  if (s === 'delivered') return 'pill-success'
  if (s === 'exception') return 'pill-danger'
  if (s === 'in_transit' || s === 'out_for_delivery') return isReverseWaybill.value ? 'pill-rma' : 'pill-active'
  return 'pill-info'
})

function hubTypeName(type) {
  const map = { warehouse: '始发制造仓', hub: '国家枢纽集散港', transfer: '干线中转站', client: '客户交付现场' }
  return map[type] || '物流节点'
}

function rmaHubTypeName(type) {
  const map = {
    return_origin: '买家退货出库',
    return_dest: '售后维修质检仓',
    warehouse: '始发售后服务站',
    hub: '国家逆向物流枢纽',
    transfer: '干线中转分拣中心',
    client: '售后入库基地'
  }
  return map[type] || '逆向物流节点'
}

function getNodeBadgeName(type) {
  return isReverseWaybill.value ? rmaHubTypeName(type) : hubTypeName(type)
}

function nodeTypeClass(type) {
  return `type-${type || 'hub'}`
}

/* ===== 高德底图初始化与真实路网轨迹规划 ===== */
/* 凭据链不变：key/securityJsCode 仍由后端 /logistics/map-config 代理下发，前端不落明文 */

// 登录端点统一下发高德凭据（模块级缓存：会话内仅拉取一次；失败不缓存，允许重试）
let mapConfigPromise = null
function fetchMapConfig() {
  if (!mapConfigPromise) {
    mapConfigPromise = request.get('/logistics/map-config', { silent: true }).catch((e) => {
      mapConfigPromise = null
      throw e
    })
  }
  return mapConfigPromise
}

/**
 * 高德失败统一收口（设计 A5 四出口）：停 loading → 蒙层可达（amapError 非空）+ warning 提示。
 * 卸载竞态（容器已销毁）仅复位 loading，不弹错、不写蒙层状态。
 * 蒙层提供「重试连接」与「降级使用内置中国矢量底图」两个出口；vector 应急兜底保留。
 */
const AMAP_UNAVAILABLE_HINT = '高德图源不可用（未配置 Key 或加载超时）：请在部署环境 .env 配置 AMAP_KEY 与 AMAP_SECURITY_JS_CODE 后重启后端。'
function failAmap(reason) {
  amapLoading.value = false
  if (!amapContainerRef.value) return
  amapError.value = reason
  message.warning(reason)
}

function retryAmap() {
  amapError.value = ''
  // loader 失败态会被缓存（Status.AMap=failed → 后续 load 直接 reject），重试前必须复位
  try { AMapLoader.reset?.() } catch (_) {}
  initAMap()
}

/** 应急降级：AMap 图源不可用时回退内置矢量底图（蒙层按钮触发，vector 兜底保留） */
function fallbackToVectorEngine(reason) {
  if (activeEngine.value === 'vector') return
  activeEngine.value = 'vector'
  amapLoading.value = false
  amapError.value = ''
  destroyAMap()
  if (reason) message.warning(reason)
}

function destroyAMap() {
  drivingPlanToken++
  clearRouteOverlays()
  if (amapInstance) {
    try { amapInstance.destroy() } catch (_) {}
    amapInstance = null
  }
  drivingPlanner = null
  routePlanState.value = 'idle'
}

function clearRouteOverlays() {
  const overlays = [amapPolyline, amapPassedPolyline, amapStartMarker, amapEndMarker, amapTruckMarker].filter(Boolean)
  if (amapInstance && overlays.length) {
    try { amapInstance.remove(overlays) } catch (_) {}
  }
  amapPolyline = null
  amapPassedPolyline = null
  amapStartMarker = null
  amapEndMarker = null
  amapTruckMarker = null
}

/**
 * 动态挂载高德地图 AMap JS API 2.0（官方 @amap/amap-jsapi-loader 范式）：
 * 先设 window._AMapSecurityConfig（securityJsCode 来自后端 map-config 下发），
 * 再 AMapLoader.load({ key, version: '2.0', plugins })；失败经 failAmap 统一收口。
 */
async function initAMap() {
  if (activeEngine.value !== 'amap') return

  amapLoading.value = true
  amapError.value = ''

  // 出口①：凭据拉取超时/失败
  let cfg = null
  try {
    cfg = await Promise.race([
      fetchMapConfig(),
      new Promise((_, reject) => setTimeout(() => reject(new Error('地图凭据拉取超时 (6秒)')), 6000))
    ])
  } catch (e) {
    failAmap('地图凭据服务暂不可用（拉取超时或失败）')
    return
  }

  const key = cfg?.key || ''
  const sec = cfg?.securityJsCode || ''

  // 出口②：平台未配置 Key（不暴露任何凭据细节）
  if (!key) {
    failAmap(AMAP_UNAVAILABLE_HINT)
    return
  }

  try {
    // 安全密钥必须在 AMapLoader.load 之前挂到 window（官方 JSAPI Vue3 范式）
    if (sec) {
      window._AMapSecurityConfig = { securityJsCode: sec }
    }

    const AMap = await Promise.race([
      AMapLoader.load({ key, version: '2.0', plugins: ['AMap.Driving'] }),
      new Promise((_, reject) => setTimeout(() => reject(new Error('高德图源脚本加载超时 (8秒)')), 8000))
    ])

    // 出口③：容器缺失 / 已卸载（卸载竞态时 failAmap 仅复位 loading）
    if (!amapContainerRef.value) {
      failAmap('高德地图容器缺失或已卸载')
      return
    }

    if (amapInstance) {
      try { amapInstance.destroy() } catch (_) {}
      amapInstance = null
    }
    amapInstance = new AMap.Map(amapContainerRef.value, {
      zoom: 6,
      center: [114.05, 28.5],
      mapStyle: AMAP_BASE_STYLE,
      features: ['bg', 'road', 'building', 'point'],
      viewMode: '2D'
    })

    amapLoading.value = false
    planDrivingRoute()
  } catch (err) {
    // 出口④：加载异常（网络失败 / 凭据校验未通过）
    failAmap('高德图源加载失败（网络不可达或凭据校验未通过）')
  }
}

/** 以运单轨迹数据的起终点（经停点作为途经点）沿真实道路网做驾车路径规划 */
function planDrivingRoute() {
  const AMap = window.AMap
  if (!AMap || !amapInstance) return

  const validNodes = collectValidGeoNodes()
  if (validNodes.length < 2) {
    // 冷加载竞态：轨迹数据可能晚于 AMap 就绪到达，此处不画任何东西，等数据过阈值由恢复 watcher 重规划
    applyFallbackRoute('轨迹节点尚未就绪，数据到达后自动重试')
    return
  }

  const token = ++drivingPlanToken
  routePlanState.value = 'planning'

  // 看门狗：插件加载或规划响应挂起（网络受限）时兜底降级，保证地图不白屏
  const watchdog = setTimeout(() => {
    if (token === drivingPlanToken && routePlanState.value === 'planning') {
      applyFallbackRoute('路线规划响应超时，已降级为起终点直连示意虚线')
    }
  }, 12000)

  AMap.plugin('AMap.Driving', () => {
    if (token !== drivingPlanToken) { clearTimeout(watchdog); return }
    try {
      const origin = [validNodes[0].lng, validNodes[0].lat]
      const dest = [validNodes[validNodes.length - 1].lng, validNodes[validNodes.length - 1].lat]
      const waypoints = validNodes.slice(1, -1).slice(0, 15).map(n => [n.lng, n.lat])

      if (!drivingPlanner) {
        drivingPlanner = new AMap.Driving({ policy: AMap.DrivingPolicy?.LEAST_TIME || 0, ferry: false })
      }
      drivingPlanner.search(origin, dest, { waypoints }, (status, result) => {
        if (token !== drivingPlanToken) return
        clearTimeout(watchdog)
        if (status === 'complete' && result?.routes?.length) {
          const route = result.routes[0]
          const pts = []
          for (const step of route.steps || []) {
            for (const p of (step.path || [])) {
              const lng = Number(p.lng ?? p.getLng?.())
              const lat = Number(p.lat ?? p.getLat?.())
              if (!Number.isFinite(lng) || !Number.isFinite(lat)) continue
              const last = pts[pts.length - 1]
              if (last && Math.abs(last[0] - lng) < 1e-7 && Math.abs(last[1] - lat) < 1e-7) continue
              pts.push([lng, lat])
            }
          }
          if (pts.length >= 2) applyDrivingRoute(pts, route.distance)
          else applyFallbackRoute('路线规划返回空结果，已降级为起终点直连示意虚线')
        } else {
          applyFallbackRoute('真实路网规划暂不可用（配额或网络受限），已降级为起终点直连示意虚线')
        }
      })
    } catch (e) {
      clearTimeout(watchdog)
      if (token === drivingPlanToken) applyFallbackRoute('路线规划初始化失败，已降级为起终点直连示意虚线')
    }
  })
}

function collectValidGeoNodes() {
  const out = []
  for (const n of (activeHighwayNodes.value || [])) {
    const lng = Number(n.lng)
    const lat = Number(n.lat)
    if (!Number.isFinite(lng) || !Number.isFinite(lat)) continue
    const prev = out[out.length - 1]
    // 相邻重合节点（同城多节点）会导致零长规划段，去重
    if (prev && Math.abs(prev.lng - lng) < 1e-4 && Math.abs(prev.lat - lat) < 1e-4) continue
    out.push({ name: n.name, lng, lat })
  }
  return out
}

/** 规划成功：沿真实道路网折线绘制轨迹 + 车标 */
function applyDrivingRoute(pts, distanceMeters) {
  drivingPath = pts
  rebuildArcIndex()
  plannedRouteDistanceKm.value = distanceMeters > 0 ? Math.round(distanceMeters / 100) / 10 : Math.round(drivingPathTotalKm)
  routePlanState.value = 'ready'
  drawRouteOnMap()
}

/** 规划失败（配额/网络/节点不足）：起终点直连虚线兜底并提示，地图不白屏 */
function applyFallbackRoute(reason) {
  const validNodes = collectValidGeoNodes()
  if (validNodes.length >= 2) {
    const first = validNodes[0]
    const last = validNodes[validNodes.length - 1]
    drivingPath = [[first.lng, first.lat], [last.lng, last.lat]]
  } else {
    drivingPath = []
  }
  rebuildArcIndex()
  plannedRouteDistanceKm.value = drivingPath.length === 2
    ? Math.round(haversineDistance(drivingPath[0][1], drivingPath[0][0], drivingPath[1][1], drivingPath[1][0]))
    : null
  routePlanState.value = 'fallback'
  drawRouteOnMap()
  if (reason) message.warning(reason)
}

function rebuildArcIndex() {
  drivingPathCumKm = [0]
  for (let i = 1; i < drivingPath.length; i++) {
    drivingPathCumKm.push(
      drivingPathCumKm[i - 1] + haversineDistance(drivingPath[i - 1][1], drivingPath[i - 1][0], drivingPath[i][1], drivingPath[i][0])
    )
  }
  drivingPathTotalKm = drivingPathCumKm[drivingPathCumKm.length - 1] || 0
}

function drawRouteOnMap() {
  const AMap = window.AMap
  if (!AMap || !amapInstance || drivingPath.length < 2) return
  clearRouteOverlays()

  const isFallback = routePlanState.value === 'fallback'
  const validNodes = collectValidGeoNodes()

  // canvas/AMap 选项不认 CSS 变量：经 readLlToken 运行时读语义令牌（主题切换时重读重涂）
  amapPolyline = new AMap.Polyline({
    path: drivingPath,
    strokeColor: amapRouteColor(),
    strokeWeight: isFallback ? 4 : 7,
    strokeOpacity: isFallback ? 0.85 : 0.5,
    strokeStyle: isFallback ? 'dashed' : 'solid',
    strokeDasharray: isFallback ? [10, 6] : undefined,
    showDir: !isFallback,
    lineJoin: 'round',
    lineCap: 'round',
    zIndex: 50
  })

  if (!isFallback) {
    amapPassedPolyline = new AMap.Polyline({
      path: [],
      strokeColor: amapPassedColor(),
      strokeWeight: 5,
      strokeOpacity: 0.95,
      lineJoin: 'round',
      lineCap: 'round',
      zIndex: 55
    })
  }

  if (validNodes.length >= 2) {
    amapStartMarker = new AMap.Marker({
      position: [validNodes[0].lng, validNodes[0].lat],
      content: buildEndpointHtml(validNodes[0].name || '始发仓库', 'start'),
      anchor: 'bottom-center',
      offset: [0, -8],
      zIndex: 80
    })
    amapEndMarker = new AMap.Marker({
      position: [validNodes[validNodes.length - 1].lng, validNodes[validNodes.length - 1].lat],
      content: buildEndpointHtml(validNodes[validNodes.length - 1].name || '交付现场', 'end'),
      anchor: 'bottom-center',
      offset: [0, -8],
      zIndex: 80
    })
  }

  amapTruckMarker = new AMap.Marker({
    position: [drivingPath[0][0], drivingPath[0][1]],
    content: buildTruckMarkerHtml(),
    anchor: 'center',
    angle: 0,
    zIndex: 120
  })

  const overlays = [amapPolyline, amapPassedPolyline, amapStartMarker, amapEndMarker, amapTruckMarker].filter(Boolean)
  amapInstance.add(overlays)
  amapInstance.setFitView([amapPolyline], false, [70, 70, 70, 70])

  syncTruckToRoute()
}

/** 轨迹主线色：正向/RMA 各取语义令牌（读不到令牌时留空交 AMap 默认，绝不硬编码色值） */
function amapRouteColor() {
  return readLlToken(isReverseWaybill.value ? '--ll-map-track-rma' : '--ll-map-track-todo')
}
function amapPassedColor() {
  return readLlToken(isReverseWaybill.value ? '--ll-map-track-rma-passed' : '--ll-map-track-passed')
}

/** 主题切换重读令牌并重涂 canvas 绘制链（蒙层/DOM 侧样式由 CSS var 自动跟随） */
function applyAmapThemeColors() {
  if (!amapInstance) return
  try {
    amapPolyline?.setOptions({ strokeColor: amapRouteColor() })
    amapPassedPolyline?.setOptions({ strokeColor: amapPassedColor() })
  } catch (_) {}
}

function buildEndpointHtml(name, kind) {
  return `<div class="amap-route-endpoint ${kind}"><span class="ep-dot"></span><span class="ep-text">${name}</span></div>`
}

/** 车标 DOM 不再内联色值：颜色全部走 :deep CSS 令牌类（正向 / is-rma 双主题） */
function buildTruckMarkerHtml() {
  return `<div class="amap-truck-marker${isReverseWaybill.value ? ' is-rma' : ''}">
      <svg width="34" height="34" viewBox="0 0 34 34" fill="none" aria-hidden="true">
        <circle class="amap-truck-ring" cx="17" cy="17" r="15.5" stroke-width="1.5"/>
        <rect class="amap-truck-body" x="11" y="15" width="12" height="10.5" rx="2.5" stroke-width="1"/>
        <path class="amap-truck-cab" d="M13 15 L13 10.5 Q13 9 14.5 9 L19.5 9 Q21 9 21 10.5 L21 15 Z" stroke-width="1"/>
      </svg>
    </div>`
}

/** 按回放进度沿路线弧长插值推进车标，并同步 HUD 航向/坐标与已行驶高亮 */
function syncTruckToRoute() {
  if (activeEngine.value !== 'amap') return
  if (!amapTruckMarker || drivingPath.length < 2) return
  const sample = sampleRouteAtFraction(playbackProgress.value / 100)
  if (!sample) return

  amapTruckMarker.setPosition([sample.lng, sample.lat])
  amapTruckMarker.setAngle(Math.round(sample.heading * 10) / 10)
  currentHeading.value = sample.heading
  currentLngLat.value = { lng: sample.lng, lat: sample.lat }

  if (amapPassedPolyline) {
    const passed = drivingPath.slice(0, sample.segIdx + 1).map(p => [p[0], p[1]])
    passed.push([sample.lng, sample.lat])
    amapPassedPolyline.setPath(passed)
  }
}

function sampleRouteAtFraction(frac) {
  if (drivingPath.length < 2) return null
  const clamped = Math.max(0, Math.min(1, Number.isFinite(frac) ? frac : 0))
  const target = drivingPathTotalKm * clamped
  let i = 1
  while (i < drivingPathCumKm.length - 1 && drivingPathCumKm[i] < target) i++
  const segStartKm = drivingPathCumKm[i - 1]
  const segLen = drivingPathCumKm[i] - segStartKm
  const t = segLen > 0 ? (target - segStartKm) / segLen : 0
  const a = drivingPath[i - 1]
  const b = drivingPath[i]
  return {
    lng: a[0] + (b[0] - a[0]) * t,
    lat: a[1] + (b[1] - a[1]) * t,
    heading: bearingDeg(a[0], a[1], b[0], b[1]),
    segIdx: i - 1
  }
}

/** 相邻路网折线段的地理方位角（0°=正北，顺时针），驱动车标旋转与 HUD 航向 */
function bearingDeg(lng1, lat1, lng2, lat2) {
  const rad = Math.PI / 180
  const dLng = (lng2 - lng1) * rad
  const y = Math.sin(dLng) * Math.cos(lat2 * rad)
  const x = Math.cos(lat1 * rad) * Math.sin(lat2 * rad) - Math.sin(lat1 * rad) * Math.cos(lat2 * rad) * Math.cos(dLng)
  return (Math.atan2(y, x) / rad + 360) % 360
}

function updatePlaybackFromProps() {
  if (props.mapRoute?.truckPosition?.progressFraction != null) {
    playbackProgress.value = Math.round(props.mapRoute.truckPosition.progressFraction * 100)
  } else if (props.waybillInfo?.status === 'delivered') {
    playbackProgress.value = 100
  } else if (props.waybillInfo?.status === 'in_transit') {
    playbackProgress.value = 45
  } else if (props.waybillInfo?.status === 'out_for_delivery') {
    playbackProgress.value = 85
  }
}

watch(() => [props.waybillInfo, props.mapRoute], () => {
  updatePlaybackFromProps()
}, { deep: true })

/* 回放进度（含 0.5x/1x/2x 变速与进度滑杆拖拽）驱动车标沿路线推进 */
watch(playbackProgress, () => {
  syncTruckToRoute()
})

/* 运单轨迹节点变化时沿真实路网重新规划 */
const routePlanKey = computed(() => (activeHighwayNodes.value || [])
  .map(n => `${Number(n.lng).toFixed(4)},${Number(n.lat).toFixed(4)}`)
  .join('|'))

watch(routePlanKey, () => {
  plannedRouteDistanceKm.value = null
  drivingPath = []
  drivingPathTotalKm = 0
  if (activeEngine.value === 'amap' && amapInstance) planDrivingRoute()
})

/* 冷加载竞态恢复：AMap 先就绪而轨迹数据晚到时（首轮 <2 节点走了 fallback），
 * 节点数一旦过阈值即自动重规划——routePlanKey 身份不变的场景由本 watcher 兜底 */
watch(() => collectValidGeoNodes().length, (n, prev) => {
  if (n >= 2 && (prev ?? 0) < 2 && activeEngine.value === 'amap' && amapInstance && routePlanState.value === 'fallback') {
    planDrivingRoute()
  }
})

function checkIsMobile() {
  if (typeof window !== 'undefined') {
    // 断点对齐仓库 991/700 惯例（原 768 拆分：991 折叠 HUD、700 收纳引擎药丸）
    isMobile.value = window.innerWidth <= 991
    if (isMobile.value) {
      showTelemetryHud.value = false
    }
  }
}

onMounted(() => {
  checkIsMobile()
  window.addEventListener('resize', checkIsMobile)
  // 主题切换重读令牌并重涂 canvas 绘制链（data-theme 写在 <html> 上）
  if (typeof MutationObserver !== 'undefined' && document.documentElement) {
    themeObserver = new MutationObserver(() => {
      llTokenCache.clear()
      applyAmapThemeColors()
    })
    themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
  }
  // 历史版本曾读写 localStorage（lanlink_map_prefs 引擎/风格偏好、lanlink_amap_config 明文凭据），升级后仅清理、不再读写
  try {
    localStorage.removeItem('lanlink_map_prefs')
    localStorage.removeItem('lanlink_amap_config')
  } catch (_) {}
  initAMap()

  updatePlaybackFromProps()
  if (props.autoPlay) onPlayingUpdate(true)

  window.addEventListener('pointerup', onPointerUp)
  window.addEventListener('pointercancel', onPointerUp)
})

onBeforeUnmount(() => {
  stopAnimation()
  window.removeEventListener('resize', checkIsMobile)
  window.removeEventListener('pointerup', onPointerUp)
  window.removeEventListener('pointercancel', onPointerUp)
  if (ctrlWheelHintTimer) clearTimeout(ctrlWheelHintTimer)
  if (mobileGestureHintTimer) clearTimeout(mobileGestureHintTimer)
  if (themeObserver) {
    themeObserver.disconnect()
    themeObserver = null
  }
  destroyAMap()
})
</script>

<style scoped>
.logistics-dynamic-map-wrapper {
  background: radial-gradient(circle at 50% 20%, var(--ll-map-hud-bg) 0%, var(--ll-map-canvas-bg) 100%);
  border-radius: 12px;
  border: 1px solid var(--ll-map-truck-glow);
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.45), inset 0 1px 0 rgba(255, 255, 255, 0.08);
  overflow: hidden;
  color: var(--ll-text-inverse, #fff);
  position: relative;
  width: 100%;
}

.map-hud-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 14px;
  background: var(--ll-map-hud-bg);
  backdrop-filter: blur(8px);
  border-bottom: 1px solid var(--ll-map-grid-line);
  flex-wrap: wrap;
  gap: 8px;
}

.hud-left {
  display: flex;
  align-items: center;
  gap: 8px;
}
.hud-title {
  font-size: 13px;
  font-weight: 600;
  color: #f8fafc;
  letter-spacing: 0.02em;
}
.hud-sub {
  font-size: 11.5px;
  color: #94a3b8;
}
.mode-tag {
  display: inline-block;
  padding: 1px 6px;
  background: rgba(56, 189, 248, 0.15);
  color: #38bdf8;
  border-radius: 4px;
  font-size: 10.5px;
  margin-left: 4px;
}

.hud-right {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 11.5px;
  color: #cbd5e1;
}
.engine-indicator-pill {
  display: inline-flex;
  align-items: center;
  padding: 2px 7px;
  background: rgba(16, 185, 129, 0.15);
  color: #34d399;
  border: 1px solid rgba(16, 185, 129, 0.3);
  border-radius: 12px;
  font-size: 11px;
}
.engine-indicator-pill.is-amap {
  background: rgba(56, 189, 248, 0.15);
  color: #38bdf8;
  border-color: rgba(56, 189, 248, 0.3);
}

.status-pill {
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 600;
}
.pill-active { background: rgba(2, 132, 199, 0.25); color: #38bdf8; border: 1px solid #0284c7; }
.pill-success { background: rgba(16, 185, 129, 0.25); color: #34d399; border: 1px solid #10b981; }
.pill-danger { background: rgba(239, 68, 68, 0.25); color: #f87171; border: 1px solid #ef4444; }
.pill-info { background: rgba(148, 163, 184, 0.2); color: #cbd5e1; }

.hud-toggle-btn {
  display: inline-flex;
  align-items: center;
  min-height: 28px;
  padding: 3px 10px;
  border-radius: 6px;
  background: rgba(30, 41, 59, 0.8);
  border: 1px solid rgba(56, 189, 248, 0.3);
  color: #e2e8f0;
  font-size: 11.5px;
  cursor: pointer;
  transition: all 0.2s ease;
}
.hud-toggle-btn.is-active {
  background: #0284c7;
  color: #fff;
  border-color: #38bdf8;
}

.map-canvas-container {
  position: relative;
  width: 100%;
  height: 420px;
  overflow: hidden;
  background: #070c14;
  touch-action: pan-y;
  cursor: grab;
  user-select: none;
}
.map-canvas-container:active {
  cursor: grabbing;
}

.map-svg {
  width: 100%;
  height: 100%;
  display: block;
}

/* 省份多边形样式 - 高清晰对比度工业底图 */
.province-polygon {
  fill: #132238;
  stroke: #1e3a5f;
  stroke-width: 1.2;
  transition: fill 0.25s ease, stroke 0.25s ease;
}
.province-polygon:hover {
  fill: #1c3558;
  stroke: #38bdf8;
}
.province-polygon.is-active-corridor {
  fill: rgba(14, 165, 233, 0.22);
  stroke: #0ea5e9;
  stroke-width: 1.8;
}

/* 高对比度模式下的省份高亮覆盖 */
.is-high-contrast .province-polygon {
  fill: #1a2f4c;
  stroke: #38bdf8;
  stroke-width: 1.4;
}
.is-high-contrast .province-polygon.is-active-corridor {
  fill: rgba(56, 189, 248, 0.35);
  stroke: #67e8f9;
  stroke-width: 2.2;
}

.province-name-label {
  font-size: 10px;
  fill: #cbd5e1;
  font-weight: 500;
  text-shadow: 0 1px 3px rgba(0, 0, 0, 0.9);
  pointer-events: none;
  user-select: none;
}
.is-high-contrast .province-name-label {
  fill: #f8fafc;
  font-weight: 600;
}

/* 水运河流样式（长江、黄河） */
.river-flow-path {
  fill: none;
  stroke: #06b6d4;
  stroke-width: 2.4;
  stroke-linecap: round;
  stroke-linejoin: round;
  filter: drop-shadow(0 0 3px rgba(6, 182, 212, 0.45));
}

/* 南海十段线 */
.ten-dash-line {
  fill: none;
  stroke: #38bdf8;
  stroke-width: 1.8;
  stroke-dasharray: 4, 3;
}
.ten-dash-text {
  font-size: 9px;
  fill: #38bdf8;
  font-weight: 500;
  letter-spacing: 0.1em;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.8);
}

/* 高速路网背景线 */
.highway-corridor-path {
  fill: none;
  stroke: rgba(100, 116, 139, 0.55);
  stroke-width: 1.6;
  stroke-dasharray: 4, 3;
}

/* 城市点位 */
.city-dot {
  fill: #64748b;
  transition: fill 0.2s ease;
}
.city-name {
  font-size: 8.5px;
  fill: #94a3b8;
  pointer-events: none;
  user-select: none;
}
.major-city-point.is-hub .city-dot {
  fill: #38bdf8;
}
.major-city-point.is-hub .city-name {
  fill: #e2e8f0;
  font-weight: 600;
}

/* 运单真实轨迹线 */
.route-track-bg {
  fill: none;
  stroke: rgba(2, 132, 199, 0.3);
  stroke-width: 5;
  stroke-linecap: round;
}
.route-track-bg.is-rma {
  stroke: rgba(245, 158, 11, 0.35);
}
.route-track-flow {
  fill: none;
  stroke: url(#routeGrad);
  stroke-width: 3.5;
  stroke-linecap: round;
  stroke-dasharray: 8, 4;
  animation: flowLine 25s linear infinite;
}
.route-track-flow.is-rma {
  stroke: url(#rmaRouteGrad);
  animation: rmaFlowLine 25s linear infinite;
}
@keyframes flowLine {
  from { stroke-dashoffset: 600; }
  to { stroke-dashoffset: 0; }
}
@keyframes rmaFlowLine {
  from { stroke-dashoffset: 0; }
  to { stroke-dashoffset: 600; }
}

/* 逆向标签与状态药丸 */
.mode-tag.is-rma {
  background: rgba(245, 158, 11, 0.2);
  color: #f59e0b;
  border: 1px solid rgba(245, 158, 11, 0.4);
}
.pill-rma {
  background: rgba(245, 158, 11, 0.25);
  color: #fbbf24;
  border: 1px solid #f59e0b;
}

/* 枢纽节点雷达脉冲 */
.map-node-group {
  cursor: pointer;
}
.radar-pulse {
  fill: none;
  stroke-width: 1.5;
  animation: radarWave 2s cubic-bezier(0, 0.2, 0.8, 1) infinite;
}
@keyframes radarWave {
  0% { r: 6; opacity: 1; }
  100% { r: 20; opacity: 0; }
}
.node-core {
  stroke: #ffffff;
  stroke-width: 1.5;
}
.node-label {
  font-size: 11px;
  fill: #f1f5f9;
  font-weight: 600;
  text-shadow: 0 1px 3px rgba(0, 0, 0, 0.8);
}
.type-warehouse { stroke: #10b981; fill: #059669; }
.type-hub { stroke: #38bdf8; fill: #0284c7; }
.type-transfer { stroke: #818cf8; fill: #4f46e5; }
.type-client { stroke: #f43f5e; fill: #e11d48; }
.type-return_origin { stroke: #fbbf24; fill: #d97706; }
.type-return_dest { stroke: #c084fc; fill: #7c3aed; }

/* 卡车航向与探照灯 */
.truck-beacon-pulse {
  fill: rgba(56, 189, 248, 0.2);
  stroke: #38bdf8;
  stroke-width: 1.5;
  animation: radarWave 1.6s ease-out infinite;
}
.truck-marker-group.is-rma .truck-beacon-pulse {
  fill: rgba(245, 158, 11, 0.2);
  stroke: #f59e0b;
}
.truck-headlight {
  fill: rgba(56, 189, 248, 0.28);
  filter: blur(1.5px);
}
.truck-body {
  fill: #0284c7;
  stroke: #e0f2fe;
  stroke-width: 1;
}
.truck-marker-group.is-rma .truck-body {
  fill: #7c3aed;
  stroke: #fdf4ff;
}
.truck-cab {
  fill: #38bdf8;
}
.truck-marker-group.is-rma .truck-cab {
  fill: #f59e0b;
}
.truck-hud-text {
  font-size: 10.5px;
  fill: #38bdf8;
  font-weight: 600;
  text-shadow: 0 1px 4px rgba(0, 0, 0, 0.9);
}
.truck-marker-group.is-rma .truck-hud-text {
  fill: #fbbf24;
}

/* SVG 内置自跟随提示气泡 (100% 贴合，缩放平移不脱靶) */
.svg-node-popover {
  user-select: none;
}
.svg-popover-bg {
  fill: rgba(15, 23, 42, 0.95);
  stroke: #38bdf8;
  stroke-width: 1.5;
  filter: drop-shadow(0 4px 12px rgba(0, 0, 0, 0.7));
}
.svg-popover-arrow {
  fill: #38bdf8;
}
.svg-popover-title {
  font-size: 11px;
  fill: #f8fafc;
  font-weight: 600;
}
.svg-popover-coords {
  font-size: 9.5px;
  fill: #94a3b8;
  font-family: monospace;
}

/* 协同手势轻提示 (PC & Mobile) */
.ctrl-wheel-hint,
.mobile-gesture-hint {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  background: rgba(15, 23, 42, 0.94);
  border: 1px solid rgba(56, 189, 248, 0.4);
  color: #e0f2fe;
  padding: 8px 16px;
  border-radius: 20px;
  font-size: 12px;
  font-weight: 500;
  pointer-events: none;
  z-index: 30;
  backdrop-filter: blur(8px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.6);
  white-space: nowrap;
}

/* 移动端手势解锁药丸按钮 */
.mobile-gesture-pill {
  position: absolute;
  bottom: 12px;
  right: 12px;
  z-index: 20;
  display: inline-flex;
  align-items: center;
  padding: 5px 10px;
  border-radius: 16px;
  background: rgba(15, 23, 42, 0.9);
  border: 1px solid rgba(56, 189, 248, 0.35);
  color: #94a3b8;
  font-size: 11px;
  cursor: pointer;
  backdrop-filter: blur(8px);
  transition: all 0.2s ease;
}
.mobile-gesture-pill.is-unlocked {
  background: #0284c7;
  color: #fff;
  border-color: #38bdf8;
  box-shadow: 0 0 10px rgba(2, 132, 199, 0.5);
}

/* 移动端紧凑遥测胶囊 */
.mobile-telemetry-capsule {
  position: absolute;
  bottom: 12px;
  left: 12px;
  z-index: 20;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  border-radius: 16px;
  background: rgba(15, 23, 42, 0.92);
  border: 1px solid rgba(56, 189, 248, 0.35);
  color: #f1f5f9;
  font-size: 10.5px;
  cursor: pointer;
  backdrop-filter: blur(8px);
  max-width: calc(100% - 150px);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.capsule-more {
  color: #38bdf8;
  font-size: 10px;
}

/* 遥测 Bento HUD */
.telemetry-bento-hud {
  position: absolute;
  top: 12px;
  left: 12px;
  z-index: 10;
  background: rgba(15, 23, 42, 0.88);
  backdrop-filter: blur(12px);
  border: 1px solid rgba(56, 189, 248, 0.25);
  border-radius: 10px;
  padding: 10px 14px;
  width: 290px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.4);
}
.hud-bento-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.bento-title {
  font-size: 11.5px;
  font-weight: 600;
  color: #f1f5f9;
}
.bento-tag {
  font-size: 10px;
  color: #34d399;
  background: rgba(16, 185, 129, 0.15);
  padding: 1px 5px;
  border-radius: 4px;
}
.hud-bento-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
  margin-bottom: 8px;
}
.bento-cell {
  background: rgba(30, 41, 59, 0.6);
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: 6px;
  padding: 6px 8px;
  display: flex;
  flex-direction: column;
}
.cell-label {
  font-size: 10px;
  color: #94a3b8;
  margin-bottom: 2px;
}
.cell-val {
  font-size: 11.5px;
  font-weight: 600;
  color: #f8fafc;
}
.hud-bento-foot {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 11px;
  color: #cbd5e1;
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  padding-top: 6px;
}

/* 视角与设置工具栏 */
.map-view-controls {
  position: absolute;
  top: 12px;
  right: 12px;
  z-index: 10;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.view-btn {
  width: 32px;
  height: 32px;
  border-radius: 6px;
  background: rgba(15, 23, 42, 0.85);
  backdrop-filter: blur(8px);
  border: 1px solid rgba(56, 189, 248, 0.25);
  color: #f8fafc;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.2s ease;
  touch-action: manipulation;
}
.view-btn:hover {
  background: #0284c7;
  border-color: #38bdf8;
}
.view-btn.is-active {
  background: #0284c7;
  color: #fff;
}

/* 高德地图容器与加载/失败遮罩 */
.amap-container {
  width: 100%;
  height: 100%;
  position: relative;
}
.amap-loading-mask {
  position: absolute;
  inset: 0;
  background: var(--ll-map-mask-bg);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #cbd5e1;
  font-size: 12px;
  gap: 8px;
  padding: 20px;
  text-align: center;
}
/* 失败蒙层（设计 A5）：原因 + 配置引导 + 重试/降级双出口 */
.amap-error-mask {
  position: absolute;
  inset: 0;
  z-index: 5;
  background: var(--ll-map-mask-bg);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  color: #cbd5e1;
  font-size: 12px;
  gap: 8px;
  padding: 20px;
  text-align: center;
}
.amap-error-icon {
  color: var(--ll-amber);
}
.amap-error-text {
  color: #f8fafc;
  max-width: 480px;
  line-height: 1.6;
}
.amap-error-hint {
  color: #94a3b8;
  max-width: 480px;
  line-height: 1.6;
}
.amap-error-actions {
  display: flex;
  gap: 10px;
  margin-top: 4px;
  flex-wrap: wrap;
  justify-content: center;
}
.fallback-btn {
  padding: 6px 14px;
  background: var(--ll-map-track-passed);
  border: none;
  border-radius: var(--ll-radius-md, 10px);
  color: var(--ll-text-inverse, #fff);
  font-size: 12px;
  cursor: pointer;
  transition: background 0.2s ease;
}
.fallback-btn:hover {
  background: var(--ll-map-track-todo);
}
.fallback-btn.is-ghost {
  background: transparent;
  border: 1px solid var(--ll-map-track-todo);
  color: #cbd5e1;
}
.fallback-btn.is-ghost:hover {
  background: var(--ll-map-grid-line);
}

/* 高德轨迹覆盖物（AMap 运行时注入的 DOM 不带 scoped 属性，需 :deep 穿透；颜色全部走令牌） */
.amap-container :deep(.amap-truck-marker) {
  width: 34px;
  height: 34px;
}
.amap-container :deep(.amap-truck-marker .amap-truck-ring) {
  fill: var(--ll-map-truck-glow);
  stroke: var(--ll-map-truck-body-f);
}
.amap-container :deep(.amap-truck-marker .amap-truck-body) {
  fill: var(--ll-map-truck-body-f);
  stroke: var(--ll-map-truck-edge-f);
}
.amap-container :deep(.amap-truck-marker .amap-truck-cab) {
  fill: var(--ll-map-truck-cab-f);
  stroke: var(--ll-map-truck-edge-f);
}
.amap-container :deep(.amap-truck-marker.is-rma .amap-truck-ring) {
  fill: var(--ll-map-truck-glow-r);
  stroke: var(--ll-map-truck-body-r);
}
.amap-container :deep(.amap-truck-marker.is-rma .amap-truck-body) {
  fill: var(--ll-map-truck-body-r);
  stroke: var(--ll-map-truck-edge-r);
}
.amap-container :deep(.amap-truck-marker.is-rma .amap-truck-cab) {
  fill: var(--ll-map-truck-cab-r);
  stroke: var(--ll-map-truck-edge-r);
}
.amap-container :deep(.amap-route-endpoint) {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 8px;
  border-radius: 12px;
  background: var(--ll-map-hud-bg);
  border: 1px solid var(--ll-map-truck-glow);
  color: var(--ll-text-inverse, #fff);
  font-size: 11px;
  white-space: nowrap;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.35);
}
.amap-container :deep(.amap-route-endpoint .ep-dot) {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}
.amap-container :deep(.amap-route-endpoint.start .ep-dot) {
  background: var(--ll-map-start-dot);
}
.amap-container :deep(.amap-route-endpoint.end .ep-dot) {
  background: var(--ll-map-end-dot);
}
.amap-container :deep(.amap-route-endpoint .ep-text) {
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.8);
}

/* 悬浮提示框 */
.node-popover {
  position: absolute;
  z-index: 15;
  background: rgba(15, 23, 42, 0.95);
  border: 1px solid #38bdf8;
  border-radius: 6px;
  padding: 8px 12px;
  font-size: 11.5px;
  color: #f8fafc;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.5);
  pointer-events: none;
}
.hub-pill {
  font-size: 10px;
  color: #38bdf8;
  background: rgba(56, 189, 248, 0.15);
  padding: 1px 5px;
  border-radius: 4px;
  margin-right: 6px;
}

/* 底部装载明细 */
.map-footer-panel {
  padding: 10px 14px;
  background: rgba(15, 23, 42, 0.9);
  border-top: 1px solid rgba(56, 189, 248, 0.15);
}
.panel-header {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #cbd5e1;
  margin-bottom: 6px;
}
.sku-chips-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.sku-chip {
  background: rgba(30, 41, 59, 0.7);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 6px;
  padding: 4px 8px;
  font-size: 11px;
}
.chip-name { color: #f8fafc; margin-right: 6px; }
.chip-qty { color: #38bdf8; font-weight: 600; }

.pulse-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #34d399;
  display: inline-block;
  animation: pulse 1.6s infinite;
}
@keyframes pulse {
  0% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(52, 211, 153, 0.7); }
  70% { transform: scale(1); box-shadow: 0 0 0 6px rgba(52, 211, 153, 0); }
  100% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(52, 211, 153, 0); }
}

/* 平板/小窗断点（≤991，原 768 档上移对齐仓库惯例）：HUD 压缩为摘要行 */
@media (max-width: 991px) {
  .map-hud-bar {
    padding: 6px 10px;
    gap: 4px;
  }
  .hud-left {
    gap: 6px;
  }
  .hud-title {
    font-size: 11.5px;
  }
  .hud-sub {
    font-size: 10.5px;
  }
  .hud-right {
    gap: 6px;
    font-size: 10.5px;
  }
  .map-canvas-container {
    height: 340px;
    touch-action: pan-y;
  }
  .map-canvas-container.is-gesture-unlocked {
    touch-action: none;
  }
  .telemetry-bento-hud {
    width: calc(100% - 24px);
    max-width: 290px;
    padding: 8px 10px;
    top: 8px;
    left: 8px;
  }
  .view-btn {
    width: 34px;
    height: 34px;
  }
  .map-view-controls {
    top: 8px;
    right: 8px;
  }
}

/* 手机断点（≤700）：引擎药丸收起、HUD 收敛到状态行（里程/航向由遥测胶囊承载） */
@media (max-width: 700px) {
  .engine-indicator-pill {
    display: none;
  }
  .hud-right {
    gap: 4px;
  }
  .hud-metric-dist {
    display: none;
  }
  .map-canvas-container {
    height: 300px;
  }
  .mobile-telemetry-capsule {
    max-width: calc(100% - 16px);
  }
}
</style>

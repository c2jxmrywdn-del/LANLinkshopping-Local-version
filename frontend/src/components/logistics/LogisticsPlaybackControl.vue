<template>
  <div class="playback-control-bar">
    <!-- 左侧主操作区：播放/暂停、重播、单步微调 -->
    <div class="control-actions">
      <button
        type="button"
        class="ctrl-btn play-btn"
        :class="{ 'is-playing': playing }"
        :title="playing ? '暂停回放' : '开始轨迹动态回放'"
        @click="togglePlay"
      >
        <Pause v-if="playing" :size="15" class="align-middle" />
        <Play v-else :size="15" class="align-middle ml-0.5" />
      </button>

      <button
        type="button"
        class="ctrl-btn reset-btn"
        title="重置回放至起点"
        @click="resetProgress"
      >
        <RotateCcw :size="14" class="align-middle" />
      </button>

      <div class="speed-selector">
        <button
          v-for="s in speedOptions"
          :key="s"
          type="button"
          class="speed-btn"
          :class="{ 'is-active': currentSpeed === s }"
          @click="selectSpeed(s)"
        >
          {{ s }}x
        </button>
      </div>
    </div>

    <!-- 中间：交互式时空拖拽滑块与关键节点标记 -->
    <div class="timeline-scrubber-wrap">
      <div class="slider-rail" ref="railRef" @click="handleRailClick">
        <!-- 进度填充条 -->
        <div class="slider-fill" :style="{ width: `${clampedProgress}%` }"></div>

        <!-- 关键枢纽里程碑锚点 -->
        <div
          v-for="(node, idx) in milestones"
          :key="idx"
          class="milestone-dot"
          :class="{ 'is-passed': clampedProgress >= node.pct, 'is-active': activeNodeIdx === idx }"
          :style="{ left: `${node.pct}%` }"
          :title="`${node.name} (${node.pct}%)`"
          @click.stop="seekToPercent(node.pct)"
        >
          <span class="milestone-inner"></span>
          <span class="milestone-label">{{ node.name }}</span>
        </div>

        <!-- 拖拽滑块柄 -->
        <div
          class="slider-thumb"
          :style="{ left: `${clampedProgress}%` }"
          tabindex="0"
          role="slider"
          :aria-valuenow="Math.round(clampedProgress)"
          aria-valuemin="0"
          aria-valuemax="100"
          @mousedown="startDrag"
          @touchstart="startTouchDrag"
        >
          <span class="thumb-glow"></span>
        </div>
      </div>
    </div>

    <!-- 右侧：当前时空进度与状态指示 -->
    <div class="timeline-meta">
      <div class="pct-badge">
        <Compass :size="12" class="inline mr-1 text-sky-400" />
        <span>{{ Math.round(clampedProgress) }}%</span>
      </div>
      <div class="node-tip" :title="currentNodeDesc">
        {{ currentNodeDesc }}
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, onMounted, onBeforeUnmount } from 'vue'
import { Play, Pause, RotateCcw, Compass } from 'lucide-vue-next'

const props = defineProps({
  progress: {
    type: Number,
    default: 0
  },
  playing: {
    type: Boolean,
    default: false
  },
  speed: {
    type: Number,
    default: 1.0
  },
  nodes: {
    type: Array,
    default: () => []
  }
})

const emit = defineEmits(['update:progress', 'update:playing', 'update:speed', 'seek'])

const speedOptions = [0.5, 1.0, 2.0]
const currentSpeed = computed(() => props.speed || 1.0)
const railRef = ref(null)
let isDragging = false

const clampedProgress = computed(() => {
  const p = Number(props.progress || 0)
  return Math.max(0, Math.min(100, p))
})

/** 计算各节点在时间轴上的百分比位置 */
const milestones = computed(() => {
  const list = props.nodes || []
  if (!list.length) {
    return [
      { name: '始发揽收', pct: 0 },
      { name: '干线转运', pct: 45 },
      { name: '末端派送', pct: 85 },
      { name: '签收现场', pct: 100 }
    ]
  }
  const n = list.length
  return list.map((item, idx) => {
    const pct = n <= 1 ? 0 : Math.round((idx / (n - 1)) * 100)
    return {
      name: item.name || `节点${idx + 1}`,
      pct,
      data: item
    }
  })
})

const activeNodeIdx = computed(() => {
  const p = clampedProgress.value
  const ms = milestones.value
  for (let i = ms.length - 1; i >= 0; i--) {
    if (p >= ms[i].pct) return i
  }
  return 0
})

const currentNodeDesc = computed(() => {
  const ms = milestones.value
  if (!ms.length) return '运输筹备中'
  const curr = ms[activeNodeIdx.value]
  if (clampedProgress.value >= 100) return '已送达签收现场'
  if (clampedProgress.value <= 0) return '始发仓装车待发'
  return `经停：${curr?.name || '干线中转'}`
})

function togglePlay() {
  emit('update:playing', !props.playing)
}

function resetProgress() {
  emit('update:progress', 0)
  emit('seek', 0)
}

function selectSpeed(s) {
  emit('update:speed', s)
}

function seekToPercent(pct) {
  const target = Math.max(0, Math.min(100, pct))
  emit('update:progress', target)
  emit('seek', target)
}

function handleRailClick(e) {
  if (!railRef.value) return
  const rect = railRef.value.getBoundingClientRect()
  const clickX = e.clientX - rect.left
  const pct = (clickX / rect.width) * 100
  seekToPercent(pct)
}

/* 鼠标拖拽 */
function startDrag(e) {
  isDragging = true
  window.addEventListener('mousemove', onMouseMove)
  window.addEventListener('mouseup', stopDrag)
  e.preventDefault()
}

function onMouseMove(e) {
  if (!isDragging || !railRef.value) return
  const rect = railRef.value.getBoundingClientRect()
  const currentX = e.clientX - rect.left
  const pct = (currentX / rect.width) * 100
  seekToPercent(pct)
}

function stopDrag() {
  isDragging = false
  window.removeEventListener('mousemove', onMouseMove)
  window.removeEventListener('mouseup', stopDrag)
}

/* 移动端触控拖拽 */
function startTouchDrag(e) {
  isDragging = true
  window.addEventListener('touchmove', onTouchMove, { passive: false })
  window.addEventListener('touchend', stopTouchDrag)
}

function onTouchMove(e) {
  if (!isDragging || !railRef.value || !e.touches?.length) return
  const touch = e.touches[0]
  const rect = railRef.value.getBoundingClientRect()
  const currentX = touch.clientX - rect.left
  const pct = (currentX / rect.width) * 100
  seekToPercent(pct)
  e.preventDefault()
}

function stopTouchDrag() {
  isDragging = false
  window.removeEventListener('touchmove', onTouchMove)
  window.removeEventListener('touchend', stopTouchDrag)
}

onBeforeUnmount(() => {
  stopDrag()
  stopTouchDrag()
})
</script>

<style scoped>
.playback-control-bar {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 10px 16px;
  background: rgba(15, 23, 42, 0.95);
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  backdrop-filter: blur(12px);
  color: #f1f5f9;
  font-size: 12px;
  user-select: none;
}

/* 操作按钮区 */
.control-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.ctrl-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  min-width: 32px;
  min-height: 32px;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(255, 255, 255, 0.06);
  color: #e2e8f0;
  cursor: pointer;
  transition: all 0.2s ease;
  touch-action: manipulation;
}
.ctrl-btn:hover {
  background: rgba(56, 189, 248, 0.2);
  border-color: rgba(56, 189, 248, 0.5);
  color: #38bdf8;
}

.play-btn {
  background: #0284c7;
  border-color: #38bdf8;
  color: #ffffff;
}
.play-btn:hover {
  background: #0369a1;
  color: #ffffff;
}
.play-btn.is-playing {
  background: #d97706;
  border-color: #fbbf24;
}

/* 速率选择器 */
.speed-selector {
  display: flex;
  align-items: center;
  background: rgba(0, 0, 0, 0.3);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 6px;
  padding: 2px;
}
.speed-btn {
  background: transparent;
  border: none;
  color: #94a3b8;
  font-size: 11px;
  font-weight: 600;
  padding: 2px 6px;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.15s;
  touch-action: manipulation;
}
.speed-btn:hover {
  color: #f1f5f9;
}
.speed-btn.is-active {
  background: rgba(56, 189, 248, 0.25);
  color: #38bdf8;
}

/* 时间轴滑轨 */
.timeline-scrubber-wrap {
  flex: 1;
  position: relative;
  padding: 12px 6px;
}
.slider-rail {
  position: relative;
  width: 100%;
  height: 6px;
  background: rgba(255, 255, 255, 0.14);
  border-radius: 3px;
  cursor: pointer;
}
.slider-fill {
  position: absolute;
  left: 0;
  top: 0;
  height: 100%;
  background: linear-gradient(90deg, #0284c7 0%, #38bdf8 100%);
  border-radius: 3px;
  pointer-events: none;
  box-shadow: 0 0 8px rgba(56, 189, 248, 0.5);
}

/* 里程碑锚点 */
.milestone-dot {
  position: absolute;
  top: 50%;
  transform: translate(-50%, -50%);
  width: 12px;
  height: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  z-index: 2;
}
.milestone-inner {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #64748b;
  border: 1px solid #0f172a;
  transition: all 0.2s;
}
.milestone-dot.is-passed .milestone-inner {
  background: #38bdf8;
  box-shadow: 0 0 6px #38bdf8;
}
.milestone-dot.is-active .milestone-inner {
  transform: scale(1.4);
  background: #fbbf24;
  box-shadow: 0 0 8px #fbbf24;
}
.milestone-label {
  display: none;
  position: absolute;
  bottom: 14px;
  left: 50%;
  transform: translateX(-50%);
  background: rgba(15, 23, 42, 0.9);
  border: 1px solid rgba(255, 255, 255, 0.15);
  padding: 2px 6px;
  border-radius: 4px;
  font-size: 10px;
  color: #e2e8f0;
  white-space: nowrap;
  pointer-events: none;
}
.milestone-dot:hover .milestone-label {
  display: block;
}

/* 滑块柄 */
.slider-thumb {
  position: absolute;
  top: 50%;
  transform: translate(-50%, -50%);
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #ffffff;
  border: 2px solid #0284c7;
  cursor: grab;
  z-index: 3;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.4);
  transition: transform 0.1s ease;
  touch-action: none;
}
.slider-thumb:active {
  cursor: grabbing;
  transform: translate(-50%, -50%) scale(1.2);
}
.thumb-glow {
  display: block;
  width: 100%;
  height: 100%;
  border-radius: 50%;
  background: #38bdf8;
  opacity: 0.4;
  animation: pulse-glow 1.8s infinite;
}
@keyframes pulse-glow {
  0%, 100% { transform: scale(1); opacity: 0.4; }
  50% { transform: scale(1.6); opacity: 0; }
}

/* 进度信息 */
.timeline-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  min-width: 110px;
  flex-shrink: 0;
}
.pct-badge {
  display: inline-flex;
  align-items: center;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  font-weight: 700;
  color: #38bdf8;
}
.node-tip {
  font-size: 11px;
  color: #94a3b8;
  max-width: 130px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 移动端响应式（<= 640px） */
@media (max-width: 640px) {
  .playback-control-bar {
    flex-wrap: wrap;
    gap: 8px;
    padding: 8px 12px;
  }
  .timeline-scrubber-wrap {
    order: 1;
    width: 100%;
    flex: none;
    padding: 10px 0;
  }
  .control-actions {
    order: 2;
  }
  .ctrl-btn {
    width: 38px;
    height: 38px;
  }
  .timeline-meta {
    order: 3;
    margin-left: auto;
  }
}
</style>

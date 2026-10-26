<template>
  <div
    :class="[
      'apple-dynamic-island-wrapper',
      fixed ? 'is-fixed fixed top-3 left-1/2 -translate-x-1/2 z-50 pointer-events-none' : 'relative inline-flex'
    ]"
  >
    <div
      ref="islandRef"
      class="apple-dynamic-island pointer-events-auto"
      :class="[
        `state-${currentState}`,
        { 'is-interactive': interactive }
      ]"
      @click="handleIslandClick"
      @mouseenter="handleMouseEnter"
      @mouseleave="handleMouseLeave"
    >
      <!-- State 1: COMPACT 紧凑药丸态 -->
      <div v-if="currentState === 'compact'" class="island-content island-compact">
        <slot name="compact">
          <div class="compact-leading">
            <AppleIcon
              v-if="icon"
              :name="icon"
              class="island-mini-icon"
              :class="statusType"
              :size="14"
            />
            <span v-else class="status-pulse-dot" :class="statusType"></span>
            <span class="compact-title">{{ title || 'LANLink Live' }}</span>
          </div>
          <div class="compact-trailing">
            <span v-if="badge" class="compact-badge">{{ badge }}</span>
            <div v-else class="compact-wave">
              <span class="wave-bar"></span>
              <span class="wave-bar"></span>
              <span class="wave-bar"></span>
            </div>
          </div>
        </slot>
      </div>

      <!-- State 2: NOTIFICATION 横向通知态 -->
      <div v-else-if="currentState === 'notification'" class="island-content island-notification">
        <slot name="notification">
          <div class="notif-avatar">
            <slot name="icon">
              <div v-if="icon" class="default-icon" :class="statusType">
                <AppleIcon :name="icon" :size="16" />
              </div>
              <div v-else class="default-icon" :class="statusType">
                <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M13 10V3L4 14h7v7l9-11h-7z" />
                </svg>
              </div>
            </slot>
          </div>
          <div class="notif-body">
            <div class="notif-title">{{ title || 'LANLink 实时活动已就绪' }}</div>
            <div class="notif-desc">{{ description || '轻触查看最新大促与订单状态' }}</div>
          </div>
          <div class="notif-trailing">
            <span class="notif-tag">{{ tag || 'LIVE' }}</span>
          </div>
        </slot>
      </div>

      <!-- State 3: EXPANDED 深度沉浸展开态 (移动端全宽自适应) -->
      <div v-else class="island-content island-expanded">
        <slot name="expanded">
          <div class="expanded-header">
            <div class="header-left">
              <div v-if="icon" class="app-icon" :class="statusType">
                <AppleIcon :name="icon" :size="20" />
              </div>
              <div v-else class="app-icon" :class="statusType">
                <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="2">
                  <path stroke-linecap="round" stroke-linejoin="round" d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z" />
                </svg>
              </div>
              <div class="header-text">
                <div class="header-title">{{ title || 'LANLink 实时活动中心' }}</div>
                <div class="header-subtitle">{{ description || '态势感知与业务提醒' }}</div>
              </div>
            </div>
            <button
              class="close-button"
              type="button"
              aria-label="收起"
              @click.stop="setMode('compact')"
            >
              <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.5">
                <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>
          </div>

          <div class="expanded-body">
            <div class="metric-row">
              <div class="metric-item">
                <div class="metric-label">服务状态</div>
                <div class="metric-value text-emerald-400">运行良好</div>
              </div>
              <div class="metric-item">
                <div class="metric-label">大促特惠</div>
                <div class="metric-value text-sky-400">进行中</div>
              </div>
              <div class="metric-item">
                <div class="metric-label">网络时延</div>
                <div class="metric-value text-amber-300">&lt; 18ms</div>
              </div>
            </div>

            <!-- Progress bar -->
            <div class="progress-wrap">
              <div class="progress-bar">
                <div class="progress-fill" :style="{ width: `${progress}%` }"></div>
              </div>
              <div class="progress-label">
                <span>{{ progressText || '大促特惠倒计时' }}</span>
                <span>{{ progress }}%</span>
              </div>
            </div>
          </div>

          <div class="expanded-actions">
            <button
              v-for="act in actions"
              :key="act.key"
              type="button"
              class="action-btn"
              :class="act.variant || 'default'"
              @click.stop="handleAction(act)"
            >
              {{ act.label }}
            </button>
            <button
              type="button"
              class="action-btn primary"
              @click.stop="handlePrimaryClick"
            >
              {{ primaryText || '进入详情' }}
            </button>
          </div>
        </slot>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * AppleDynamicIsland.vue — iOS 灵动岛交互悬浮组件 (支持移动端与桌面全适配)
 * 灵感源: 21st.dev Cult UI / SmoothUI
 *
 * 特性:
 * - 纯 Vue 3 原生实现，零第三方动画依赖
 * - 移动端自适应: 自动根据屏幕视口宽度约束药丸与卡片尺寸，避免横向撑爆
 * - 适配 Safe Area Insets (全面屏手机刘海与动态岛避让)
 * - 仿生 iOS 物理弹簧变形 (cubic-bezier 减速回弹曲线)
 * - 3 种标准尺寸态势: compact (药丸), notification (通告横幅), expanded (全功能卡片)
 */
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import AppleIcon from './AppleIcon.vue'

const props = defineProps({
  /** 模式: 'compact' | 'notification' | 'expanded' */
  modelValue: {
    type: String,
    default: 'compact'
  },
  /** 是否固定置顶于视口中央 */
  fixed: {
    type: Boolean,
    default: true
  },
  /** 是否允许点击/悬停切换状态 */
  interactive: {
    type: Boolean,
    default: true
  },
  /** 是否悬停时自动展开为 notification (桌面端生效) */
  expandOnHover: {
    type: Boolean,
    default: true
  },
  /** 标题 */
  title: {
    type: String,
    default: ''
  },
  /** 自定义矢量图标组件 (Lucide 等) */
  icon: {
    type: [Object, Function, String],
    default: null
  },
  /** 副标题或详情说明 */
  description: {
    type: String,
    default: ''
  },
  /** 右侧徽章文字 */
  badge: {
    type: String,
    default: ''
  },
  /** 标签文字 */
  tag: {
    type: String,
    default: ''
  },
  /** 状态类型: 'success' | 'warning' | 'info' | 'danger' */
  statusType: {
    type: String,
    default: 'info'
  },
  /** 进度百分比 0-100 */
  progress: {
    type: Number,
    default: 68
  },
  /** 进度说明 */
  progressText: {
    type: String,
    default: '大促进行中'
  },
  /** 展开态主操作按钮文字 */
  primaryText: {
    type: String,
    default: '立即查看'
  },
  /** 展开态辅助操作按钮列表: [{ key, label, variant }] */
  actions: {
    type: Array,
    default: () => [
      { key: 'dismiss', label: '收起', variant: 'ghost' }
    ]
  }
})

const emit = defineEmits(['update:modelValue', 'change', 'action', 'primary-click'])

const islandRef = ref(null)
const internalState = ref(props.modelValue || 'compact')

const currentState = computed(() => props.modelValue || internalState.value)

watch(() => props.modelValue, (newVal) => {
  if (newVal) internalState.value = newVal
})

function setMode(mode) {
  internalState.value = mode
  emit('update:modelValue', mode)
  emit('change', mode)
}

function handleIslandClick() {
  if (!props.interactive) return
  if (currentState.value === 'compact') {
    setMode('notification')
  } else if (currentState.value === 'notification') {
    setMode('expanded')
  } else {
    setMode('compact')
  }
}

let hoverTimer = null
function handleMouseEnter() {
  if (!props.interactive || !props.expandOnHover) return
  // 仅在支持鼠标悬停的设备上触发 hover 自动展开，避免手机端误触
  if (window.matchMedia && !window.matchMedia('(hover: hover)').matches) return
  if (currentState.value === 'compact') {
    hoverTimer = setTimeout(() => {
      setMode('notification')
    }, 120)
  }
}

function handleMouseLeave() {
  if (hoverTimer) {
    clearTimeout(hoverTimer)
    hoverTimer = null
  }
}

function handleAction(action) {
  emit('action', action)
  if (action.key === 'dismiss') {
    setMode('compact')
  }
}

function handlePrimaryClick() {
  emit('primary-click')
}

// 移动端点击外部收起
function onDocClick(e) {
  if (currentState.value !== 'compact' && islandRef.value && !islandRef.value.contains(e.target)) {
    setMode('compact')
  }
}

onMounted(() => {
  document.addEventListener('click', onDocClick)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick)
})

defineExpose({
  setMode,
  currentState
})
</script>

<style scoped>
.apple-dynamic-island-wrapper {
  perspective: 1000px;
}

.apple-dynamic-island-wrapper.is-fixed {
  top: max(10px, env(safe-area-inset-top, 10px));
}

/* 灵动岛黑色外壳 - 核心物理弹簧形变容器 */
.apple-dynamic-island {
  position: relative;
  box-sizing: border-box;
  background-color: #000000;
  color: #ffffff;
  overflow: hidden;
  user-select: none;
  cursor: pointer;
  
  /* Apple 物理高光与重影阴影 */
  box-shadow:
    0 12px 32px -4px rgba(0, 0, 0, 0.45),
    0 4px 12px 0 rgba(0, 0, 0, 0.3),
    inset 0 1px 1.5px 0 rgba(255, 255, 255, 0.22),
    inset 0 -1px 1px 0 rgba(255, 255, 255, 0.05);

  /* iOS 经典拟真弹簧弹性过渡 */
  transition:
    width 0.45s cubic-bezier(0.16, 1, 0.3, 1),
    height 0.45s cubic-bezier(0.16, 1, 0.3, 1),
    border-radius 0.45s cubic-bezier(0.16, 1, 0.3, 1),
    box-shadow 0.3s ease;
}

.apple-dynamic-island.is-interactive:hover {
  box-shadow:
    0 16px 40px -4px rgba(0, 0, 0, 0.55),
    0 6px 16px 0 rgba(0, 0, 0, 0.35),
    inset 0 1px 2px 0 rgba(255, 255, 255, 0.32);
}

/* 尺寸 1: 紧凑药丸态 (Compact) */
.state-compact {
  width: min(200px, calc(100vw - 32px));
  height: 38px;
  border-radius: 9999px;
  padding: 0 14px;
}

/* 尺寸 2: 横向通知态 (Notification) */
.state-notification {
  width: min(360px, calc(100vw - 20px));
  height: 56px;
  border-radius: 28px;
  padding: 0 16px;
}

/* 尺寸 3: 深度沉浸展开态 (Expanded) */
.state-expanded {
  width: min(400px, calc(100vw - 20px));
  min-height: 228px;
  height: auto;
  border-radius: 34px;
  padding: 18px 20px;
}

/* 内容布局通用 */
.island-content {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  animation: islandFadeIn 0.3s cubic-bezier(0.16, 1, 0.3, 1);
}

@keyframes islandFadeIn {
  from { opacity: 0; transform: scale(0.96); }
  to { opacity: 1; transform: scale(1); }
}

/* ========== 紧凑态详细样式 ========== */
.island-compact {
  justify-content: space-between;
}

.compact-leading {
  display: flex;
  align-items: center;
  gap: 8px;
  overflow: hidden;
}

.status-pulse-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background-color: #38bdf8;
  box-shadow: 0 0 10px #38bdf8;
  animation: islandPulse 2s infinite ease-in-out;
  flex-shrink: 0;
}
.status-pulse-dot.success { background-color: #34d399; box-shadow: 0 0 10px #34d399; }
.status-pulse-dot.warning { background-color: #fbbf24; box-shadow: 0 0 10px #fbbf24; }
.status-pulse-dot.danger  { background-color: #f87171; box-shadow: 0 0 10px #f87171; }

@keyframes islandPulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.5; transform: scale(0.85); }
}

.compact-title {
  font-size: 13px;
  font-weight: 500;
  color: #f8fafc;
  letter-spacing: -0.01em;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.island-mini-icon {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
  stroke-width: 2.2px;
}
.island-mini-icon.warning { color: #f59e0b; }
.island-mini-icon.danger { color: #ef4444; }
.island-mini-icon.info { color: #38bdf8; }
.island-mini-icon.success { color: #34d399; }

.compact-trailing {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}

.compact-badge {
  font-size: 11px;
  font-weight: 600;
  color: #38bdf8;
  background: rgba(56, 189, 248, 0.15);
  padding: 2px 7px;
  border-radius: 9999px;
}

.compact-wave {
  display: flex;
  align-items: center;
  gap: 2.5px;
  height: 14px;
}
.wave-bar {
  width: 2.5px;
  height: 100%;
  background: #38bdf8;
  border-radius: 9999px;
  animation: soundWave 1.2s infinite ease-in-out;
}
.wave-bar:nth-child(1) { animation-delay: 0s; height: 8px; }
.wave-bar:nth-child(2) { animation-delay: 0.2s; height: 14px; }
.wave-bar:nth-child(3) { animation-delay: 0.4s; height: 10px; }

@keyframes soundWave {
  0%, 100% { transform: scaleY(0.4); }
  50% { transform: scaleY(1); }
}

/* ========== 通知态详细样式 ========== */
.island-notification {
  gap: 12px;
}

.notif-avatar {
  flex-shrink: 0;
}

.default-icon {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(56, 189, 248, 0.2);
  color: #38bdf8;
}
.default-icon.success { background: rgba(52, 211, 153, 0.2); color: #34d399; }
.default-icon.warning { background: rgba(251, 191, 36, 0.2); color: #fbbf24; }
.default-icon.danger  { background: rgba(248, 113, 113, 0.2); color: #f87171; }

.notif-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
}

.notif-title {
  font-size: 13.5px;
  font-weight: 600;
  color: #ffffff;
  line-height: 1.25;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.notif-desc {
  font-size: 11.5px;
  color: #94a3b8;
  line-height: 1.25;
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.notif-trailing {
  flex-shrink: 0;
}

.notif-tag {
  font-size: 10.5px;
  font-weight: 700;
  color: #38bdf8;
  background: rgba(56, 189, 248, 0.16);
  padding: 3px 8px;
  border-radius: 9999px;
  letter-spacing: 0.05em;
}

/* ========== 展开态详细样式 ========== */
.island-expanded {
  flex-direction: column;
  justify-content: space-between;
  align-items: stretch;
}

.expanded-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.app-icon {
  width: 36px;
  height: 36px;
  border-radius: 12px;
  background: linear-gradient(135deg, rgba(56, 189, 248, 0.25), rgba(14, 165, 233, 0.05));
  border: 1px solid rgba(56, 189, 248, 0.3);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #38bdf8;
  flex-shrink: 0;
}

.header-text {
  min-width: 0;
}

.header-title {
  font-size: 14px;
  font-weight: 600;
  color: #ffffff;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.header-subtitle {
  font-size: 11.5px;
  color: #94a3b8;
  margin-top: 1px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.close-button {
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.12);
  color: #94a3b8;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  cursor: pointer;
  flex-shrink: 0;
  transition: all 0.2s;
}
.close-button:hover {
  background: rgba(255, 255, 255, 0.25);
  color: #ffffff;
}

.expanded-body {
  margin: 12px 0 10px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.metric-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 14px;
  padding: 8px 10px;
}

.metric-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
}

.metric-label {
  font-size: 10.5px;
  color: #64748b;
  margin-bottom: 2px;
}

.metric-value {
  font-size: 12px;
  font-weight: 600;
}

.progress-wrap {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.progress-bar {
  width: 100%;
  height: 6px;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 9999px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #38bdf8, #60a5fa);
  border-radius: 9999px;
  transition: width 0.4s ease;
}

.progress-label {
  display: flex;
  justify-content: space-between;
  font-size: 10.5px;
  color: #94a3b8;
}

.expanded-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 4px;
}

.action-btn {
  font-size: 12px;
  font-weight: 500;
  padding: 6px 14px;
  border-radius: 9999px;
  border: none;
  cursor: pointer;
  transition: all 0.2s;
}

.action-btn.ghost {
  background: rgba(255, 255, 255, 0.08);
  color: #94a3b8;
}
.action-btn.ghost:hover {
  background: rgba(255, 255, 255, 0.16);
  color: #ffffff;
}

.action-btn.primary {
  background: #ffffff;
  color: #000000;
  font-weight: 600;
}
.action-btn.primary:hover {
  background: #f1f5f9;
  transform: scale(1.02);
}

/* ================= 移动端适配 (≤ 640px) ================= */
@media (max-width: 640px) {
  .state-compact {
    width: min(180px, calc(100vw - 24px));
    height: 36px;
    padding: 0 10px;
  }
  .compact-title {
    font-size: 12px;
  }
  .state-notification {
    width: calc(100vw - 20px);
    height: 52px;
    padding: 0 12px;
  }
  .notif-title {
    font-size: 13px;
  }
  .notif-desc {
    font-size: 11px;
  }
  .state-expanded {
    width: calc(100vw - 20px);
    padding: 14px 14px;
    border-radius: 26px;
  }
  .close-button {
    width: 32px;
    height: 32px;
  }
  .metric-row {
    padding: 6px 4px;
    gap: 4px;
  }
  .metric-label {
    font-size: 9.5px;
  }
  .metric-value {
    font-size: 11px;
  }
  .action-btn {
    padding: 7px 12px;
    font-size: 11.5px;
  }
}
</style>

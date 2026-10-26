<template>
  <div
    ref="dockRef"
    class="apple-dock-container"
    :class="[
      fixed ? 'is-fixed fixed bottom-4 left-1/2 -translate-x-1/2 z-50' : 'relative inline-flex',
      `direction-${direction}`
    ]"
    @mousemove="handleMouseMove"
    @mouseleave="handleMouseLeave"
    @touchstart.passive="handleTouchStart"
    @touchmove.passive="handleTouchMove"
    @touchend="handleTouchEnd"
  >
    <div class="apple-dock-glass-panel hide-scrollbar">
      <div
        v-for="(item, index) in items"
        :key="item.id || index"
        class="dock-item-wrapper"
        :class="{ 'has-separator': item.separator }"
      >
        <!-- 分隔线 -->
        <div v-if="item.separator" class="dock-separator" aria-hidden="true"></div>

        <!-- Dock 图标实体 -->
        <div
          v-else
          :ref="el => setItemRef(el, index)"
          class="dock-icon-box"
          :class="{
            'is-bouncing': bouncingIndex === index,
            'is-active': activeId === item.id
          }"
          :style="getItemStyle(index)"
          @click="handleItemClick(item, index)"
          @mouseenter="hoveredIndex = index"
          @mouseleave="onItemMouseLeave(index)"
        >
          <!-- Tooltip 悬停气泡 (移动端轻触短时显示) -->
          <transition name="dock-tip">
            <div
              v-if="hoveredIndex === index && item.label"
              class="dock-tooltip"
            >
              {{ item.label }}
            </div>
          </transition>

          <!-- 徽标 Badge (响应式微缩) -->
          <span v-if="item.badge" class="dock-badge">
            {{ item.badge }}
          </span>

          <!-- 图标插槽或默认图标 (严禁文字或字母替代) -->
          <div
            class="dock-icon-inner"
            :style="item.gradient ? { background: item.gradient, borderColor: 'rgba(255,255,255,0.35)' } : (item.bg ? { background: item.bg } : {})"
          >
            <slot :name="`icon-${item.id}`" :item="item">
              <AppleIcon
                v-if="item.icon && (typeof item.icon !== 'string' || (!item.icon.startsWith('http') && !item.icon.startsWith('/')))"
                :name="item.icon"
                class="dock-svg-icon"
                :size="iconSize ? Math.round(iconSize * 0.52) : 22"
                :style="{ color: item.color || (item.gradient ? '#ffffff' : 'inherit') }"
              />
              <img
                v-else-if="item.icon && typeof item.icon === 'string' && (item.icon.startsWith('http') || item.icon.startsWith('/'))"
                :src="item.icon"
                :alt="item.label"
                class="dock-img"
              />
              <!-- 纯矢量回退：彻底杜绝使用首字母或 Emoji 作为图标 -->
              <AppleIcon
                v-else
                name="appgrid"
                class="dock-svg-icon"
                :size="iconSize ? Math.round(iconSize * 0.52) : 22"
                :style="{ color: item.color || (item.gradient ? '#ffffff' : 'inherit') }"
              />
            </slot>
          </div>

          <!-- 底部运行中指示微点 (Active Dot) -->
          <span v-if="item.active || activeId === item.id" class="dock-active-dot" aria-hidden="true"></span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * AppleDock.vue — macOS 拟真悬浮 Dock 导航坞 (全端触控与桌面适配)
 * 灵感源: 21st.dev Magic UI (dock.tsx) + macOS Sequoia HIG
 *
 * 特性:
 * - 纯 Vue 3 实现，零沉重第三方动画库依赖
 * - 移动端自适应: 增加 Touch 手势滑动放大，自动限制宽度并横向自流转，适配 Safe Area Inset
 * - 物理仿真: 基于鼠标/触控距离的高斯抛物线鱼眼放大 (Parabolic Magnification)
 * - macOS 拟真液态毛玻璃托盘 (Liquid Frosted Glass, 28px Blur + 190% Saturation)
 * - 拟真 macOS 应用程序点击弹跳物理动效 (Double Bounce on click)
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import AppleIcon from './AppleIcon.vue'

const props = defineProps({
  /** Dock 项目列表: [{ id, label, icon, badge, active, separator, path, onClick }] */
  items: {
    type: Array,
    required: true
  },
  /** 是否固定悬浮于视口底部中央 */
  fixed: {
    type: Boolean,
    default: true
  },
  /** 基础图标尺寸 (px) */
  iconSize: {
    type: Number,
    default: 44
  },
  /** 鼠标聚焦时的最大放大尺寸 (px) */
  iconMagnification: {
    type: Number,
    default: 62
  },
  /** 影响范围距离 (px) */
  distance: {
    type: Number,
    default: 130
  },
  /** 对齐方向: 'bottom' | 'middle' | 'top' */
  direction: {
    type: String,
    default: 'bottom'
  },
  /** 当前高亮的项目 ID */
  activeId: {
    type: [String, Number],
    default: ''
  }
})

const emit = defineEmits(['select'])

const dockRef = ref(null)
const itemElements = ref([])
const mouseX = ref(Infinity)
const hoveredIndex = ref(-1)
const bouncingIndex = ref(-1)

function setItemRef(el, index) {
  if (el) itemElements.value[index] = el
}

function handleMouseMove(e) {
  mouseX.value = e.pageX
}

function handleMouseLeave() {
  mouseX.value = Infinity
  hoveredIndex.value = -1
}

function onItemMouseLeave(index) {
  if (hoveredIndex.value === index) {
    hoveredIndex.value = -1
  }
}

// 触控手势适配 (移动端滑动缩放)
function handleTouchStart(e) {
  if (e.touches && e.touches[0]) {
    mouseX.value = e.touches[0].pageX
  }
}

function handleTouchMove(e) {
  if (e.touches && e.touches[0]) {
    mouseX.value = e.touches[0].pageX
  }
}

function handleTouchEnd() {
  setTimeout(() => {
    mouseX.value = Infinity
    hoveredIndex.value = -1
  }, 400)
}

// 计算每个图标的动态缩放尺寸
function getItemStyle(index) {
  const el = itemElements.value[index]
  if (!el || mouseX.value === Infinity) {
    return {
      width: `${props.iconSize}px`,
      height: `${props.iconSize}px`
    }
  }

  const rect = el.getBoundingClientRect()
  const iconCenterX = rect.left + rect.width / 2 + window.scrollX
  const dist = Math.abs(mouseX.value - iconCenterX)

  if (dist > props.distance) {
    return {
      width: `${props.iconSize}px`,
      height: `${props.iconSize}px`
    }
  }

  // 抛物线高斯平滑插值
  const factor = Math.cos((dist / props.distance) * (Math.PI / 2))
  const smoothFactor = Math.pow(factor, 2)
  const targetSize = props.iconSize + (props.iconMagnification - props.iconSize) * smoothFactor

  return {
    width: `${targetSize.toFixed(1)}px`,
    height: `${targetSize.toFixed(1)}px`
  }
}

function handleItemClick(item, index) {
  // 触发 macOS 经典跳动
  bouncingIndex.value = index
  hoveredIndex.value = index
  setTimeout(() => {
    if (bouncingIndex.value === index) {
      bouncingIndex.value = -1
    }
    hoveredIndex.value = -1
  }, 900)

  emit('select', item)
  if (typeof item.onClick === 'function') {
    item.onClick(item)
  }
}
</script>

<style scoped>
.apple-dock-container {
  display: flex;
  user-select: none;
}

.apple-dock-container.is-fixed {
  bottom: max(12px, env(safe-area-inset-bottom, 12px));
  max-width: calc(100vw - 16px);
}

/* 拟真 macOS 液态毛玻璃底座 */
.apple-dock-glass-panel {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  padding: 8px 12px 10px;
  border-radius: 24px;
  max-width: 100%;
  
  /* Apple 液态毛玻璃: 超强模糊与饱和度提亮 */
  background: rgba(255, 255, 255, 0.45);
  backdrop-filter: blur(28px) saturate(190%);
  -webkit-backdrop-filter: blur(28px) saturate(190%);
  
  /* 双层高光边框与柔和重影 */
  border: 1px solid rgba(255, 255, 255, 0.55);
  box-shadow:
    0 16px 36px -6px rgba(0, 0, 0, 0.2),
    0 6px 14px 0 rgba(0, 0, 0, 0.08),
    inset 0 1px 1px 0 rgba(255, 255, 255, 0.7);

  transition: all 0.25s ease;
}

.hide-scrollbar::-webkit-scrollbar {
  display: none;
}
.hide-scrollbar {
  -ms-overflow-style: none;
  scrollbar-width: none;
}

/* 深色模式下的 Dock 托盘 */
:global(.dark) .apple-dock-glass-panel,
:global([data-theme="dark"]) .apple-dock-glass-panel {
  background: rgba(18, 24, 38, 0.65);
  border: 1px solid rgba(255, 255, 255, 0.12);
  box-shadow:
    0 20px 45px -8px rgba(0, 0, 0, 0.55),
    0 8px 16px 0 rgba(0, 0, 0, 0.3),
    inset 0 1px 1px 0 rgba(255, 255, 255, 0.18);
}

.dock-item-wrapper {
  position: relative;
  display: flex;
  align-items: flex-end;
  flex-shrink: 0;
}

/* 分隔竖线 */
.dock-separator {
  width: 1px;
  height: 32px;
  margin: 0 4px 6px;
  background: rgba(0, 0, 0, 0.15);
}
:global(.dark) .dock-separator {
  background: rgba(255, 255, 255, 0.15);
}

/* Dock 图标方块容器 */
.dock-icon-box {
  position: relative;
  border-radius: 14px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transform-origin: bottom center;
  
  /* 平滑阻尼回弹 */
  transition:
    width 0.18s cubic-bezier(0.16, 1, 0.3, 1),
    height 0.18s cubic-bezier(0.16, 1, 0.3, 1),
    transform 0.18s cubic-bezier(0.16, 1, 0.3, 1);
}

.dock-icon-inner {
  width: 100%;
  height: 100%;
  border-radius: 14px;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.82), rgba(240, 242, 245, 0.72));
  border: 1px solid rgba(255, 255, 255, 0.65);
  box-shadow: 0 4px 10px rgba(0, 0, 0, 0.08);
  transition: transform 0.2s cubic-bezier(0.16, 1, 0.3, 1), box-shadow 0.2s;
}

:global(.dark) .dock-icon-inner {
  background: linear-gradient(135deg, rgba(30, 41, 59, 0.85), rgba(15, 23, 42, 0.95));
  border: 1px solid rgba(255, 255, 255, 0.12);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
}

.dock-icon-box:hover .dock-icon-inner {
  box-shadow: 0 8px 20px rgba(0, 0, 0, 0.15);
}

.dock-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.dock-svg-icon {
  width: 52%;
  height: 52%;
  stroke-width: 2.1px;
  filter: drop-shadow(0 1px 2px rgba(0, 0, 0, 0.16));
  transition: transform 0.2s cubic-bezier(0.16, 1, 0.3, 1);
  display: block;
}

.dock-icon-box:hover .dock-svg-icon {
  transform: scale(1.12);
}

/* 拟真 macOS 应用程序点击弹跳物理动效 */
.dock-icon-box.is-bouncing {
  animation: macosDockBounce 0.85s cubic-bezier(0.28, 0.84, 0.42, 1);
}

@keyframes macosDockBounce {
  0% { transform: translateY(0); }
  25% { transform: translateY(-22px) scaleY(1.05); }
  45% { transform: translateY(0) scaleY(0.95); }
  65% { transform: translateY(-11px) scaleY(1.02); }
  85% { transform: translateY(0) scaleY(0.98); }
  100% { transform: translateY(0); }
}

/* 拟真 Apple Tooltip 黑胶囊悬浮气泡 */
.dock-tooltip {
  position: absolute;
  top: -38px;
  left: 50%;
  transform: translateX(-50%);
  background: rgba(15, 23, 42, 0.9);
  color: #ffffff;
  font-size: 11px;
  font-weight: 500;
  padding: 4px 10px;
  border-radius: 8px;
  white-space: nowrap;
  pointer-events: none;
  backdrop-filter: blur(12px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.25);
  border: 1px solid rgba(255, 255, 255, 0.15);
  z-index: 60;
}

.dock-tooltip::after {
  content: '';
  position: absolute;
  bottom: -4px;
  left: 50%;
  transform: translateX(-50%);
  border-width: 4px 4px 0 4px;
  border-style: solid;
  border-color: rgba(15, 23, 42, 0.9) transparent transparent transparent;
}

.dock-tip-enter-active,
.dock-tip-leave-active {
  transition: opacity 0.18s ease, transform 0.18s cubic-bezier(0.16, 1, 0.3, 1);
}
.dock-tip-enter-from,
.dock-tip-leave-to {
  opacity: 0;
  transform: translate(-50%, 6px);
}

/* 活跃指示微灯 */
.dock-active-dot {
  position: absolute;
  bottom: -6px;
  left: 50%;
  transform: translateX(-50%);
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background-color: rgba(30, 41, 59, 0.85);
}
:global(.dark) .dock-active-dot {
  background-color: rgba(248, 250, 252, 0.85);
  box-shadow: 0 0 6px rgba(255, 255, 255, 0.6);
}

/* 红色通知角标 */
.dock-badge {
  position: absolute;
  top: -4px;
  right: -4px;
  min-width: 17px;
  height: 17px;
  border-radius: 9999px;
  background-color: #ef4444;
  color: #ffffff;
  font-size: 9.5px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0 4px;
  border: 2px solid #ffffff;
  box-shadow: 0 2px 6px rgba(239, 68, 68, 0.4);
  z-index: 5;
}
:global(.dark) .dock-badge {
  border-color: #0f172a;
}

/* ================= 移动端适配 (≤ 640px) ================= */
@media (max-width: 640px) {
  .apple-dock-container.is-fixed {
    bottom: max(8px, env(safe-area-inset-bottom, 8px));
    max-width: calc(100vw - 12px);
  }
  .apple-dock-glass-panel {
    gap: 6px;
    padding: 6px 8px 8px;
    border-radius: 20px;
    overflow-x: auto;
    -webkit-overflow-scrolling: touch;
  }
  .dock-separator {
    height: 22px;
    margin: 0 2px 4px;
  }
  .dock-default-letter {
    font-size: 15px;
  }
  .dock-tooltip {
    top: -30px;
    font-size: 10px;
    padding: 3px 8px;
  }
  .dock-badge {
    min-width: 15px;
    height: 15px;
    font-size: 9px;
    top: -3px;
    right: -3px;
  }
}
</style>

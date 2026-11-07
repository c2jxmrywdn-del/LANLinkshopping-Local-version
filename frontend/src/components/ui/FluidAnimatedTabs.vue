<template>
  <div
    ref="containerRef"
    class="fluid-animated-tabs-root"
    :class="[customClass, variantClass]"
    role="tablist"
  >
    <!-- 21st.dev 流体平滑滑动的背景高光指示器 (Sliding Pill Indicator) -->
    <div
      class="sliding-pill-indicator"
      :style="{
        transform: `translate3d(${pillLeft}px, 0, 0)`,
        width: `${pillWidth}px`,
        opacity: isInitialized ? 1 : 0
      }"
      aria-hidden="true"
    />

    <!-- Tab 选项按钮 -->
    <button
      v-for="(tab, idx) in items"
      :key="tab.value"
      :ref="el => setTabRef(el, idx)"
      type="button"
      role="tab"
      :aria-selected="modelValue === tab.value"
      :disabled="tab.disabled"
      class="fluid-tab-item"
      :class="{
        'is-active': modelValue === tab.value,
        'is-disabled': tab.disabled
      }"
      @click="onSelectTab(tab.value, idx)"
    >
      <component
        :is="tab.icon"
        v-if="tab.icon"
        :size="15"
        class="tab-icon"
        aria-hidden="true"
      />
      <span class="tab-label">{{ tab.label }}</span>
      <span v-if="tab.badge !== undefined && tab.badge !== null" class="tab-badge">
        {{ tab.badge }}
      </span>
    </button>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const props = defineProps({
  modelValue: {
    type: [String, Number],
    required: true
  },
  items: {
    type: Array,
    required: true,
    // [{ label: '7 天', value: 7, icon?: Component, badge?: string|number, disabled?: boolean }]
    default: () => []
  },
  /** 'pill' (默认胶囊滑块) | 'underline' (底部滑行动画线) | 'segment' (分段立体卡) */
  variant: {
    type: String,
    default: 'pill'
  },
  customClass: {
    type: String,
    default: ''
  }
})

const emit = defineEmits(['update:modelValue', 'change'])

const containerRef = ref(null)
const tabRefs = ref([])
const pillLeft = ref(0)
const pillWidth = ref(0)
const isInitialized = ref(false)

const variantClass = computed(() => `variant-${props.variant}`)

function setTabRef(el, idx) {
  if (el) tabRefs.value[idx] = el
}

function updatePillPosition() {
  if (!containerRef.value) return
  const activeIdx = props.items.findIndex(it => it.value === props.modelValue)
  if (activeIdx < 0 || !tabRefs.value[activeIdx]) {
    pillWidth.value = 0
    return
  }

  const containerRect = containerRef.value.getBoundingClientRect()
  const activeRect = tabRefs.value[activeIdx].getBoundingClientRect()

  pillLeft.value = Math.max(0, activeRect.left - containerRect.left)
  pillWidth.value = activeRect.width
  if (!isInitialized.value) {
    nextTick(() => { isInitialized.value = true })
  }
}

function onSelectTab(val, idx) {
  if (val === props.modelValue) return
  emit('update:modelValue', val)
  emit('change', val)
}

watch(
  () => props.modelValue,
  () => {
    nextTick(updatePillPosition)
  }
)

watch(
  () => props.items,
  () => {
    tabRefs.value = []
    nextTick(updatePillPosition)
  },
  { deep: true }
)

let resizeObserver = null

onMounted(() => {
  nextTick(() => {
    updatePillPosition()
    if (typeof ResizeObserver !== 'undefined' && containerRef.value) {
      resizeObserver = new ResizeObserver(() => {
        updatePillPosition()
      })
      resizeObserver.observe(containerRef.value)
    }
  })
})

onBeforeUnmount(() => {
  if (resizeObserver) {
    resizeObserver.disconnect()
    resizeObserver = null
  }
})
</script>

<style scoped>
.fluid-animated-tabs-root {
  position: relative;
  display: inline-flex;
  align-items: center;
  padding: 4px;
  background: rgba(241, 245, 249, 0.85);
  border: 1px solid rgba(226, 232, 240, 0.9);
  border-radius: 9999px;
  user-select: none;
  -webkit-tap-highlight-color: transparent;
  box-sizing: border-box;
  max-width: 100%;
  overflow-x: auto;
  scrollbar-width: none;
}
.fluid-animated-tabs-root::-webkit-scrollbar {
  display: none;
}

/* 21st.dev 标杆滑块物理动效 */
.sliding-pill-indicator {
  position: absolute;
  top: 4px;
  bottom: 4px;
  left: 0;
  border-radius: 9999px;
  background: #ffffff;
  box-shadow: 0 2px 8px -1px rgba(0, 0, 0, 0.08), 0 1px 3px rgba(0, 0, 0, 0.04);
  pointer-events: none;
  will-change: transform, width;
  transition:
    transform 280ms cubic-bezier(0.25, 1, 0.5, 1),
    width 280ms cubic-bezier(0.25, 1, 0.5, 1),
    opacity 160ms ease;
}

.variant-segment {
  border-radius: 10px;
  background: #f1f5f9;
}
.variant-segment .sliding-pill-indicator {
  border-radius: 8px;
  top: 3px;
  bottom: 3px;
}

.variant-underline {
  border-radius: 0;
  background: transparent;
  border: none;
  border-bottom: 2px solid #e2e8f0;
  padding: 0;
}
.variant-underline .sliding-pill-indicator {
  top: auto;
  bottom: -2px;
  height: 2.5px;
  border-radius: 2px;
  background: #0284c7;
  box-shadow: 0 0 8px rgba(2, 132, 199, 0.4);
}

.fluid-tab-item {
  position: relative;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 6px 16px;
  border: none;
  background: transparent;
  color: #64748b;
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
  cursor: pointer;
  border-radius: 9999px;
  outline: none;
  transition: color 200ms ease;
  min-height: 36px;
}

/* 触摸热区防误触合规 */
.fluid-tab-item::after {
  content: '';
  position: absolute;
  inset: -4px;
}

.fluid-tab-item:hover:not(.is-disabled) {
  color: #0f172a;
}

.fluid-tab-item.is-active {
  color: #0284c7;
  font-weight: 600;
}

.variant-underline .fluid-tab-item.is-active {
  color: #0284c7;
}

.fluid-tab-item.is-disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.tab-icon {
  flex-shrink: 0;
}

.tab-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 1px 6px;
  font-size: 11px;
  font-weight: 600;
  line-height: 1.2;
  border-radius: 9999px;
  background: rgba(2, 132, 199, 0.1);
  color: #0284c7;
}

.fluid-tab-item.is-active .tab-badge {
  background: #0284c7;
  color: #ffffff;
}

/* 深色模式 / 地图 HUD 适配 */
:global(.dark-gis-theme) .fluid-animated-tabs-root {
  background: rgba(15, 23, 42, 0.85);
  border-color: rgba(51, 65, 85, 0.8);
}
:global(.dark-gis-theme) .sliding-pill-indicator {
  background: #1e293b;
  box-shadow: 0 0 12px rgba(56, 189, 248, 0.25);
  border: 1px solid rgba(56, 189, 248, 0.35);
}
:global(.dark-gis-theme) .fluid-tab-item {
  color: #94a3b8;
}
:global(.dark-gis-theme) .fluid-tab-item:hover {
  color: #f8fafc;
}
:global(.dark-gis-theme) .fluid-tab-item.is-active {
  color: #38bdf8;
}
</style>

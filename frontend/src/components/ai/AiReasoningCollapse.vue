<template>
  <div v-if="reasoning || isThinking" class="ai-reasoning-collapse" :class="{ 'is-thinking': isThinking, 'is-open': isOpen }"
    :role="isThinking ? 'status' : undefined" :aria-live="isThinking ? 'polite' : undefined">
    <!-- 折叠栏：思考中只留当前步骤、省略号和已用秒数；结束后收成「已思考」 -->
    <button
      type="button"
      class="reasoning-header"
      :aria-expanded="isOpen"
      :title="isOpen ? '点击收起思考过程' : '点击展开思考过程'"
      @click="toggle"
    >
      <span class="header-title">
        <template v-if="isThinking">
          <span class="thinking-text">{{ stepLabel }}</span>
          <span class="thinking-dots" aria-hidden="true"><span></span><span></span><span></span></span>
          <span class="meta-tag">{{ shownSeconds.toFixed(1) }}s</span>
        </template>
        <template v-else>
          <span class="done-text">已思考</span>
          <span v-if="durationSec > 0" class="meta-tag">{{ durationSec.toFixed(1) }}s</span>
        </template>
      </span>

      <span class="chevron" :class="{ 'chevron-up': isOpen }" aria-hidden="true">
        <svg viewBox="0 0 24 24" width="14" height="14" stroke="currentColor" stroke-width="2" fill="none">
          <polyline points="6 9 12 15 18 9"></polyline>
        </svg>
      </span>
    </button>

    <!-- 思考内容容器（支持平滑手风琴展开收缩） -->
    <transition name="collapse">
      <div v-show="isOpen && reasoning" class="reasoning-body">
        <div class="reasoning-content">
          <pre class="reasoning-pre">{{ reasoning }}</pre>
          <span v-if="isThinking" class="cursor-blink" aria-hidden="true"></span>
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'

const props = defineProps({
  reasoning: {
    type: String,
    default: ''
  },
  isThinking: {
    type: Boolean,
    default: false
  },
  durationSec: {
    type: Number,
    default: 0
  }
})

// 客服思考步骤按商城业务来，不套用与订单无关的工业参数文案
const STEPS = ['查阅商城知识库', '核对订单与商品规则', '整理可回答范围']

// 正在推导时默认展开，推导完毕自动折叠
const isOpen = ref(props.isThinking)
const stepIndex = ref(0)
const elapsed = ref(0)
let stepTimer = null
let tickTimer = null

function stopTimers() {
  if (stepTimer) clearInterval(stepTimer)
  if (tickTimer) clearInterval(tickTimer)
  stepTimer = null
  tickTimer = null
}

watch(
  () => props.isThinking,
  (thinking, prev) => {
    stopTimers()
    if (thinking) {
      isOpen.value = true
      elapsed.value = 0
      stepIndex.value = 0
      const started = Date.now()
      tickTimer = setInterval(() => {
        elapsed.value = Math.max(0.1, (Date.now() - started) / 1000)
      }, 100)
      stepTimer = setInterval(() => {
        stepIndex.value = (stepIndex.value + 1) % STEPS.length
      }, 1600)
    } else if (prev && !thinking) {
      // 思考刚结束，自动平滑收起
      isOpen.value = false
    }
  },
  { immediate: true }
)

onBeforeUnmount(stopTimers)

function toggle() {
  isOpen.value = !isOpen.value
}

const stepLabel = computed(() => {
  if ((props.reasoning || '').trim()) return '整理回答'
  return STEPS[stepIndex.value]
})

const shownSeconds = computed(() => elapsed.value || 0.1)
</script>

<style scoped>
.ai-reasoning-collapse {
  margin: 0 0 8px;
  border-radius: 10px;
  background: transparent;
  border: 0;
  overflow: hidden;
}

.ai-reasoning-collapse.is-open:not(.is-thinking) {
  background: #fafaf9;
  border: 1px solid #e7e5e4;
}

.reasoning-header {
  display: flex;
  align-items: center;
  width: 100%;
  padding: 0;
  background: transparent;
  border: none;
  cursor: pointer;
  font-size: 12px;
  color: #78716c;
  text-align: left;
  user-select: none;
}

.is-open:not(.is-thinking) .reasoning-header {
  padding: 6px 10px;
}

.reasoning-header:hover { color: #1c1917; }

.header-title {
  flex: 1;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.thinking-text {
  font-weight: 500;
  color: #57534e;
}

.thinking-dots { display: inline-flex; align-items: center; gap: 3px; }
.thinking-dots span {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: #F59E0B;
  animation: dot-fade 1.2s infinite;
}
.thinking-dots span:nth-child(2) { animation-delay: .15s; }
.thinking-dots span:nth-child(3) { animation-delay: .3s; }

@keyframes dot-fade {
  0%, 100% { opacity: .25; }
  50% { opacity: 1; }
}

.done-text {
  font-weight: 600;
  color: #78716c;
}

.meta-tag {
  font-variant-numeric: tabular-nums;
  font-size: 11px;
  color: #a8a29e;
}

.chevron {
  display: inline-flex;
  align-items: center;
  color: #a8a29e;
  transition: transform .2s ease;
}

.chevron.chevron-up { transform: rotate(180deg); }

.reasoning-body {
  border-top: 1px solid #e7e5e4;
  padding: 8px 10px 10px;
}

.reasoning-content { position: relative; }

.reasoning-pre {
  margin: 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11.5px;
  line-height: 1.55;
  color: #57534e;
  white-space: pre-wrap;
  word-break: break-word;
}

.cursor-blink {
  display: inline-block;
  width: 6px;
  height: 1px;
  margin-left: 2px;
  background: #F59E0B;
  vertical-align: middle;
  animation: cursor-blink .8s infinite;
}

@keyframes cursor-blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

.collapse-enter-active,
.collapse-leave-active {
  transition: opacity .22s ease, max-height .22s ease;
  max-height: 400px;
  opacity: 1;
  overflow: hidden;
}

.collapse-enter-from,
.collapse-leave-to {
  max-height: 0;
  opacity: 0;
}

@media (prefers-reduced-motion: reduce) {
  .thinking-dots span,
  .cursor-blink,
  .chevron { animation: none; transition: none; }
}

:global([data-theme='dark']) .thinking-text,
:global([data-theme='dark']) .done-text { color: #d6d3d1; }
:global([data-theme='dark']) .ai-reasoning-collapse.is-open:not(.is-thinking) { background: #16202e; border-color: #31405a; }
:global([data-theme='dark']) .reasoning-pre { color: #c8d6e8; }
:global([data-theme='dark']) .reasoning-body { border-top-color: #31405a; }
</style>

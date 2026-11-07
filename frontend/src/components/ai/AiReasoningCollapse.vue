<template>
  <div v-if="reasoning || isThinking" class="ai-reasoning-collapse" :class="{ 'is-thinking': isThinking, 'is-open': isOpen }">
    <!-- 折叠栏头部 / 胶囊触发器 -->
    <button
      type="button"
      class="reasoning-header"
      :aria-expanded="isOpen"
      :title="isOpen ? '点击收起思考过程' : '点击展开思考过程'"
      @click="toggle"
    >
      <span class="header-icon-box">
        <Brain v-if="isThinking" :size="16" class="text-purple-500 animate-pulse" aria-hidden="true" />
        <Sparkles v-else :size="15" class="text-amber-500" aria-hidden="true" />
      </span>

      <span class="header-title">
        <template v-if="isThinking">
          <span class="thinking-text">正在深度推导工业品参数与业务规则…</span>
          <span class="thinking-dots"><span>.</span><span>.</span><span>.</span></span>
        </template>
        <template v-else>
          <span class="done-text">已深度思考</span>
          <span v-if="durationSec > 0" class="meta-tag">耗时 {{ durationSec.toFixed(1) }}s</span>
          <span class="meta-chars">共 {{ charCount }} 字逻辑</span>
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
      <div v-show="isOpen" class="reasoning-body">
        <div class="reasoning-content">
          <pre class="reasoning-pre">{{ reasoning }}</pre>
          <span v-if="isThinking" class="cursor-blink" aria-hidden="true">▌</span>
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { Brain, Sparkles } from 'lucide-vue-next'

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

// 正在推导时默认展开，推导完毕自动折叠
const isOpen = ref(props.isThinking)

watch(
  () => props.isThinking,
  (thinking, prev) => {
    if (thinking) {
      isOpen.value = true
    } else if (prev && !thinking) {
      // 思考刚结束，自动平滑收起
      isOpen.value = false
    }
  }
)

function toggle() {
  isOpen.value = !isOpen.value
}

const charCount = computed(() => {
  return (props.reasoning || '').trim().length
})
</script>

<style scoped>
.ai-reasoning-collapse {
  margin: 6px 0 10px 0;
  border-radius: 10px;
  background: rgba(241, 245, 249, 0.65);
  border: 1px solid rgba(226, 232, 240, 0.85);
  overflow: hidden;
  transition: all 0.25s ease;
}

.ai-reasoning-collapse.is-thinking {
  background: rgba(238, 242, 255, 0.6);
  border-color: rgba(199, 210, 254, 0.7);
  box-shadow: 0 0 10px rgba(99, 102, 241, 0.08);
}

.reasoning-header {
  display: flex;
  align-items: center;
  width: 100%;
  padding: 6px 12px;
  background: transparent;
  border: none;
  cursor: pointer;
  font-size: 12px;
  color: #475569;
  text-align: left;
  user-select: none;
  transition: background 0.15s ease;
}

.reasoning-header:hover {
  background: rgba(241, 245, 249, 0.95);
  color: #1e293b;
}

.header-icon-box {
  margin-right: 6px;
  display: flex;
  align-items: center;
}

.pulse-brain {
  display: inline-block;
  animation: pulse-brain 1.2s infinite ease-in-out;
}

@keyframes pulse-brain {
  0%, 100% { transform: scale(1); filter: drop-shadow(0 0 1px rgba(99, 102, 241, 0.3)); }
  50% { transform: scale(1.2); filter: drop-shadow(0 0 5px rgba(99, 102, 241, 0.6)); }
}

.header-title {
  flex: 1;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.thinking-text {
  font-weight: 500;
  color: #4f46e5;
}

.thinking-dots span {
  animation: dot-fade 1.4s infinite;
  opacity: 0;
}
.thinking-dots span:nth-child(1) { animation-delay: 0s; }
.thinking-dots span:nth-child(2) { animation-delay: 0.2s; }
.thinking-dots span:nth-child(3) { animation-delay: 0.4s; }

@keyframes dot-fade {
  0% { opacity: 0; }
  50% { opacity: 1; }
  100% { opacity: 0; }
}

.done-text {
  font-weight: 600;
  color: #64748b;
}

.meta-tag {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 4px;
  background: rgba(148, 163, 184, 0.15);
  font-size: 10px;
  font-family: ui-monospace, SFMono-Regular, monospace;
  color: #475569;
}

.meta-chars {
  font-size: 11px;
  color: #94a3b8;
}

.chevron {
  display: inline-flex;
  align-items: center;
  color: #94a3b8;
  transition: transform 0.2s ease;
}

.chevron.chevron-up {
  transform: rotate(180deg);
}

.reasoning-body {
  border-top: 1px dashed rgba(203, 213, 225, 0.6);
  padding: 8px 12px 10px 12px;
  background: rgba(248, 250, 252, 0.8);
}

.reasoning-content {
  position: relative;
}

.reasoning-pre {
  margin: 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 11.5px;
  line-height: 1.55;
  color: #475569;
  white-space: pre-wrap;
  word-break: break-word;
}

.cursor-blink {
  display: inline-block;
  margin-left: 2px;
  font-size: 12px;
  color: #6366f1;
  animation: cursor-blink 0.8s infinite;
}

@keyframes cursor-blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

/* 折叠展开动画 */
.collapse-enter-active,
.collapse-leave-active {
  transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1);
  max-height: 400px;
  opacity: 1;
  overflow: hidden;
}

.collapse-enter-from,
.collapse-leave-to {
  max-height: 0;
  opacity: 0;
  padding-top: 0;
  padding-bottom: 0;
}
</style>

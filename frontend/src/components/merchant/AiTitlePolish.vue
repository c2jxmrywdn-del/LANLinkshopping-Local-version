<template>
  <div class="ai-polish">
    <div class="ai-polish-bar">
      <a-button size="small" :loading="state.phase === STATE.LOADING" :disabled="!canClick"
                @click="polish(false)">
        <template #icon><sparkles :size="13" aria-hidden="true" /></template>
        {{ state.phase === STATE.LOADING ? '润色中…' : 'AI 润色' }}
      </a-button>
    </div>

    <!-- 候选面板（纯前端态，不持久化；1-3 张单列卡，服务端契约 [1,3]） -->
    <div v-if="state.phase === STATE.CANDIDATES && state.candidates.length" class="ai-polish-panel">
      <div class="ai-polish-head">AI 候选标题</div>
      <div v-for="(c, i) in state.candidates" :key="i" class="ai-polish-card">
        <span class="ai-polish-text">{{ c }}</span>
        <a-button type="primary" size="small" @click="apply(c)">应用</a-button>
      </div>
      <div v-if="resubmitHint" class="ai-polish-hint">
        应用新标题并保存后将重新送审，复审通过前买家与外部 AI 检索均看不到该商品。
      </div>
      <div class="ai-polish-actions">
        <a-button size="small" :disabled="busy" @click="polish(true)">重新生成</a-button>
        <a-button size="small" @click="discard">放弃</a-button>
      </div>
    </div>

    <!-- 失败条（silent:true 时全局 toast 被抑制，此处是失败的唯一展示；429/502/503/504 拥塞
         分支由 request.js 无条件轻提示，此处不再重复同一文案）。
         cooldown 剩余秒数为纯 ASCII 数字节点，不进 DOM 替换引擎（§7）。 -->
    <div v-if="state.error" class="ai-polish-error" role="alert">
      {{ state.error }}<span v-if="state.phase === STATE.COOLDOWN">&nbsp;{{ state.remaining }}s</span>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, ref, shallowRef } from 'vue'
import { message } from 'ant-design-vue'
import { Sparkles } from 'lucide-vue-next'
import { productApi } from '../../api'
import {
  STATE, initialPolishState, canPolish,
  onPolishStart, onPolishSuccess, onPolishFailure, onCooldownTick, onReset,
  distinctCandidates
} from '../../utils/aiPolish'

/**
 * AI 标题润色按钮 + 候选面板（out/ai-polish-design.md §2.1-2.4）。
 * 点选「应用」emit('apply', text) 由父级回填表单标题，走既有校验/发布流程，无校验绕过；
 * 请求带 silent:true（api/index.js），失败只在面板失败条展示；全失败/配额/熔断绝不虚构候选。
 */
const props = defineProps({
  title: { type: String, default: '' },
  indId: { type: Number, default: null },
  catId: { type: Number, default: null },
  brand: { type: String, default: '' },
  submitting: { type: Boolean, default: false },
  /** 编辑页场景：候选面板追加「应用后重新送审」灰字提示（§2.4） */
  resubmitHint: { type: Boolean, default: false }
})
const emit = defineEmits(['apply', 'pending'])

const state = shallowRef(initialPolishState())
const timer = ref(null)

const busy = computed(() => state.value.phase === STATE.LOADING || state.value.phase === STATE.COOLDOWN)
const canClick = computed(() => canPolish({
  title: props.title, phase: state.value.phase, submitting: props.submitting
}))

function patch(next) {
  state.value = next
  syncCooldownTimer(next)
}

/** 冷却倒计时：秒级 setInterval，仅冷却期运行（组件卸载/重置即清） */
function syncCooldownTimer(s) {
  if (s.phase === STATE.COOLDOWN && !timer.value) {
    timer.value = setInterval(() => patch(onCooldownTick(state.value)), 1000)
  } else if (s.phase !== STATE.COOLDOWN && timer.value) {
    clearInterval(timer.value)
    timer.value = null
  }
}

async function polish(regenerate) {
  if (!canClick.value && !regenerate) return
  if (regenerate && busy.value) return
  patch(onPolishStart(state.value))
  emit('pending', true)
  try {
    const data = await productApi.polishTitle({
      title: (props.title || '').trim(),
      indId: props.indId, catId: props.catId,
      brand: (props.brand || '').trim(),
      regenerate: !!regenerate
    })
    const { candidates, allFiltered } = distinctCandidates(data?.candidates, props.title)
    if (allFiltered) {
      // 过滤后为空：服务端契约 [1,3] 全部与当前标题逐字相同 → 提示改标题，不进冷却
      patch({ ...onReset(state.value), error: '候选与当前标题相同，请修改后再试' })
    } else {
      patch(onPolishSuccess(state.value, candidates))
    }
  } catch (e) {
    const msg = (e && e.message) || 'AI 润色暂不可用，请稍后再试'
    patch(onPolishFailure(state.value, msg))
  } finally {
    emit('pending', false)
  }
}

function apply(text) {
  emit('apply', text)
  message.success('已应用 AI 标题')
  patch(onReset(state.value))
}

function discard() {
  patch(onReset(state.value))
}

/** resetForm / Modal 重开路径重置（§2.3）：父级经 ref 调用 */
function reset() {
  patch(onReset(state.value))
}

onBeforeUnmount(() => {
  if (timer.value) { clearInterval(timer.value); timer.value = null }
})

defineExpose({ reset })
</script>

<style scoped>
/* 全部引用 theme.css 既有 --ll-* tokens，零硬编码色值；深色模式随 token 翻转自动生效 */
.ai-polish { width: 100%; }
.ai-polish-bar { display: flex; align-items: center; gap: 8px; margin-top: 6px; }
.ai-polish-bar .ant-btn { display: inline-flex; align-items: center; gap: 4px;
  color: var(--ll-primary); border-color: var(--ll-primary); }
.ai-polish-panel {
  margin-top: 8px;
  padding: 10px;
  background: var(--ll-card-soft);
  border: 1px solid var(--ll-border);
  border-radius: var(--ll-radius-md);
  box-shadow: var(--ll-shadow-xs);
}
.ai-polish-head { font-size: var(--ll-text-xs); color: var(--ll-muted); margin-bottom: 6px; }
.ai-polish-card {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  padding: 8px 10px; margin-bottom: 6px;
  background: var(--ll-card);
  border: 1px solid var(--ll-border);
  border-radius: var(--ll-radius-md);
  transition: border-color var(--ll-dur-fast);
}
.ai-polish-card:hover { border-color: var(--ll-primary); }
.ai-polish-text { flex: 1; font-size: var(--ll-text-sm); color: var(--ll-ink); word-break: break-all; }
.ai-polish-hint { font-size: var(--ll-text-xs); color: var(--ll-muted); margin: 2px 0 6px; }
.ai-polish-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 4px; }
.ai-polish-error {
  margin-top: 6px;
  font-size: var(--ll-text-xs);
  color: var(--ll-amber-strong);
  word-break: break-all;
}
</style>

<template>
  <div ref="rootRef" class="emoji-picker" aria-label="表情选择">
    <div v-for="g in groups" :key="g.name" class="ep-group">
      <div class="ep-title">{{ g.name }}</div>
      <div class="ep-grid">
        <button v-for="e in g.emojis" :key="e" type="button" class="ep-cell"
                :aria-label="'插入表情 ' + e" @click="$emit('select', e)">{{ e }}</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { EMOJI_GROUPS } from '../../utils/emojiData'

const emit = defineEmits(['select', 'close'])
const groups = EMOJI_GROUPS
const rootRef = ref(null)

/** 点面板外/Esc 关闭（由父级 v-if 控制挂载，卸载即随监听一并移除） */
function onDocClick(e) {
  if (rootRef.value && !rootRef.value.contains(e.target)) emit('close')
}
function onKey(e) {
  if (e.key === 'Escape') emit('close')
}

onMounted(() => {
  document.addEventListener('click', onDocClick)
  document.addEventListener('keydown', onKey)
})
onBeforeUnmount(() => {
  document.removeEventListener('click', onDocClick)
  document.removeEventListener('keydown', onKey)
})
</script>

<style scoped>
/* 主题经 CSS 变量注入：默认亮色，商户中心深色主题由父级 :deep 覆盖变量即可 */
.emoji-picker {
  --ep-bg: #fff;
  --ep-border: #e5e2da;
  --ep-ink: #182230;
  --ep-muted: #8a94a3;
  --ep-hover: rgba(14, 165, 233, .1);
  position: absolute; bottom: calc(100% + 8px); left: 0; z-index: 30;
  width: min(320px, calc(100vw - 24px)); max-height: 264px; overflow-y: auto;
  padding: 10px 12px 12px;
  background: var(--ep-bg); border: 1px solid var(--ep-border); border-radius: 14px;
  box-shadow: 0 10px 30px rgba(19, 35, 58, .14);
  animation: epIn .18s ease-out;
}
@keyframes epIn { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; transform: none; } }
.ep-title { margin: 10px 2px 6px; font-size: 11px; color: var(--ep-muted); letter-spacing: .02em; }
.ep-group:first-child .ep-title { margin-top: 0; }
.ep-grid { display: grid; grid-template-columns: repeat(8, 1fr); gap: 2px; }
.ep-cell { border: 0; background: transparent; font-size: 20px; line-height: 1; padding: 5px 0;
  border-radius: 8px; cursor: pointer; transition: background .12s ease, transform .12s ease;
  /* 统一表情字形（Noto 子集，见 theme.css @font-face 'LL Emoji'） */
  font-family: 'LL Emoji', 'Apple Color Emoji', 'Segoe UI Emoji', 'Noto Color Emoji', sans-serif; }
.ep-cell:hover { background: var(--ep-hover); transform: scale(1.12); }
.ep-cell:active { transform: scale(.95); }

@media (max-width: 600px) {
  .emoji-picker { left: 0; width: calc(100vw - 44px); max-height: 232px; }
}
</style>

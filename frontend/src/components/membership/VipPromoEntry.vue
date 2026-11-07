<template>
  <div v-if="visible" class="vip-promo" :class="'vip-promo--' + variant" role="note">
    <span class="vip-promo-ico" aria-hidden="true"><Crown :size="14" :stroke-width="2.1" /></span>
    <span class="vip-promo-text"><slot /></span>
    <button v-if="closable" type="button" class="vip-promo-close" aria-label="关闭" @click="close">
      <X :size="14" :stroke-width="2.2" aria-hidden="true" />
    </button>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { Crown, X } from 'lucide-vue-next'
import { tryExposePrompt, dismissPrompt } from '../../utils/vipPrompt'

/**
 * 通用转化引导位（入口 2/3/6 复用）：行 / 条两形态 + 关闭/负频控内建。
 * 频控：关闭 7 天冷却 + 会话曝光 ≤2（优先级见 utils/vipPrompt）；
 * dedupeKey（如订单号）防同对象重访重复曝光。
 * 父级用 v-if 控制「数据条件」（如未订阅/应付达门槛），本组件只管「打扰频控」。
 */
const props = defineProps({
  slotKey: { type: String, required: true },          // 频控槽位：pdp / checkout / order
  variant: { type: String, default: 'row' },          // row：行内引导；bar：顶部非阻塞条
  dedupeKey: { type: String, default: '' },           // 同对象防重访键（order:{orderNo}）
  closable: { type: Boolean, default: true }
})
const emit = defineEmits(['expose'])

const visible = ref(false)

function dedupeStorageKey() { return 'll_vip_prompt_' + props.slotKey + ':' + props.dedupeKey }

function setup() {
  if (props.dedupeKey) {
    try { if (localStorage.getItem(dedupeStorageKey())) return } catch (e) { /* 忽略 */ }
  }
  if (!tryExposePrompt(props.slotKey)) return
  visible.value = true
  if (props.dedupeKey) {
    try { localStorage.setItem(dedupeStorageKey(), '1') } catch (e) { /* 忽略 */ }
  }
  emit('expose')
}

function close() {
  visible.value = false
  dismissPrompt(props.slotKey)
}

setup()
</script>

<style scoped>
.vip-promo{display:flex;align-items:center;gap:8px;padding:9px 12px;border-radius:var(--ll-radius-md);background:var(--ll-surface-inset);font-size:12px;color:var(--ll-slate-muted)}
.vip-promo--row{margin-top:8px}
.vip-promo--bar{margin:0 0 14px;padding:11px 14px;border:1px solid var(--ll-separator);border-radius:var(--ll-radius-lg);font-size:13px}
.vip-promo-ico{display:inline-flex;color:var(--ll-primary);flex:0 0 auto}
.vip-promo-text{flex:1 1 auto;min-width:0;line-height:1.6}
.vip-promo-text a{color:var(--ll-primary);font-weight:600;text-decoration:none}
.vip-promo-text a:hover{text-decoration:underline}
.vip-promo-close{display:inline-flex;align-items:center;justify-content:center;width:24px;height:24px;border:0;border-radius:8px;background:none;color:var(--ll-muted);cursor:pointer;flex:0 0 auto}
.vip-promo-close:hover{background:var(--ll-surface-overlay);color:var(--ll-ink)}
.vip-promo-close:focus-visible{outline:none;box-shadow:var(--ll-focus-ring)}
</style>

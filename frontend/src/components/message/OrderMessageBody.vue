<template>
  <div class="order-body">
    <p class="order-text">{{ summary }}</p>

    <!-- 二/三级入口：只在能解析出真实订单号时出现，避免出现点了 404 的假链接 -->
    <div v-if="primary || secondary" class="order-links">
      <button
        v-if="primary"
        type="button"
        class="order-link"
        @click.stop="go(primary)"
      >
        <ReceiptText :size="13" aria-hidden="true" />
        {{ primary.label }}
      </button>
      <button
        v-if="secondary"
        type="button"
        class="order-link subtle"
        @click.stop="go(secondary)"
      >
        <component :is="secondaryIcon" :size="13" aria-hidden="true" />
        {{ secondary.label }}
      </button>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ReceiptText, Undo2, Truck } from 'lucide-vue-next'
import { MSG_KIND, messageKind, primaryLink, secondaryLink, cleanContent } from '../../utils/messageLink'

/**
 * 订单类消息正文：把「一段文字」变成「可下钻到真实业务页面」的入口。
 *
 * <p>层级对应：
 * <ul>
 *   <li>二级：订单详情 `/orders/:orderNo`（所有订单类消息都有）；</li>
 *   <li>三级：退款进度 `?tab=refund` / 物流轨迹 `?tab=logistics`（按消息标题关键词决定）。</li>
 * </ul>
 *
 * <p>解析不到合法订单号时只渲染文字，不渲染按钮——宁可少一个入口，也不给坏链。
 */
const props = defineProps({
  item: { type: Object, required: true }
})

const router = useRouter()

const summary = computed(() => cleanContent(props.item))
const primary = computed(() => primaryLink(props.item))
const secondary = computed(() => secondaryLink(props.item))

const secondaryIcon = computed(() => (
  messageKind(props.item) === MSG_KIND.REFUND ? Undo2 : Truck
))

function go(link) {
  router.push(link.path)
}
</script>

<style scoped>
.order-body { display: flex; flex-direction: column; gap: 8px; }
.order-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--ll-muted, #6e6e73);
  word-break: break-word;
}
.order-links { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.order-link {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 11px;
  border-radius: 980px;
  border: 1px solid color-mix(in srgb, var(--ll-primary, #1E6EB8) 32%, transparent);
  background: color-mix(in srgb, var(--ll-primary, #1E6EB8) 7%, transparent);
  color: var(--ll-primary, #1E6EB8);
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  transition: background .18s ease, transform .18s ease;
}
.order-link:hover { background: color-mix(in srgb, var(--ll-primary, #1E6EB8) 13%, transparent); transform: translateY(-1px); }
.order-link.subtle {
  border-color: var(--ll-border, #e0ded8);
  background: transparent;
  color: var(--ll-ink, #1d1d1f);
}
.order-link.subtle:hover { background: var(--ll-page, #f2f2ef); }

@media (max-width: 560px) {
  .order-links { width: 100%; }
  .order-link { flex: 1 1 auto; justify-content: center; }
}
</style>

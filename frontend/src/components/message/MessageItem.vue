<template>
  <article class="msg-item" :class="{ unread: isUnread }">
    <!-- 左侧状态轨：未读点 + 竖线，视觉上把同一订单的连续消息串成一条时间线 -->
    <div class="msg-rail" aria-hidden="true">
      <span v-if="isUnread" class="msg-dot" />
      <span v-else class="msg-dot read" />
    </div>

    <div class="msg-body">
      <header class="msg-head">
        <h3 class="msg-title">{{ item.title }}</h3>
        <a-tag :color="tagColor" class="msg-tag">{{ tagName }}</a-tag>
        <slot name="badge" />
      </header>

      <!-- 业务正文：订单类走 OrderMessageBody（带二/三级跳转），其余走纯文本 -->
      <slot>
        <p class="msg-content">{{ content }}</p>
      </slot>

      <footer class="msg-foot">
        <time class="msg-time">{{ timeText }}</time>
        <span v-if="item.relatedNo" class="msg-no">关联单号：{{ item.relatedNo }}</span>
        <span class="msg-actions">
          <slot name="actions" />
          <a-button
            v-if="isUnread"
            type="link"
            size="small"
            class="msg-read-btn"
            @click.stop="$emit('read', item)"
          >标为已读</a-button>
        </span>
      </footer>
    </div>
  </article>
</template>

<script setup>
import { computed } from 'vue'

/**
 * 单条站内消息的统一外壳。
 *
 * <p>只负责「外壳」：未读态、标题、类型标签、时间、关联单号、已读按钮。
 * 正文与业务动作由父级通过默认插槽 / `actions` 插槽注入，
 * 这样订单、促销、系统等不同业务可以各自渲染，而不必复制一套外壳样式。
 */
const props = defineProps({
  item: { type: Object, required: true },
  /** 自定义正文文本；不传则用 item.content */
  content: { type: String, default: '' }
})

defineEmits(['read'])

const isUnread = computed(() => props.item?.readFlag === 0)

const TYPE_NAME = { price: '价格', order: '订单', promotion: '促销', system: '系统' }
const TYPE_COLOR = { price: 'gold', order: 'blue', promotion: 'magenta', system: 'default' }
const tagName = computed(() => TYPE_NAME[props.item?.type] || props.item?.type || '通知')
const tagColor = computed(() => TYPE_COLOR[props.item?.type] || 'default')

const timeText = computed(() => {
  const d = props.item?.createTime
  if (!d) return ''
  const s = String(d).replace('T', ' ')
  return s.length >= 16 ? s.slice(0, 16) : s
})
</script>

<style scoped>
.msg-item {
  display: flex;
  gap: 12px;
  padding: 14px 4px 14px 0;
  border-bottom: 1px solid var(--ll-border, #eceae4);
  transition: background .16s ease;
}
.msg-item:hover { background: var(--ll-page, #f7f7f5); }
.msg-item.unread { background: color-mix(in srgb, var(--ll-primary, #1E6EB8) 4%, transparent); }

.msg-rail {
  width: 16px;
  flex-shrink: 0;
  display: flex;
  justify-content: center;
  padding-top: 6px;
}
.msg-dot {
  width: 7px; height: 7px; border-radius: 50%;
  background: var(--ll-primary, #1E6EB8);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--ll-primary, #1E6EB8) 18%, transparent);
}
.msg-dot.read { background: var(--ll-gray2, #b8b8b8); box-shadow: none; }

.msg-body { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6px; }

.msg-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.msg-title {
  margin: 0;
  font-size: 14px;
  font-weight: 650;
  color: var(--ll-ink, #1d1d1f);
  line-height: 1.5;
}
.msg-item.unread .msg-title { font-weight: 700; }
.msg-tag { margin: 0; }

.msg-content {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--ll-muted, #6e6e73);
  word-break: break-word;
}

.msg-foot {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--ll-gray2, #98a2b3);
}
.msg-no { color: var(--ll-muted, #6e6e73); font-variant-numeric: tabular-nums; }
.msg-actions { display: inline-flex; align-items: center; gap: 6px; margin-left: auto; }
.msg-read-btn { padding: 0 4px; height: auto; font-size: 12px; }

@media (max-width: 560px) {
  .msg-item { padding: 12px 2px 12px 0; gap: 8px; }
  .msg-foot { gap: 8px; }
  .msg-actions { margin-left: 0; width: 100%; justify-content: flex-end; }
  .msg-no { word-break: break-all; }
}
</style>

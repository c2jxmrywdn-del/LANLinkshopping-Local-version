<template>
  <div class="notice-body">
    <p class="notice-text">{{ summary }}</p>

    <!--
      系统/促销/价格通知的二、三级入口。
      与订单消息同一套「解析不到就不渲染」原则：解析失败时只显示文字，不给死链。
    -->
    <div v-if="primary || secondary" class="notice-links">
      <button v-if="primary" type="button" class="notice-link" @click.stop="go(primary)">
        <component :is="primaryIcon" :size="13" aria-hidden="true" />
        {{ primary.label }}
      </button>
      <button v-if="secondary" type="button" class="notice-link subtle" @click.stop="go(secondary)">
        <component :is="secondaryIcon" :size="13" aria-hidden="true" />
        {{ secondary.label }}
      </button>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { UserCog, MapPin, Ticket, Coins, LineChart, UserRound } from 'lucide-vue-next'
import { MSG_KIND, messageKind, primaryLink, secondaryLink, cleanContent } from '../../utils/messageLink'

/**
 * 非订单类站内消息正文（系统 / 促销 / 价格）。
 *
 * <p>这些通知此前只是一段纯文本，用户看完不知道下一步去哪。
 * 组件按业务类型给出二、三级入口：
 * <ul>
 *   <li>系统：个人中心 / 账户设置（二级）→ 登录记录、收货地址（三级）；</li>
 *   <li>促销：活动中心（二级）→ 领券中心（三级）；</li>
 *   <li>价格：价格情报台（二级）→ 积分明细（三级）。</li>
 * </ul>
 */
const props = defineProps({
  item: { type: Object, required: true }
})

const router = useRouter()

const kind = computed(() => messageKind(props.item))
const summary = computed(() => cleanContent(props.item) || String(props.item?.content || ''))
const primary = computed(() => primaryLink(props.item))
const secondary = computed(() => secondaryLink(props.item))

const primaryIcon = computed(() => {
  if (kind.value === MSG_KIND.SYSTEM) {
    return String(props.item?.title || '').match(/资料|设置|安全|密码|认证|绑定/) ? UserCog : UserRound
  }
  if (kind.value === MSG_KIND.PROMOTION) return Ticket
  return LineChart
})

const secondaryIcon = computed(() => {
  if (kind.value === MSG_KIND.SYSTEM) {
    return String(props.item?.title || '').match(/安全|密码|两步|2FA|登录/) ? UserCog : MapPin
  }
  return Coins
})

function go(link) {
  router.push(link.path)
}
</script>

<style scoped>
.notice-body { display: flex; flex-direction: column; gap: 8px; }
.notice-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--ll-muted, #6e6e73);
  word-break: break-word;
}
.notice-links { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.notice-link {
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
.notice-link:hover { background: color-mix(in srgb, var(--ll-primary, #1E6EB8) 13%, transparent); transform: translateY(-1px); }
.notice-link.subtle {
  border-color: var(--ll-border, #e0ded8);
  background: transparent;
  color: var(--ll-ink, #1d1d1f);
}
.notice-link.subtle:hover { background: var(--ll-page, #f2f2ef); }

@media (max-width: 560px) {
  .notice-links { width: 100%; }
  .notice-link { flex: 1 1 auto; justify-content: center; }
}
</style>

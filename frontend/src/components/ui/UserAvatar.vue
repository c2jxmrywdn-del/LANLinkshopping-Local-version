<template>
  <span
    class="ll-user-avatar"
    :class="[`ll-user-avatar--${avatarRole}`, { 'll-user-avatar--no-ring': !ring }]"
    :style="avatarStyle"
    :role="useUploadedPhoto ? undefined : 'img'"
    :aria-label="resolvedAlt"
    :title="name || resolvedAlt"
  >
    <img v-if="useUploadedPhoto" :src="src" :alt="resolvedAlt"
      class="ll-user-avatar__photo" loading="lazy" decoding="async" @error="handleImageError" />
    <span v-else-if="fallbackMode === 'sprite'" class="ll-user-avatar__fallback"
      :style="{ backgroundPosition: fallbackPosition }" aria-hidden="true" />
    <span v-else-if="initials" class="ll-user-avatar__initials"
      :style="{ backgroundImage: initialsGradient }" aria-hidden="true">{{ initials }}</span>
    <svg v-else class="ll-user-avatar__mark" viewBox="0 0 24 24" aria-hidden="true">
      <circle cx="12" cy="8.6" r="4.1" />
      <path d="M4.4 20.6a7.6 7.6 0 0 1 15.2 0Z" />
    </svg>
  </span>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import avatarSprite from '../../assets/avatars/user-avatar-sprite.webp'
import { avatarGradientOf, initialsOf } from '../../utils/avatarIdentity'
import { locale, translateDataText } from '../../i18n'

const props = defineProps({
  src: { type: String, default: '' },
  name: { type: String, default: '' },
  alt: { type: String, default: '' },
  gender: { type: String, default: 'secret' },
  role: { type: String, default: 'user' },
  size: { type: [Number, String], default: 48 },
  ring: { type: Boolean, default: true },
  /** 无上传图片时的回退样式：initials（姓名缩写 + 确定性配色，默认）| sprite（内置卡通精灵图） */
  fallbackMode: { type: String, default: 'initials' },
  /** 覆盖自动缩写（如企业名场景传商号缩写，避免取到「公司」等组织后缀字）；空则按 name 推导 */
  initials: { type: String, default: '' }
})

const imageError = ref(false)
watch(() => props.src, () => { imageError.value = false })

const avatarRole = computed(() => {
  const role = String(props.role || 'user').trim().toLowerCase()
  if (['admin', 'administrator', 'platform-admin', '平台管理员', '运营'].includes(role)) return 'admin'
  if (['merchant', 'seller', '商户'].includes(role)) return 'merchant'
  return 'user'
})
const normalizedGender = computed(() => String(props.gender || '').trim().toLowerCase())
const female = computed(() => ['female', 'f', 'woman', '女', '女性', '2'].includes(normalizedGender.value))
const fallbackPosition = computed(() => {
  if (avatarRole.value === 'admin') return '100% 50%'
  if (female.value) return '50% 50%'
  return '0% 50%'
})
const useUploadedPhoto = computed(() => Boolean(props.src && !imageError.value))
// 英文模式下昵称按词表翻译后再取缩写，避免头像仍显示「理员」等中文字符
const displayName = computed(() => locale.value === 'en-US' ? translateDataText(props.name) : props.name)
const resolvedAlt = computed(() => {
  if (props.alt) return locale.value === 'en-US' ? translateDataText(props.alt) : props.alt
  if (displayName.value) return `${displayName.value}${locale.value === 'en-US' ? "'s avatar" : ' 的头像'}`
  return locale.value === 'en-US' ? 'LANLinkshopping user avatar' : 'LANLinkshopping 用户头像'
})
// 21st.dev 头像规范：缩写 + 由姓名确定性推导的配色（同一用户各处颜色一致）
const initials = computed(() => props.initials || initialsOf(displayName.value))
const initialsGradient = computed(() =>
  avatarGradientOf(props.name || props.alt || 'LANLinkshopping').css)
const avatarStyle = computed(() => ({
  '--ll-avatar-size': typeof props.size === 'number' ? `${props.size}px` : props.size,
  '--ll-avatar-ring': props.ring
    ? (avatarRole.value === 'admin' ? 'var(--ll-brand-gold, #C8A45C)' : 'rgba(91,143,181,.55)')
    : 'transparent',
  '--ll-avatar-image': `url("${avatarSprite}")`
}))

function handleImageError() { imageError.value = true }
</script>

<style scoped>
.ll-user-avatar {
  position: relative; display: inline-flex; flex: 0 0 auto;
  align-items: center; justify-content: center;
  width: var(--ll-avatar-size, 48px); height: var(--ll-avatar-size, 48px);
  border: 2px solid var(--ll-avatar-ring); border-radius: 50%; overflow: hidden;
  vertical-align: middle; background: #F5F0E8;
  box-shadow: 0 3px 10px rgba(19,35,58,.09); isolation: isolate;
}
.ll-user-avatar--admin { box-shadow: 0 0 0 2px rgba(200,164,92,.18), 0 3px 12px rgba(19,35,58,.14); }
.ll-user-avatar--no-ring { box-shadow: none; }
.ll-user-avatar__photo, .ll-user-avatar__fallback {
  display: block; width: 100%; height: 100%; border-radius: inherit;
}
.ll-user-avatar__photo { object-fit: cover; object-position: center 35%; }
.ll-user-avatar__fallback {
  background-image: var(--ll-avatar-image); background-size: 300% 100%;
  background-position: 0% 50%; background-repeat: no-repeat;
}
/* 姓名缩写层：白字 + 由姓名确定性推导的深色渐变（白字对渐变两端对比度均 ≥4.8:1） */
.ll-user-avatar__initials {
  display: grid; place-items: center; width: 100%; height: 100%; border-radius: inherit;
  color: #fff; font-weight: 700; line-height: 1;
  font-size: calc(var(--ll-avatar-size, 48px) * 0.38);
  letter-spacing: .01em;
  /* 视觉居中：大写字母的重心低于几何中心，向右下微微补偿 */
  padding-bottom: .06em;
  text-shadow: 0 1px 2px rgba(0, 0, 0, .20);
  user-select: none;
}
/* 第三层中性标记：无有效姓名时的人物剪影（非文本图形 ≥3:1） */
.ll-user-avatar__mark { width: 58%; height: 58%; fill: var(--ll-muted, #64748B); }
</style>

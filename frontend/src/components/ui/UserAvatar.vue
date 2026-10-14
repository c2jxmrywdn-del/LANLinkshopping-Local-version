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
    <span v-else class="ll-user-avatar__fallback"
      :style="{ backgroundPosition: fallbackPosition }" aria-hidden="true" />
  </span>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import avatarSprite from '../../assets/avatars/user-avatar-sprite.webp'

const props = defineProps({
  src: { type: String, default: '' },
  name: { type: String, default: '' },
  alt: { type: String, default: '' },
  gender: { type: String, default: 'secret' },
  role: { type: String, default: 'user' },
  size: { type: [Number, String], default: 48 },
  ring: { type: Boolean, default: true }
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
const resolvedAlt = computed(() => props.alt || (props.name ? `${props.name} 的头像` : 'LANLinkshopping 用户头像'))
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
</style>

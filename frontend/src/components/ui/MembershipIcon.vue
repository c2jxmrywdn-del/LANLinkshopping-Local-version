<template>
  <svg
    class="membership-icon"
    :width="size"
    :height="size"
    viewBox="0 0 40 40"
    fill="none"
    :role="label ? 'img' : undefined"
    :aria-label="label || undefined"
    :aria-hidden="label ? undefined : 'true'"
    xmlns="http://www.w3.org/2000/svg"
  >
    <defs>
      <linearGradient :id="`${gradientId}-gold`" x1="6" y1="4" x2="34" y2="36" gradientUnits="userSpaceOnUse">
        <stop stop-color="#F9E5A6" />
        <stop offset=".48" stop-color="#C8A45C" />
        <stop offset="1" stop-color="#946B2D" />
      </linearGradient>
      <linearGradient :id="`${gradientId}-navy`" x1="4" y1="2" x2="37" y2="38" gradientUnits="userSpaceOnUse">
        <stop stop-color="#243B5A" />
        <stop offset="1" stop-color="#101C30" />
      </linearGradient>
      <linearGradient :id="`${gradientId}-shine`" x1="0" y1="0" x2="1" y2="0">
        <stop offset="0" stop-color="#FFFFFF" stop-opacity="0" />
        <stop offset=".48" stop-color="#FFF7D8" stop-opacity=".72" />
        <stop offset="1" stop-color="#FFFFFF" stop-opacity="0" />
      </linearGradient>
      <clipPath :id="`${gradientId}-clip`"><circle cx="20" cy="20" r="17.4" /></clipPath>
    </defs>
    <circle cx="20" cy="20" r="18.2" :fill="`url(#${gradientId}-navy)`" :stroke="`url(#${gradientId}-gold)`" stroke-width="2.2" />
    <circle cx="20" cy="20" r="14.7" stroke="#E7CC83" stroke-opacity=".4" stroke-width=".8" />
    <path d="M9.2 13.1 15 17.8 20 8.8l5 9 5.8-4.7-2.2 13.2H11.4L9.2 13.1Z" :fill="`url(#${gradientId}-gold)`" stroke="#FFF0BD" stroke-width=".65" stroke-linejoin="round" />
    <circle cx="9.2" cy="12.3" r="2" fill="#F8E4A4" />
    <circle cx="20" cy="8.1" r="2.1" fill="#F8E4A4" />
    <circle cx="30.8" cy="12.3" r="2" fill="#F8E4A4" />
    <path d="M14.1 29.1c0-2.8 2.4-4.8 5.9-4.8s5.9 2 5.9 4.8H14.1Z" :fill="`url(#${gradientId}-gold)`" />
    <circle cx="17.6" cy="28.7" r="3.2" stroke="#FFE7A0" stroke-width="1.2" />
    <circle cx="22.4" cy="28.7" r="3.2" stroke="#FFE7A0" stroke-width="1.2" />
    <g :clip-path="`url(#${gradientId}-clip)`" class="membership-icon__shine">
      <rect x="-12" y="0" width="12" height="40" :fill="`url(#${gradientId}-shine)`" transform="skewX(-18)" />
    </g>
  </svg>
</template>

<script setup>
import { useId } from 'vue'

const gradientId = useId().replace(/:/g, '')

defineProps({
  size: { type: [Number, String], default: 20 },
  label: { type: String, default: '' }
})
</script>

<style scoped>
.membership-icon {
  display: inline-block;
  flex: 0 0 auto;
  vertical-align: middle;
  transform-origin: center;
  transform-box: fill-box;
  animation: membershipIconBreathe 3.8s ease-in-out infinite;
  will-change: transform, filter;
}
.membership-icon__shine {
  opacity: 0;
  animation: membershipIconShine 4.8s ease-in-out infinite;
}
@keyframes membershipIconBreathe {
  0%, 100% { transform: translateY(0) scale(1); filter: drop-shadow(0 0 0 rgba(200,164,92,0)); }
  50% { transform: translateY(-.7px) scale(1.045); filter: drop-shadow(0 1px 3px rgba(200,164,92,.35)); }
}
@keyframes membershipIconShine {
  0%, 12% { transform: translateX(-22px); opacity: 0; }
  28% { opacity: .78; }
  54%, 100% { transform: translateX(46px); opacity: 0; }
}
@media (prefers-reduced-motion: reduce) {
  .membership-icon, .membership-icon__shine { animation: none; will-change: auto; }
}
</style>

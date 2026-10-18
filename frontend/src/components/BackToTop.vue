<template>
  <button
    type="button"
    class="ll-backtop"
    :class="{ 'is-visible': visible }"
    aria-label="返回顶部"
    @click="scrollTop"
  >↑</button>
</template>

<script setup>
// 返回顶部：滚动超过 1.5 屏显示，点击平滑回顶（尊重系统减少动效偏好）
import { ref, onMounted, onBeforeUnmount } from 'vue'

const visible = ref(false)
let ticking = false

function onScroll() {
  if (ticking) return
  ticking = true
  requestAnimationFrame(() => {
    visible.value = window.scrollY > window.innerHeight * 1.5
    ticking = false
  })
}

function scrollTop() {
  const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  window.scrollTo({ top: 0, behavior: reduce ? 'auto' : 'smooth' })
}

onMounted(() => {
  window.addEventListener('scroll', onScroll, { passive: true })
  onScroll()
})
onBeforeUnmount(() => window.removeEventListener('scroll', onScroll))
</script>

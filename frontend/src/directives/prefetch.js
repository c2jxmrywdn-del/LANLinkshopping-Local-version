import { prefetchRoute } from '../utils/routePrefetcher'

/**
 * 链接与元素预取指令 v-prefetch
 * 在用户鼠标悬停超过 65ms 或元素进入可视区时提前预热路由与数据。
 *
 * 用法:
 * <router-link to="/mall" v-prefetch="'mall'">商城</router-link>
 * <div v-prefetch="{ route: 'activity', delay: 80 }">活动</div>
 */

const prefetchMap = new WeakMap()

export const prefetchDirective = {
  mounted(el, binding) {
    const rawVal = binding.value
    if (!rawVal) return

    const config = typeof rawVal === 'string'
      ? { route: rawVal, delay: 65 }
      : { route: rawVal.route, delay: rawVal.delay || 65, mode: rawVal.mode || 'hover' }

    if (!config.route) return

    let timer = null

    const handleEnter = () => {
      timer = setTimeout(() => {
        prefetchRoute(config.route)
      }, config.delay)
    }

    const handleLeave = () => {
      if (timer) {
        clearTimeout(timer)
        timer = null
      }
    }

    el.addEventListener('pointerenter', handleEnter, { passive: true })
    el.addEventListener('pointerleave', handleLeave, { passive: true })

    let observer = null
    if (config.mode === 'visible' && typeof IntersectionObserver !== 'undefined') {
      observer = new IntersectionObserver((entries) => {
        entries.forEach(entry => {
          if (entry.isIntersecting) {
            prefetchRoute(config.route)
            observer.disconnect()
          }
        })
      }, { rootMargin: '100px' })
      observer.observe(el)
    }

    prefetchMap.set(el, { handleEnter, handleLeave, observer })
  },

  unmounted(el) {
    const record = prefetchMap.get(el)
    if (record) {
      el.removeEventListener('pointerenter', record.handleEnter)
      el.removeEventListener('pointerleave', record.handleLeave)
      if (record.observer) {
        record.observer.disconnect()
      }
      prefetchMap.delete(el)
    }
  }
}

export default prefetchDirective

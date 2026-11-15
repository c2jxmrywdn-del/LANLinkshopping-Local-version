import { homeApi } from '../api'

/**
 * 路由分包与关联数据预取器（Route & Data Prefetcher）
 * 支持网络感知（弱网与 Save-Data 省流模式自动跳过）、组件分包加载与关联数据并行预热。
 */

export const prefetchedComponents = new Set()

export const ROUTE_COMPONENT_LOADERS = {
  home: () => import('../views/Home.vue'),
  mall: () => import('../views/Mall.vue'),
  activity: () => import('../views/ActivityCenter.vue'),
  'merchant-center': () => import('../views/merchant/CenterDashboard.vue'),
  cart: () => import('../views/Cart.vue'),
  tech: () => import('../views/techservice/TechService.vue')
}

/**
 * 检查当前网络环境与安全开关是否允许预取
 */
export function canPrefetch() {
  if (typeof window === 'undefined') return false
  if (window.__LL_DISABLE_PRELOAD) return false

  const conn = navigator.connection || navigator.mozConnection || navigator.webkitConnection
  if (conn) {
    if (conn.saveData) return false
    const et = String(conn.effectiveType || '').toLowerCase()
    if (et === 'slow-2g' || et === '2g') return false
  }

  return true
}

/**
 * 预取指定路由的 JS 异步分包及关联接口数据
 * @param {string} routeName 路由名称或标识
 */
export async function prefetchRoute(routeName) {
  if (!routeName || !canPrefetch()) return

  const cleanName = String(routeName).replace(/^\//, '').toLowerCase()

  // 1. 预加载组件分包（仅执行一次）
  const loader = ROUTE_COMPONENT_LOADERS[cleanName]
  if (loader && !prefetchedComponents.has(cleanName)) {
    prefetchedComponents.add(cleanName)
    try {
      await loader()
    } catch {
      // 容错：网络微抖时不影响后续正常点击路由跳转
      prefetchedComponents.delete(cleanName)
    }
  }

  // 2. 预热该路由关联的只读数据
  try {
    if (cleanName === 'mall' || cleanName === 'home') {
      if (typeof homeApi?.industries === 'function') {
        homeApi.industries().catch(() => {})
      }
      if (cleanName === 'mall' && typeof homeApi?.categories === 'function') {
        homeApi.categories().catch(() => {})
      }
    }
  } catch {
    // 静默忽略数据预取异常
  }
}

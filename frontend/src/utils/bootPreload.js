import { homeApi } from '../api'

/**
 * 应用首屏空闲期公共配置预热管道（Bootstrap Preload Pipeline）
 * 利用 requestIdleCallback 在浏览器空闲时非阻塞预热高频字典与常用接口数据。
 */
export function warmUpPublicConfigs() {
  if (typeof window === 'undefined') return
  if (window.__LL_DISABLE_PRELOAD) return

  const warmUp = () => {
    // 预加载属于"锦上添花"：全部 silent，失败不弹 toast（否则首屏会叠出多条重复错误提示）
    // 1. 行业元数据预热（Header 导航、首页、商城跨组件高频共享）
    if (typeof homeApi?.industries === 'function') {
      homeApi.industries({ silent: true }).catch(() => {})
    }

    // 2. 全量分类树预热
    if (typeof homeApi?.categories === 'function') {
      homeApi.categories(undefined, { silent: true }).catch(() => {})
    }

    // 3. 首页热门商品预热
    if (typeof homeApi?.hot === 'function') {
      homeApi.hot(8, { silent: true }).catch(() => {})
    }
  }

  if (typeof window.requestIdleCallback === 'function') {
    window.requestIdleCallback(warmUp, { timeout: 2500 })
  } else {
    setTimeout(warmUp, 200)
  }
}

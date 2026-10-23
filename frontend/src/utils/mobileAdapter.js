/**
 * 移动端适配器
 *
 * 通过 matchMedia 在 <html> 上打 `data-viewport="mobile" | "desktop"` 标记：
 * - 所有移动端样式都以 html[data-viewport="mobile"] 为前缀，PC 端不加任何覆盖，保持原版；
 * - 视口跨过断点或旋转屏幕时自动切换标记，无需刷新；
 * - index.html 内联脚本会在首屏渲染前先写入一次标记，避免样式闪变。
 *
 * 断点与项目现有移动布局保持一致（<= 767px）。
 */
const MOBILE_QUERY = '(max-width: 767px)'

function applyViewport(mobile) {
  const root = document.documentElement
  root.dataset.viewport = mobile ? 'mobile' : 'desktop'
  root.classList.toggle('ll-mobile', mobile)
}

export function isMobileViewport() {
  return typeof window !== 'undefined' && window.matchMedia(MOBILE_QUERY).matches
}

export function installMobileAdapter() {
  if (typeof window === 'undefined') return () => {}
  // HMR 场景下避免重复绑定监听器
  if (window.__llMobileAdapterInstalled) return () => {}
  window.__llMobileAdapterInstalled = true

  const mq = window.matchMedia(MOBILE_QUERY)
  applyViewport(mq.matches)

  const onChange = (event) => applyViewport(event.matches)
  if (mq.addEventListener) mq.addEventListener('change', onChange)
  else if (mq.addListener) mq.addListener(onChange)

  return () => {
    if (mq.removeEventListener) mq.removeEventListener('change', onChange)
    else if (mq.removeListener) mq.removeListener(onChange)
    window.__llMobileAdapterInstalled = false
  }
}

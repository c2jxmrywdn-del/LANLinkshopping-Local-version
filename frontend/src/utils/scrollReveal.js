/**
 * 滚动进场动效（21st.dev 式：轻微上浮淡入 + 错落延迟）
 * 零依赖：IntersectionObserver + data-reveal 属性，自动批量生效。
 *
 * 用法（任意页面模板）：
 *   <section data-reveal>                 进入视口时上浮淡入
 *   <div data-reveal="fade-up">           同上（显式）
 *   <div data-reveal="fade-right">        右侧滑入
 *   <div data-reveal="scale">             缩放浮现（适合图片/卡片）
 *   <div data-reveal data-delay="120">    延迟 120ms（列表错落）
 *
 * 特性：
 *  - 自动扫描：监听 ll:page-entered（路由切换后）+ 启动时
 *  - 一次性触发，元素移出视口不再重复动画（性能友好）
 *  - 尊重 prefers-reduced-motion：直接显示，无动画
 */
const REVEAL_ATTR = 'data-reveal'
let observer = null

function setupObserver() {
  if (observer) return observer
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  if (reduceMotion) {
    // 无障碍：直接显示所有元素
    document.querySelectorAll(`[${REVEAL_ATTR}]`).forEach((el) => el.classList.add('ll-revealed'))
    observer = { observe() {}, disconnect() {} }
    return observer
  }
  observer = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          const el = entry.target
          const delay = el.dataset.delay
          if (delay) el.style.transitionDelay = `${delay}ms`
          el.classList.add('ll-revealed')
          observer.unobserve(el) // 一次性：动画完即解除，避免反复触发
        }
      })
    },
    { threshold: 0.12, rootMargin: '0px 0px -40px 0px' }
  )
  return observer
}

/** 扫描当前文档中所有未进场元素并注册观察 */
export function scanReveal(root = document) {
  const obs = setupObserver()
  root.querySelectorAll(`[${REVEAL_ATTR}]:not(.ll-revealed)`).forEach((el) => {
    el.classList.add('ll-reveal-armed') // 标记已注册，初始隐藏由 CSS 控制
    obs.observe(el)
  })
}

/** 初始化：启动时 + 每次路由页面进入后自动扫描（幂等：重复调用不重复挂监听） */
let _inited = false
export function initScrollReveal() {
  if (_inited) return
  _inited = true
  scanReveal()
  document.addEventListener('ll:page-entered', () => scanReveal())
}

/** 手动立即显示全部（如弹窗内动态插入元素后调用） */
export function revealAll(root = document) {
  root.querySelectorAll(`[${REVEAL_ATTR}]`).forEach((el) => {
    el.classList.remove('ll-reveal-armed')
    el.classList.add('ll-revealed')
  })
}

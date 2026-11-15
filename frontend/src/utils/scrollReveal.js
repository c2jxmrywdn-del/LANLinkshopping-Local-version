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
 *  - 自动扫描：启动时 + ll:page-entered（路由切换后）+ DOM 后插入补扫（MutationObserver）
 *  - 一次性触发，元素移出视口不再重复动画（性能友好）
 *  - 尊重 prefers-reduced-motion：直接显示，无动画
 */
const REVEAL_ATTR = 'data-reveal'
let observer = null

function setupObserver() {
  if (observer) return observer
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    // 无障碍：不注册观察，登记路径直接显示（armElement 内处理）
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

/** 单个元素登记：armed 隐藏 + 进入观察；reduced-motion 下直接显示、不做动画 */
function armElement(el) {
  if (el.classList.contains('ll-revealed') || el.classList.contains('ll-reveal-armed')) return
  if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    el.classList.add('ll-revealed')
    return
  }
  el.classList.add('ll-reveal-armed') // 标记已注册，初始隐藏由 CSS 控制
  observer.observe(el)
}

/** 扫描当前文档中所有未进场元素并注册观察 */
export function scanReveal(root = document) {
  setupObserver()
  root.querySelectorAll(`[${REVEAL_ATTR}]:not(.ll-revealed):not(.ll-reveal-armed)`).forEach((el) => armElement(el))
}

/**
 * 后插入补扫：v-if / 异步数据渲染的 [data-reveal] 元素（如首页活动入口条、商品网格）
 * 在启动扫描与 ll:page-entered 扫描的时点还不存在。监听 body 子树的新增节点，
 * rAF 合帧后批量补登记；只挂 childList（不监听属性/文本），本模块加 class 不会自触发。
 */
let mutationObserver = null
function installMutationScan() {
  if (mutationObserver || typeof MutationObserver !== 'function') return
  const target = document.body || document.documentElement
  if (!target) return
  const pending = new Set()
  let scheduled = false
  mutationObserver = new MutationObserver((mutations) => {
    for (const m of mutations) {
      for (const node of m.addedNodes) {
        if (node.nodeType === 1) pending.add(node)
      }
    }
    if (scheduled || !pending.size) return
    scheduled = true
    requestAnimationFrame(() => {
      scheduled = false
      const batch = Array.from(pending)
      pending.clear()
      setupObserver()
      for (const node of batch) {
        if (node.matches(`[${REVEAL_ATTR}]`)) armElement(node)
        node.querySelectorAll(`[${REVEAL_ATTR}]`).forEach((el) => armElement(el))
      }
    })
  })
  mutationObserver.observe(target, { childList: true, subtree: true })
}

/** 初始化：启动扫描 + 每次路由页面进入后扫描 + DOM 后插入补扫（幂等：重复调用不重复挂监听） */
let _inited = false
export function initScrollReveal() {
  if (_inited) return
  _inited = true
  scanReveal()
  document.addEventListener('ll:page-entered', () => scanReveal())
  installMutationScan()
}

/** 手动立即显示全部（如弹窗内动态插入元素后调用） */
export function revealAll(root = document) {
  root.querySelectorAll(`[${REVEAL_ATTR}]`).forEach((el) => {
    el.classList.remove('ll-reveal-armed')
    el.classList.add('ll-revealed')
  })
}

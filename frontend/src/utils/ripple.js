/**
 * 按钮点击波纹（Material 风格，全站统一）
 * 通过事件委托监听 pointerdown，在 .ant-btn 或带 .ll-ripple-host 的元素内注入
 * .ll-ripple 波纹节点，动画结束自动移除。颜色由 CSS 变量 --ll-ripple-color
 * 按元素表面自适应（浅底深波纹 / 深底白波纹），定义见 styles/theme.css。
 */
const RIPPLE_SELECTOR = '.ant-btn, .ll-ripple-host'
const RIPPLE_SPAN = 'll-ripple'

export function initRipple() {
  if (typeof document === 'undefined') return
  document.addEventListener('pointerdown', (e) => {
    if (e.button !== 0) return // 仅主键
    const btn = e.target.closest(RIPPLE_SELECTOR)
    if (!btn) return
    if (btn.disabled || btn.getAttribute('disabled') != null || btn.classList.contains('ant-btn-loading')) return

    const rect = btn.getBoundingClientRect()
    // 波纹直径取按钮外接圆，保证扩散覆盖整个按钮
    const d = Math.max(rect.width, rect.height) * 2.2
    const span = document.createElement('span')
    span.className = RIPPLE_SPAN
    span.style.width = `${d}px`
    span.style.height = `${d}px`
    span.style.left = `${e.clientX - rect.left - d / 2}px`
    span.style.top = `${e.clientY - rect.top - d / 2}px`
    btn.appendChild(span)
    span.addEventListener('animationend', () => span.remove())
  })
}

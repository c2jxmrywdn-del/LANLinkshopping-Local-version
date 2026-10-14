/**
 * 导航触达反馈：视觉（缩放+高亮 150ms）+ 触觉（移动端震动 15ms）
 * 用法：可点元素加 class="ll-tap"，pointerdown 时调用 tapFeedback(e.currentTarget)
 * 样式定义见 styles/theme.css 的 .ll-tap / .ll-tapped
 */

/** 触觉反馈：轻震动 15ms（仅移动端浏览器支持；不支持的设备静默跳过） */
export function haptic(ms = 15) {
  try {
    if (typeof navigator !== 'undefined' && typeof navigator.vibrate === 'function') {
      navigator.vibrate(ms)
    }
  } catch (e) { /* 部分浏览器在非用户手势上下文调用会抛错，忽略 */ }
}

/**
 * 导航模块点击反馈：150ms 视觉反馈（在 100-200ms 区间）+ 触觉震动
 * @param {HTMLElement} el 触发元素（需带 .ll-tap 类）
 */
export function tapFeedback(el) {
  haptic(15)
  if (el && el.classList) {
    el.classList.add('ll-tapped')
    setTimeout(() => el.classList.remove('ll-tapped'), 150)
  }
}

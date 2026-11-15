/**
 * 支付渠道表单安全提交。
 *
 * 背景：支付宝「电脑网站支付」返回的是一段自动提交的 HTML 表单
 * （`<form action=... method=post><input name=... value=...></form>`），
 * 历史实现直接 `div.innerHTML = html` 再 `document.forms[0].submit()`。
 * 这段 HTML 来自后端/支付渠道，一旦被篡改即可注入 `<script>`、`<img onerror>` 等，
 * 形成 DOM XSS（可窃取会话与支付信息）。
 *
 * 本工具不再把字符串交给 innerHTML，而是解析后**只重建 form 与 input/select/textarea**，
 * 且仅复制支付必需的白名单属性。未知标签、事件属性、script/style/iframe 一律丢弃。
 * 对合法支付表单行为完全一致：仍以相同 action/method 提交相同字段。
 */

const FORM_ATTRS = ['action', 'method', 'target', 'accept-charset', 'enctype', 'name', 'id']
const FIELD_TAGS = new Set(['input', 'select', 'textarea', 'button'])
const FIELD_ATTRS = ['type', 'name', 'value', 'checked', 'selected', 'disabled', 'readonly']
const ALLOWED_PROTOCOLS = ['https:', 'http:']

function copyAttrs(source, target, allowed) {
  for (const attr of Array.from(source.attributes || [])) {
    const name = attr.name.toLowerCase()
    if (allowed.includes(name)) target.setAttribute(name, attr.value)
  }
}

function assertSafeAction(action) {
  if (!action) return
  let url
  try {
    // 相对地址按当前页面解析；绝对地址取其协议判定
    url = new URL(action, window.location.origin)
  } catch {
    throw new Error('支付表单 action 非法')
  }
  if (!ALLOWED_PROTOCOLS.includes(url.protocol)) {
    throw new Error('支付表单 action 协议不被允许: ' + url.protocol)
  }
}

/**
 * 解析支付表单 HTML，返回一个只包含白名单节点的 form 元素。
 * @param {string} html 支付渠道返回的表单 HTML
 * @returns {HTMLFormElement}
 */
export function buildPayForm(html) {
  if (typeof html !== 'string' || !html.trim()) {
    throw new Error('支付表单为空')
  }
  // DOMParser 生成的是惰性文档：其中的 <script> 不执行、图片不加载，
  // 与 innerHTML 不同，解析阶段本身无副作用。
  const doc = new DOMParser().parseFromString(html, 'text/html')
  const source = doc.querySelector('form')
  if (!source) throw new Error('支付表单缺少 form 元素')

  const form = document.createElement('form')
  copyAttrs(source, form, FORM_ATTRS)
  assertSafeAction(form.getAttribute('action'))
  if (!form.getAttribute('method')) form.setAttribute('method', 'post')

  for (const node of Array.from(source.querySelectorAll('*'))) {
    const tag = node.tagName.toLowerCase()
    if (!FIELD_TAGS.has(tag)) continue
    // 只接受 form 的直接或嵌套字段，但不复制字段内部的子结构
    const field = document.createElement(tag)
    copyAttrs(node, field, FIELD_ATTRS)
    form.appendChild(field)
  }

  if (!form.querySelector('input[name], select[name], textarea[name]')) {
    throw new Error('支付表单缺少可提交字段')
  }
  return form
}

/**
 * 安全地提交支付表单：重建白名单 form → 挂载 → 提交 → 清理。
 * 返回 true 表示已触发跳转（调用方应停止后续轮询/提示）。
 */
export function submitPayForm(html) {
  const form = buildPayForm(html)
  form.style.display = 'none'
  document.body.appendChild(form)
  try {
    form.submit()
  } finally {
    // 提交后立即移除，避免页面残留与重复提交
    form.remove()
  }
  return true
}

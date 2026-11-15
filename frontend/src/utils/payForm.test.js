import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { buildPayForm, submitPayForm } from './payForm'

const ALIPAY_FORM = `
<form name="punchout_form" method="post" action="https://openapi.alipay.com/gateway.do?charset=UTF-8">
  <input type="hidden" name="biz_content" value="{&quot;out_trade_no&quot;:&quot;ORD1&quot;}">
  <input type="hidden" name="sign" value="abc123">
  <input type="submit" value="立即支付" style="display:none">
</form>
<script>window.__pwned = true</script>
`

describe('payForm', () => {
  let appended

  beforeEach(() => {
    appended = []
    const realAppend = document.body.appendChild.bind(document.body)
    document.body.appendChild = (node) => {
      appended.push(node)
      return realAppend(node)
    }
  })

  afterEach(() => {
    document.body.innerHTML = ''
    delete window.__pwned
  })

  it('保留合法支付表单的 action/method 与字段', () => {
    const form = buildPayForm(ALIPAY_FORM)
    expect(form.tagName).toBe('FORM')
    expect(form.getAttribute('method')).toBe('post')
    expect(form.getAttribute('action')).toContain('openapi.alipay.com')
    const names = Array.from(form.querySelectorAll('input')).map((i) => i.getAttribute('name'))
    expect(names).toContain('biz_content')
    expect(names).toContain('sign')
  })

  it('不注入 script，也不复制事件属性', () => {
    const html = `<form action="https://pay.example.com/pay" method="post">
        <input name="a" value="1" onclick="window.__pwned=1">
        <img src=x onerror="window.__pwned=1">
        <script>window.__pwned=true</script>
      </form>`
    const form = buildPayForm(html)
    expect(form.querySelector('script')).toBeNull()
    expect(form.querySelector('img')).toBeNull()
    const input = form.querySelector('input[name="a"]')
    expect(input.hasAttribute('onclick')).toBe(false)
  })

  it('拒绝 javascript: 等危险 action', () => {
    expect(() => buildPayForm('<form action="javascript:alert(1)"><input name="a" value="1"></form>'))
      .toThrow(/协议/)
  })

  it('拒绝没有 form 或没有可提交字段的输入', () => {
    expect(() => buildPayForm('<div>no form</div>')).toThrow(/form/)
    expect(() => buildPayForm('<form action="https://a.com"><span>x</span></form>')).toThrow(/字段/)
    expect(() => buildPayForm('')).toThrow(/为空/)
  })

  it('submitPayForm 挂载后立即清理，不留残留节点', () => {
    const form = document.createElement('form')
    const submitSpy = (el) => {
      el.submit = () => {}
    }
    const originalCreate = document.createElement.bind(document)
    document.createElement = (tag) => {
      const el = originalCreate(tag)
      if (tag === 'form') submitSpy(el)
      return el
    }
    try {
      expect(submitPayForm(ALIPAY_FORM)).toBe(true)
    } finally {
      document.createElement = originalCreate
    }
    expect(document.body.querySelector('form')).toBeNull()
    expect(window.__pwned).toBeUndefined()
  })
})

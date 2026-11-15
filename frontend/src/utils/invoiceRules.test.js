import { describe, it, expect } from 'vitest'
import {
  validateInvoiceRequest,
  INVOICE_TITLE_MAX, INVOICE_TAX_NO_MIN, INVOICE_TAX_NO_MAX,
  INVOICE_CATEGORY_MAX, INVOICE_DETAIL_MAX,
  ALLOWED_INVOICE_TITLE_TYPES, ALLOWED_INVOICE_CONTENT_TYPES
} from './validationRules'

// P1-5 发票需求校验（与后端 OrderService.applyInvoiceSnapshot / CheckoutDTO.InvoiceRequest 同契约）
const validCompany = {
  needed: true,
  titleType: 'company',
  title: '测试科技有限公司',
  taxNo: '91330100MA2XYZ1234',
  contentType: 'category',
  category: '工业设备',
  detail: ''
}

describe('validateInvoiceRequest', () => {
  it('passes a complete company invoice request', () => {
    const r = validateInvoiceRequest(validCompany)
    expect(r.ok).toBe(true)
  })

  it('passes when invoice is not requested', () => {
    expect(validateInvoiceRequest(null).ok).toBe(true)
    expect(validateInvoiceRequest({ ...validCompany, needed: false }).ok).toBe(true)
  })

  it('rejects invalid title type', () => {
    const r = validateInvoiceRequest({ ...validCompany, titleType: 'government' })
    expect(r.ok).toBe(false)
    expect(r.msg).toContain('抬头类型')
  })

  it('rejects empty or oversized title', () => {
    expect(validateInvoiceRequest({ ...validCompany, title: ' ' }).msg).toContain('发票抬头不能为空')
    expect(validateInvoiceRequest({ ...validCompany, title: 'A'.repeat(INVOICE_TITLE_MAX + 1) }).msg).toContain('发票抬头')
  })

  it('requires taxNo for company title', () => {
    const r = validateInvoiceRequest({ ...validCompany, taxNo: '' })
    expect(r.ok).toBe(false)
    expect(r.msg).toContain('纳税人识别号')
  })

  it('rejects taxNo with lowercase letters', () => {
    const r = validateInvoiceRequest({ ...validCompany, taxNo: '91330100ma2xyz1234' })
    expect(r.ok).toBe(false)
    expect(r.msg).toContain('纳税人识别号')
  })

  it(`bounds taxNo length between ${INVOICE_TAX_NO_MIN} and ${INVOICE_TAX_NO_MAX}`, () => {
    expect(validateInvoiceRequest({ ...validCompany, taxNo: '91330100MA2XYZ' }).ok).toBe(false) // 14 位
    expect(validateInvoiceRequest({ ...validCompany, taxNo: '91330100MA2XYZ1234567' }).ok).toBe(false) // 21 位
    expect(validateInvoiceRequest({ ...validCompany, taxNo: 'ABCDEFGHIJKLMNO' }).ok).toBe(true) // 15 位
    expect(validateInvoiceRequest({ ...validCompany, taxNo: 'ABCDEFGHIJKLMNOPQRST' }).ok).toBe(true) // 20 位
  })

  it('rejects invalid content type', () => {
    const r = validateInvoiceRequest({ ...validCompany, contentType: 'custom' })
    expect(r.ok).toBe(false)
    expect(r.msg).toContain('发票内容')
  })

  it('requires category when contentType=category', () => {
    expect(validateInvoiceRequest({ ...validCompany, category: ' ' }).msg).toContain('商品类别')
    expect(validateInvoiceRequest({ ...validCompany, category: 'A'.repeat(INVOICE_CATEGORY_MAX + 1) }).msg).toContain('商品类别')
  })

  it('requires detail when contentType=detail', () => {
    const r = validateInvoiceRequest({ ...validCompany, contentType: 'detail', category: '', detail: '数控机床配件一批' })
    expect(r.ok).toBe(true)
    expect(validateInvoiceRequest({ ...validCompany, contentType: 'detail', category: '', detail: '' }).msg).toContain('开具明细')
    expect(validateInvoiceRequest({
      ...validCompany, contentType: 'detail', category: '', detail: 'A'.repeat(INVOICE_DETAIL_MAX + 1)
    }).msg).toContain('开具明细')
  })

  it('mirrors backend contract sets', () => {
    // 与后端 ValidationRules 白名单同口径（契约镜像断言，一侧改动即两侧测试报警）
    expect(ALLOWED_INVOICE_TITLE_TYPES).toEqual(['personal', 'company'])
    expect(ALLOWED_INVOICE_CONTENT_TYPES).toEqual(['category', 'detail'])
  })
})

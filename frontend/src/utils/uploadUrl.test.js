import { describe, expect, it } from 'vitest'
import { fixMerchantUploadUrls, normalizeUploadUrl } from './uploadUrl'

describe('normalizeUploadUrl', () => {
  it('为缺失 context-path 的上传地址补齐 /api 前缀', () => {
    expect(normalizeUploadUrl('/uploads/license.jpg')).toBe('/api/uploads/license.jpg')
  })

  it('不会重复添加 /api 前缀', () => {
    expect(normalizeUploadUrl('/api/uploads/license.jpg')).toBe('/api/uploads/license.jpg')
  })

  it('同时归一 JSON 数组字符串中的每一个地址', () => {
    expect(normalizeUploadUrl('["/uploads/a.jpg","/uploads/b.jpg"]'))
      .toBe('["/api/uploads/a.jpg","/api/uploads/b.jpg"]')
    expect(normalizeUploadUrl('["/uploads/a.jpg","/api/uploads/b.jpg"]'))
      .toBe('["/api/uploads/a.jpg","/api/uploads/b.jpg"]')
  })

  it('同时归一逗号分隔串中的每一个地址（Merchant.taxProofUrls 实际存储格式）', () => {
    expect(normalizeUploadUrl('/uploads/a.jpg,/uploads/b.jpg'))
      .toBe('/api/uploads/a.jpg,/api/uploads/b.jpg')
    expect(normalizeUploadUrl('/uploads/a.jpg, /uploads/b.jpg'))
      .toBe('/api/uploads/a.jpg, /api/uploads/b.jpg')
    expect(normalizeUploadUrl('/uploads/a.jpg,/api/uploads/b.jpg'))
      .toBe('/api/uploads/a.jpg,/api/uploads/b.jpg')
  })

  it('空值与非字符串原样返回', () => {
    expect(normalizeUploadUrl(null)).toBeNull()
    expect(normalizeUploadUrl(undefined)).toBeUndefined()
    expect(normalizeUploadUrl('')).toBe('')
    expect(normalizeUploadUrl('   ')).toBe('   ')
    expect(normalizeUploadUrl(123)).toBe(123)
  })

  it('保留查询参数与 hash', () => {
    expect(normalizeUploadUrl('/uploads/a.jpg?download=1#top'))
      .toBe('/api/uploads/a.jpg?download=1#top')
  })

  it('外域绝对地址、无前导斜杠路径与大小写异常均保持原样', () => {
    expect(normalizeUploadUrl('https://cdn.example.com/uploads/a.jpg'))
      .toBe('https://cdn.example.com/uploads/a.jpg')
    expect(normalizeUploadUrl('uploads/a.jpg')).toBe('uploads/a.jpg')
    expect(normalizeUploadUrl('/UPLOADS/a.jpg')).toBe('/UPLOADS/a.jpg')
  })

  it('归一 JSON 中被反斜杠转义的 \\/uploads\\/ 形态', () => {
    expect(normalizeUploadUrl('["\\/uploads\\/a.jpg","\\/uploads\\/b.jpg"]'))
      .toBe('["\\/api\\/uploads\\/a.jpg","\\/api\\/uploads\\/b.jpg"]')
  })

  it('归一数组入参并返回新数组', () => {
    const input = ['/uploads/a.jpg', '/api/uploads/b.jpg', null]
    const output = normalizeUploadUrl(input)

    expect(output).toEqual(['/api/uploads/a.jpg', '/api/uploads/b.jpg', null])
    expect(output).not.toBe(input)
    expect(input[0]).toBe('/uploads/a.jpg')
  })

  it('支持逗号、单引号与空白等 JSON 分隔符边界', () => {
    expect(normalizeUploadUrl("['/uploads/a.jpg', '/uploads/b.jpg']"))
      .toBe("['/api/uploads/a.jpg', '/api/uploads/b.jpg']")
    expect(normalizeUploadUrl(' /uploads/a.jpg')).toBe(' /api/uploads/a.jpg')
  })

  it('左括号包裹的文本与外域 URL 查询参数不被误改', () => {
    expect(normalizeUploadUrl('(/uploads/a.jpg)')).toBe('(/uploads/a.jpg)')
    expect(normalizeUploadUrl('说明:(/uploads/a.jpg)')).toBe('说明:(/uploads/a.jpg)')
    expect(normalizeUploadUrl('https://cdn.example.com/download?file=(/uploads/a.jpg)'))
      .toBe('https://cdn.example.com/download?file=(/uploads/a.jpg)')
  })

  it('混合 JSON 中仅归一内部相对地址，外域地址不动', () => {
    expect(normalizeUploadUrl('["https://cdn.example.com/uploads/a.jpg","/uploads/b.jpg"]'))
      .toBe('["https://cdn.example.com/uploads/a.jpg","/api/uploads/b.jpg"]')
  })
})

describe('fixMerchantUploadUrls', () => {
  it('返回新对象且不修改原始商户数据', () => {
    const original = {
      merId: 1,
      licenseUrl: '/uploads/license.jpg',
      specialPermitUrl: '/uploads/permit.jpg',
      taxProofUrls: '["/uploads/t1.jpg","/uploads/t2.jpg"]'
    }
    const snapshot = JSON.parse(JSON.stringify(original))

    const fixed = fixMerchantUploadUrls(original)

    expect(fixed).not.toBe(original)
    expect(fixed.licenseUrl).toBe('/api/uploads/license.jpg')
    expect(fixed.specialPermitUrl).toBe('/api/uploads/permit.jpg')
    expect(fixed.taxProofUrls).toBe('["/api/uploads/t1.jpg","/api/uploads/t2.jpg"]')
    expect(original).toEqual(snapshot)
  })

  it('处理 { merchant } 包装形态时同时创建新的顶层与嵌套对象', () => {
    const original = {
      merchant: { licenseUrl: '/uploads/license.jpg' },
      extra: 'keep'
    }

    const fixed = fixMerchantUploadUrls(original)

    expect(fixed).not.toBe(original)
    expect(fixed.merchant).not.toBe(original.merchant)
    expect(fixed.merchant.licenseUrl).toBe('/api/uploads/license.jpg')
    expect(fixed.extra).toBe('keep')
    expect(original.merchant.licenseUrl).toBe('/uploads/license.jpg')
  })

  it('只归一存在的字段，不凭空添加 undefined 属性', () => {
    const fixed = fixMerchantUploadUrls({ merId: 2, enterpriseName: '示例企业' })

    expect('licenseUrl' in fixed).toBe(false)
    expect('specialPermitUrl' in fixed).toBe(false)
    expect('taxProofUrls' in fixed).toBe(false)
    expect(fixed.merId).toBe(2)
  })

  it('非对象入参安全返回', () => {
    expect(fixMerchantUploadUrls(null)).toBeNull()
    expect(fixMerchantUploadUrls(undefined)).toBeUndefined()
    expect(fixMerchantUploadUrls('merchant')).toBe('merchant')
  })

  it('数组入参原样返回，不会被展开成对象', () => {
    const rows = [{ licenseUrl: '/uploads/a.jpg' }]
    expect(fixMerchantUploadUrls(rows)).toBe(rows)
  })

  it('merchant 为 null 或数组时不按包装对象处理', () => {
    const nullMerchant = { licenseUrl: '/uploads/a.jpg', merchant: null }
    const fixedNull = fixMerchantUploadUrls(nullMerchant)
    expect(fixedNull).not.toBe(nullMerchant)
    expect(fixedNull.merchant).toBeNull()
    expect(fixedNull.licenseUrl).toBe('/api/uploads/a.jpg')

    const merchantList = []
    const wrappedList = { licenseUrl: '/uploads/a.jpg', merchant: merchantList }
    const fixedList = fixMerchantUploadUrls(wrappedList)
    expect(fixedList.merchant).toBe(merchantList)
    expect(fixedList.licenseUrl).toBe('/api/uploads/a.jpg')
  })

  it('冻结对象不会被修改且仍能返回归一结果', () => {
    const original = Object.freeze({
      licenseUrl: '/uploads/a.jpg',
      merchant: Object.freeze({ specialPermitUrl: '/uploads/permit.jpg' })
    })

    const fixed = fixMerchantUploadUrls(original)

    expect(fixed.licenseUrl).toBe('/api/uploads/a.jpg')
    expect(fixed.merchant.specialPermitUrl).toBe('/api/uploads/permit.jpg')
    expect(original.licenseUrl).toBe('/uploads/a.jpg')
    expect(original.merchant.specialPermitUrl).toBe('/uploads/permit.jpg')
  })

  it('taxProofUrls 为真实数组时归一并返回新数组', () => {
    const original = {
      taxProofUrls: ['/uploads/a.jpg', '/api/uploads/b.jpg']
    }

    const fixed = fixMerchantUploadUrls(original)

    expect(fixed.taxProofUrls).toEqual(['/api/uploads/a.jpg', '/api/uploads/b.jpg'])
    expect(fixed.taxProofUrls).not.toBe(original.taxProofUrls)
    expect(original.taxProofUrls[0]).toBe('/uploads/a.jpg')
  })

  it('多次调用结果稳定且不会二次添加 /api', () => {
    const once = fixMerchantUploadUrls({
      licenseUrl: '/uploads/a.jpg',
      taxProofUrls: '["/uploads/t1.jpg"]'
    })
    const twice = fixMerchantUploadUrls(once)

    expect(twice).not.toBe(once)
    expect(twice).toEqual(once)
    expect(twice.licenseUrl).toBe('/api/uploads/a.jpg')
    expect(twice.taxProofUrls).toBe('["/api/uploads/t1.jpg"]')
  })

  it('保留未涉及的字段与原嵌套对象引用', () => {
    const meta = { createdBy: 'seed' }
    const fixed = fixMerchantUploadUrls({ licenseUrl: '/uploads/a.jpg', meta })

    expect(fixed.meta).toBe(meta)
    expect(fixed.meta).toEqual({ createdBy: 'seed' })
  })
})

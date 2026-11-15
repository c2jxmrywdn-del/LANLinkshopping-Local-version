// 前后端同步校验规则常量（前端镜像）。
//
// 需求 3：本文件与后端 `backend/src/main/java/com/lanlink/shopping/common/ValidationRules.java`
// 一一对应，是同一份契约的前端镜像。任何一侧修改规则必须同步修改另一侧与
// `docs/数据校验规则契约.md`，并在篡改攻击测试中补充回归用例。
//
// 敏感字段分级：
//   L1 服务端权威重算（禁止信任前端）：价格、订单金额、运费、积分抵扣金额
//   L2 状态机原子流转（禁止前端指定目标状态）：支付状态、退款状态、订单状态
//   L3 形式校验（前后端同规则双重校验）：收货人、电话、地址、备注、支付方式

// ===== L3 字段长度（与后端 @Size 同口径） =====
export const RECEIVER_MAX = 32
export const PHONE_MAX = 20
export const ADDRESS_MAX = 255
export const REMARK_MAX = 255
export const REFUND_REASON_MAX = 255

// ===== 退款凭证证据（与后端 ValidationRules / RefundEvidenceValidator 同口径） =====
export const REFUND_EVIDENCE_MAX_COUNT = 9
export const REFUND_EVIDENCE_NAME_MAX = 200
export const REFUND_EVIDENCE_URL_MAX = 300
// 后端字面量唯一持有方是 common/storage（UploadPathGuardrailTest 门禁），此处为契约镜像值
export const REFUND_EVIDENCE_URL_PREFIX = '/api/uploads/'
export const ALLOWED_REFUND_EVIDENCE_TYPES = ['image', 'video', 'file']
export const ALLOWED_REFUND_EVIDENCE_EXT = {
  image: ['jpg', 'png', 'webp', 'gif'],
  video: ['mp4', 'mov'],
  file: ['pdf', 'txt', 'csv', 'bin'],
}

// ===== 支付方式白名单（与后端 ALLOWED_PAY_TYPES 一致） =====
export const ALLOWED_PAY_TYPES = ['balance', 'corporate', 'term', 'alipay', 'wechat', 'stripe', 'apple_pay', 'google_pay']

// ===== 数值边界 =====
export const MAX_CART_ITEMS = 200
export const MAX_ITEM_QUANTITY = 9999
export const MIN_USE_POINTS = 0

// ===== 幂等键 =====
export const IDEMPOTENT_KEY_MAX = 64

// ===== 订单开票（P1-5，与后端 ValidationRules 同口径） =====
export const INVOICE_TYPE_PERSONAL = 'personal'
export const INVOICE_TYPE_COMPANY = 'company'
export const ALLOWED_INVOICE_TITLE_TYPES = [INVOICE_TYPE_PERSONAL, INVOICE_TYPE_COMPANY]
export const INVOICE_CONTENT_CATEGORY = 'category'
export const INVOICE_CONTENT_DETAIL = 'detail'
export const ALLOWED_INVOICE_CONTENT_TYPES = [INVOICE_CONTENT_CATEGORY, INVOICE_CONTENT_DETAIL]
export const INVOICE_TITLE_MAX = 100
export const INVOICE_TAX_NO_MIN = 15
export const INVOICE_TAX_NO_MAX = 20
export const INVOICE_CATEGORY_MAX = 60
export const INVOICE_DETAIL_MAX = 255

/**
 * 发票需求表单校验（P1-5，与后端 OrderService.applyInvoiceSnapshot 同规则双重校验）。
 * @param {{needed:boolean, titleType:string, title:string, taxNo:string, contentType:string, category:string, detail:string}} inv
 * @returns {{ok:boolean, msg:string}}
 */
export function validateInvoiceRequest(inv) {
  if (!inv || !inv.needed) return { ok: true, msg: '' }
  if (!ALLOWED_INVOICE_TITLE_TYPES.includes(inv.titleType)) return { ok: false, msg: '请选择发票抬头类型' }
  const title = (inv.title || '').trim()
  if (!title) return { ok: false, msg: '发票抬头不能为空' }
  if (title.length > INVOICE_TITLE_MAX) return { ok: false, msg: `发票抬头最长 ${INVOICE_TITLE_MAX} 字` }
  if (inv.titleType === INVOICE_TYPE_COMPANY) {
    const taxNo = (inv.taxNo || '').trim()
    if (!taxNo) return { ok: false, msg: '单位抬头必须填写纳税人识别号' }
    if (!/^[A-Z0-9]{15,20}$/.test(taxNo)) {
      return { ok: false, msg: `纳税人识别号须为 ${INVOICE_TAX_NO_MIN}-${INVOICE_TAX_NO_MAX} 位大写字母或数字` }
    }
  }
  if (!ALLOWED_INVOICE_CONTENT_TYPES.includes(inv.contentType)) return { ok: false, msg: '请选择发票内容类型' }
  if (inv.contentType === INVOICE_CONTENT_CATEGORY) {
    const category = (inv.category || '').trim()
    if (!category) return { ok: false, msg: '商品类别不能为空' }
    if (category.length > INVOICE_CATEGORY_MAX) return { ok: false, msg: `商品类别最长 ${INVOICE_CATEGORY_MAX} 字` }
  } else {
    const detail = (inv.detail || '').trim()
    if (!detail) return { ok: false, msg: '开具明细不能为空' }
    if (detail.length > INVOICE_DETAIL_MAX) return { ok: false, msg: `开具明细最长 ${INVOICE_DETAIL_MAX} 字` }
  }
  return { ok: true, msg: '' }
}

/**
 * 结算表单提交前校验（L3 形式校验，与后端 CheckoutDTO 注解同规则）。
 * 注意：金额/价格为 L1 字段，前端不校验、不传值，由服务端权威重算。
 * @param {{receiver:string, phone:string, address:string, remark:string, payType:string}} form
 * @returns {{ok:boolean, msg:string}}
 */
export function validateCheckoutForm(form) {
  const receiver = (form.receiver || '').trim()
  const phone = (form.phone || '').trim()
  const address = (form.address || '').trim()
  const remark = form.remark || ''
  if (!receiver) return { ok: false, msg: '收货人不能为空' }
  if (receiver.length > RECEIVER_MAX) return { ok: false, msg: `收货人最长 ${RECEIVER_MAX} 字` }
  if (!phone) return { ok: false, msg: '联系电话不能为空' }
  if (phone.length > PHONE_MAX) return { ok: false, msg: `联系电话最长 ${PHONE_MAX} 字` }
  if (!address) return { ok: false, msg: '收货地址不能为空' }
  if (address.length > ADDRESS_MAX) return { ok: false, msg: `收货地址最长 ${ADDRESS_MAX} 字` }
  if (remark.length > REMARK_MAX) return { ok: false, msg: `备注最长 ${REMARK_MAX} 字` }
  if (!ALLOWED_PAY_TYPES.includes(form.payType)) return { ok: false, msg: '支付方式不合法' }
  return { ok: true, msg: '' }
}

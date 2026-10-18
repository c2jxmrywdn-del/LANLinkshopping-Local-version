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

// ===== 支付方式白名单（与后端 ALLOWED_PAY_TYPES 一致） =====
export const ALLOWED_PAY_TYPES = ['balance', 'corporate', 'term']

// ===== 数值边界 =====
export const MAX_CART_ITEMS = 200
export const MAX_ITEM_QUANTITY = 9999
export const MIN_USE_POINTS = 0

// ===== 幂等键 =====
export const IDEMPOTENT_KEY_MAX = 64

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

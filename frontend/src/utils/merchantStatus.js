/**
 * 商户中心状态字典：订单/商品/运单状态文案与配色，供商户中心各页面共用。
 * 口径与买家侧 MyOrders.vue / OrderDetail.vue 保持一致。
 */

/** 订单履约状态 t_order.order_status */
export const ORDER_STATUS = {
  0: { text: '待发货', color: 'orange' },
  1: { text: '已发货', color: 'blue' },
  2: { text: '已完成', color: 'green' },
  3: { text: '已取消', color: 'default' },
  4: { text: '已退款', color: 'default' }
}

/** 支付状态 t_order.pay_status */
export const PAY_STATUS = {
  0: { text: '待付款', color: 'orange' },
  1: { text: '已付款', color: 'green' },
  2: { text: '已退款', color: 'purple' }
}

/** 退款申请状态 t_order.refund_status */
export const REFUND_STATUS = {
  none: { text: '无退款', color: 'default' },
  applied: { text: '退款申请中 · 待平台处理', color: 'orange' },
  rejected: { text: '退款申请被驳回', color: 'default' },
  processing: { text: '退款处理中', color: 'blue' },
  success: { text: '已退款', color: 'purple' }
}

/** 商品审核状态 t_product.review_status */
export const REVIEW_STATUS = {
  0: { text: '待审核', color: 'orange' },
  1: { text: '审核通过', color: 'green' },
  2: { text: '审核驳回', color: 'red' }
}

/** 运单状态 t_logistics_waybill.status（与后端 LogisticsStateMachine 一致） */
export const WAYBILL_STATUS = {
  pending_ship: { text: '待揽收', color: 'orange' },
  picked_up: { text: '已揽收', color: 'blue' },
  in_transit: { text: '运输中', color: 'blue' },
  out_for_delivery: { text: '派送中', color: 'cyan' },
  delivered: { text: '已签收', color: 'green' },
  exception: { text: '异常', color: 'red' },
  returned: { text: '已退回', color: 'default' },
  lost: { text: '遗失', color: 'red' },
  cancelled: { text: '已取消', color: 'default' }
}

/** 支付渠道文案 */
export const PAY_CHANNEL = {
  wallet: '钱包',
  mock: '模拟网关',
  wechat: '微信支付',
  alipay: '支付宝'
}

/** 商户入驻审核状态 t_merchant.review_status */
export const MERCHANT_REVIEW = {
  0: { text: '审核中', color: 'orange' },
  1: { text: '已通过', color: 'green' },
  2: { text: '已驳回', color: 'red' }
}

/** 订单详情步骤条：未付 0 → 已付待发货 1 → 已发货 2 → 已完成 3；取消/退款回到 0 灰显 */
export function orderStepIndex(o) {
  if (!o) return 0
  if (o.orderStatus === 2) return 3
  if (o.orderStatus === 1) return 2
  return o.payStatus === 1 ? 1 : 0
}

/** 订单是否已实际付款（含账期单：payType=term 下单即进入履约） */
export function isPaid(o) {
  return !!o && (Number(o.payStatus) === 1 || o.payType === 'term')
}

/** 金额展示：统一两位小数 + 千分位 */
export function fmtMoney(n) {
  return Number(n || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

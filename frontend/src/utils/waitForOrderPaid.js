/**
 * 轮询服务端订单支付状态，直到确认已支付或次数耗尽。
 *
 * <p>为什么需要它：Stripe 扣款成功 ≠ 平台订单已结算。平台的唯一事实源是 webhook，
 * 而 webhook 是"尽力投递"，可能晚到几秒甚至丢失。如果前端拿到 Stripe 的
 * `succeeded` 就直接提示"支付成功"，用户回到订单列表会看到同一行仍是"未支付"，
 * 这是典型的假成功。
 *
 * <p>本工具把「等服务端确认」这件事收口到一处，避免各页面各写一套：
 * `Checkout.vue`、`MyOrders.vue`、`OrderDetail.vue` 三个支付入口共用同一语义。
 *
 * @param {string} orderNo 订单号
 * @param {(orderNo: string) => Promise<any>} queryFn 查单函数（通常是 paymentApi.query）
 * @param {{attempts?: number, intervalMs?: number}} [opts]
 * @returns {Promise<boolean>} 服务端是否已确认 payStatus=1
 */
export async function waitForOrderPaid(orderNo, queryFn, opts = {}) {
  const attempts = Number.isFinite(opts.attempts) ? opts.attempts : 6
  const intervalMs = Number.isFinite(opts.intervalMs) ? opts.intervalMs : 1200
  if (!orderNo || typeof queryFn !== 'function') return false
  for (let i = 0; i < attempts; i++) {
    try {
      const status = await queryFn(orderNo)
      if (Number(status?.payStatus) === 1) return true
    } catch (e) {
      // 网络抖动/后端重启窗口：继续下一轮，由调用方决定最终提示
    }
    if (i < attempts - 1) {
      await new Promise((resolve) => setTimeout(resolve, intervalMs))
    }
  }
  return false
}

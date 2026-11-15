import { describe, it, expect, vi } from 'vitest'
import { waitForOrderPaid } from './waitForOrderPaid'

/**
 * 支付成功判定的服务端核对。
 *
 * 缺陷形态：Stripe 扣款成功后前端直接提示"支付成功"，但平台唯一事实源是 webhook；
 * webhook 晚到或丢失时，用户看到"支付成功"却在订单列表看到"未支付"。
 */
describe('waitForOrderPaid', () => {
  it('第一次查询就已支付时立即返回 true，不做多余轮询', async () => {
    const query = vi.fn().mockResolvedValue({ payStatus: 1 })

    const ok = await waitForOrderPaid('ORD1', query, { attempts: 6, intervalMs: 1 })

    expect(ok).toBe(true)
    expect(query).toHaveBeenCalledTimes(1)
  })

  it('前几次未支付、后一次确认时返回 true（webhook 延迟到达的常见形态）', async () => {
    const query = vi.fn()
      .mockResolvedValueOnce({ payStatus: 0 })
      .mockResolvedValueOnce({ payStatus: 0 })
      .mockResolvedValueOnce({ payStatus: 1 })

    const ok = await waitForOrderPaid('ORD1', query, { attempts: 6, intervalMs: 1 })

    expect(ok).toBe(true)
    expect(query).toHaveBeenCalledTimes(3)
  })

  it('轮询耗尽仍未见已支付时返回 false，由调用方给出"同步中"提示', async () => {
    const query = vi.fn().mockResolvedValue({ payStatus: 0 })

    const ok = await waitForOrderPaid('ORD1', query, { attempts: 3, intervalMs: 1 })

    expect(ok).toBe(false)
    expect(query).toHaveBeenCalledTimes(3)
  })

  it('查询抛错不中断轮询（后端重启窗口），最终仍能确认', async () => {
    const query = vi.fn()
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce({ payStatus: 1 })

    const ok = await waitForOrderPaid('ORD1', query, { attempts: 3, intervalMs: 1 })

    expect(ok).toBe(true)
  })

  it('缺少订单号或查询函数时直接返回 false，不发起请求', async () => {
    const query = vi.fn()

    expect(await waitForOrderPaid('', query)).toBe(false)
    expect(await waitForOrderPaid('ORD1', null)).toBe(false)
    expect(query).not.toHaveBeenCalled()
  })
})

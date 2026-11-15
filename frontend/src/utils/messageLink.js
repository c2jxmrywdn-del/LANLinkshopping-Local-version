/**
 * 站内消息 → 业务落地页解析。
 *
 * <p>背景：站内消息只带 `type` + `title` + `content` + `relatedNo`（订单号/退款单号等），
 * 没有独立的详情页。用户看到「订单已完成」这类通知时，需要能一键进入
 * 订单详情（二级）、退款/物流（三级）等真实业务页面，而不是只能看到一段文字。
 *
 * <p>设计原则：
 * <ul>
 *   <li><b>纯函数</b>：不依赖组件/路由实例，便于单测与在任意容器复用；</li>
 *   <li><b>只认已知形态</b>：识别不到就返回 null，由调用方降级为纯文本，绝不拼出坏链；</li>
 *   <li><b>路径与 router 表一致</b>：订单详情 `/orders/:orderNo`，退款在订单详情页内锚点，
 *       物流在同页 tab，避免造出不存在的路由。</li>
 * </ul>
 */

/** 消息业务归类：用于选择正文渲染组件与跳转策略。 */
export const MSG_KIND = {
  ORDER: 'order',
  REFUND: 'refund',
  LOGISTICS: 'logistics',
  /** 系统通知：账号开通、安全提醒、资料完善等 → 个人中心/设置/安全页 */
  SYSTEM: 'system',
  /** 促销：活动/优惠券/大促 → 活动中心、领券中心 */
  PROMOTION: 'promotion',
  /** 价格：价格预警/行情提醒 → 价格情报台 */
  PRICE: 'price',
  OTHER: 'other'
}

/** 退款/售后相关标题关键词（含商户初审、平台打款等中间态）。 */
const REFUND_KEYWORDS = ['退款', '退货', '售后', 'RMA', 'rma', '退款单']
/** 物流相关标题关键词。 */
const LOGISTICS_KEYWORDS = ['发货', '物流', '运单', '签收', '配送', '在途', '揽收', '派送']

/**
 * 判断消息的业务类型。
 *
 * @param {{type?:string,title?:string}} msg 站内消息
 * @returns {'order'|'refund'|'logistics'|'other'}
 */
export function messageKind(msg) {
  if (!msg) return MSG_KIND.OTHER
  const title = String(msg.title || '')
  // 非订单类走各自的业务落地页，不参与订单跳转
  if (msg.type === 'system') return MSG_KIND.SYSTEM
  if (msg.type === 'promotion') return MSG_KIND.PROMOTION
  if (msg.type === 'price') return MSG_KIND.PRICE
  if (msg.type && msg.type !== 'order') return MSG_KIND.OTHER
  if (REFUND_KEYWORDS.some((k) => title.includes(k))) return MSG_KIND.REFUND
  if (LOGISTICS_KEYWORDS.some((k) => title.includes(k))) return MSG_KIND.LOGISTICS
  if (msg.type === 'order') return MSG_KIND.ORDER
  return MSG_KIND.OTHER
}

/**
 * 订单号合法性：当前订单号是纯数字（如 20261107072056914277）。
 *
 * <p>只接受 8~40 位字母数字，避免把任意 relatedNo（可能是 RMA 单号、促销 ID）
 * 误拼进 `/orders/` 造成 404。
 */
export function isOrderNo(value) {
  const s = String(value == null ? '' : value).trim()
  return /^[A-Za-z0-9]{8,40}$/.test(s)
}

/**
 * 解析消息的主跳转目标（二级页面）。
 *
 * @returns {{path:string,label:string}|null} 无法解析返回 null
 */
export function primaryLink(msg) {
  if (!msg) return null
  const no = String(msg.relatedNo || '').trim()
  const kind = messageKind(msg)
  // 系统/促销/价格通知不带订单号，按业务类型直达对应页面
  if (kind === MSG_KIND.SYSTEM) {
    // 「完善资料」类引导到设置页，其余系统通知回个人中心
    const content = String(msg.title || '') + String(msg.content || '')
    const toSettings = /资料|设置|安全|密码|认证|绑定/.test(content)
    return toSettings
      ? { path: '/me/settings', label: '前往账户设置' }
      : { path: '/me', label: '进入个人中心' }
  }
  if (kind === MSG_KIND.PROMOTION) {
    return { path: '/activity', label: '查看活动中心' }
  }
  if (kind === MSG_KIND.PRICE) {
    return { path: '/price-monitor', label: '查看价格情报' }
  }
  // 订单业务链：必须有合法订单号，否则不给入口（避免坏链）
  if (!isOrderNo(no)) return null
  if (kind === MSG_KIND.OTHER) return null
  return { path: '/orders/' + encodeURIComponent(no), label: '查看订单详情' }
}

/**
 * 解析消息的次级动作（三级页面/页内区块）。
 *
 * <p>用 query 标记落点，订单详情页据此自动切到对应 tab：
 * 退款 → `?tab=refund`；物流 → `?tab=logistics`。这样不需要新增路由，
 * 也不会出现"跳过去但用户还要自己找"的断层。
 *
 * @returns {{path:string,label:string}|null}
 */
export function secondaryLink(msg) {
  const kind = messageKind(msg)
  // 系统/促销/价格的「三级」是更具体的业务页
  if (kind === MSG_KIND.SYSTEM) {
    const content = String(msg.title || '') + String(msg.content || '')
    if (/安全|密码|两步|2FA|登录/.test(content)) {
      return { path: '/me/login-log', label: '查看登录记录' }
    }
    if (/资料|认证|绑定/.test(content)) {
      return { path: '/me/address', label: '管理收货地址' }
    }
    return null
  }
  if (kind === MSG_KIND.PROMOTION) {
    return { path: '/coupons', label: '去领券中心' }
  }
  if (kind === MSG_KIND.PRICE) {
    return { path: '/membership/points', label: '查看积分明细' }
  }

  const primary = primaryLink(msg)
  if (!primary) return null
  if (kind === MSG_KIND.REFUND) {
    return { path: primary.path + '?tab=refund', label: '查看退款进度' }
  }
  if (kind === MSG_KIND.LOGISTICS) {
    return { path: primary.path + '?tab=logistics', label: '查看物流轨迹' }
  }
  return null
}

/**
 * 消息的一句话业务摘要（正文过长时用）。
 *
 * <p>订单号在正文里重复出现（"订单 2026... 已确认收货"），
 * 卡片标题区已单独展示关联单号，正文里再重复一遍是噪声，这里做去重。
 */
export function cleanContent(msg) {
  if (!msg) return ''
  let text = String(msg.content || '').trim()
  const no = String(msg.relatedNo || '').trim()
  if (no) {
    // 去掉正文里与关联单号相同的重复订单号（保留其余语义）
    text = text.split(no).join('').replace(/\s{2,}/g, ' ').trim()
  }
  return text
}

import { defineStore } from 'pinia'
import { watch } from 'vue'
import { membershipApi, subscriptionApi } from '../api'
import { useUserStore } from './user'

// 双轨会员口径聚合（out/vip-subscription-design.md §3）：
// 轨 A 等级折扣 = /membership/my 的 level.discountRate（有效订阅+成长值双条件，无订阅回落普通会员 1.000，
//   见后端 MembershipService.levelForGrowth）；轨 B 消费身份 VIP 不在此判定，前端仅读权限位与倍数。
// 真实数据取不到（未登录/接口失败）时优雅回退 1.0：调用方据 hasDiscount 决定是否展示对比价，
// 不再出现写死的 0.95 假折扣。
const ACTIVE_STATUS = new Set(['active', 'valid', 'normal', 'running', 'paid', '1', '有效'])
const INACTIVE_STATUS = new Set(['expired', 'finished', '2', '已过期', 'cancelled', 'canceled', 'refunded', '3', '0', '已取消'])
// 状态归一沿用 MembershipCenter.normStatus 语义：空值/未知状态按有效处理
function statusActive(s) {
  if (s == null || s === '') return true
  const k = String(s).toLowerCase()
  if (ACTIVE_STATUS.has(k)) return true
  if (INACTIVE_STATUS.has(k)) return false
  return true
}

let stopWatchLogged = null

export const useMembershipStore = defineStore('membership', {
  state: () => ({ level: null, rule: null, sub: null, checked: false, inflight: null }),
  getters: {
    // 等级折扣率（0 < r ≤ 1 才可信，否则回退 1.0 = 无折扣）
    discountRate(s) {
      const r = Number(s.level?.discountRate)
      return Number.isFinite(r) && r > 0 && r <= 1 ? r : 1
    },
    // 是否存在可展示的真实折扣（对比价只在 hasDiscount 时出现）
    hasDiscount() { return this.checked && this.discountRate < 1 },
    hasActiveSub(s) { return !!s.sub && statusActive(s.sub.status) },
    vipEarnMultiplier(s) {
      const n = Number(s.rule?.vipEarnMultiplier)
      return Number.isFinite(n) && n >= 1 ? n : 1
    }
  },
  actions: {
    /**
     * 拉取一次会员口径（幂等）。未登录时跳过请求（避免访客在公共页触发 401 跳转），
     * 并挂一次性 watch：fetchMe 恢复登录态后自动补拉（MainLayout 挂载后才调 fetchMe）。
     */
    async ensure() {
      if (this.checked) return
      const user = useUserStore()
      if (!user.logged) {
        if (!stopWatchLogged) {
          stopWatchLogged = watch(() => user.logged, (v) => {
            if (v && stopWatchLogged) { stopWatchLogged(); stopWatchLogged = null; this._fetch() }
          })
        }
        return
      }
      return this._fetch()
    },
    async _fetch() {
      if (this.checked) return
      if (this.inflight) return this.inflight
      this.inflight = (async () => {
        try {
          const my = await membershipApi.my()
          this.level = my?.level || null
          this.rule = my?.rule || null
          if (my?.subscription) this.sub = my.subscription
        } catch (e) { /* 拦截器已提示；口径回退 1.0 */ }
        if (!this.sub) {
          try { this.sub = (await subscriptionApi.my()) || null } catch (e) { this.sub = null }
        }
        this.checked = true
        this.inflight = null
      })()
      return this.inflight
    },
    /** 订阅购买/取消成功后本地同步，免二次请求 */
    setSub(sub) {
      this.sub = sub || null
      this.checked = true
    }
  }
})

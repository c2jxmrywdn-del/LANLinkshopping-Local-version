import { defineStore } from 'pinia'
import { authApi, userApi } from '../api'

export const useUserStore = defineStore('user', {
  state: () => ({ user: null }),
  getters: {
    logged: (s) => !!s.user,
    // ===== 身份类型（服务端 /auth/me 实时识别返回） =====
    identity: (s) => (s.user && s.user.identity && s.user.identity.code) || 'guest',
    identityName: (s) => (s.user && s.user.identity && s.user.identity.name) || '访客',
    identityScope: (s) => (s.user && s.user.identity && s.user.identity.serviceScope) || '',
    // 是否具备指定权限（服务端权限矩阵的镜像，仅用于展示；服务端仍做强制校验）
    hasPerm: (s) => (perm) => (s.user && s.user.identity && s.user.identity.permissions || []).includes(perm),
    isVip: (s) => s.identity === 'vip',
    isMerchant: (s) => s.identity === 'merchant' || (s.user && s.user.roleCode === 'merchant'),
    isAdmin: (s) => s.identity === 'admin' || (s.user && s.user.roleCode === 'admin'),
    unread: (s) => (s.user && s.user.unreadCount) || 0
  },
  actions: {
    /**
     * 登录：密码通过后若服务端要求两步验证（require2fa），不建立会话、不写 ll_user，
     * 返回原始数据（含 loginTicket），由登录页引导输入动态码后调 login2fa。
     */
    async login(payload) {
      const data = await authApi.login(payload)
      if (data && data.require2fa) return data
      this.user = data
      localStorage.setItem('ll_user', JSON.stringify(data))
      return data
    },
    /** 两步验证登录：校验动态码成功后建立会话 */
    async login2fa(loginTicket, code) {
      this.user = await authApi.login2fa({ loginTicket, code })
      localStorage.setItem('ll_user', JSON.stringify(this.user))
    },
    async register(payload) {
      await authApi.register(payload)
    },
    async fetchMe() {
      try {
        this.user = await authApi.me()
        localStorage.setItem('ll_user', JSON.stringify(this.user))
      } catch (e) { this.user = null }
    },
    /** 写入绝对未读数（登录后由 /auth/me 带回，或读操作后本地更新） */
    setUnread(n) {
      if (this.user) {
        this.user.unreadCount = n
        localStorage.setItem('ll_user', JSON.stringify(this.user))
      }
    },
    /** 从服务端拉取最新未读数并刷新角标 */
    async refreshUnread() {
      try {
        const data = await userApi.messageUnread()
        this.setUnread(data.count || 0)
      } catch (e) { /* 静默：未读数拉取失败不影响浏览 */ }
    },
    async logout() {
      try { await authApi.logout() } catch (e) {}
      this.user = null
      localStorage.removeItem('ll_user')
    }
  }
})
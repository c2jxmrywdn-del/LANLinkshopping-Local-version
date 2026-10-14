// 个人资料 store：IndexedDB 缓存兜底 + 增量保存 + AES 加密草稿（刷新恢复未保存内容）
import { defineStore } from 'pinia'
import { userApi } from '../api'
import { getCache, setCache } from '../utils/idb'
import { secureSet, secureGet, secureDel } from '../utils/secureStore'
import { sanitizeBio } from '../utils/validators'
import { useUserStore } from './user'

const CACHE_KEY = 'profile'
const DRAFT_KEY = 'll_draft_profile'
const TTL = 24 * 60 * 60 * 1000 // 24h

export const useProfileStore = defineStore('profile', {
  state: () => ({
    profile: null,   // 服务端最新数据（修改基准）
    loaded: false,
    loadError: false // 无缓存且网络失败时展示重试
  }),
  actions: {
    /** 缓存优先渲染，后台异步拉新；无缓存且失败则标记 loadError 供页面重试 */
    async load() {
      const cached = await getCache(CACHE_KEY)
      if (cached) {
        this.profile = cached
        this.loaded = true
      }
      try {
        const data = await userApi.getProfile()
        this.profile = data
        this.loaded = true
        this.loadError = false
        setCache(CACHE_KEY, data, TTL)
      } catch (e) {
        if (!cached) this.loadError = true
      }
    },
    /**
     * 增量保存：仅提交与基准不同的字段
     * @param {object} form 编辑后的完整表单
     * @returns {object|null} 提交后的 profile；无改动返回 null
     */
    async save(form) {
      const base = this.profile || {}
      const patch = {}
      for (const k of ['realName', 'nickname', 'gender', 'birthday', 'bio']) {
        let v = form[k]
        if (k === 'bio') v = sanitizeBio(v) // 提交前内容合规预检
        if ((v ?? '') !== (base[k] ?? '')) patch[k] = v
      }
      if (Object.keys(patch).length === 0) return null
      const data = await userApi.putProfile(patch)
      this.profile = data
      setCache(CACHE_KEY, data, TTL)
      // 同步顶栏昵称
      const user = useUserStore()
      if (user.user && data.nickname) {
        user.user.nickname = data.nickname
        localStorage.setItem('ll_user', JSON.stringify(user.user))
      }
      this.clearDraft()
      return data
    },
    /** 头像上传成功后写回缓存 */
    applyAvatar(url) {
      if (this.profile) {
        this.profile.avatar = url
        setCache(CACHE_KEY, this.profile, TTL)
      }
    },
    /** 换绑手机号/邮箱成功后写回 */
    applyContact(data) {
      if (data) {
        this.profile = data
        setCache(CACHE_KEY, data, TTL)
      }
    },
    // ===== 草稿（AES 加密写 localStorage） =====
    saveDraft(form) { return secureSet(DRAFT_KEY, form) },
    loadDraft() { return secureGet(DRAFT_KEY) },
    clearDraft() { secureDel(DRAFT_KEY) },
    clear() {
      this.profile = null
      this.loaded = false
      this.loadError = false
    }
  }
})

import { getCache, setCache, idbDel } from './idb'

/**
 * 全局预加载与双级缓存管理器（PreloadManager）
 * 具备 L1 内存 LRU 缓存 + L2 IndexedDB 持久化 + In-Flight 飞行请求去重合并 + SWR 后台静默刷新机制。
 */
export class PreloadManager {
  constructor(options = {}) {
    this.maxMemoryItems = options.maxEntries || 100
    this.defaultTtl = options.defaultTtl || 60 * 1000
    this.defaultStaleTtl = options.defaultStaleTtl || 5 * 60 * 1000
    // L1 高速内存缓存: Map<key, { data, timestamp, ttl, staleTtl }>
    this.memoryCache = new Map()
    // 飞行请求去重池: Map<key, Promise<any>>
    this.inFlightRequests = new Map()
    // 缓存失效标签索引: Map<tag, Set<cacheKey>>
    this.tagIndex = new Map()
  }

  /**
   * 检查紧急逃生停用开关
   */
  isDisabled() {
    if (typeof window === 'undefined') return false
    if (window.__LL_DISABLE_PRELOAD === true) return true
    try {
      return localStorage.getItem('ll_perf_opt_out') === '1'
    } catch {
      return false
    }
  }

  /**
   * 生成规范化缓存键
   */
  generateKey(url, params = {}) {
    if (!params || Object.keys(params).length === 0) return url
    const query = Object.keys(params)
      .sort()
      .map(k => `${k}=${encodeURIComponent(params[k] === undefined ? '' : params[k])}`)
      .join('&')
    return query ? `${url}?${query}` : url
  }

  /**
   * 关联缓存键与失效标签
   */
  associateTags(key, tags = []) {
    if (!tags || !tags.length) return
    for (const tag of tags) {
      if (!this.tagIndex.has(tag)) {
        this.tagIndex.set(tag, new Set())
      }
      this.tagIndex.get(tag).add(key)
    }
  }

  /**
   * 按标签批量失效缓存（L1 内存与 L2 存储同步清除）
   */
  async invalidateTag(tag) {
    if (!this.tagIndex.has(tag)) return
    const keys = Array.from(this.tagIndex.get(tag))
    for (const key of keys) {
      this.memoryCache.delete(key)
      if (typeof window !== 'undefined' && window.indexedDB) {
        await idbDel(key).catch(() => {})
      }
    }
    this.tagIndex.delete(tag)
  }

  /**
   * 单键失效（L1 内存与 L2 存储同步清除）
   */
  async invalidate(key) {
    this.memoryCache.delete(key)
    if (typeof window !== 'undefined' && window.indexedDB) {
      await idbDel(key).catch(() => {})
    }
  }

  /**
   * 写入 L1 内存缓存并维护 LRU 容量上限
   */
  setMemoryCache(key, entry) {
    if (this.memoryCache.has(key)) {
      this.memoryCache.delete(key)
    } else if (this.memoryCache.size >= this.maxMemoryItems) {
      const oldestKey = this.memoryCache.keys().next().value
      if (oldestKey) this.memoryCache.delete(oldestKey)
    }
    this.memoryCache.set(key, entry)
  }

  /**
   * 获取当前缓存统计信息
   */
  getStats() {
    return {
      size: this.memoryCache.size,
      inFlight: this.inFlightRequests.size,
      tags: this.tagIndex.size
    }
  }

  /**
   * 执行带缓存、去重与 SWR 机制的数据获取
   * @param {string} key 缓存唯一标识
   * @param {Function} fetcher 异步数据获取函数
   * @param {Object} options 配置项
   * @param {number} options.ttl 强保鲜期(ms)，在此期间直接命中返回，0网络请求
   * @param {number} options.staleTtl 弱保鲜期(ms)，在此期间立即返回旧缓存并在后台静默更新
   * @param {boolean} options.persist 是否写入 L2 IndexedDB 持久化
   * @param {string[]} options.tags 绑定的失效标签列表
   */
  async execute(key, fetcher, {
    ttl = this.defaultTtl,
    staleTtl = this.defaultStaleTtl,
    persist = false,
    tags = []
  } = {}) {
    if (this.isDisabled()) {
      return fetcher()
    }

    const now = Date.now()
    this.associateTags(key, tags)

    // 1. 检查 L1 内存缓存
    const mem = this.memoryCache.get(key)
    if (mem) {
      const age = now - mem.timestamp
      if (age < mem.ttl) {
        return mem.data
      }
      if (age < mem.staleTtl) {
        // SWR 触发：立即返回旧数据，后台异步重放更新
        this.revalidateInBackground(key, fetcher, { ttl, staleTtl, persist })
        return mem.data
      }
    }

    // 2. 检查 L2 IndexedDB 持久化存储
    if (persist && !mem && typeof window !== 'undefined' && window.indexedDB) {
      try {
        const stored = await getCache(key)
        if (stored && stored.__preloadedAt) {
          const age = now - stored.__preloadedAt
          const storedTtl = stored.__ttl || ttl
          const storedStale = stored.__staleTtl || staleTtl
          if (age < storedTtl) {
            this.setMemoryCache(key, {
              data: stored.data,
              timestamp: stored.__preloadedAt,
              ttl: storedTtl,
              staleTtl: storedStale
            })
            return stored.data
          }
          if (age < storedStale) {
            this.setMemoryCache(key, {
              data: stored.data,
              timestamp: stored.__preloadedAt,
              ttl: storedTtl,
              staleTtl: storedStale
            })
            this.revalidateInBackground(key, fetcher, { ttl, staleTtl, persist })
            return stored.data
          }
        }
      } catch {
        // IndexedDB 异常降级至网络
      }
    }

    // 3. 缓存未命中或彻底过期：走 In-Flight 飞行请求去重合并
    return this.fetchWithDeduplication(key, fetcher, { ttl, staleTtl, persist })
  }

  /**
   * In-Flight 并发请求去重合并
   */
  fetchWithDeduplication(key, fetcher, { ttl, staleTtl, persist }) {
    if (this.inFlightRequests.has(key)) {
      return this.inFlightRequests.get(key)
    }

    const promise = (async () => {
      try {
        const data = await fetcher()
        const now = Date.now()
        this.setMemoryCache(key, { data, timestamp: now, ttl, staleTtl })
        if (persist && typeof window !== 'undefined' && window.indexedDB) {
          setCache(key, { data, __preloadedAt: now, __ttl: ttl, __staleTtl: staleTtl }, staleTtl).catch(() => {})
        }
        return data
      } finally {
        this.inFlightRequests.delete(key)
      }
    })()

    this.inFlightRequests.set(key, promise)
    return promise
  }

  /**
   * SWR 后台静默刷新（不抛出错误，静默兜底）
   */
  async revalidateInBackground(key, fetcher, options) {
    if (this.inFlightRequests.has(key)) return
    try {
      await this.fetchWithDeduplication(key, fetcher, options)
    } catch {
      // 静默处理，保留现有缓存
    }
  }

  /**
   * 清空所有缓存与飞行请求
   */
  clear() {
    this.memoryCache.clear()
    this.inFlightRequests.clear()
    this.tagIndex.clear()
  }
}

export const preloadManager = new PreloadManager()

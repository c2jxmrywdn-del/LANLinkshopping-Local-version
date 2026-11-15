import { describe, it, expect, beforeEach, vi } from 'vitest'
import { PreloadManager, preloadManager } from './preloadManager'

describe('PreloadManager', () => {
  let manager

  beforeEach(() => {
    delete window.__LL_DISABLE_PRELOAD
    manager = new PreloadManager({ maxEntries: 3, defaultTtl: 500, defaultStaleTtl: 2000 })
  })

  it('L1 Cache: returns cached result without re-invoking loaderFn within TTL', async () => {
    let callCount = 0
    const loader = async () => {
      callCount++
      return { id: 1, name: 'Industry-A' }
    }

    const first = await manager.execute('/test/data', loader)
    const second = await manager.execute('/test/data', loader)

    expect(first).toEqual({ id: 1, name: 'Industry-A' })
    expect(second).toEqual({ id: 1, name: 'Industry-A' })
    expect(callCount).toBe(1)
  })

  it('In-Flight Deduplication: merges concurrent identical requests into one promise', async () => {
    let callCount = 0
    const slowLoader = () => new Promise((resolve) => {
      callCount++
      setTimeout(() => resolve('slow-response'), 50)
    })

    const [res1, res2, res3] = await Promise.all([
      manager.execute('/concurrent/key', slowLoader),
      manager.execute('/concurrent/key', slowLoader),
      manager.execute('/concurrent/key', slowLoader)
    ])

    expect(res1).toBe('slow-response')
    expect(res2).toBe('slow-response')
    expect(res3).toBe('slow-response')
    expect(callCount).toBe(1)
  })

  it('Tag Invalidation: invalidates matching entries by tag', async () => {
    let count1 = 0
    let count2 = 0
    const loader1 = async () => (++count1, 'val-1')
    const loader2 = async () => (++count2, 'val-2')

    await manager.execute('/api/dict1', loader1, { tags: ['dict', 'system'] })
    await manager.execute('/api/dict2', loader2, { tags: ['dict', 'user'] })

    expect(count1).toBe(1)
    expect(count2).toBe(1)

    manager.invalidateTag('system')

    await manager.execute('/api/dict1', loader1, { tags: ['dict', 'system'] })
    await manager.execute('/api/dict2', loader2, { tags: ['dict', 'user'] })

    expect(count1).toBe(2)
    expect(count2).toBe(1)
  })

  it('Key Invalidation & Clear: removes specific key or entire cache', async () => {
    let count = 0
    const loader = async () => (++count, 'val')

    await manager.execute('/api/key1', loader)
    await manager.execute('/api/key2', loader)

    manager.invalidate('/api/key1')
    await manager.execute('/api/key1', loader)
    expect(count).toBe(3)

    manager.clear()
    await manager.execute('/api/key2', loader)
    expect(count).toBe(4)
  })

  it('Emergency Escape Switch: window.__LL_DISABLE_PRELOAD bypasses caching completely', async () => {
    window.__LL_DISABLE_PRELOAD = true

    let count = 0
    const loader = async () => (++count, 'bypassed')

    const r1 = await manager.execute('/escape/test', loader)
    const r2 = await manager.execute('/escape/test', loader)

    expect(r1).toBe('bypassed')
    expect(r2).toBe('bypassed')
    expect(count).toBe(2)
  })

  it('LRU Eviction: evicts oldest key when maxEntries is exceeded', async () => {
    const l1 = async () => 'v1'
    const l2 = async () => 'v2'
    const l3 = async () => 'v3'
    const l4 = async () => 'v4'

    await manager.execute('/k1', l1)
    await manager.execute('/k2', l2)
    await manager.execute('/k3', l3)
    // maxEntries is 3. Adding 4th should evict /k1
    await manager.execute('/k4', l4)

    expect(manager.getStats().size).toBe(3)
  })

  it('Singleton preloadManager is properly initialized', () => {
    expect(preloadManager).toBeDefined()
    expect(typeof preloadManager.execute).toBe('function')
  })
})

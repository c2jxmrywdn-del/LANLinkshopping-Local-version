import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'

vi.mock('../api', () => ({
  homeApi: {
    industries: vi.fn().mockResolvedValue([{ id: 1, name: 'Industrial' }]),
    categories: vi.fn().mockResolvedValue([{ id: 10, name: 'Steel' }])
  }
}))

import { canPrefetch, prefetchRoute, prefetchedComponents, ROUTE_COMPONENT_LOADERS } from './routePrefetcher'
import { homeApi } from '../api'

describe('routePrefetcher', () => {
  const originalConnection = navigator.connection

  beforeEach(() => {
    delete window.__LL_DISABLE_PRELOAD
    prefetchedComponents.clear()
    vi.clearAllMocks()
  })

  afterEach(() => {
    Object.defineProperty(navigator, 'connection', {
      value: originalConnection,
      writable: true,
      configurable: true
    })
  })

  describe('canPrefetch', () => {
    it('returns true under normal conditions', () => {
      expect(canPrefetch()).toBe(true)
    })

    it('returns false when window.__LL_DISABLE_PRELOAD is true', () => {
      window.__LL_DISABLE_PRELOAD = true
      expect(canPrefetch()).toBe(false)
    })

    it('returns false when connection has saveData enabled', () => {
      Object.defineProperty(navigator, 'connection', {
        value: { saveData: true, effectiveType: '4g' },
        writable: true,
        configurable: true
      })
      expect(canPrefetch()).toBe(false)
    })

    it('returns false on 2g or slow-2g network', () => {
      Object.defineProperty(navigator, 'connection', {
        value: { saveData: false, effectiveType: '2g' },
        writable: true,
        configurable: true
      })
      expect(canPrefetch()).toBe(false)

      Object.defineProperty(navigator, 'connection', {
        value: { saveData: false, effectiveType: 'slow-2g' },
        writable: true,
        configurable: true
      })
      expect(canPrefetch()).toBe(false)
    })

    it('returns true on 4g network', () => {
      Object.defineProperty(navigator, 'connection', {
        value: { saveData: false, effectiveType: '4g' },
        writable: true,
        configurable: true
      })
      expect(canPrefetch()).toBe(true)
    })
  })

  describe('prefetchRoute', () => {
    it('handles empty or unknown route safely without throwing', async () => {
      await expect(prefetchRoute('')).resolves.toBeUndefined()
      await expect(prefetchRoute('unknown-route-xyz')).resolves.toBeUndefined()
    })

    it('triggers mocked component loader and data prefetch for mall', async () => {
      const mockLoader = vi.fn().mockResolvedValue({ default: {} })
      ROUTE_COMPONENT_LOADERS['test-mall'] = mockLoader

      await prefetchRoute('test-mall')
      expect(mockLoader).toHaveBeenCalledTimes(1)
      expect(prefetchedComponents.has('test-mall')).toBe(true)

      // Subsequent call does not call loader again
      await prefetchRoute('test-mall')
      expect(mockLoader).toHaveBeenCalledTimes(1)
    })

    it('triggers data prefetch for mall route', async () => {
      ROUTE_COMPONENT_LOADERS['mall'] = vi.fn().mockResolvedValue({ default: {} })
      await prefetchRoute('mall')

      expect(homeApi.industries).toHaveBeenCalled()
      expect(homeApi.categories).toHaveBeenCalled()
    })
  })
})

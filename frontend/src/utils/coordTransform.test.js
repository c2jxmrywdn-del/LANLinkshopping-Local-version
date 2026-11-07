import { describe, expect, it } from 'vitest'
import {
  DICT_DATUM_IS_GCJ02,
  gcj02ToWgs84,
  toAmapLngLat,
  wgs84ToGcj02,
} from './coordTransform'

describe('wgs84ToGcj02', () => {
  it('境内偏移量级 0.001~0.01 度（北京/上海/深圳/乌鲁木齐）', () => {
    const cases = [
      [116.407526, 39.90403],
      [121.473701, 31.230416],
      [114.057868, 22.543099],
      [87.6168, 43.8256],
    ]
    for (const [lng, lat] of cases) {
      const [gLng, gLat] = wgs84ToGcj02(lng, lat)
      expect(Math.abs(gLng - lng)).toBeGreaterThan(0.001)
      expect(Math.abs(gLng - lng)).toBeLessThan(0.01)
      expect(Math.abs(gLat - lat)).toBeGreaterThan(0.0005)
      expect(Math.abs(gLat - lat)).toBeLessThan(0.01)
    }
  })

  it('境外坐标恒等直通', () => {
    expect(wgs84ToGcj02(8.55, 47.36667)).toEqual([8.55, 47.36667])
    expect(wgs84ToGcj02(139.7, 35.7)).toEqual([139.7, 35.7])
    expect(wgs84ToGcj02(-0.13, 51.5)).toEqual([-0.13, 51.5])
  })

  it('纯函数：同入参同输出、不改写入参', () => {
    const a = wgs84ToGcj02(121.5111816, 31.1773528)
    const b = wgs84ToGcj02(121.5111816, 31.1773528)
    expect(a).toEqual(b)
  })
})

describe('gcj02ToWgs84', () => {
  it('往返闭合：gcj02ToWgs84(wgs84ToGcj02(x)) ≈ x（一轮近似逆变换，残差 <1e-4 度 ≈ 米级）', () => {
    const cases = [
      [116.407526, 39.90403],
      [121.5111816, 31.1773528],
      [113.264434, 23.129162],
    ]
    for (const [lng, lat] of cases) {
      const [gLng, gLat] = wgs84ToGcj02(lng, lat)
      const [rLng, rLat] = gcj02ToWgs84(gLng, gLat)
      expect(rLng).toBeCloseTo(lng, 4)
      expect(rLat).toBeCloseTo(lat, 4)
    }
  })

  it('二次转换不幂等：对已偏移点再转一次会继续偏移——datum 直通开关存在的依据', () => {
    const [gLng, gLat] = wgs84ToGcj02(116.407526, 39.90403)
    const [g2Lng, g2Lat] = wgs84ToGcj02(gLng, gLat)
    // 对 GCJ 点再套一次 WGS→GCJ 会把点再推离数百米（本机实测偏移 ≈0.006 度），绝不等于原点
    expect(g2Lng).not.toBeCloseTo(gLng, 3)
    expect(g2Lat).not.toBeCloseTo(gLat, 3)
  })

  it('境外坐标恒等直通', () => {
    expect(gcj02ToWgs84(8.55, 47.36667)).toEqual([8.55, 47.36667])
  })
})

describe('toAmapLngLat（datum 开关）', () => {
  it('实测字典即 GCJ-02：开关开启时对字典坐标直通，不叠加偏移', () => {
    expect(DICT_DATUM_IS_GCJ02).toBe(true)
    // 后端 GeoCoordinateRegistry 字典点（2026-11-06 高德 Geocoder 抽查：距 GCJ-02 城市中心 北京 20m / 上海 13m）
    expect(toAmapLngLat(116.407526, 39.90403)).toEqual([116.407526, 39.90403])
    expect(toAmapLngLat(121.473701, 31.230416)).toEqual([121.473701, 31.230416])
  })
})

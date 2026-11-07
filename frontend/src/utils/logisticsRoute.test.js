import { describe, expect, it } from 'vitest'
import { bearingDeg, sliceRouteAtFraction } from './logisticsRoute'

/** G4 走廊子集（真实字典经纬度，经度递增便于断言） */
const NODES = [
  { name: '深圳', lng: 114.057868, lat: 22.543099 },
  { name: '广州', lng: 113.264434, lat: 23.129162 },
  { name: '长沙', lng: 112.982279, lat: 28.19409 },
  { name: '武汉', lng: 114.305393, lat: 30.593099 },
  { name: '北京', lng: 116.407526, lat: 39.90403 },
]

describe('sliceRouteAtFraction', () => {
  it('进度 0：passed 只含首点（含共享割点），todo 为割点+全部节点', () => {
    const { passed, todo, head } = sliceRouteAtFraction(NODES, 0)
    expect(passed).toHaveLength(2) // 首点 + 共享割点（=首点）
    expect(passed[0]).toEqual([114.057868, 22.543099])
    expect(todo).toHaveLength(NODES.length) // 割点(=首点) + 其余 4 节点
    expect(head.lng).toBeCloseTo(114.057868, 9)
  })

  it('进度 100：passed 为割点+全线，todo 只含终点（共享割点即终点）', () => {
    const { passed, todo, head } = sliceRouteAtFraction(NODES, 100)
    expect(passed).toHaveLength(NODES.length) // 4 节点 + 割点(=终点)
    expect(todo).toHaveLength(2) // 割点(=终点) + 终点
    expect(head.lng).toBeCloseTo(116.407526, 9)
    expect(head.lat).toBeCloseTo(39.90403, 9)
  })

  it('进度 50：按累计里程割点落在武汉-北京段，两段共享割点且车头航向朝东北', () => {
    const { passed, todo, head } = sliceRouteAtFraction(NODES, 50)
    expect(passed[passed.length - 1]).toEqual(todo[0]) // 首尾相接
    // 深圳→广州→长沙→武汉约占全程一半，50% 割点已越过武汉进入武汉-北京段
    expect(head.lng).toBeGreaterThan(114.305393)
    expect(head.lng).toBeLessThan(116.407526)
    expect(head.headingAngle).toBeGreaterThan(0)
    expect(head.headingAngle).toBeLessThan(90)
  })

  it('进度 clamp：负数按 0、超百按 100', () => {
    const zero = sliceRouteAtFraction(NODES, -5)
    expect(zero.fraction).toBe(0)
    const over = sliceRouteAtFraction(NODES, 150)
    expect(over.fraction).toBe(1)
    expect(over.head.lng).toBeCloseTo(116.407526, 9)
  })

  it('退化输入：空链 / 单点链不抛错', () => {
    const empty = sliceRouteAtFraction([], 50)
    expect(empty.passed).toEqual([])
    expect(empty.head).toBeNull()
    const single = sliceRouteAtFraction([NODES[0]], 50)
    expect(single.head).toEqual({ lng: 114.057868, lat: 22.543099, headingAngle: 0 })
    expect(single.passed).toEqual([[114.057868, 22.543099]])
  })

  it('纯函数：同入参同输出', () => {
    const a = sliceRouteAtFraction(NODES, 37)
    const b = sliceRouteAtFraction(NODES, 37)
    expect(a).toEqual(b)
  })
})

describe('bearingDeg', () => {
  it('正东 / 正北 / 正西航向角（同纬度东西向因大圆弧略偏，±0.5° 内）', () => {
    expect(bearingDeg(114, 30, 115, 30)).toBeCloseTo(90, 0) // 正东
    expect(bearingDeg(114, 30, 114, 31)).toBeCloseTo(0, 6) // 正北（严格 0）
    expect(bearingDeg(114, 30, 113, 30)).toBeCloseTo(270, 0) // 正西
  })

  it('两点重合返回 0', () => {
    expect(bearingDeg(114, 30, 114, 30)).toBe(0)
  })
})

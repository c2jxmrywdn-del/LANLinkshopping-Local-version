import { describe, it, expect } from 'vitest'
import {
  quadraticBezierPoint,
  quadraticBezierTangent,
  interpolateTruckPosition,
  haversineDistance,
  findCityGeo,
  synthesizeHighwayRoute,
  projectChinaGeoToSvg,
  parseCityFromAddress,
  PROVINCES_DATA,
  MAJOR_CITIES,
  HIGHWAY_CORRIDORS
} from './logisticsGis'

describe('logisticsGis 空间算法与真实中国路网测试', () => {
  const p0 = { x: 0, y: 0 }
  const p1 = { x: 50, y: 100 }
  const p2 = { x: 100, y: 0 }

  it('quadraticBezierPoint 在 u=0, 0.5, 1 时坐标正确', () => {
    const start = quadraticBezierPoint(0, p0, p1, p2)
    expect(start.x).toBe(0)
    expect(start.y).toBe(0)

    const mid = quadraticBezierPoint(0.5, p0, p1, p2)
    expect(mid.x).toBe(50)
    expect(mid.y).toBe(50)

    const end = quadraticBezierPoint(1, p0, p1, p2)
    expect(end.x).toBe(100)
    expect(end.y).toBe(0)
  })

  it('quadraticBezierTangent 切线导数计算符合预期', () => {
    const tanStart = quadraticBezierTangent(0, p0, p1, p2)
    expect(tanStart.dx).toBe(100)
    expect(tanStart.dy).toBe(200)
    expect(typeof tanStart.angleDeg).toBe('number')

    const tanEnd = quadraticBezierTangent(1, p0, p1, p2)
    expect(tanEnd.dx).toBe(100)
    expect(tanEnd.dy).toBe(-200)
  })

  it('interpolateTruckPosition 在边界及多段节点时平滑运行', () => {
    const nodes = [
      { name: '深圳仓', x: 0, y: 0 },
      { name: '武汉中心', x: 100, y: 100 },
      { name: '北京现场', x: 200, y: 0 }
    ]

    const pos0 = interpolateTruckPosition(0, nodes)
    expect(pos0.x).toBe(0)
    expect(pos0.y).toBe(0)
    expect(pos0.locationName).toContain('深圳仓 (装车待发)')

    const pos50 = interpolateTruckPosition(50, nodes)
    expect(pos50.x).toBe(100)
    expect(pos50.y).toBe(100)

    const pos100 = interpolateTruckPosition(100, nodes)
    expect(pos100.x).toBe(200)
    expect(pos100.y).toBe(0)
    expect(pos100.locationName).toContain('北京现场 (末端签收)')
  })

  it('异常输入兜底处理：节点不足2个时不崩溃', () => {
    const empty = interpolateTruckPosition(50, [])
    expect(empty.x).toBe(0)
    expect(empty.locationName).toBe('筹备中')

    const single = interpolateTruckPosition(50, [{ name: '孤立节点', x: 10, y: 10 }])
    expect(single.x).toBe(0)
  })

  it('中国地理墨卡托投影 projectChinaGeoToSvg 映射真实有效', () => {
    const sz = projectChinaGeoToSvg(114.05, 22.54)
    const bj = projectChinaGeoToSvg(116.40, 39.90)

    // 北京在深圳北面（y 更小，因为 SVG y 向下增长）
    expect(bj.y).toBeLessThan(sz.y)
    // 经度北京偏东一点点（x 略大）
    expect(bj.x).toBeGreaterThan(sz.x)
    expect(sz.x).toBeGreaterThan(0)
    expect(sz.y).toBeGreaterThan(0)
  })

  it('中国 34 省份与 42 工业重点城市数据完整性', () => {
    expect(PROVINCES_DATA.length).toBe(34)
    expect(MAJOR_CITIES.length).toBeGreaterThanOrEqual(40)
    expect(HIGHWAY_CORRIDORS.G4).toBeDefined()
    expect(HIGHWAY_CORRIDORS.G2).toBeDefined()
    expect(HIGHWAY_CORRIDORS.G42).toBeDefined()
  })

  it('haversineDistance 计算真实地表球面里程', () => {
    // 深圳到北京大圆直线约 1940 km
    const d = haversineDistance(22.54, 114.05, 39.90, 116.40)
    expect(d).toBeGreaterThan(1800)
    expect(d).toBeLessThan(2100)
  })

  it('synthesizeHighwayRoute 沿 G4 京港澳高速自动吸附枢纽节点', () => {
    const route = synthesizeHighwayRoute('深圳工业制造仓', '北京顺义现场')
    expect(route.corridorName).toContain('G4')
    expect(route.nodes.length).toBeGreaterThan(5)
    expect(route.distanceKm).toBeGreaterThan(2000)
  })

  it('parseCityFromAddress 智能解析英文、拼音与中文收货地址', () => {
    expect(parseCityFromAddress('Chongqing Test Road 1')).toBe('重庆')
    expect(parseCityFromAddress('Shenzhen Hi-Tech Industrial Park')).toBe('深圳')
    expect(parseCityFromAddress('Shanghai Pudong Free Trade Zone')).toBe('上海')
    expect(parseCityFromAddress('Chengdu Tianfu Software Park 2')).toBe('成都')
    expect(parseCityFromAddress('Wuhan Optics Valley Center')).toBe('武汉')
    expect(parseCityFromAddress('北京市海淀区中关村南大街1号')).toBe('北京')
    expect(parseCityFromAddress('广东省广州市天河区工业路')).toBe('广州')
    expect(parseCityFromAddress('四川省绵阳市高新技术开发区')).toBe('成都')
  })

  it('synthesizeHighwayRoute 准确规划 G65 渝湘高速走廊 (深圳 -> 重庆)', () => {
    const route = synthesizeHighwayRoute('深圳工业制造中心', '重庆江北测试路')
    expect(route.corridorName).toContain('G65')
    const nodeNames = route.nodes.map(n => n.name)
    expect(nodeNames).toContain('深圳')
    expect(nodeNames).toContain('广州')
    expect(nodeNames).toContain('怀化')
    expect(nodeNames).toContain('重庆')
    expect(route.nodes[0].name).toBe('深圳')
    expect(route.nodes[route.nodes.length - 1].name).toBe('重庆')
    expect(route.nodes[0].hubType).toBe('warehouse')
    expect(route.nodes[route.nodes.length - 1].hubType).toBe('client')
  })

  it('synthesizeHighwayRoute 支持 RMA 逆向物流退货路线与节点语义纠偏', () => {
    const reverseRoute = synthesizeHighwayRoute('重庆江北买家退货点', '深圳售后质检返修仓', true)
    expect(reverseRoute.corridorName).toContain('RMA逆向返厂通道')
    expect(reverseRoute.nodes[0].name).toBe('重庆')
    expect(reverseRoute.nodes[0].hubType).toBe('return_origin')
    expect(reverseRoute.nodes[reverseRoute.nodes.length - 1].name).toBe('深圳')
    expect(reverseRoute.nodes[reverseRoute.nodes.length - 1].hubType).toBe('return_dest')
  })
})

import { describe, it, expect, vi, beforeEach } from 'vitest'

// AMap 与凭据服务全部 mock：组件在 jsdom 内真实编译挂载，验证双态路由与降级链路。
// 组件内 mapConfig 有模块级缓存，先 resetModules 再动态 import 以隔离各用例。
vi.mock('../../api/request', () => ({ default: { get: vi.fn() } }))
vi.mock('@amap/amap-jsapi-loader', () => ({ default: { load: vi.fn(), reset: vi.fn() } }))

import { h, reactive, createApp, nextTick } from 'vue'

let request
let AMapLoader
let LogisticsMap

async function freshModules() {
  vi.resetModules()
  ;({ default: request } = await import('../../api/request'))
  ;({ default: AMapLoader } = await import('@amap/amap-jsapi-loader'))
  ;({ default: LogisticsMap } = await import('./LogisticsMap.vue'))
}

// 无 @vue/test-utils 依赖（零新增 npm 包）：createApp + render 函数手动挂载到 jsdom
function mountMap(initialProps) {
  const props = reactive({ traces: [], height: 300, ...initialProps })
  const root = document.createElement('div')
  document.body.appendChild(root)
  const app = createApp({ setup: () => () => h(LogisticsMap, { ...props }) })
  app.mount(root)
  return {
    root, props,
    set(p) { Object.assign(props, p); return nextTick() },
    unmount() { app.unmount(); root.remove() }
  }
}

const GEO_TRACES = [
  { traceId: 1, traceTime: '2026-11-06T08:00:00', location: '深圳市宝安区xx仓', city: '深圳', longitude: 113.88, latitude: 22.55, status: 'picked_up', isAbnormal: 0 },
  { traceId: 2, traceTime: '2026-11-06T10:30:00', location: '武汉中转站', city: '武汉', longitude: 114.30, latitude: 30.60, status: 'exception', description: '滞留', isAbnormal: 1 }
]
const CITY_TRACES = [
  { traceId: 1, traceTime: '2026-11-06T08:00:00', location: '深圳市宝安区xx仓', city: '深圳', status: 'picked_up', isAbnormal: 0 },
  { traceId: 2, traceTime: '2026-11-06T10:30:00', location: '南昌分拨中心', city: '南昌', status: 'in_transit', isAbnormal: 0 }
]

// FakeAMap：记录 add 的覆盖物，Polyline/Marker 构造参数可断言。
// 注意：AMap 的 Polyline/Marker 以 new 调用，vitest 4 的箭头函数 mock 不可构造，须用普通函数。
let lastFakeMap = null
let ctorCalls = { polyline: [], marker: [] }
function installFakeAMap() {
  ctorCalls = { polyline: [], marker: [] }
  const FakeMap = class {
    constructor(el, opts) { this.el = el; this.opts = opts; this.overlays = []; this.destroyed = false; lastFakeMap = this }
    add(o) { this.overlays.push(...[].concat(o)) }
    setFitView() {}
    destroy() { this.destroyed = true }
  }
  const FakePolyline = function (cfg) { ctorCalls.polyline.push(cfg); Object.assign(this, cfg) }
  const FakeMarker = function (cfg) { ctorCalls.marker.push(cfg); Object.assign(this, cfg) }
  const FakeAMap = { Map: FakeMap, Polyline: FakePolyline, Marker: FakeMarker }
  window.AMap = FakeAMap
  return { FakeAMap }
}

describe('LogisticsMap', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    lastFakeMap = null
    delete window.AMap
    await freshModules()
  })

  it('无经纬度坐标：直接降级城市级占位，不请求地图凭据，按时间正序串联城市节点', async () => {
    request.get.mockResolvedValue({ key: 'k', securityJsCode: 's' })
    const t = mountMap({ traces: CITY_TRACES })
    await nextTick()
    expect(request.get).not.toHaveBeenCalled()
    expect(t.root.querySelector('.rm-mode').textContent).toContain('城市级示意')
    const chips = [...t.root.querySelectorAll('.rm-city-name')]
    expect(chips.map(c => c.textContent)).toEqual(['深圳', '南昌'])
    expect(t.root.querySelector('.rm-degrade').textContent).toContain('暂无经纬度坐标')
    expect(t.root.querySelector('.rm-city-se').textContent).toContain('始发')
    t.unmount()
  })

  it('有坐标且 AMap 可用：画 Polyline + 逐节点 Marker，模式徽标为高德图源', async () => {
    installFakeAMap()
    request.get.mockResolvedValue({ key: 'k', securityJsCode: 's' })
    AMapLoader.load.mockResolvedValue(window.AMap)
    const t = mountMap({ traces: GEO_TRACES })
    await vi.waitFor(() => expect(lastFakeMap?.overlays.length).toBeGreaterThan(0))
    expect(t.root.querySelector('.rm-mode').textContent).toContain('高德图源')
    expect(ctorCalls.polyline).toHaveLength(1)
    expect(ctorCalls.polyline[0].path).toEqual([[113.88, 22.55], [114.3, 30.6]])
    expect(ctorCalls.marker).toHaveLength(2)
    const abnormalMarker = ctorCalls.marker[1].content
    expect(abnormalMarker).toContain('is-abnormal')
    expect(abnormalMarker).toContain('武汉中转站')
    t.unmount()
    expect(lastFakeMap.destroyed).toBe(true)
  })

  it('有坐标但 AMap 加载失败：降级城市级占位并给出原因，不白屏', async () => {
    installFakeAMap()
    request.get.mockResolvedValue({ key: 'k', securityJsCode: 's' })
    AMapLoader.load.mockRejectedValue(new Error('amap down'))
    const t = mountMap({ traces: GEO_TRACES })
    await vi.waitFor(() => expect(t.root.querySelector('.rm-degrade')).toBeTruthy())
    expect(t.root.querySelector('.rm-degrade').textContent).toContain('高德图源加载失败')
    const chips = [...t.root.querySelectorAll('.rm-city-name')]
    expect(chips.map(c => c.textContent)).toEqual(['深圳', '武汉'])
    t.unmount()
  })

  it('未配置地图凭据（key 为空）：降级城市级占位，不触发 loader', async () => {
    request.get.mockResolvedValue({ key: '', securityJsCode: '' })
    const t = mountMap({ traces: GEO_TRACES })
    await vi.waitFor(() => expect(t.root.querySelector('.rm-degrade')).toBeTruthy())
    expect(t.root.querySelector('.rm-degrade').textContent).toContain('地图凭据未配置')
    expect(AMapLoader.load).not.toHaveBeenCalled()
    t.unmount()
  })
})

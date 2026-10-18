/**
 * perfGovernor 性能治理测试：
 * 覆盖 解码路径切换（30%/50% 阈值）、熔断降级（200ms/内存攀升）、
 * 恢复阶梯、帧率缩放、并发上限、离屏跳过。
 */
import { describe, it, expect, beforeEach } from 'vitest'
import {
  PERF_TIER, DECODE_MODE,
  deriveDecodeMode, deriveTier,
  reportExternalLoad, reportLongTask,
  registerAnimation, requestFrame,
  getPerfSnapshot, onPerfChange,
  __test
} from './perfGovernor'

const OPTS = {
  cpuSoftMax: 0.3, cpuHardMin: 0.5, sustainSamples: 3,
  longTaskDegradeMs: 200, frameGapDegradeMs: 200,
  memoryClimbSamples: 5, recoverSamples: 6
}

beforeEach(() => {
  __test.reset()
})

describe('解码路径自适应（软/硬）', () => {
  it('CPU < 30% 持续 → soft（兼容优先）', () => {
    let mode = DECODE_MODE.HARD
    let counters = { above: 3, below: 0 }
    for (let i = 0; i < 3; i++) {
      const r = deriveDecodeMode(mode, 0.2, OPTS, counters)
      mode = r.mode; counters = r.counters
    }
    expect(mode).toBe(DECODE_MODE.SOFT)
  })

  it('CPU > 50% 持续 → hard（GPU 加速）', () => {
    let mode = DECODE_MODE.SOFT
    let counters = { above: 0, below: 0 }
    for (let i = 0; i < 3; i++) {
      const r = deriveDecodeMode(mode, 0.6, OPTS, counters)
      mode = r.mode; counters = r.counters
    }
    expect(mode).toBe(DECODE_MODE.HARD)
  })

  it('持续判定：仅 1 次超 50% 不切换（非持续）', () => {
    const r = deriveDecodeMode(DECODE_MODE.SOFT, 0.6, OPTS, { above: 0, below: 0 })
    expect(r.mode).toBe(DECODE_MODE.SOFT) // 第 1 次不动
    expect(r.counters.above).toBe(1)
  })

  it('迟滞区间 30%~50%：维持现状防抖动', () => {
    const r1 = deriveDecodeMode(DECODE_MODE.SOFT, 0.4, OPTS, { above: 0, below: 5 })
    expect(r1.mode).toBe(DECODE_MODE.SOFT)
    const r2 = deriveDecodeMode(DECODE_MODE.HARD, 0.4, OPTS, { above: 5, below: 0 })
    expect(r2.mode).toBe(DECODE_MODE.HARD)
  })

  it('运行时 reportExternalLoad 切换 decodeMode 并广播', () => {
    let events = 0
    onPerfChange(() => events++)
    reportExternalLoad(0.2); reportExternalLoad(0.2); reportExternalLoad(0.2)
    expect(getPerfSnapshot().decodeMode).toBe(DECODE_MODE.SOFT)
    reportExternalLoad(0.7); reportExternalLoad(0.7); reportExternalLoad(0.7)
    expect(getPerfSnapshot().decodeMode).toBe(DECODE_MODE.HARD)
    expect(events).toBeGreaterThanOrEqual(1)
  })
})

describe('熔断保护（降级阶梯）', () => {
  it('主线程任务 > 200ms → full 降为 reduced', () => {
    expect(deriveTier(PERF_TIER.FULL, { longTaskMs: 250 }, OPTS)).toBe(PERF_TIER.REDUCED)
  })

  it('reduced 再熔断 → suspended（暂停非必要动效）', () => {
    expect(deriveTier(PERF_TIER.REDUCED, { frameGapMs: 300 }, OPTS)).toBe(PERF_TIER.SUSPENDED)
    expect(deriveTier(PERF_TIER.REDUCED, { longTaskMs: 400 }, OPTS)).toBe(PERF_TIER.SUSPENDED)
  })

  it('内存持续攀升 → 降级', () => {
    expect(deriveTier(PERF_TIER.FULL, { heapClimbing: 5 }, OPTS)).toBe(PERF_TIER.REDUCED)
  })

  it('未达熔断阈值不降级', () => {
    expect(deriveTier(PERF_TIER.FULL, { longTaskMs: 150 }, OPTS)).toBe(PERF_TIER.FULL)
  })

  it('健康持续 → 逐级恢复 suspended → reduced → full', () => {
    expect(deriveTier(PERF_TIER.SUSPENDED, { healthy: true }, OPTS)).toBe(PERF_TIER.REDUCED)
    expect(deriveTier(PERF_TIER.REDUCED, { healthy: true }, OPTS)).toBe(PERF_TIER.FULL)
  })

  it('运行时 reportLongTask 熔断并广播', () => {
    reportLongTask(320)
    expect(getPerfSnapshot().tier).toBe(PERF_TIER.REDUCED)
    reportLongTask(500)
    expect(getPerfSnapshot().tier).toBe(PERF_TIER.SUSPENDED)
  })
})

describe('帧调度：节流/并发上限/离屏跳过', () => {
  it('registerAnimation 返回 dispose 并可注销', () => {
    const dispose = registerAnimation({ tick: () => {} })
    expect(__test.animations.size).toBe(1)
    dispose()
    expect(__test.animations.size).toBe(0)
  })

  it('帧率自适应：reduced 档帧间隔翻倍（帧率减半）', () => {
    // 阶段 A：full 档（60fps → 16.6ms 间隔）
    const callsA = []
    const disposeA = registerAnimation({ fps: 60, tick: (t) => callsA.push(t) })
    __test.state.tier = PERF_TIER.FULL
    __test.dispatchFrame(1000) // 首帧（lastRunAt=0）必然触发
    expect(callsA.length).toBe(1)
    __test.dispatchFrame(1010) // 10ms < 16.6ms 不触发
    expect(callsA.length).toBe(1)
    __test.dispatchFrame(1020) // 20ms ≥ 16.6ms 触发
    expect(callsA.length).toBe(2)
    disposeA()

    // 阶段 B：reduced 档（30fps → 33ms 间隔），全新动画避免 lastRunAt 残留
    const callsB = []
    registerAnimation({ fps: 60, tick: (t) => callsB.push(t) })
    __test.state.tier = PERF_TIER.REDUCED
    __test.dispatchFrame(2000) // 首帧触发
    expect(callsB.length).toBe(1)
    __test.dispatchFrame(2010) // 10ms < 33ms 不触发
    expect(callsB.length).toBe(1)
    __test.dispatchFrame(2040) // 40ms ≥ 33ms 触发
    expect(callsB.length).toBe(2)
  })

  it('离屏元素跳过渲染（visible=false）', () => {
    const calls = []
    const a = registerAnimation({ fps: 60, tick: (t) => calls.push(t) })
    const anim = [...__test.animations.values()][0]
    anim.visible = false
    __test.dispatchFrame(1000)
    __test.dispatchFrame(2000)
    expect(calls.length).toBe(0)
    anim.visible = true
    __test.dispatchFrame(3000)
    expect(calls.length).toBe(1)
    a()
  })

  it('suspended 档暂停辅助动画、保留核心动画', () => {
    const auxCalls = []
    const coreCalls = []
    registerAnimation({ priority: 'auxiliary', tick: () => auxCalls.push(1) })
    registerAnimation({ priority: 'core', tick: () => coreCalls.push(1) })
    __test.state.tier = PERF_TIER.SUSPENDED
    __test.dispatchFrame(1000)
    __test.dispatchFrame(2000)
    expect(auxCalls.length).toBe(0) // 非必要暂停
    expect(coreCalls.length).toBeGreaterThan(0) // 核心保留
  })

  it('辅助动画并发上限生效', () => {
    const ran = []
    for (let i = 0; i < 10; i++) {
      registerAnimation({ priority: 'auxiliary', tick: () => ran.push(i) })
    }
    __test.dispatchFrame(1000)
    expect(ran.length).toBeLessThanOrEqual(6) // maxConcurrentAux = 6
    expect(ran.length).toBeGreaterThan(0)
  })

  it('requestFrame 即用即销', () => {
    let called = 0
    requestFrame(() => called++)
    __test.dispatchFrame(1000)
    expect(called).toBe(1)
    expect(__test.animations.size).toBe(0) // 执行后自动注销
    __test.dispatchFrame(2000)
    expect(called).toBe(1) // 不重复
  })

  it('单个动画抛异常不影响其他动画', () => {
    const ok = []
    registerAnimation({ tick: () => { throw new Error('boom') } })
    registerAnimation({ tick: () => ok.push(1) })
    __test.dispatchFrame(1000)
    expect(ok.length).toBe(1)
  })
})

describe('降级状态广播（CSS 钩子）', () => {
  it('tier 变化写入 data 属性与事件', () => {
    reportLongTask(320)
    const snap = getPerfSnapshot()
    expect(snap.tier).toBe(PERF_TIER.REDUCED)
    expect(typeof snap.load).toBe('number')
  })
})

/**
 * 前端动态效果性能治理核心（perfGovernor）
 *
 * 职责：
 *  1. 自适应解码/渲染路径选择：
 *     - CPU 负载 < 30%  → soft（软路径）：JS/CSS 通用渲染，不提升合成层，兼容性优先；
 *     - CPU 负载持续 > 50% → hard（硬路径）：GPU 合成层加速（translateZ/will-change/矩阵变换），
 *       将栅格化与合成卸载到 GPU，降低 CPU 占用。
 *  2. 帧率自适应 + 渲染节流 + 离屏优化 + 动画并发上限：
 *     - 统一 rAF 调度器按 tier 缩放帧率（full 60fps / reduced 30fps / suspended 15fps）；
 *     - 离屏元素（IntersectionObserver）跳过渲染；
 *     - 辅助类（auxiliary）动画并发执行数量受 maxConcurrentAux 限制。
 *  3. 资源监控 + 熔断降级：
 *     - 主线程任务排队（longtask）单次 > 200ms 或帧间隙 > 200ms → 降级一档；
 *     - JS 堆占用连续攀升 → 降级一档；
 *     - 三级阶梯 full → reduced（降帧率/关特效）→ suspended（暂停非核心动效）；
 *     - 负载回落后自动逐级恢复（带迟滞，防抖动）。
 *
 * 说明：浏览器不直接暴露真实 CPU 百分比，此处以「调度窗口内 JS 忙时占比 +
 * longtask 占比 + 帧间隙」推导负载估计值（0~1），阈值语义与需求一致；
 * 亦可通过 reportExternalLoad() 注入外部 CPU 采样（如性能面板/宿主上报）覆盖。
 */

export const PERF_TIER = { FULL: 'full', REDUCED: 'reduced', SUSPENDED: 'suspended' }
export const DECODE_MODE = { SOFT: 'soft', HARD: 'hard' }

const DEFAULTS = {
  cpuSoftMax: 0.3,          // 低于 30% → soft（兼容优先）
  cpuHardMin: 0.5,          // 持续高于 50% → hard（GPU 加速）
  sustainSamples: 3,        // “持续”判定：连续采样次数
  longTaskDegradeMs: 200,   // 主线程任务排队/长任务 > 200ms → 熔断降级
  frameGapDegradeMs: 200,   // 帧间隙 > 200ms（主线程阻塞）→ 熔断降级
  memoryClimbSamples: 5,    // JS 堆连续攀升次数 → 降级
  recoverSamples: 6,        // 连续健康窗口数 → 恢复一档
  sampleWindowMs: 1000,     // 负载采样窗口
  heapSampleMs: 5000,       // 内存采样间隔
  maxConcurrentAux: 6,      // 非核心动画最大并发数
  fpsScale: { full: 1, reduced: 0.5, suspended: 0.25 }, // tier → 帧率缩放
  minFps: 8
}

const state = {
  tier: PERF_TIER.FULL,
  decodeMode: DECODE_MODE.SOFT, // 默认 soft：兼容性优先
  load: 0,
  lastLongTaskMs: 0,
  lastFrameGapMs: 0,
  heapBytes: 0,
  heapClimbing: 0,
  healthyWindows: 0,
  busyMs: 0,
  windowStart: 0,
  aboveCount: 0,
  belowCount: 0,
  started: false,
  opts: { ...DEFAULTS }
}

/** 注册的动画任务：{ id, priority:'core'|'auxiliary', fps, tick, running, lastRunAt, visible } */
const animations = new Map()
const listeners = new Set()
let rafId = 0
let heapTimer = 0
let longTaskObserver = null
let visibilityObserver = null
let seq = 0

function emit() {
  const detail = getPerfSnapshot()
  listeners.forEach((cb) => {
    try { cb(detail) } catch { /* 监听器异常不影响治理主流程 */ }
  })
  try {
    document.documentElement.dataset.perfTier = state.tier
    document.documentElement.dataset.decodeMode = state.decodeMode
    window.dispatchEvent(new CustomEvent('ll:perf-change', { detail }))
  } catch { /* 非 DOM 环境（测试）忽略 */ }
}

export function getPerfSnapshot() {
  return {
    tier: state.tier,
    decodeMode: state.decodeMode,
    load: Number(state.load.toFixed(3)),
    lastLongTaskMs: state.lastLongTaskMs,
    lastFrameGapMs: state.lastFrameGapMs,
    heapBytes: state.heapBytes,
    animationCount: animations.size
  }
}

export function onPerfChange(cb) {
  listeners.add(cb)
  return () => listeners.delete(cb)
}

/* ---------------- 负载估计与模式/分级决策 ---------------- */

/** 纯决策函数（可单测）：输入当前模式 + 负载 + 计数器，输出下一模式与计数器 */
export function deriveDecodeMode(currentMode, load, opts, counters) {
  const c = { above: counters?.above ?? 0, below: counters?.below ?? 0 }
  let mode = currentMode
  if (load > opts.cpuHardMin) {
    c.above++
    c.below = 0
    if (c.above >= opts.sustainSamples) mode = DECODE_MODE.HARD // 持续高负载 → GPU 硬加速
  } else if (load < opts.cpuSoftMax) {
    c.below++
    c.above = 0
    if (c.below >= opts.sustainSamples) mode = DECODE_MODE.SOFT // 低负载 → 软路径保兼容
  } else {
    // 30%~50% 迟滞区间：维持现状，防抖动
  }
  return { mode, counters: c }
}

/** 纯决策函数（可单测）：熔断降级/恢复阶梯 */
export function deriveTier(currentTier, { longTaskMs = 0, frameGapMs = 0, heapClimbing = 0, healthy = false }, opts) {
  // 熔断条件优先：主线程排队 > 200ms / 帧间隙 > 200ms / 内存持续攀升 → 降一档
  const tripped = longTaskMs > opts.longTaskDegradeMs
    || frameGapMs > opts.frameGapDegradeMs
    || heapClimbing >= opts.memoryClimbSamples
  if (tripped) {
    if (currentTier === PERF_TIER.FULL) return PERF_TIER.REDUCED
    return PERF_TIER.SUSPENDED // reduced 再熔断 → suspended（暂停非必要动效）
  }
  if (healthy) {
    if (currentTier === PERF_TIER.SUSPENDED) return PERF_TIER.REDUCED
    if (currentTier === PERF_TIER.REDUCED) return PERF_TIER.FULL
  }
  return currentTier
}

function pushLoadSample(load, longTaskMs = 0, frameGapMs = 0) {
  state.load = load
  state.lastLongTaskMs = Math.max(state.lastLongTaskMs * 0.8, longTaskMs) // 平滑衰减
  state.lastFrameGapMs = Math.max(state.lastFrameGapMs * 0.8, frameGapMs)

  const { mode, counters } = deriveDecodeMode(state.decodeMode, load, state.opts, {
    above: state.aboveCount, below: state.belowCount
  })
  state.aboveCount = counters.above
  state.belowCount = counters.below
  const modeChanged = mode !== state.decodeMode
  state.decodeMode = mode

  const healthy = load < state.opts.cpuSoftMax
    && longTaskMs <= state.opts.longTaskDegradeMs
    && frameGapMs <= state.opts.frameGapDegradeMs
  if (healthy) state.healthyWindows++
  else state.healthyWindows = 0

  const needRecover = state.healthyWindows >= state.opts.recoverSamples
  const nextTier = deriveTier(
    state.tier,
    { longTaskMs, frameGapMs, heapClimbing: state.heapClimbing, healthy: needRecover },
    state.opts
  )
  if (needRecover) state.healthyWindows = 0
  const tierChanged = nextTier !== state.tier
  state.tier = nextTier
  if (modeChanged || tierChanged) emit()
}

/** 注入外部 CPU 负载采样（0~1），覆盖推导值（如宿主上报真实 CPU 占用率） */
export function reportExternalLoad(cpuRatio) {
  pushLoadSample(Math.min(1, Math.max(0, cpuRatio)))
}

/** 外部/测试触发一次 longtask 级阻塞（模拟主线程任务排队） */
export function reportLongTask(ms) {
  state.heapClimbing = state.heapClimbing // 不变
  pushLoadSample(state.load, ms, ms)
}

/* ---------------- 帧调度器：节流 + 并发上限 + 离屏跳过 ---------------- */

function tierFps(anim) {
  const scale = state.opts.fpsScale[state.tier] ?? 1
  const fps = Math.max(state.opts.minFps, Math.round((anim.fps ?? 60) * scale))
  return fps
}

function allowedToRun(anim) {
  if (anim.visible === false) return false // 离屏渲染优化：不可见不渲染
  if (state.tier === PERF_TIER.SUSPENDED && anim.priority !== 'core') return false // 熔断暂停非必要动效
  return true
}

function dispatchFrame(now) {
  let auxRunning = 0
  const due = []
  animations.forEach((anim) => {
    if (!allowedToRun(anim)) return
    const interval = 1000 / tierFps(anim)
    if (now - anim.lastRunAt >= interval) due.push(anim)
  })
  // 并发上限：辅助动画同一帧最多 maxConcurrentAux 个执行
  due.sort((a, b) => (a.priority === 'core' ? -1 : 1) - (b.priority === 'core' ? -1 : 1))
  for (const anim of due) {
    if (anim.priority !== 'core') {
      if (auxRunning >= state.opts.maxConcurrentAux) break
      auxRunning++
    }
    anim.lastRunAt = now
    const t0 = typeof performance !== 'undefined' ? performance.now() : Date.now()
    try { anim.tick(now) } catch { /* 单个动画异常不拖垮调度器 */ }
    const t1 = typeof performance !== 'undefined' ? performance.now() : Date.now()
    state.busyMs += (t1 - t0)
  }

  // 窗口结算 → 负载采样
  if (!state.windowStart) state.windowStart = now
  const elapsed = now - state.windowStart
  if (elapsed >= state.opts.sampleWindowMs) {
    const gap = Math.max(0, elapsed - state.opts.sampleWindowMs)
    const load = Math.min(1, state.busyMs / Math.max(1, elapsed))
    const busyReset = state.busyMs
    state.busyMs = 0
    state.windowStart = now
    pushLoadSample(load, 0, gap > state.opts.frameGapDegradeMs ? gap : 0)
    void busyReset
  }
}

function loop() {
  if (!state.started) return
  dispatchFrame(typeof performance !== 'undefined' ? performance.now() : Date.now())
  if (typeof requestAnimationFrame === 'function') {
    rafId = requestAnimationFrame(loop)
  } else {
    rafId = setTimeout(loop, 16)
  }
}

/**
 * 注册一个动态效果任务。
 * @param {{id?:string, priority?:'core'|'auxiliary', fps?:number, tick:Function, element?:Element}} desc
 * @returns {Function} dispose
 */
export function registerAnimation(desc) {
  const id = `anim-${++seq}`
  const anim = {
    id,
    priority: desc.priority === 'core' ? 'core' : 'auxiliary',
    fps: desc.fps ?? 60,
    tick: desc.tick,
    lastRunAt: 0,
    visible: true
  }
  animations.set(id, anim)
  if (desc.element && typeof IntersectionObserver === 'function') {
    // 离屏渲染优化：元素不可见时跳过 tick
    if (!visibilityObserver) {
      visibilityObserver = new IntersectionObserver((entries) => {
        entries.forEach((entry) => {
          animations.forEach((a) => {
            if (a.element === entry.target) a.visible = entry.isIntersecting
          })
        })
      }, { threshold: 0 })
    }
    anim.element = desc.element
    visibilityObserver.observe(desc.element)
  }
  return () => {
    if (anim.element && visibilityObserver) visibilityObserver.unobserve(anim.element)
    animations.delete(id)
  }
}

/** 帧节流辅助：按目标 fps 在调度器内执行一次回调（即用即销） */
export function requestFrame(cb, { fps = 60, priority = 'auxiliary', element } = {}) {
  const dispose = registerAnimation({
    priority, fps, element,
    tick: (now) => { dispose(); cb(now) }
  })
  return dispose
}

/* ---------------- 视频/Canvas 解码与渲染治理 ---------------- */

/**
 * 视频元素解码路径治理：
 *  - soft：不提升合成层，走浏览器默认兼容路径；
 *  - hard：提升为独立合成层 + GPU 栅格化卸载（硬件加速解码/合成），
 *    并保留音高（preservesPitch）避免额外重采样计算。
 *  同时绑定视口可见性：离屏自动暂停解码（省 CPU/内存）。
 */
export function attachVideoDecode(el) {
  if (!el) return () => {}
  const apply = () => {
    if (state.decodeMode === DECODE_MODE.HARD) {
      el.style.transform = 'translateZ(0)'
      el.style.willChange = 'transform'
      el.classList.add('ll-decode-hard')
      el.classList.remove('ll-decode-soft')
    } else {
      el.style.transform = ''
      el.style.willChange = ''
      el.classList.add('ll-decode-soft')
      el.classList.remove('ll-decode-hard')
    }
    try { el.preservesPitch = true } catch { /* 旧浏览器忽略 */ }
    el.dataset.decodeMode = state.decodeMode
  }
  apply()
  const off = onPerfChange(apply)
  let io = null
  if (typeof IntersectionObserver === 'function') {
    io = new IntersectionObserver(([entry]) => {
      if (entry.isIntersecting) {
        if (el.paused && el.dataset.autoplay === 'true') el.play?.().catch(() => {})
      } else if (!el.paused) {
        el.pause?.() // 离屏暂停解码
      }
    }, { threshold: 0 })
    io.observe(el)
  }
  return () => { off(); io?.disconnect() }
}

/**
 * Canvas 渲染循环治理：fps 节流 + 离屏跳帧 + 分级降帧/暂停。
 */
export function attachCanvasLoop(el, draw, { fps = 60, priority = 'auxiliary' } = {}) {
  return registerAnimation({
    fps, priority, element: el,
    tick: (now) => draw(now)
  })
}

/* ---------------- 监控启动 / 停止 ---------------- */

function sampleHeap() {
  const mem = typeof performance !== 'undefined' ? performance.memory : null
  if (!mem || !mem.usedJSHeapSize) return
  const cur = mem.usedJSHeapSize
  if (state.heapBytes && cur > state.heapBytes) {
    state.heapClimbing++
    if (state.heapClimbing === state.opts.memoryClimbSamples) {
      pushLoadSample(state.load) // 触发内存熔断降级
    }
  } else {
    state.heapClimbing = 0
  }
  state.heapBytes = cur
}

export function initPerfGovernor(options = {}) {
  if (state.started) return stopPerfGovernor
  state.opts = { ...DEFAULTS, ...options }
  state.started = true
  state.windowStart = 0
  state.busyMs = 0

  // longtask 监控：主线程任务排队/长任务 > 200ms → 熔断
  if (typeof PerformanceObserver !== 'undefined') {
    try {
      longTaskObserver = new PerformanceObserver((list) => {
        list.getEntries().forEach((entry) => {
          if (entry.duration >= state.opts.longTaskDegradeMs) reportLongTask(entry.duration)
        })
      })
      longTaskObserver.observe({ entryTypes: ['longtask'] })
    } catch { /* 不支持 longtask 的浏览器走帧间隙降级兜底 */ }
  }
  heapTimer = setInterval(sampleHeap, state.opts.heapSampleMs)
  emit()
  loop()
  return stopPerfGovernor
}

export function stopPerfGovernor() {
  state.started = false
  if (rafId) {
    if (typeof cancelAnimationFrame === 'function') cancelAnimationFrame(rafId)
    else clearTimeout(rafId)
    rafId = 0
  }
  if (heapTimer) { clearInterval(heapTimer); heapTimer = 0 }
  longTaskObserver?.disconnect()
  longTaskObserver = null
  visibilityObserver?.disconnect()
  visibilityObserver = null
  animations.clear()
}

/* ---------------- 测试钩子（不对外暴露业务语义） ---------------- */
export const __test = {
  pushLoadSample,
  dispatchFrame,
  state,
  animations,
  reset() {
    stopPerfGovernor()
    state.tier = PERF_TIER.FULL
    state.decodeMode = DECODE_MODE.SOFT
    state.load = 0
    state.lastLongTaskMs = 0
    state.lastFrameGapMs = 0
    state.heapBytes = 0
    state.heapClimbing = 0
    state.healthyWindows = 0
    state.busyMs = 0
    state.windowStart = 0
    state.aboveCount = 0
    state.belowCount = 0
    listeners.clear()
  }
}

/**
 * AI 标题润色状态机（out/ai-polish-design.md §2.2/§2.3，纯逻辑抽离便于单测）。
 *
 * idle ──润色──▶ loading ──成功──▶ candidates ──应用/放弃──▶ idle
 *                  │ 失败（R.fail / 网络错误）
 *                  ▼
 *               cooldown(60s 倒计时, 按钮禁用, 面板失败条) ──倒计时结束──▶ idle
 *
 * 契约：candidates.length ∈ [1,3]（服务端过滤后剩 1 条也返回）；前端再过滤
 * 与当前标题逐字相同的候选，全被过滤时提示改标题而不是空面板。
 */

export const COOLDOWN_SECONDS = 60

export const STATE = {
  IDLE: 'idle',
  LOADING: 'loading',
  CANDIDATES: 'candidates',
  COOLDOWN: 'cooldown'
}

export function initialPolishState() {
  return { phase: STATE.IDLE, candidates: [], remaining: 0, error: '' }
}

/** 按钮可点条件：标题 ≥2 字符、非 loading、非 cooldown、父级非 submitting（§2.2）。 */
export function canPolish({ title, phase, submitting }) {
  return typeof title === 'string'
    && title.trim().length >= 2
    && phase !== STATE.LOADING
    && phase !== STATE.COOLDOWN
    && !submitting
}

export function onPolishStart(state) {
  return { ...state, phase: STATE.LOADING, candidates: [], error: '', remaining: 0 }
}

export function onPolishSuccess(state, candidates) {
  return { ...state, phase: STATE.CANDIDATES, candidates: candidates || [], error: '' }
}

/**
 * 失败 → 60s 冷却：后端 2min 熔断与前端 60s 展示刻意不对齐（拍板项 6）——
 * 冷却结束若仍在熔断期，再点一次即返回失败并重新冷却；失败文案注明稍后再试。
 */
export function onPolishFailure(state, message) {
  return { ...state, phase: STATE.COOLDOWN, remaining: COOLDOWN_SECONDS, error: message || '', candidates: [] }
}

export function onCooldownTick(state) {
  if (state.phase !== STATE.COOLDOWN) return state
  if (state.remaining <= 1) return { ...state, phase: STATE.IDLE, remaining: 0 }
  return { ...state, remaining: state.remaining - 1 }
}

/** 应用/放弃/重置（resetForm、Modal 重开）：面板为纯前端态，一律回到 idle 并清空。 */
export function onReset(state) {
  return initialPolishState()
}

/**
 * 候选过滤（§2.3）：与当前标题逐字相同的剔除 + 列表内去重，保序。
 * 返回过滤后的候选与「是否全被过滤」，调用方据此提示「候选与当前标题相同，请修改后再试」。
 */
export function distinctCandidates(candidates, currentTitle) {
  const current = (currentTitle || '').trim()
  const seen = new Set()
  const out = []
  for (const c of candidates || []) {
    const text = typeof c === 'string' ? c : ''
    if (!text || seen.has(text)) continue
    if (current && text === current) continue
    seen.add(text)
    out.push(text)
  }
  return { candidates: out, allFiltered: (candidates || []).length > 0 && out.length === 0 }
}

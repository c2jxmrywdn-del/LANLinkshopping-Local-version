import { describe, it, expect } from 'vitest'
import {
  COOLDOWN_SECONDS, STATE,
  initialPolishState, canPolish,
  onPolishStart, onPolishSuccess, onPolishFailure, onCooldownTick, onReset,
  distinctCandidates
} from './aiPolish'

describe('aiPolish 状态机（out/ai-polish-design.md §2.2）', () => {
  it('初始态为 idle 且无候选', () => {
    const s = initialPolishState()
    expect(s.phase).toBe(STATE.IDLE)
    expect(s.candidates).toEqual([])
    expect(s.remaining).toBe(0)
    expect(s.error).toBe('')
  })

  it('canPolish：标题 ≥2 字符、非 loading、非 cooldown、父级非提交中', () => {
    const ok = { title: '304不锈钢管', phase: STATE.IDLE, submitting: false }
    expect(canPolish(ok)).toBe(true)
    expect(canPolish({ ...ok, title: '管' })).toBe(false) // 1 个字符不可点
    expect(canPolish({ ...ok, title: '  304不锈钢管  ' })).toBe(true) // trim 后 ≥2 即可
    expect(canPolish({ ...ok, title: '   ' })).toBe(false) // 空白标题不可点
    expect(canPolish({ ...ok, phase: STATE.LOADING })).toBe(false) // loading 中不可点
    expect(canPolish({ ...ok, phase: STATE.COOLDOWN })).toBe(false) // 冷却中不可点
    expect(canPolish({ ...ok, submitting: true })).toBe(false) // 父级提交中不可点
    expect(canPolish({ ...ok, phase: STATE.CANDIDATES })).toBe(true) // 候选态允许重新润色
  })

  it('成功路径：idle → loading → candidates → 应用/放弃 → idle', () => {
    let s = initialPolishState()
    s = onPolishStart(s)
    expect(s.phase).toBe(STATE.LOADING)
    expect(s.candidates).toEqual([])

    s = onPolishSuccess(s, ['标题A', '标题B'])
    expect(s.phase).toBe(STATE.CANDIDATES)
    expect(s.candidates).toEqual(['标题A', '标题B'])
    expect(s.error).toBe('')

    s = onReset(s)
    expect(s.phase).toBe(STATE.IDLE)
    expect(s.candidates).toEqual([])
  })

  it('失败路径：进入 60s 冷却并清空候选，倒计时结束回 idle（拍板项 6：与后端熔断不对齐）', () => {
    let s = onPolishStart(initialPolishState())
    s = onPolishFailure(s, 'AI 润色暂不可用，请稍后再试')
    expect(s.phase).toBe(STATE.COOLDOWN)
    expect(s.remaining).toBe(COOLDOWN_SECONDS)
    expect(s.remaining).toBe(60)
    expect(s.error).toBe('AI 润色暂不可用，请稍后再试')
    expect(s.candidates).toEqual([])

    for (let i = 0; i < 59; i++) {
      s = onCooldownTick(s)
      expect(s.phase).toBe(STATE.COOLDOWN)
    }
    s = onCooldownTick(s)
    expect(s.phase).toBe(STATE.IDLE) // 60 次倒计时后回 idle
    expect(s.remaining).toBe(0)

    // 冷却态之外 tick 不产生副作用
    expect(onCooldownTick(initialPolishState())).toEqual(initialPolishState())
  })

  it('loading 期间收到失败后重新开始会清空 error', () => {
    let s = onPolishFailure(initialPolishState(), 'err')
    s = onPolishStart(s)
    expect(s.phase).toBe(STATE.LOADING)
    expect(s.error).toBe('')
    expect(s.remaining).toBe(0)
  })

  it('resetForm/Modal 重开路径重置为初始态', () => {
    const s = onReset(onPolishSuccess(onPolishStart(initialPolishState()), ['x']))
    expect(s).toEqual(initialPolishState())
  })
})

describe('distinctCandidates 候选过滤（§2.3）', () => {
  it('剔除与当前标题逐字相同的候选与列表内重复，保序', () => {
    const { candidates, allFiltered } = distinctCandidates(
      ['304不锈钢管', '304不锈钢管【优质】', '304不锈钢管【优质】', '304不锈钢管【现货】'],
      '304不锈钢管'
    )
    expect(candidates).toEqual(['304不锈钢管【优质】', '304不锈钢管【现货】'])
    expect(allFiltered).toBe(false)
  })

  it('全部候选与当前标题逐字相同 → allFiltered=true（提示「候选与当前标题相同」）', () => {
    const { candidates, allFiltered } = distinctCandidates(['304不锈钢管', '304不锈钢管'], '304不锈钢管')
    expect(candidates).toEqual([])
    expect(allFiltered).toBe(true)
  })

  it('仅逐字比较：含空白差异的候选保留（服务端已做大小写敏感硬 token 校验）', () => {
    const { candidates, allFiltered } = distinctCandidates([' 304不锈钢管 '], '304不锈钢管')
    expect(candidates).toEqual([' 304不锈钢管 '])
    expect(allFiltered).toBe(false)
  })

  it('服务端返回 1 条且与标题不同 → 正常返回单卡（契约 [1,3]）', () => {
    const { candidates, allFiltered } = distinctCandidates(['304不锈钢管【优选】'], '304不锈钢管')
    expect(candidates).toEqual(['304不锈钢管【优选】'])
    expect(allFiltered).toBe(false)
  })

  it('空入参安全', () => {
    expect(distinctCandidates(null, 'x')).toEqual({ candidates: [], allFiltered: false })
    expect(distinctCandidates([], '')).toEqual({ candidates: [], allFiltered: false })
    expect(distinctCandidates(['a', 'b'], null).candidates).toEqual(['a', 'b'])
  })
})

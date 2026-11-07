import { describe, it, expect, beforeEach } from 'vitest'
import { t, setLocale } from './index'

describe('轻量 i18n', () => {
  beforeEach(() => setLocale('zh-CN'))

  it('中文取值', () => {
    expect(t('acct.nav.profile')).toBe('个人信息')
    expect(t('acct.profile.male')).toBe('男')
  })

  it('英文切换', () => {
    setLocale('en-US')
    expect(t('acct.nav.profile')).toBe('Profile')
    expect(t('acct.profile.male')).toBe('Male')
  })

  it('插值 {n}', () => {
    expect(t('acct.profile.bioHelp', { n: 12 })).toBe('剩余可输入 12 字')
  })

  it('未知 key 回退到 key 本身', () => {
    expect(t('no.such.key')).toBe('no.such.key')
  })

  it('核心商户资质词条可在英文界面正常显示', () => {
    document.body.innerHTML = '<div>营业执照 · 税务登记号 · 查看原件 · 记录</div>'
    setLocale('en-US')
    expect(document.body.textContent).toContain('Business License')
    expect(document.body.textContent).toContain('Tax Registration No.')
    expect(document.body.textContent).toContain('View Original')
    expect(document.body.textContent).toContain('Record')

    setLocale('zh-CN')
    expect(document.body.textContent).toContain('营业执照')
    expect(document.body.textContent).toContain('税务登记号')
    expect(document.body.textContent).toContain('查看原件')
    expect(document.body.textContent).toContain('记录')
  })

  it('快照覆盖状态词条（已分析徽标 / 未分析指引 / 网络资讯降级标注）', () => {
    document.body.innerHTML =
      '<div class="a">已分析 379/379</div>' +
      '<span class="b" title="未分析 · 整点 50 分后自动纳入">未分析 · 整点 50 分后自动纳入</span>' +
      '<span class="c">网络资讯暂缺（降级）</span>'
    setLocale('en-US')
    // 已分析 N/M：动态数字部分保留，词条部分整译
    expect(document.querySelector('.a').textContent).toBe('Analyzed 379/379')
    expect(document.querySelector('.b').textContent).toBe('Not analyzed · joins the auto chain at :50')
    expect(document.querySelector('.b').getAttribute('title')).toBe('Not analyzed · joins the auto chain at :50')
    expect(document.querySelector('.c').textContent).toBe('Web info unavailable (degraded)')

    setLocale('zh-CN')
    expect(document.querySelector('.a').textContent).toBe('已分析 379/379')
    expect(document.querySelector('.b').textContent).toBe('未分析 · 整点 50 分后自动纳入')
    expect(document.querySelector('.c').textContent).toBe('网络资讯暂缺（降级）')
  })

  it('非法语言回退中文', () => {
    setLocale('fr-FR')
    expect(t('acct.nav.profile')).toBe('个人信息')
  })
})

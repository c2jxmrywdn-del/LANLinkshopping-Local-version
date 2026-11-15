import { describe, it, expect } from 'vitest'
import appSource from './App.vue?raw'

/**
 * antd 组件语言包绑定守卫。
 *
 * <p>回归缺陷：`a-config-provider` 只绑了 `theme`，没绑 `locale`。
 * 项目 i18n 只覆盖自有文案，antd 内置文案（分页「10 / page」、空态、日期选择器等）
 * 永远走默认英文，导致中文界面里出现「10 / page」这类混排。
 *
 * <p>这里做静态契约断言，避免以后重构 ConfigProvider 时把 locale 又丢掉。
 */
describe('App.vue antd locale 绑定', () => {
  const src = appSource.replace(/\r\n/g, '\n')

  it('ConfigProvider 显式绑定 :locale', () => {
    expect(src).toMatch(/<a-config-provider[\s\S]{0,200}:locale="antdLocale"/)
  })

  it('同时引入中英文语言包', () => {
    expect(src).toContain("import zhCN from 'ant-design-vue/es/locale/zh_CN'")
    expect(src).toContain("import enUS from 'ant-design-vue/es/locale/en_US'")
  })

  it('locale 跟随项目 i18n 的 locale ref 切换', () => {
    expect(src).toContain("import { locale } from './i18n'")
    expect(src).toMatch(/const antdLocale = computed\(/)
    // 英文判断必须基于 locale.value 前缀
    expect(src).toMatch(/locale\.value[\s\S]{0,120}enUS/)
  })

  it('中文为默认分支（未匹配 en 时给 zhCN）', () => {
    const idx = src.indexOf('const antdLocale = computed')
    const body = src.slice(idx, idx + 260)
    expect(body).toContain('zhCN')
    expect(body).toContain('enUS')
    // 三元表达式里 enUS 在前、zhCN 在后 = 中文兜底
    expect(body.indexOf('enUS')).toBeLessThan(body.indexOf('zhCN'))
  })
})

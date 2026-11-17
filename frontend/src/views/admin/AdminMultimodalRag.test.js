import { describe, it, expect } from 'vitest'
import source from './AdminMultimodalRag.vue?raw'

/**
 * 多模态知识库页面的**契约测试**（源码级断言）。
 *
 * 为什么不做组件挂载测试：本仓库没有 `@vue/test-utils`，
 * 而该页面的核心风险不在渲染，而在**几个容易写错的交互约定**——
 * 这些都能在源码里断言，比挂载测试更稳、更快。
 */
describe('AdminMultimodalRag', () => {
  it('服务不可用时用常驻 a-alert，而不是一次性 toast', () => {
    // 用户可能过几分钟才回来操作，toast 早已消失，必须有常驻提示
    expect(source).toContain('<a-alert')
    expect(source).toContain('v-if="!healthy"')
  })

  it('按业务码 503 判断服务不可用（后端返回 HTTP 200 + code 503）', () => {
    // 只看 HTTP 状态会漏判：后端用 200 包 503，request.js 挂到 err.bizCode
    expect(source).toContain('bizCode === 503')
    expect(source).not.toMatch(/status\s*===\s*503/)
  })

  it('health 探测失败不弹错（silent 语义 + 判 data 本身）', () => {
    // health 是可选增强能力，挂载即探测，不该打扰用户
    expect(source).toContain('multimodalHealth')
    expect(source).toContain('data && data.ok')
  })

  it('写入时按空行切段（服务端 split_by_character="\\n\\n"）', () => {
    // 整篇塞进去会变成单个巨型 chunk，检索精度明显下降
    expect(source).toMatch(/split\(\/\\n\\s\*\\n\//)
    expect(source).toContain('paragraphs')
  })

  it('提供回退到文本知识库的入口', () => {
    expect(source).toContain('改用文本知识库')
    expect(source).toContain('goTextKb')
  })
})

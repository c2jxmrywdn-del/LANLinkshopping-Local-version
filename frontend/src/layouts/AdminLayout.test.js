import { describe, it, expect } from 'vitest'
import layoutSource from './AdminLayout.vue?raw'

/**
 * 运营后台侧边栏图标导入完整性守卫。
 *
 * <p>回归缺陷：`admin-live`（直播管理）模板里写了 `<VideoCameraOutlined />`，
 * 但导入列表漏了它 —— Vue 解析不到组件时静默渲染成空节点，
 * 表现为「这一项没有图标」，而其他项都正常，肉眼容易漏看。
 *
 * <p>这里做静态断言：模板中出现的每个 `*Outlined` 图标组件，必须在
 * `@ant-design/icons-vue` 的导入列表里。新增菜单项忘了 import 会直接失败。
 */
describe('AdminLayout 侧边栏图标', () => {
  const src = layoutSource.replace(/\r\n/g, '\n')

  function usedIcons() {
    const set = new Set()
    for (const m of src.matchAll(/<([A-Z][A-Za-z0-9]*Outlined)\b/g)) set.add(m[1])
    return [...set]
  }

  function importedIcons() {
    const idx = src.indexOf("} from '@ant-design/icons-vue'")
    expect(idx, '必须存在 @ant-design/icons-vue 导入块').toBeGreaterThan(-1)
    const start = src.lastIndexOf('import {', idx)
    expect(start, '导入块必须是 import { ... } from 形式').toBeGreaterThan(-1)
    const block = src.slice(start, idx)
    const set = new Set()
    for (const m of block.matchAll(/\b([A-Z][A-Za-z0-9]*Outlined)\b/g)) set.add(m[1])
    return [...set]
  }

  it('模板里用到的图标必须全部已导入（否则该菜单项图标为空白）', () => {
    const used = usedIcons()
    const imported = new Set(importedIcons())
    const missing = used.filter((n) => !imported.has(n))
    expect(missing, `以下图标在模板中使用但未导入：${missing.join(', ')}`).toEqual([])
  })

  it('直播管理使用 VideoCameraOutlined 且已导入（本次回归点）', () => {
    expect(src).toContain('key="admin-live"')
    expect(src).toContain('<VideoCameraOutlined />')
    expect(importedIcons()).toContain('VideoCameraOutlined')
  })

  it('不导入未使用的图标，避免打包体积无谓增大', () => {
    const used = new Set(usedIcons())
    const unused = importedIcons().filter((n) => !used.has(n))
    expect(unused, `以下图标已导入但模板未使用：${unused.join(', ')}`).toEqual([])
  })
})

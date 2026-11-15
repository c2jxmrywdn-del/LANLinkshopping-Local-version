import { describe, it, expect } from 'vitest'
import storeSource from './CenterStore.vue?raw'
import gateSource from '../../components/admin/LicenseGateImage.vue?raw'

/**
 * 商户端营业执照展示契约。
 *
 * <p>回归缺陷：原实现 `<a-image :src="store.licenseUrl">` 直接输出原件，
 * 既绕过人机验证，也把无水印原图暴露给任何能打开商户中心的人。
 *
 * <p>现在必须走 LicenseGateImage（验证码 → 票据 → 服务端水印），
 * 且商户端不得自行传 merId（越权面交给服务端按登录账号收敛）。
 */
describe('商户中心 · 营业执照展示', () => {
  const store = storeSource.replace(/\r\n/g, '\n')
  const gate = gateSource.replace(/\r\n/g, '\n')

  it('不再用 a-image 直出原始 licenseUrl', () => {
    // 去掉 HTML 注释后再断言：注释里提到历史实现是允许的
    const withoutComments = store.replace(/<!--[\s\S]*?-->/g, '')
    expect(withoutComments).not.toMatch(/<a-image[^>]*store\.licenseUrl/)
    expect(withoutComments).not.toContain(':src="store.licenseUrl"')
  })

  it('改为使用门控查看器并声明 self-service', () => {
    expect(store).toContain("import LicenseGateImage from '../../components/admin/LicenseGateImage.vue'")
    const idx = store.indexOf('<LicenseGateImage')
    expect(idx).toBeGreaterThan(-1)
    const tag = store.slice(idx, store.indexOf('/>', idx))
    expect(tag).toContain('self-service')
    expect(tag).toContain(':has-license="true"')
  })

  it('门控组件支持 selfService 模式并切换到商户自助接口', () => {
    expect(gate).toContain('selfService')
    expect(gate).toContain('myLicenseCaptcha')
    expect(gate).toContain('myLicenseVerify')
    expect(gate).toContain('/merchant/license-watermarked')
  })

  it('自助模式下不向前端传 merId（防越权看他人执照）', () => {
    // 提交逻辑必须是条件式：selfService 时不写 merId
    expect(gate).toMatch(/if\s*\(!props\.selfService\)\s*payload\.merId\s*=\s*props\.merId/)
  })

  it('水印图请求带 ticket 且不含明文执照地址', () => {
    const idx = gate.indexOf('watermarkedSrc.value = props.selfService')
    expect(idx).toBeGreaterThan(-1)
    const block = gate.slice(idx, idx + 400)
    expect(block).toContain('ticket=')
    expect(block).not.toContain('licenseUrl')
  })
})

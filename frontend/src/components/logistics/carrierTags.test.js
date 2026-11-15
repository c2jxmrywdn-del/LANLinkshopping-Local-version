import { describe, expect, it } from 'vitest'
import { t } from '../../i18n'
import {
  TAG_I18N_KEYS,
  isInternationalQuote
} from '../../components/logistics/carrierTags'

describe('carrier capability tags i18n mapping', () => {
  it('maps every enum key to an existing i18n key with zh text', () => {
    for (const [enumKey, i18nKey] of Object.entries(TAG_I18N_KEYS)) {
      const zh = t(i18nKey)
      expect(zh, `missing zh dict entry for ${i18nKey}`).not.toBe(i18nKey)
    }
  })

  it('renders heavy/international tags in zh locale', () => {
    expect(t('logistics.tagHeavy')).toBe('大件专线')
    expect(t('logistics.tagInternational')).toBe('国际线路 · 报价联系客服')
  })

  it('parameterizes free-shipping badge amount via t()', () => {
    expect(t('logistics.freeShippingOver', { x: 199 })).toBe('满 ¥199 包邮')
    expect(t('logistics.freeShippingOver', { x: 0 })).toBe('满 ¥0 包邮')
  })

  it('flags international quotes by capability tag', () => {
    expect(isInternationalQuote({ capabilityTags: ['INTERNATIONAL'] })).toBe(true)
    expect(isInternationalQuote({ capabilityTags: ['HEAVY'] })).toBe(false)
    expect(isInternationalQuote({})).toBe(false)
  })

  it('intl blocked message is an exact-match dictionary term', () => {
    const zh = '国际线路暂不支持线上发货，请联系客服询价'
    expect(t('logistics.intlBlocked')).toBe(zh)
  })
})

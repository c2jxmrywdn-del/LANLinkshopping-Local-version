// 承运商能力标签枚举 ↔ i18n 键映射与图标映射
// （CarrierRecommendationCards.vue 与测试共用；新增标签先补 i18n/logistics 词条再入此表）
export const TAG_I18N_KEYS = {
  HEAVY: 'logistics.tagHeavy',
  PALLET: 'logistics.tagPallet',
  GUARANTEED: 'logistics.tagGuaranteed',
  INSTALL: 'logistics.tagInstall',
  LOW_COST: 'logistics.tagLowCost',
  INTERNATIONAL: 'logistics.tagInternational',
  SELF: 'logistics.tagSelf'
}

export const TAG_ICONS = {
  HEAVY: 'Weight',
  PALLET: 'HandPlatter',
  GUARANTEED: 'BadgeCheck',
  INSTALL: 'Forklift',
  LOW_COST: 'PiggyBank',
  INTERNATIONAL: 'Globe',
  SELF: 'Store'
}

// 国际线路询价制报价：卡片不渲染预估运费
export function isInternationalQuote(quote) {
  const tags = quote?.capabilityTags || []
  return tags.includes('INTERNATIONAL')
}

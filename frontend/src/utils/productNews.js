/** 过宽词不能拿来判断「这条资讯影不影响本商品」。 */
const HINT_STOP = new Set(['价格', '行情', '市场', '行业', '商品', '采购', '供应', '供给', '需求', '政策', '订单', '公司', '中国'])

/**
 * 行业名上的补充词，和因子画像里的行业词典对齐。
 * 商品全名（如「硅酸盐水泥」）对不上资讯里的「水泥」时，靠这些词补上。
 */
const INDUSTRY_HINTS = [
  ['建筑', ['水泥', '钢材', '建材', '螺纹钢', '混凝土', '管材', '基建']],
  ['建材', ['水泥', '钢材', '建材', '螺纹钢', '混凝土', '管材']],
  ['纺织', ['纺织', '纱线', '面料', '坯布', '涤纶', '棉']],
  ['石化', ['化工', '原油', '树脂', '塑料', '纯碱']],
  ['化工', ['化工', '原油', '树脂', '塑料', '纯碱']],
  ['电子', ['元器件', '芯片', '半导体', '面板', '连接器']],
  ['钢铁', ['钢铁', '钢材', '铁矿石', '螺纹钢', '钢坯']],
  ['机械', ['机械', '机床', '轴承', '液压']],
  ['能源', ['电力', '电价', '能源', '光伏']],
  ['电力', ['电力', '电价', '能源']],
  ['包装', ['包装', '纸浆', '瓦楞', '印刷']],
  ['新能源', ['锂', '电池', '光伏', '新能源']]
]

function absorb(bag, raw) {
  const text = String(raw || '').trim()
  // 只收短词（品牌、规格、短标题）。长标题靠下面的行业词命中，避免「普通」「盐水」这类切词误伤。
  if (text.length >= 2 && text.length <= 6 && !HINT_STOP.has(text)) bag.add(text)
  const runs = text.match(/[\u4e00-\u9fff]+/g) || []
  for (const run of runs) {
    if (run.length >= 2 && run.length <= 6 && !HINT_STOP.has(run)) bag.add(run)
  }
}

/** 当前商品能被哪些词命中：标题、品牌、规格、行业名，外加行业同义词。 */
export function productHints(product, trend) {
  const bag = new Set()
  absorb(bag, product?.title)
  absorb(bag, product?.brand)
  absorb(bag, product?.spec)
  absorb(bag, trend?.industryName)
  const scope = `${trend?.industryName || ''}${product?.title || ''}${product?.brand || ''}${product?.spec || ''}`
  const known = new Set(INDUSTRY_HINTS.flatMap(([, words]) => words))
  for (const word of known) {
    if (scope.includes(word)) bag.add(word)
  }
  for (const [key, words] of INDUSTRY_HINTS) {
    if (scope.includes(key)) words.forEach((word) => bag.add(word))
  }
  return [...bag].filter((word) => word.length >= 2 && !HINT_STOP.has(word))
}

/** 资讯标题、摘要或标签里出现商品词，才算会影响这件商品。 */
export function storyAffectsProduct(story, product, trend) {
  const hints = productHints(product, trend)
  if (!hints.length || !story) return false
  const text = `${story.title || ''} ${story.summary || ''} ${story.tags || ''}`.toLowerCase()
  return hints.some((hint) => text.includes(String(hint).toLowerCase()))
}

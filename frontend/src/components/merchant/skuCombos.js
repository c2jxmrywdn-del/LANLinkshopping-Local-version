/**
 * SKU 组合生成纯函数（从 SkuEditor.vue 抽出，便于单测）。
 *
 * @param {Array<{name:string, values:string[]}>} specs 规格维度
 * @returns {Array<Array<{specIndex:number, valueIndex:number}>>} 笛卡尔积组合；
 *          每维取且仅取一个值；无维度返回空数组。
 */
export function buildCombos(specs) {
  if (!specs.length) return []
  let combos = [[]]
  specs.forEach((spec, specIndex) => {
    const next = []
    combos.forEach(prefix => {
      spec.values.forEach((_, valueIndex) => next.push([...prefix, { specIndex, valueIndex }]))
    })
    combos = next
  })
  return combos
}

/**
 * 组合的「内容键」：用于 skuRows（组合 → 价格/库存/编码）的稳定索引。
 *
 * 不能按下标做键：删除某维的某个值后，后续 valueIndex 整体前移，按下标存的旧行会被
 * 幸存组合错误继承（价格/编码串号，属静默的成交数据损坏）。改为按「维度下标 + 值文本」
 * 做键，删除同维的其它值不会改变幸存组合的键；保留 specIndex 使不同维度里的同名值不撞键。
 *
 * @param {Array<{specIndex:number, valueIndex:number}>} combo buildCombos 产出的单个组合
 * @param {Array<{name:string, values:string[]}>} specs 该组合对应的规格维度
 * @returns {string} 形如 "0=红|1=S"
 */
export function comboKey(combo, specs) {
  return combo.map(r => r.specIndex + '=' + (specs[r.specIndex]?.values?.[r.valueIndex] ?? '')).join('|')
}

/**
 * 后端 specValueIds 的规范形式：数值升序、逗号分隔。
 *
 * 必须按数值排序而非字典序：后端 SkuService.save 用 Collections.sort(List<Long>)，
 * 故 valueId 20 与 100 的组合是 "20,100"；字典序会给出 "100,20" 而匹配不上。
 *
 * @param {Array<number|string>} ids
 * @returns {string} 形如 "20,23"
 */
export function buildValueIdKey(ids) {
  return ids
    .map(Number)
    .sort((a, b) => a - b)
    .join(',')
}

/**
 * 组合 → 该组合各维取值的 valueId 列表（顺序同 combo 的维度顺序）。
 *
 * 规格值文本可以合法包含 ';' 或 ':'（后端只校验空/长度/合规），因此用展示文本
 * specText 作回填键会有歧义；valueId 才是无歧义标识。
 * specs[i].valueIdByText 由 SkuEditor.load() 从 SkuTreeView.specs[].values[].valueId 建立
 * （同维内规格值文本唯一，故 text→valueId 是一一映射）。
 *
 * @returns {number[]|null} 任一取值解析不到 valueId 时返回 null（调用方据此回退）
 */
export function comboValueIds(combo, specs) {
  const ids = []
  for (const r of combo) {
    const spec = specs[r.specIndex]
    const text = spec?.values?.[r.valueIndex]
    const id = spec?.valueIdByText?.[text]
    if (id === undefined || id === null || id === '') return null
    ids.push(id)
  }
  return ids
}

/**
 * 组合的回填键：与后端 t_product_sku.spec_value_ids 同构的升序逗号串。
 * @returns {string|null} 无法解析 valueId 时返回 null（调用方回退到文本匹配）
 */
export function valueIdKeyOf(combo, specs) {
  const ids = comboValueIds(combo, specs)
  return ids ? buildValueIdKey(ids) : null
}

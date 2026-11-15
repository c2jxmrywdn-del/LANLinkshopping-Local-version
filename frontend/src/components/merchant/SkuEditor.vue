<template>
  <a-card :title="txt(L.title)" size="small" class="sku-editor">
    <a-spin :spinning="loading">
      <div class="sku-sub">{{ txt(L.dim) }}</div>

      <div v-for="(spec, si) in specs" :key="si" class="spec-dim">
        <a-input v-model:value="spec.name" :placeholder="txt(L.dimName)" :maxlength="20" style="width:160px" />
        <a-tag v-for="(v, vi) in spec.values" :key="vi" closable @close="removeValue(si, vi)">{{ v }}</a-tag>
        <a-input v-model:value="newValue[si]" :placeholder="txt(L.specValue)" :maxlength="40" style="width:120px"
                 @pressEnter="addValue(si)" />
        <a-button size="small" @click="addValue(si)">{{ txt(L.addValue) }}</a-button>
        <a-button size="small" danger type="text" @click="removeSpec(si)">{{ txt(L.delete) }}</a-button>
      </div>

      <a-button type="dashed" block :disabled="specs.length >= 3" @click="addSpec">+ {{ txt(L.addDim) }}</a-button>

      <a-alert v-if="tooManyCombos" type="warning" show-icon :message="txt(L.tooMany)"
               style="margin-top:12px" />

      <a-table v-if="combos.length" :columns="columns" :data-source="comboRows" :pagination="pagination"
               size="small" row-key="key" style="margin-top:12px">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'price'">
            <a-input-number v-model:value="record.row.price" :min="0.01" :precision="2" style="width:100%" />
          </template>
          <template v-else-if="column.key === 'stock'">
            <a-input-number v-model:value="record.row.stock" :min="0" :precision="0" style="width:100%" />
          </template>
          <template v-else-if="column.key === 'skuCode'">
            <a-input v-model:value="record.row.skuCode" :maxlength="64" />
          </template>
        </template>
      </a-table>
      <a-empty v-else :description="txt(L.empty)" />

      <a-button type="primary" :loading="saving" :disabled="!loaded || saving || tooManyCombos"
                @click="save" style="margin-top:12px">
        {{ txt(L.save) }}
      </a-button>
    </a-spin>
  </a-card>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { skuApi } from '../../api'
import { buildCombos, comboKey, valueIdKeyOf, buildValueIdKey } from './skuCombos.js'
import { locale } from '../../i18n'
import { UI_ZH_EN_EXTRA } from '../../i18n/zh-en-extra'

/**
 * 商户商品 SKU 多规格编辑器（规格维度 + 笛卡尔积组合矩阵）。
 *
 * 契约：GET /product/my/sku/{prodId} → SkuTreeView；POST /product/my/sku/{prodId}/save
 * 请求体即后端 SkuTreeSaveRequest：{ specs:[{name,values:[String]}], skus:[{values:[{specIndex,valueIndex}],price,stock,skuCode}] }。
 * 组合生成抽到 ./skuCombos.js 纯函数（单测覆盖）；本组件只做交互与回填。
 */
const props = defineProps({ prodId: { type: [String, Number], required: true } })

/** 全部 UI 文案键集中在此：模板保持零中文，一律经 txt() 取词 */
const L = {
  title: '多规格 SKU',
  dim: '规格维度',
  dimName: '维度名',
  specValue: '规格值',
  addValue: '添加值',
  addDim: '添加维度',
  delete: '删除',
  combo: 'SKU 组合',
  price: '价格',
  stock: '库存',
  code: '编码',
  save: '保存规格',
  empty: '暂无规格，添加维度生成 SKU',
  saved: '规格保存成功',
  maxValues: '每维最多 30 个值',
  maxDims: '最多 3 个维度',
  needPrice: '请填写每个组合的价格',
  tooMany: 'SKU 数量超过 200，请减少规格组合',
  notLoaded: '加载失败，无法保存',
}

const isEn = computed(() => String(locale.value || '').toLowerCase().startsWith('en'))
const txt = (zh) => (isEn.value ? (UI_ZH_EN_EXTRA[zh] || zh) : zh)

const loading = ref(false)
const saving = ref(false)
// 仅在 GET 成功后置 true：save 是「全量替换」语义，若加载失败仍允许保存会把整棵 SKU 树清空
const loaded = ref(false)
const specs = ref([])            // [{name, values:[String]}]
const newValue = reactive({})    // si -> 输入中的规格值
const skuRows = ref({})          // comboKey -> {price, stock, skuCode}

const combos = computed(() => buildCombos(specs.value))
// 与后端 SkuService 上限一致：超过 200 个组合后端必 400，前端提前拦截并提示
const MAX_COMBOS = 200
const comboCount = computed(() => combos.value.length)
const tooManyCombos = computed(() => comboCount.value > MAX_COMBOS)
const pagination = { pageSize: 20, size: 'small', showSizeChanger: false }

/** 组合展示文本：与后端 SkuTreeView.skus[].specText（"规格名:值;规格名:值"）同构，用于回填匹配 */
function comboTextOf(specList, combo) {
  return combo
    .map(r => {
      const spec = specList[r.specIndex]
      if (!spec) return ''
      return `${spec.name}:${spec.values[r.valueIndex]}`
    })
    .join(';')
}
const comboText = (c) => comboTextOf(specs.value, c)

const emptyRow = () => ({ price: undefined, stock: 0, skuCode: '' })

// 组合变化时补全缺失行（副作用放在 watch，comboRows 保持纯只读投影）。
// 默认 pre flush：watcher 在组件重渲染前执行，故新行在渲染前已就位；save 路径另有兜底。
// 键为内容键（维度下标 + 值文本）：删除同维其它值不会让幸存组合换行，价格/编码不会串号。
watch(combos, (list) => {
  const store = skuRows.value || {}
  let changed = false
  list.forEach(c => {
    const k = comboKey(c, specs.value)
    if (!store[k]) { store[k] = emptyRow(); changed = true }
  })
  if (changed) skuRows.value = { ...store }
}, { immediate: true })

const comboRows = computed(() => combos.value.map(c => {
  const k = comboKey(c, specs.value)
  return { key: k, text: comboText(c), row: skuRows.value[k] || emptyRow() }
}))

const columns = computed(() => [
  { title: txt(L.combo), dataIndex: 'text', key: 'text' },
  { title: txt(L.price), key: 'price' },
  { title: txt(L.stock), key: 'stock' },
  { title: txt(L.code), key: 'skuCode' },
])

function addSpec() {
  if (specs.value.length >= 3) { message.warning(txt(L.maxDims)); return }
  specs.value.push({ name: '', values: [] })
}
function removeSpec(si) {
  specs.value.splice(si, 1)
  // 维度下标整体前移，输入中的规格值无法按旧下标对应，清空避免错位
  Object.keys(newValue).forEach(k => { delete newValue[k] })
}
function addValue(si) {
  const v = (newValue[si] || '').trim()
  if (!v) return
  const spec = specs.value[si]
  if (!spec) return
  if (spec.values.length >= 30) { message.warning(txt(L.maxValues)); return }
  // 长度上限由输入框 maxlength=40 保证，无需在此重复判断
  if (!spec.values.includes(v)) spec.values.push(v)
  newValue[si] = ''
}
function removeValue(si, vi) {
  const spec = specs.value[si]
  if (spec) spec.values.splice(vi, 1)
}

async function load() {
  if (!props.prodId) return
  loading.value = true
  // 重新加载（含切换 prodId）期间先锁保存：上一次的成功状态不能沿用给新商品
  loaded.value = false
  try {
    // request 拦截器已解包 R.data：直接拿到 SkuTreeView
    const tree = (await skuApi.get(props.prodId)) || {}
    const loadedSpecs = (tree.specs || []).map(s => {
      const vals = s.values || []
      const valueIdByText = {}
      vals.forEach(v => { if (v && v.value != null && v.valueId != null) valueIdByText[v.value] = v.valueId })
      return { name: s.name || '', values: vals.map(v => v.value), valueIdByText }
    })
    specs.value = loadedSpecs
    // 已存 SKU 按 specValueIds（valueId 升序逗号串）回填 —— 这是无歧义键。
    // 规格值文本可以合法包含 ';' 或 ':'（后端只校验空白/长度/合规），故用展示文本
    // specText 作键时两个不同组合可能塌缩成同一键，静默串价/串库存/串编码。
    const byValueIds = new Map()
    const byText = new Map()
    ;(tree.skus || []).forEach(k => {
      if (!k) return
      if (k.specValueIds) byValueIds.set(buildValueIdKey(String(k.specValueIds).split(',')), k)
      if (k.specText) byText.set(k.specText, k)
    })
    const next = {}
    buildCombos(loadedSpecs).forEach(c => {
      const idKey = valueIdKeyOf(c, loadedSpecs)
      // 仅在 valueId 解析不出来时（理论上只发生在老后端无 valueId）回退到文本匹配
      const saved = idKey ? byValueIds.get(idKey) : byText.get(comboTextOf(loadedSpecs, c))
      next[comboKey(c, loadedSpecs)] = saved
        ? { price: Number(saved.price), stock: Number(saved.stock ?? 0), skuCode: saved.skuCode || '' }
        : emptyRow()
    })
    skuRows.value = next
    // 只有真正拿到服务端状态后才解锁保存（save 是全量替换，空态保存会清空后端 SKU）
    loaded.value = true
  } catch (e) {
    // 静默降级：编辑态商品可能尚无 SKU（后端返回空树），异常不阻断商品编辑主流程；
    // 但 loaded 保持 false，Save 被禁用，避免把「加载失败」误当「无规格」而清空数据。
    console.warn('[SkuEditor] load failed', e)
  } finally {
    loading.value = false
  }
}

async function save() {
  if (!loaded.value) { message.warning(txt(L.notLoaded)); return }
  if (tooManyCombos.value) { message.warning(txt(L.tooMany)); return }
  for (const s of specs.value) {
    if (!s.name.trim()) { message.warning(txt(L.dimName)); return }
    if (!s.values.length) { message.warning(txt(L.specValue)); return }
  }
  const rows = combos.value.map(c => ({ combo: c, row: skuRows.value[comboKey(c, specs.value)] || emptyRow() }))
  if (rows.some(r => !(Number(r.row.price) > 0))) { message.warning(txt(L.needPrice)); return }

  const payload = {
    specs: specs.value.map(s => ({ name: s.name.trim(), values: s.values.map(v => String(v).trim()) })),
    skus: rows.map(({ combo, row }) => ({
      values: combo,
      price: Number(row.price),
      stock: Math.max(0, Math.trunc(Number(row.stock) || 0)),
      skuCode: (row.skuCode || '').trim() || null,
    })),
  }

  saving.value = true
  try {
    await skuApi.save(props.prodId, payload)
    message.success(txt(L.saved))
    await load()
  } catch (e) {
    // 拦截器已按后端 message 统一提示，此处不重复 toast（与 CenterProductEdit 既有约定一致）
  } finally {
    saving.value = false
  }
}

onMounted(load)
watch(() => props.prodId, load)
</script>

<style scoped>
.sku-editor { margin-top: 0; }
.sku-sub { font-size: 12px; color: var(--mc-muted2, #86868b); margin-bottom: 8px; }
.spec-dim { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-bottom: 10px; }
</style>

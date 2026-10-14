<template>
  <div class="industry-mega">
    <section class="industry-pane">
      <div class="pane-head">
        <div>
          <strong>行业</strong>
          <small>INDUSTRIES · {{ industries.length }}</small>
        </div>
        <span v-if="loading" class="syncing">同步中…</span>
        <span v-else class="syncing">自动同步</span>
      </div>

      <button type="button" class="all-entry" :class="{ active: !activeIndustry }" @click.stop="selectAll">
        <span>全部商品</span>
        <i>→</i>
      </button>

      <div class="industry-grid">
        <button
          v-for="industry in industries"
          :key="industry.indId"
          type="button"
          class="industry-item"
          :class="{ active: activeIndustry?.indId === industry.indId }"
          @mouseenter="setActive(industry)"
          @focus="setActive(industry)"
          @click.stop="selectIndustry(industry)"
        >
          <span>{{ industry.name }}</span>
          <i>›</i>
        </button>
      </div>

      <div v-if="error" class="load-error">
        <span>行业数据暂时无法更新</span>
        <button type="button" @click.stop="refresh(true)">重试</button>
      </div>
    </section>

    <section class="category-pane">
      <div class="pane-head">
        <div>
          <strong>{{ activeIndustry?.name || '全部行业' }}</strong>
          <small>PRODUCT CATEGORIES</small>
        </div>
        <span v-if="activeIndustry">{{ activeIndustryCount }} 类</span>
      </div>

      <div v-if="activeIndustry && categoryTree.length" class="category-grid">
        <article v-for="root in categoryTree" :key="root.catId" class="category-group">
          <button type="button" class="root-cat" @click.stop="selectCategory(root)">
            <span class="category-dot"></span>
            <b>{{ root.name }}</b>
            <em>进入</em>
          </button>

          <div v-if="root.children?.length" class="child-list">
            <button
              v-for="child in root.children"
              :key="child.catId"
              type="button"
              class="child-cat"
              @click.stop="selectCategory(child)"
            >
              <span>{{ child.name }}</span>
              <i v-if="child.children?.length">{{ child.children.length }}</i>
            </button>

            <template v-for="child in root.children" :key="`leaf-${child.catId}`">
              <div v-if="child.children?.length" class="leaf-wrap">
                <span class="leaf-label">{{ child.name }}</span>
                <button
                  v-for="leaf in child.children"
                  :key="leaf.catId"
                  type="button"
                  class="leaf-cat"
                  @click.stop="selectCategory(leaf)"
                >
                  {{ leaf.name }}
                </button>
              </div>
            </template>
          </div>
        </article>
      </div>

      <div v-else-if="activeIndustry" class="empty-state">
        <strong>该行业暂无细分分类</strong>
        <span>行业已自动接入，等待商品分类配置。</span>
      </div>

      <div v-else class="empty-state">
        <strong>综合商品中心</strong>
        <span>覆盖平台当前全部行业采购供给。</span>
      </div>
    </section>

    <section class="quick-pane">
      <div class="pane-head">
        <div>
          <strong>采购入口</strong>
          <small>QUICK ACCESS</small>
        </div>
      </div>

      <button type="button" class="shortcut-card" @click.stop="quick('all')">
        <b>全部商品</b>
        <span>查看全平台供给</span>
        <i>→</i>
      </button>
      <button type="button" class="shortcut-card" @click.stop="quick('sales')">
        <b>热销采购</b>
        <span>按销量快速筛选</span>
        <i>→</i>
      </button>
      <button type="button" class="shortcut-card" @click.stop="quick('promotions')">
        <b>采购优惠</b>
        <span>满减与行业折扣</span>
        <i>→</i>
      </button>
    </section>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { homeApi } from '../api'

const props = defineProps({
  visible: { type: Boolean, default: false },
  selectedIndustryId: { type: [Number, String], default: 0 },
  refreshInterval: { type: Number, default: 60000 }
})

const emit = defineEmits(['select-all', 'select-industry', 'select-category', 'quick'])

const industries = ref([])
const categories = ref([])
const activeIndustry = ref(null)
const loading = ref(false)
const error = ref('')
let refreshTimer = null

const categoryTree = computed(() => {
  if (!activeIndustry.value) return []
  return buildTree(
    categories.value.filter(c => Number(c.indId) === Number(activeIndustry.value.indId))
  )
})

const activeIndustryCount = computed(() => {
  const count = categories.value.filter(
    c => Number(c.indId) === Number(activeIndustry.value?.indId)
  ).length
  return count
})

function sortItems(items) {
  return [...items].sort((a, b) => {
    const sortDelta = Number(a.sort || 0) - Number(b.sort || 0)
    if (sortDelta !== 0) return sortDelta
    return String(a.name || '').localeCompare(String(b.name || ''), 'zh-CN')
  })
}

function buildTree(items) {
  const map = new Map()
  const roots = []

  for (const item of sortItems(items)) {
    map.set(String(item.catId), { ...item, children: [] })
  }

  for (const node of map.values()) {
    const parentId = Number(node.parentId || 0)
    const parent = map.get(String(parentId))
    if (parent && parent.catId !== node.catId) parent.children.push(node)
    else roots.push(node)
  }

  return sortItems(roots).map(root => ({
    ...root,
    children: sortItems(root.children).map(child => ({
      ...child,
      children: sortItems(child.children)
    }))
  }))
}

function applyIndustrySelection(list) {
  const preferredId = Number(props.selectedIndustryId || 0)
  const preferred = list.find(item => Number(item.indId) === preferredId)
  activeIndustry.value = preferred || list[0] || null
}

async function refresh(force = false) {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const [industryRows, categoryRows] = await Promise.all([
      homeApi.industries(),
      homeApi.categories()
    ])
    industries.value = sortItems(industryRows || [])
    categories.value = categoryRows || []
    const currentId = Number(activeIndustry.value?.indId || 0)
    activeIndustry.value =
      industries.value.find(item => Number(item.indId) === currentId) || null
    if (!activeIndustry.value) applyIndustrySelection(industries.value)
  } catch (e) {
    error.value = 'load'
    if (force && !industries.value.length) applyIndustrySelection([])
  } finally {
    loading.value = false
  }
}

function setActive(industry) {
  activeIndustry.value = industry
}

function selectAll() {
  emit('select-all')
}

function selectIndustry(industry) {
  activeIndustry.value = industry
  emit('select-industry', industry)
}

function selectCategory(category) {
  emit('select-category', {
    ...category,
    industryId: activeIndustry.value?.indId
  })
}

function quick(action) {
  emit('quick', action)
}

function handleFocus() {
  if (props.visible) refresh()
}

watch(
  () => props.visible,
  visible => {
    if (visible) refresh()
  }
)

watch(
  () => props.selectedIndustryId,
  value => {
    const item = industries.value.find(ind => Number(ind.indId) === Number(value || 0))
    if (item) activeIndustry.value = item
  }
)

onMounted(() => {
  refresh(true)
  refreshTimer = window.setInterval(() => refresh(), Math.max(15000, props.refreshInterval))
  window.addEventListener('focus', handleFocus)
})

onBeforeUnmount(() => {
  if (refreshTimer) window.clearInterval(refreshTimer)
  window.removeEventListener('focus', handleFocus)
})
</script>

<style scoped>
.industry-mega {
  position: absolute;
  left: 0;
  top: 58px;
  width: min(1040px, calc(100vw - 40px));
  display: grid;
  grid-template-columns: 290px minmax(420px, 1fr) 250px;
  background: rgba(255,255,255,.975);
  border: 1px solid rgba(148,163,184,.22);
  border-radius: 0 0 20px 20px;
  box-shadow: 0 22px 58px rgba(15,23,42,.22);
  backdrop-filter: blur(22px) saturate(125%);
  overflow: hidden;
  z-index: 120;
  animation: industryMegaIn .16s ease-out;
}

.industry-pane,
.category-pane,
.quick-pane {
  min-height: 430px;
  padding: 18px 16px;
}

.industry-pane {
  background: linear-gradient(180deg, rgba(19,35,58,.055), rgba(19,35,58,.018));
  border-right: 1px solid rgba(148,163,184,.18);
}

.category-pane { border-right: 1px solid rgba(148,163,184,.18); }

.pane-head {
  min-height: 28px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.pane-head > div {
  display: flex;
  align-items: flex-end;
  gap: 12px;
}

.pane-head strong {
  color: var(--ll-navy,#13233A);
  font-weight: 800;
  font-size: 14px;
}

.pane-head small {
  color: #94a3b8;
  font-size: 9px;
  letter-spacing: .12em;
}

.pane-head > span {
  color: #9aa6b2;
  font-size: 10px;
}

.syncing { color: #8c9aaa !important; white-space: nowrap; }

.all-entry,
.industry-item,
.root-cat,
.child-cat,
.leaf-cat,
.shortcut-card {
  width: 100%;
  border: 0;
  cursor: pointer;
  text-align: left;
  font: inherit;
  background: transparent;
}

.all-entry,
.industry-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 10px;
  border-radius: 10px;
  color: #526070;
  transition: background .18s ease, color .18s ease, transform .18s ease;
}

.all-entry { margin-bottom: 6px; }

.all-entry:hover,
.all-entry.active,
.industry-item:hover,
.industry-item.active {
  background: rgba(14,165,233,.10);
  color: var(--ll-navy,#13233A);
  transform: translateX(2px);
}

.all-entry i,
.industry-item i {
  color: #a0aec0;
  font-size: 17px;
  line-height: 1;
  font-style: normal;
}

.industry-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  max-height: 345px;
  overflow: auto;
  padding-right: 3px;
  scrollbar-width: thin;
}

.industry-grid::-webkit-scrollbar { width: 5px; }
.industry-grid::-webkit-scrollbar-thumb {
  background: rgba(100,116,139,.20);
  border-radius: 8px;
}

.category-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0,1fr));
  gap: 12px;
  max-height: 370px;
  overflow: auto;
  padding-right: 4px;
  scrollbar-width: thin;
}

.category-grid::-webkit-scrollbar { width: 5px; }
.category-grid::-webkit-scrollbar-thumb {
  background: rgba(100,116,139,.18);
  border-radius: 8px;
}

.category-group {
  border: 1px solid rgba(148,163,184,.16);
  border-radius: 14px;
  padding: 8px;
  background: rgba(248,250,252,.56);
  transition: transform .18s ease, box-shadow .18s ease, border-color .18s ease;
}

.category-group:hover {
  transform: translateY(-1px);
  border-color: rgba(14,165,233,.20);
  box-shadow: 0 8px 22px rgba(15,23,42,.06);
}

.root-cat {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 5px 8px;
  color: var(--ll-navy,#13233A);
}

.root-cat b { font-size: 13px; }
.root-cat em {
  margin-left: auto;
  color: #9aa6b2;
  font-size: 10px;
  font-style: normal;
}

.category-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--ll-amber,#C8A45C);
  flex: 0 0 auto;
}

.child-list { display: grid; gap: 3px; }

.child-cat {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 7px 6px;
  border-radius: 8px;
  color: #526070;
  transition: .16s ease;
}

.child-cat:hover {
  background: rgba(200,164,92,.10);
  color: var(--ll-navy,#13233A);
}

.child-cat i {
  color: #b0bac5;
  font-style: normal;
  font-size: 9px;
}

.leaf-wrap {
  margin: 3px 3px 4px 10px;
  padding: 5px 0 2px 8px;
  border-left: 1px solid rgba(148,163,184,.20);
}

.leaf-label {
  display: block;
  color: #a0aab5;
  font-size: 9px;
  margin-bottom: 3px;
}

.leaf-cat {
  display: inline-block;
  width: auto;
  margin: 0 4px 4px 0;
  padding: 4px 7px;
  border-radius: 7px;
  background: rgba(19,35,58,.045);
  color: #6b7785;
  font-size: 10px;
  transition: .16s ease;
}

.leaf-cat:hover {
  background: rgba(14,165,233,.10);
  color: var(--ll-navy,#13233A);
}

.shortcut-card {
  position: relative;
  display: block;
  padding: 14px 34px 14px 12px;
  margin-bottom: 9px;
  border: 1px solid rgba(148,163,184,.18);
  border-radius: 12px;
  background: linear-gradient(135deg,rgba(255,255,255,.95),rgba(248,250,252,.76));
  transition: .18s ease;
}

.shortcut-card:hover {
  transform: translateY(-1px);
  border-color: rgba(14,165,233,.25);
  box-shadow: 0 8px 20px rgba(15,23,42,.08);
}

.shortcut-card b {
  display: block;
  color: var(--ll-navy,#13233A);
  font-size: 13px;
  margin-bottom: 4px;
}

.shortcut-card span {
  display: block;
  color: #94a3b8;
  font-size: 11px;
}

.shortcut-card i {
  position: absolute;
  right: 12px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--ll-amber-strong,#a97f2f);
  font-style: normal;
}

.empty-state {
  min-height: 300px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: #94a3b8;
  text-align: center;
}

.empty-state strong {
  color: var(--ll-navy,#13233A);
  font-size: 15px;
}

.empty-state span { font-size: 12px; }

.load-error {
  margin-top: 8px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 10px;
  border-radius: 9px;
  background: rgba(239,68,68,.05);
  color: #8c3f3f;
  font-size: 10px;
}

.load-error button {
  border: 0;
  background: transparent;
  color: #9b2d20;
  cursor: pointer;
  font: inherit;
  padding: 0;
}

@keyframes industryMegaIn {
  from { opacity: 0; transform: translateY(-5px); }
  to { opacity: 1; transform: translateY(0); }
}

@media (max-width: 1100px) {
  .industry-mega { width: min(940px, calc(100vw - 28px)); grid-template-columns: 260px minmax(380px,1fr) 220px; }
}

@media (max-width: 767px) {
  .industry-mega { display: none; }
}
</style>

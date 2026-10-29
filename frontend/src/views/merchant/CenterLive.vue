<template>
  <div class="cl-page">
    <div class="cl-head">
      <div>
        <h2>直播带货</h2>
        <p>开播即卖货：挂载商品、实时讲解、复盘转化一站完成。</p>
      </div>
      <a-button v-if="mode === 'console' && room" @click="enterRoom">
        <Video :size="14" :stroke-width="2" style="vertical-align:-2px;margin-right:4px" />进入直播间
      </a-button>
    </div>

    <a-spin :spinning="loading">
      <!-- ===== 创建直播间 ===== -->
      <div v-if="mode === 'create'" class="cl-card">
        <div class="cl-card-head"><b>创建直播间</b><span>标题必填，其余可留空稍后补全</span></div>
        <div class="cl-form">
          <label class="cl-field">
            <span>直播间标题<i class="req">*</i></span>
            <a-input v-model:value="form.title" placeholder="例如：周五工厂直供专场" :maxlength="60" show-count />
          </label>
          <label class="cl-field">
            <span>封面图 URL</span>
            <a-input v-model:value="form.coverUrl" placeholder="https://..." />
          </label>
          <label class="cl-field">
            <span>视频流地址（可选，HLS/MP4）</span>
            <a-input v-model:value="form.streamUrl" placeholder="https://.../live.m3u8" />
          </label>
          <label class="cl-field">
            <span>直播间公告</span>
            <a-textarea v-model:value="form.notice" placeholder="开播时间、优惠玩法、售后说明等" :rows="2" :maxlength="200" show-count />
          </label>
          <div class="cl-field">
            <span>平台审核</span>
            <a-alert type="info" show-icon message="直播间创建后进入平台审核，审核通过后即可开播。" />
          </div>

          <div class="cl-field">
            <span>挂载商品<span class="hint">（从我的在售商品中选择，可设置直播价与直播库存）</span></span>
            <a-select
              v-model:value="selectedProdIds"
              mode="multiple"
              placeholder="选择参与直播讲解的商品"
              style="width: 100%"
              :options="prodOptions"
              option-filter-prop="label"
              @change="syncProdForm"
            />
            <div v-if="selectedRows.length" class="cl-prod-editor">
              <div v-for="p in selectedRows" :key="p.prodId" class="cl-prod-row">
                <div class="cl-prod-cover">
                  <img v-if="p.coverUrl" :src="p.coverUrl" :alt="p.title" />
                  <div v-else class="cl-prod-fallback"><ShoppingBag :size="16" :stroke-width="1.7" /></div>
                </div>
                <span class="cl-prod-title">{{ p.title }}</span>
                <span class="cl-prod-price"><em>原价</em>&nbsp;¥{{ money(p.price) }}</span>
                <span class="cl-prod-input">
                  <em>直播价</em>
                  <a-input-number v-model:value="prodForm[p.prodId].livePrice" :min="0.01" :precision="2" size="small" />
                </span>
                <span class="cl-prod-input">
                  <em>直播库存</em>
                  <a-input-number v-model:value="prodForm[p.prodId].liveStock" :min="0" :precision="0" size="small" />
                </span>
              </div>
            </div>
          </div>

          <div class="cl-form-actions">
            <a-button type="primary" size="large" :loading="creating" @click="createRoom">创建直播间</a-button>
          </div>
        </div>
      </div>

      <!-- ===== 直播控制台（预告 / 直播中） ===== -->
      <template v-else-if="mode === 'console' && room">
        <!-- 平台审核驳回：回显原因 + 重新创建引导 -->
        <a-alert v-if="Number(room.reviewStatus) === 2" type="error" show-icon class="cl-reject-alert"
                 :message="'直播间审核未通过：' + (room.rejectReason || '直播内容不符合平台规范')">
          <template #action>
            <a-button size="small" danger @click="backToCreate">重新创建</a-button>
          </template>
        </a-alert>

        <div class="cl-kpis">
          <div class="kpi"><b><NumberTicker :value="Number(room.viewerCount || 0)" /></b><span>在线观众</span></div>
          <div class="kpi"><b><NumberTicker :value="Number(room.peakViewerCount || 0)" /></b><span>峰值观看</span></div>
          <div class="kpi"><b><NumberTicker :value="Number(room.likeCount || 0)" /></b><span>点赞</span></div>
          <div class="kpi"><b><NumberTicker :value="Number(stats ? stats.commentCount : 0)" /></b><span>弹幕</span></div>
        </div>

        <div class="cl-grid">
          <div class="cl-card">
            <div class="cl-card-head">
              <b>房间控制</b>
              <span class="cl-head-tags">
                <a-tag :color="Number(room.status) === 1 ? 'red' : 'blue'">
                  {{ Number(room.status) === 1 ? '直播中' : '预告' }}
                </a-tag>
                <a-tag v-if="Number(room.reviewStatus) === 0" color="warning">待平台审核</a-tag>
                <a-tag v-else-if="Number(room.reviewStatus) === 1" color="success">审核通过</a-tag>
                <a-tag v-else-if="Number(room.reviewStatus) === 2" color="error">未通过审核</a-tag>
              </span>
            </div>
            <div class="cl-room-line">
              <span class="cl-room-title">{{ room.title }}</span>
              <span class="cl-room-id">房间 #{{ room.roomId }}</span>
            </div>
            <div class="cl-room-actions">
              <a-tooltip v-if="Number(room.status) === 0"
                         :title="Number(room.reviewStatus) === 1 ? '' : '平台审核通过后方可开播'">
                <a-button type="primary" :loading="switching" :disabled="Number(room.reviewStatus) !== 1" @click="startLive">开播</a-button>
              </a-tooltip>
              <a-button v-else-if="Number(room.status) === 1" danger :loading="switching" @click="endLive">关播</a-button>
            </div>
            <p v-if="Number(room.status) === 0 && Number(room.reviewStatus) !== 1" class="cl-hint">平台审核通过后方可开播</p>
            <div class="cl-divider"></div>
            <div class="cl-card-head"><b>讲解中商品</b></div>
            <a-select
              :value="pinnedProdId || undefined"
              placeholder="选择讲解商品"
              style="width: 100%"
              :options="statsProdOptions"
              :disabled="pinning"
              @change="pinProduct"
            />
            <p class="cl-hint">切换后，观众端「讲解中」卡片与商品详情页直播徽章将同步更新。</p>
          </div>

          <div class="cl-card cl-chat-card">
            <div class="cl-card-head"><b>弹幕监控</b><span>只读实时流</span></div>
            <div class="cl-chat-stream">
              <div v-for="c in monitorComments" :key="c.commentId" class="cl-chat-row" :class="'t-' + c.msgType">
                <b>{{ c.nickname || '观众' }}</b>
                <span>{{ c.content }}</span>
              </div>
              <p v-if="!monitorComments.length" class="cl-hint" style="text-align:center;padding:18px 0">暂无弹幕</p>
            </div>
          </div>
        </div>

        <div class="cl-card">
          <div class="cl-card-head"><b>商品点击榜</b><span>观众点击「去购买」时累计</span></div>
          <a-table :data-source="statsProducts" :columns="consoleColumns" row-key="id" size="small" :pagination="false" :scroll="{ x: 640 }">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'title'">
                <span class="cl-prod-link">{{ record.title }}</span>
              </template>
              <template v-else-if="column.key === 'price'">¥{{ money(record.price) }}</template>
              <template v-else-if="column.key === 'livePrice'">
                <b class="cl-live-price">¥{{ money(record.livePrice) }}</b>
              </template>
              <template v-else-if="column.key === 'pinned'">
                <a-tag v-if="record.pinned" color="red">讲解中</a-tag>
                <span v-else class="cl-muted">—</span>
              </template>
            </template>
          </a-table>
        </div>
      </template>

      <!-- ===== 复盘视图（已结束场次） ===== -->
      <template v-else-if="mode === 'review' && room">
        <div class="cl-kpis">
          <div class="kpi"><b><NumberTicker :value="Number(stats ? stats.totalGmv : 0)" :decimal-places="2" /></b><span>总成交（GMV）</span></div>
          <div class="kpi"><b><NumberTicker :value="Number(stats ? stats.totalOrders : 0)" /></b><span>总单数</span></div>
          <div class="kpi"><b><NumberTicker :value="Number(room.peakViewerCount || 0)" /></b><span>峰值观看</span></div>
          <div class="kpi"><b><NumberTicker :value="Number(stats ? stats.commentCount : 0)" /></b><span>弹幕数</span></div>
        </div>

        <div class="cl-card">
          <div class="cl-card-head">
            <b>商品漏斗<em class="cl-funnel-room">&nbsp;·&nbsp;{{ room.title }}</em></b>
            <span>曝光（挂载）→ 点击 → 成交</span>
          </div>
          <a-table :data-source="statsProducts" :columns="reviewColumns" row-key="id" size="small" :pagination="false" :scroll="{ x: 640 }">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'title'">
                <span class="cl-prod-link">{{ record.title }}</span>
              </template>
              <template v-else-if="column.key === 'exposure'"><a-tag color="blue">挂载</a-tag></template>
              <template v-else-if="column.key === 'gmv'">¥{{ money(record.gmv) }}</template>
              <template v-else-if="column.key === 'cvr'">{{ cvr(record) }}</template>
            </template>
          </a-table>
          <div class="cl-review-actions">
            <a-button @click="backToCreate">再开一场</a-button>
          </div>
        </div>
      </template>
    </a-spin>
  </div>
</template>

<script setup>
/**
 * 商户直播控制台（/merchant/center/live，merchant:manage）：
 * - 无活跃直播间 → 创建表单（标题必填 + 挂载商品直播价/库存）
 * - 有活跃直播间（0 预告 / 1 直播中）→ 控制台：5s 轮询 stats、讲解商品切换 pin、开关播、弹幕监控
 * - 已结束 → 复盘：GMV/单数/峰值/弹幕 KPI + 商品漏斗（曝光=挂载、点击、成交、转化率）
 * 风格对齐 CenterPricing（--mc-* 变量）；轮询定时器 onUnmounted 清理。
 */
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Modal, message } from 'ant-design-vue'
import { ShoppingBag, Video } from 'lucide-vue-next'
import { liveApi, productApi } from '../../api'
import NumberTicker from '../../components/ui/NumberTicker.vue'

const router = useRouter()

const mode = ref('loading') // loading | create | console | review
const loading = ref(true)
const room = ref(null)
const stats = ref(null)
const creating = ref(false)
const switching = ref(false)
const pinning = ref(false)

// 创建表单（后端固定创建为 status=0 预告 + reviewStatus=0 待平台审核，审核通过后才能开播）
const form = reactive({ title: '', coverUrl: '', streamUrl: '', notice: '' })
const myProducts = ref([])
const selectedProdIds = ref([])
const prodForm = reactive({}) // prodId -> { livePrice, liveStock }

// 控制台轮询
let statsTimer = null
let chatTimer = null
const monitorComments = ref([])

const money = (v) => Number(v || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
const cvr = (r) => (Number(r.clickCount) > 0 ? ((Number(r.orders || 0) / Number(r.clickCount)) * 100).toFixed(1) + '%' : '—')

const prodOptions = computed(() => myProducts.value.map(p => ({ label: p.title, value: p.prodId })))
const selectedRows = computed(() =>
  selectedProdIds.value
    .map(id => myProducts.value.find(p => Number(p.prodId) === Number(id)))
    .filter(Boolean)
)
const statsProducts = computed(() => (stats.value && stats.value.products) || [])
const statsProdOptions = computed(() => statsProducts.value.map(p => ({ label: p.title, value: p.prodId })))
const pinnedProdId = computed(() => Number(room.value?.pinnedProdId || 0))

const consoleColumns = [
  { key: 'title', title: '商品', ellipsis: true },
  { key: 'price', title: '原价', width: 100 },
  { key: 'livePrice', title: '直播价', width: 110 },
  { key: 'liveStock', dataIndex: 'liveStock', title: '直播库存', width: 90 },
  { key: 'clickCount', dataIndex: 'clickCount', title: '点击', width: 80 },
  { key: 'pinned', title: '讲解', width: 90 }
]
const reviewColumns = [
  { key: 'title', title: '商品', ellipsis: true },
  { key: 'exposure', title: '曝光', width: 90 },
  { key: 'clickCount', dataIndex: 'clickCount', title: '点击', width: 90 },
  { key: 'orders', dataIndex: 'orders', title: '成交单数', width: 90 },
  { key: 'gmv', title: '成交额', width: 110 },
  { key: 'cvr', title: '转化率', width: 90 }
]

// ===== 初始加载 =====
async function init() {
  loading.value = true
  try {
    const cur = await liveApi.merchantCurrent()
    if (cur && Number(cur.status) !== 2) {
      room.value = cur
      mode.value = 'console'
      await loadStats()
      await loadChat()
      startConsolePolling()
    } else if (cur && Number(cur.status) === 2) {
      // 后端返回了已结束房间：直接给复盘
      room.value = cur
      await loadStats()
      mode.value = 'review'
    } else {
      await loadMyProducts()
      mode.value = 'create'
    }
  } catch (e) {
    // 拉取失败（未登录/网络）按无直播间处理，由拦截器提示
    mode.value = 'create'
    try { await loadMyProducts() } catch (e2) { /* 静默 */ }
  }
  loading.value = false
}

async function loadMyProducts() {
  try {
    const page = await productApi.myList({ current: 1, size: 100, status: 1 })
    myProducts.value = page.records || []
  } catch (e) { myProducts.value = [] }
}

// 选中商品时初始化直播价（默认原价）与直播库存
function syncProdForm(ids) {
  const removed = Object.keys(prodForm).filter(k => !ids.map(Number).includes(Number(k)))
  removed.forEach(k => { delete prodForm[k] })
  ids.forEach(id => {
    if (!prodForm[id]) {
      const p = myProducts.value.find(x => Number(x.prodId) === Number(id))
      prodForm[id] = { livePrice: p ? Number(p.price) : 0, liveStock: 0 }
    }
  })
}

async function createRoom() {
  if (!form.title.trim()) { message.warning('请填写直播间标题'); return }
  if (selectedProdIds.value.length &&
      selectedProdIds.value.some(id => !(Number(prodForm[id]?.livePrice) > 0))) {
    message.warning('请为选中商品填写大于 0 的直播价')
    return
  }
  creating.value = true
  try {
    const payload = {
      title: form.title.trim(),
      coverUrl: form.coverUrl.trim() || '',
      streamUrl: form.streamUrl.trim() || '',
      notice: form.notice.trim() || '',
      status: 0, // 后端固定创建为预告，待平台审核通过后商户方可开播
      products: selectedProdIds.value.map(id => ({
        prodId: Number(id),
        livePrice: Number(prodForm[id]?.livePrice || 0),
        liveStock: Number(prodForm[id]?.liveStock || 0)
      }))
    }
    const created = await liveApi.merchantCreate(payload)
    message.success('直播间已创建，等待平台审核')
    if (created) {
      room.value = created
      mode.value = 'console'
      await loadStats()
      await loadChat()
      startConsolePolling()
    }
  } catch (e) { /* 拦截器已提示 */ }
  creating.value = false
}

// ===== 控制台 =====
async function loadStats() {
  if (!room.value) return
  try {
    stats.value = await liveApi.merchantStats(room.value.roomId, { silent: true })
    if (stats.value && stats.value.room) room.value = stats.value.room
    // 直播已被关播（如平台强制下架）：停轮询并自动切到复盘视图
    if (mode.value === 'console' && room.value && Number(room.value.status) === 2) {
      stopConsolePolling()
      mode.value = 'review'
    }
  } catch (e) { /* 轮询静默 */ }
}

async function loadChat() {
  if (!room.value) return
  try {
    const list = (await liveApi.comments(room.value.roomId, 30, { silent: true })) || []
    // 监控视图：最新在前
    monitorComments.value = [...list].reverse()
  } catch (e) { /* 静默 */ }
}

function startConsolePolling() {
  stopConsolePolling()
  statsTimer = setInterval(() => { if (mode.value === 'console') loadStats() }, 5000)
  chatTimer = setInterval(() => { if (mode.value === 'console') loadChat() }, 3000)
}

function stopConsolePolling() {
  if (statsTimer) { clearInterval(statsTimer); statsTimer = null }
  if (chatTimer) { clearInterval(chatTimer); chatTimer = null }
}

async function startLive() {
  switching.value = true
  try {
    await liveApi.merchantStatus(room.value.roomId, 1)
    room.value = { ...room.value, status: 1 }
    message.success('直播已开始')
    loadStats()
  } catch (e) { /* 拦截器已提示 */ }
  switching.value = false
}

function endLive() {
  Modal.confirm({
    title: '确认结束直播？',
    content: '结束后观众将无法继续观看，系统将生成本场复盘数据。',
    okText: '确认关播',
    okType: 'danger',
    cancelText: '继续直播',
    onOk: async () => {
      switching.value = true
      try {
        await liveApi.merchantStatus(room.value.roomId, 2)
        room.value = { ...room.value, status: 2 }
        stopConsolePolling()
        await loadStats()
        mode.value = 'review'
        message.success('直播已结束，已生成本场复盘')
      } catch (e) { /* 拦截器已提示 */ }
      switching.value = false
    }
  })
}

async function pinProduct(prodId) {
  if (!prodId) return
  pinning.value = true
  try {
    await liveApi.merchantPin(room.value.roomId, prodId)
    room.value = { ...room.value, pinnedProdId: prodId }
    message.success('已切换讲解商品')
    loadStats()
  } catch (e) { /* 拦截器已提示 */ }
  pinning.value = false
}

function enterRoom() {
  router.push('/live/' + room.value.roomId)
}

function backToCreate() {
  room.value = null
  stats.value = null
  form.title = ''; form.coverUrl = ''; form.streamUrl = ''; form.notice = ''
  selectedProdIds.value = []
  mode.value = 'create'
}

onMounted(init)
onUnmounted(stopConsolePolling)
</script>

<style scoped>
.cl-page { padding: 6px 2px; }
.cl-head { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin-bottom: 18px; flex-wrap: wrap; }
.cl-head h2 { margin: 0 0 4px; font-size: 22px; letter-spacing: -.02em; color: var(--mc-ink, var(--ll-ink, #1d1d1f)); }
.cl-head p { margin: 0; font-size: 13px; color: var(--mc-muted, var(--ll-muted, #6e6e73)); }
.cl-reject-alert { margin-bottom: 14px; }
.cl-head-tags { display: inline-flex; gap: 6px; flex-wrap: wrap; }

.cl-kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 16px; }
.kpi { background: var(--mc-card, #fff); border: 1px solid var(--mc-border, #e8e6e1); border-radius: 14px; padding: 14px 18px; }
.kpi b { display: block; font-size: 24px; font-weight: 700; letter-spacing: -.02em; color: var(--mc-ink, var(--ll-ink, #1d1d1f)); font-variant-numeric: tabular-nums; }
.kpi span { font-size: 12px; color: var(--mc-muted, var(--ll-muted, #6e6e73)); }

.cl-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; align-items: start; margin-bottom: 16px; }
.cl-card { background: var(--mc-card, #fff); border: 1px solid var(--mc-border, #e8e6e1); border-radius: 16px; padding: 18px 20px; margin-bottom: 16px; }
.cl-card-head { display: flex; justify-content: space-between; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.cl-card-head b { font-size: 15px; color: var(--mc-ink, var(--ll-ink, #1d1d1f)); }
.cl-card-head span { font-size: 12px; color: var(--mc-muted, var(--ll-muted, #6e6e73)); }
.cl-divider { border-top: 1px dashed var(--mc-border, #e8e6e1); margin: 16px 0; }
.cl-hint { margin: 8px 0 0; font-size: 12px; color: var(--mc-muted, var(--ll-muted, #6e6e73)); }
.cl-muted { color: var(--mc-muted2, var(--ll-muted, #86868b)); }

/* 创建表单 */
.cl-form { display: flex; flex-direction: column; gap: 14px; }
.cl-field > span { display: block; font-size: 13px; font-weight: 600; color: var(--mc-text2, var(--ll-ink, #3a3a3c)); margin-bottom: 6px; }
.cl-field .req { color: #d93025; font-style: normal; margin-left: 2px; }
.cl-field .hint { font-weight: 400; color: var(--mc-muted, var(--ll-muted, #6e6e73)); }
.cl-form-actions { display: flex; justify-content: flex-end; }

.cl-prod-editor { margin-top: 10px; display: flex; flex-direction: column; gap: 8px; }
.cl-prod-row {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  border: 1px solid var(--mc-border-soft, #f0eee9);
  border-radius: 12px; padding: 8px 12px;
  background: var(--mc-tint, #faf9f6);
}
.cl-prod-cover { flex-shrink: 0; width: 40px; height: 40px; border-radius: 8px; overflow: hidden; background: var(--mc-soft, #f5f5f7); }
.cl-prod-cover img { width: 100%; height: 100%; object-fit: cover; display: block; }
.cl-prod-fallback { width: 100%; height: 100%; display: grid; place-items: center; color: var(--mc-faint, #a1a1a6); }
.cl-prod-title { flex: 1 1 140px; min-width: 0; font-size: 13px; font-weight: 600; color: var(--mc-ink, var(--ll-ink, #1d1d1f)); overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.cl-prod-price { font-size: 12px; color: var(--mc-muted, var(--ll-muted, #6e6e73)); font-variant-numeric: tabular-nums; }
.cl-prod-price em { font-style: normal; }
.cl-funnel-room { font-style: normal; font-size: 12px; font-weight: 400; color: var(--mc-muted, var(--ll-muted, #6e6e73)); }
.cl-prod-input { display: inline-flex; align-items: center; gap: 6px; }
.cl-prod-input em { font-style: normal; font-size: 12px; color: var(--mc-muted, var(--ll-muted, #6e6e73)); }

/* 控制台 */
.cl-room-line { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
.cl-room-title { font-size: 15px; font-weight: 700; color: var(--mc-ink, var(--ll-ink, #1d1d1f)); letter-spacing: -.01em; }
.cl-room-id { font-size: 12px; color: var(--mc-muted2, var(--ll-muted, #86868b)); font-variant-numeric: tabular-nums; }
.cl-room-actions { display: flex; gap: 10px; }
.cl-chat-card { display: flex; flex-direction: column; }
.cl-chat-stream { flex: 1; min-height: 220px; max-height: 320px; overflow-y: auto; display: flex; flex-direction: column; gap: 8px; }
.cl-chat-row { display: flex; gap: 8px; font-size: 12.5px; line-height: 1.5; }
.cl-chat-row b { flex-shrink: 0; color: var(--mc-muted, var(--ll-muted, #6e6e73)); font-weight: 600; }
.cl-chat-row span { min-width: 0; color: var(--mc-text2, var(--ll-ink, #3a3a3c)); word-break: break-word; }
.cl-chat-row.t-buy span { color: var(--ll-brand-gold-strong, #80601F); font-weight: 700; }
.cl-chat-row.t-system span { color: var(--mc-muted2, var(--ll-muted, #86868b)); }

.cl-prod-link { font-weight: 600; color: var(--mc-ink, var(--ll-ink, #1d1d1f)); }
.cl-live-price { color: #d93025; font-variant-numeric: tabular-nums; }

/* 复盘 */
.cl-review-actions { display: flex; justify-content: flex-end; margin-top: 14px; }

@media (max-width: 900px) {
  .cl-grid { grid-template-columns: 1fr; }
  .cl-kpis { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 560px) {
  .cl-card { padding: 14px; border-radius: 14px; }
  .cl-prod-input { flex: 1 1 100%; justify-content: flex-end; }
}
</style>

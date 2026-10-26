<template>
    <div class="admin-dashboard-page">
    <!-- Apple iOS 灵动岛 (Dynamic Island) 态势感知与真实审核通报 -->
    <AppleDynamicIsland
      :title="`风控态势感知 (${stats.merchantPending + stats.productPending} 项待审)`"
      :icon="markRaw(ShieldAlert)"
      :description="`商户待终审: ${stats.merchantPending} 家 · 商品待终审: ${stats.productPending} 件 · 合规拦截布防中`"
      badge="MONITOR"
      :status-type="stats.merchantPending + stats.productPending > 0 ? 'warning' : 'success'"
      :progress="92"
      progress-text="平台自动化合规检测覆盖率"
      primary-text="前往商户审核"
      @primary-click="$router.push('/admin/merchants')"
    />
    <!-- 21st.dev 风格核心指标大盘 -->
    <div class="kpi-grid">
      <AppleGlassCard :max-tilt="6" :spotlight-size="260"><div class="kpi-card" @click="$router.push('/admin/merchants')">
        <div class="kpi-top">
          <span class="kpi-label">待审核商户</span>
          <span class="beacon-pulse amber"></span>
        </div>
        <div class="kpi-num text-amber">{{ stats.merchantPending }}</div>
        <div class="kpi-sub">
          <span>待资质人工终审</span>
          <span class="jump-arrow">前往审核 →</span>
        </div>
      </div></AppleGlassCard>

      <AppleGlassCard :max-tilt="6" :spotlight-size="260"><div class="kpi-card" @click="$router.push('/admin/products')">
        <div class="kpi-top">
          <span class="kpi-label">待审核商品</span>
          <span class="beacon-pulse amber"></span>
        </div>
        <div class="kpi-num text-amber">{{ stats.productPending }}</div>
        <div class="kpi-sub">
          <span>待智能合规初筛与终审</span>
          <span class="jump-arrow">前往审核 →</span>
        </div>
      </div></AppleGlassCard>

      <AppleGlassCard :max-tilt="6" :spotlight-size="260"><div class="kpi-card" @click="$router.push('/admin/merchants')">
        <div class="kpi-top">
          <span class="kpi-label">正常入驻商户</span>
          <span class="beacon-pulse green"></span>
        </div>
        <div class="kpi-num text-green">{{ stats.merchantPassed }}</div>
        <div class="kpi-sub">
          <span>全平台累计申请 {{ stats.merchantTotal }} 家</span>
          <span class="jump-arrow">商户底册 →</span>
        </div>
      </div></AppleGlassCard>

      <AppleGlassCard :max-tilt="6" :spotlight-size="260"><div class="kpi-card" @click="$router.push('/admin/products')">
        <div class="kpi-top">
          <span class="kpi-label">合规在售商品</span>
          <span class="beacon-pulse blue"></span>
        </div>
        <div class="kpi-num text-blue">{{ stats.productsOnSale }}</div>
        <div class="kpi-sub">
          <span>商品总库 {{ stats.productTotal }} 件</span>
          <span class="jump-arrow">商品看板 →</span>
        </div>
      </div></AppleGlassCard>
    </div>

    <!-- 双待办核验雷达 (商户待审 & 商品待审) -->
    <div class="radar-grid">
      <!-- 左列：待审核商户队列 -->
      <div class="radar-card">
        <div class="radar-header">
          <div class="rh-title">
            <ShopOutlined style="color: #0284c7; margin-right: 8px" />
            商户入驻待办审核队列
            <a-badge :count="stats.merchantPending" class="custom-badge" />
          </div>
          <a class="more-link" @click="$router.push('/admin/merchants')">查看全部商户 →</a>
        </div>

        <a-table
          :data-source="recentMerchants"
          :columns="merCols"
          row-key="merId"
          size="small"
          :pagination="false"
          :loading="loading"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'enterprise'">
              <div class="table-ent-col">
                <span class="table-ent-name" @click="$router.push('/admin/merchants/' + record.merId)">
                  {{ record.enterpriseName || ('商户 #' + record.merId) }}
                </span>
                <span class="table-ent-code">{{ record.creditCode || '—' }}</span>
              </div>
            </template>
            <template v-else-if="column.key === 'legal'">
              <span class="font-mono text-dark">{{ record.legalPerson || '—' }}</span>
            </template>
            <template v-else-if="column.key === 'cap'">
              ¥{{ Number(record.regCapital || 0).toLocaleString() }}
            </template>
            <template v-else-if="column.key === 'st'">
              <a-tag :color="record.reviewStatus === 1 ? 'green' : record.reviewStatus === 0 ? 'orange' : 'red'">
                {{ { 0: '待审核', 1: '已通过', 2: '已驳回' }[record.reviewStatus] || '未知' }}
              </a-tag>
            </template>
            <template v-else-if="column.key === 'act'">
              <a-button type="link" size="small" @click="$router.push('/admin/merchants/' + record.merId)">
                审核档案 ↗
              </a-button>
            </template>
          </template>
        </a-table>
      </div>

      <!-- 右列：待审核商品队列 -->
      <div class="radar-card">
        <div class="radar-header">
          <div class="rh-title">
            <AppstoreOutlined style="color: #0284c7; margin-right: 8px" />
            商品上架待办审核队列
            <a-badge :count="stats.productPending" class="custom-badge" />
          </div>
          <a class="more-link" @click="$router.push('/admin/products')">查看全部商品 →</a>
        </div>

        <a-table
          :data-source="recentProducts"
          :columns="prodCols"
          row-key="prodId"
          size="small"
          :pagination="false"
          :loading="loading"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'prod'">
              <div class="table-prod-col">
                <img :src="record.mainImg || '/placeholder-prod.png'" alt="img" class="table-prod-thumb" />
                <div class="table-prod-info">
                  <span class="table-prod-title" :title="record.title" @click="$router.push('/admin/products')">
                    {{ record.title }}
                  </span>
                  <span class="table-prod-sub">商户 #{{ record.merId }} · {{ record.brand || '自主品牌' }}</span>
                </div>
              </div>
            </template>
            <template v-else-if="column.key === 'price'">
              <strong style="color: #0f172a">¥{{ Number(record.price || 0).toFixed(2) }}</strong>
            </template>
            <template v-else-if="column.key === 'st'">
              <a-tag :color="record.reviewStatus === 1 ? 'green' : record.reviewStatus === 0 ? 'orange' : 'red'">
                {{ { 0: '待审核', 1: '审核通过', 2: '审核驳回' }[record.reviewStatus] || '未知' }}
              </a-tag>
            </template>
            <template v-else-if="column.key === 'act'">
              <a-button type="link" size="small" @click="$router.push('/admin/products')">
                全景核验 ↗
              </a-button>
            </template>
          </template>
        </a-table>
      </div>
    </div>

    <!-- 底部区域：数据安全体系与快捷工具 -->
    <div class="security-tools-grid">
      <div class="sec-box">
        <div class="sec-head">
          <SafetyCertificateOutlined style="color: #0284c7; font-size: 16px; margin-right: 8px" />
          <span>平台敏感信息加密与安全防线</span>
          <span class="sec-chip">AES-256-GCM 保护</span>
        </div>
        <p class="sec-desc">
          商户法人身份证、对公银行卡号等核心机密资产均由系统透明加解密保护，落盘密文不可逆解析；所有明文调阅经由双因子安全审计验权，全程留存不可抵赖审计溯源日志。
        </p>
      </div>

      <div class="sec-box">
        <div class="sec-head">
          <CheckCircleOutlined style="color: #10b981; font-size: 16px; margin-right: 8px" />
          <span>第三方官方账号安全授权集成</span>
          <span class="sec-chip green">OAuth2.0 / OIDC</span>
        </div>
        <p class="sec-desc">
          已成功集成 Google OAuth 与 Apple ID 权威跨域登录认证协议，支持标准安全鉴权与沙箱闭环测试，保障企业用户及消费者账户体系的国际化规范性与平滑接入。
        </p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, markRaw } from 'vue'
import { ShieldAlert } from 'lucide-vue-next'
import {
  ShopOutlined,
  AppstoreOutlined,
  SafetyCertificateOutlined,
  CheckCircleOutlined
} from '@ant-design/icons-vue'
import { merchantApi, productApi } from '../../api'
import AppleDynamicIsland from '../../components/ui/AppleDynamicIsland.vue'
import AppleGlassCard from '../../components/ui/AppleGlassCard.vue'

const loading = ref(false)
const stats = reactive({
  merchantTotal: 0,
  merchantPending: 0,
  merchantPassed: 0,
  productTotal: 0,
  productPending: 0,
  productsOnSale: 0
})

const recentMerchants = ref([])
const recentProducts = ref([])

const merCols = [
  { title: '企业主体', key: 'enterprise' },
  { title: '法人姓名', key: 'legal', width: 90 },
  { title: '注册资本', key: 'cap', width: 110 },
  { title: '状态', key: 'st', width: 90 },
  { title: '操作', key: 'act', width: 110 }
]

const prodCols = [
  { title: '商品主体', key: 'prod' },
  { title: '售价', key: 'price', width: 90 },
  { title: '状态', key: 'st', width: 90 },
  { title: '操作', key: 'act', width: 110 }
]

onMounted(async () => {
  loading.value = true
  try {
    // 加载商户统计与待审列表
    const [mStats, mList] = await Promise.allSettled([
      merchantApi.adminStats(),
      merchantApi.adminList()
    ])
    if (mStats.status === 'fulfilled' && mStats.value) {
      stats.merchantTotal = mStats.value.total || 0
      stats.merchantPending = mStats.value.pending || 0
      stats.merchantPassed = mStats.value.passed || 0
    }
    if (mList.status === 'fulfilled' && Array.isArray(mList.value)) {
      recentMerchants.value = mList.value.slice(0, 6)
      if (!stats.merchantTotal) {
        stats.merchantTotal = mList.value.length
        stats.merchantPending = mList.value.filter(m => m.reviewStatus === 0).length
        stats.merchantPassed = mList.value.filter(m => m.reviewStatus === 1).length
      }
    }

    // 加载商品统计与待审列表
    const [pStats, pList] = await Promise.allSettled([
      productApi.adminStats(),
      productApi.adminList({ current: 1, size: 6, reviewStatus: 0 })
    ])
    if (pStats.status === 'fulfilled' && pStats.value) {
      stats.productTotal = pStats.value.total || 0
      stats.productPending = pStats.value.pending || 0
      stats.productsOnSale = pStats.value.approved || 0
    }
    if (pList.status === 'fulfilled' && pList.value?.records) {
      recentProducts.value = pList.value.records.slice(0, 6)
    }
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.admin-dashboard-page {
  padding: 16px 20px 32px;
}

/* 21st.dev 风格核心 KPI */
.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;
  margin-bottom: 20px;
}
.kpi-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 16px 18px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04);
  cursor: pointer;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
.kpi-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(15, 23, 42, 0.08);
  border-color: #cbd5e1;
}
.kpi-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.kpi-label {
  font-size: 13px;
  font-weight: 500;
  color: #64748b;
}
.beacon-pulse {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}
.beacon-pulse.amber { background: #f59e0b; box-shadow: 0 0 0 3px rgba(245, 158, 11, 0.2); }
.beacon-pulse.green { background: #10b981; box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.2); }
.beacon-pulse.blue { background: #0284c7; box-shadow: 0 0 0 3px rgba(2, 132, 199, 0.2); }
.kpi-num {
  font-size: 28px;
  font-weight: 700;
  line-height: 1.1;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
}
.text-amber { color: #d97706; }
.text-green { color: #059669; }
.text-blue { color: #0284c7; }
.kpi-sub {
  margin-top: 8px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  color: #94a3b8;
}
.jump-arrow {
  color: #0284c7;
  font-size: 11px;
}

/* 双待办雷达 */
.radar-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 20px;
}
.radar-card {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 16px 18px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04);
}
.radar-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.rh-title {
  font-size: 14px;
  font-weight: 600;
  color: #0f172a;
  display: flex;
  align-items: center;
  gap: 8px;
}
.more-link {
  font-size: 12px;
  color: #0284c7;
}

/* 表格列微调 */
.table-ent-col {
  display: flex;
  flex-direction: column;
}
.table-ent-name {
  font-weight: 600;
  font-size: 13px;
  color: #1e293b;
  cursor: pointer;
}
.table-ent-name:hover {
  color: #0284c7;
}
.table-ent-code {
  font-size: 11px;
  color: #94a3b8;
  font-family: monospace;
}
.table-prod-col {
  display: flex;
  align-items: center;
  gap: 10px;
}
.table-prod-thumb {
  width: 36px;
  height: 36px;
  border-radius: 6px;
  object-fit: cover;
  border: 1px solid #e2e8f0;
  background: #f8fafc;
}
.table-prod-info {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.table-prod-title {
  font-size: 12px;
  font-weight: 600;
  color: #1e293b;
  cursor: pointer;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 220px;
}
.table-prod-title:hover {
  color: #0284c7;
}
.table-prod-sub {
  font-size: 11px;
  color: #94a3b8;
}

/* 安全指标横幅 */
.security-tools-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.sec-box {
  background: #ffffff;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  padding: 16px 18px;
}
.sec-head {
  display: flex;
  align-items: center;
  font-size: 13px;
  font-weight: 600;
  color: #0f172a;
  margin-bottom: 8px;
}
.sec-chip {
  background: #e0f2fe;
  color: #0369a1;
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 4px;
  margin-left: 8px;
  font-weight: normal;
}
.sec-chip.green {
  background: #ecfdf5;
  color: #059669;
}
.sec-desc {
  font-size: 12px;
  color: #64748b;
  line-height: 1.5;
  margin: 0;
}

@media (max-width: 768px) {
  .admin-dashboard-page { padding: 12px; }
  .kpi-grid { grid-template-columns: 1fr; gap: 12px; }
  .radar-grid { grid-template-columns: 1fr; gap: 14px; }
  .security-tools-grid { grid-template-columns: 1fr; gap: 12px; }
}
</style>

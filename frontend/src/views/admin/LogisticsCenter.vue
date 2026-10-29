<template>
  <div class="logistics-page">
    <a-page-header title="物流中心" sub-title="服务商 / 运费模板 / 运单 / 异常告警 / 物流售后 / 物流结算">
      <a-tabs v-model:activeKey="tab">
        <a-tab-pane key="provider" tab="物流服务商">
          <a-button type="primary" @click="openProvider()">新增服务商</a-button>
          <a-table :data-source="providers" :columns="providerCols" row-key="providerId" :pagination="false" size="middle">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'status'">
                <a-tag :color="record.status === 1 ? 'green' : 'red'">{{ record.status === 1 ? '启用' : '停用' }}</a-tag>
              </template>
              <template v-if="column.key === 'action'">
                <a @click="openProvider(record)">编辑</a>
              </template>
            </template>
          </a-table>
        </a-tab-pane>

        <a-tab-pane key="template" tab="运费模板">
          <a-button type="primary" @click="openTemplate()">新增模板</a-button>
          <a-table :data-source="templates" :columns="templateCols" row-key="templateId" :pagination="false" size="middle">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'action'">
                <a @click="openTemplate(record)">编辑</a>
              </template>
            </template>
          </a-table>
        </a-tab-pane>

        <a-tab-pane key="waybill" tab="运单管理">
          <a-table :data-source="waybills" :columns="waybillCols" row-key="waybillId" size="middle"
            :pagination="{ current: waybillPage.page, pageSize: waybillPage.size, total: waybillPage.total, onChange: loadWaybills }">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'status'">
                <a-tag :color="statusColor(record.status)">{{ statusText(record.status) }}</a-tag>
              </template>
              <template v-if="column.key === 'action'">
                <a @click="showTrace(record)">轨迹</a>
              </template>
            </template>
          </a-table>
        </a-tab-pane>

        <a-tab-pane key="alert" tab="异常告警">
          <a-table :data-source="alerts" :columns="alertCols" row-key="alertId" size="middle"
            :pagination="{ current: alertPage.page, pageSize: alertPage.size, total: alertPage.total, onChange: loadAlerts }">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'action'">
                <a v-if="record.status !== 'resolved'" @click="handleAlert(record)">处理</a>
                <span v-else style="color:#999">已处理</span>
              </template>
            </template>
          </a-table>
        </a-tab-pane>

        <a-tab-pane key="aftersale" tab="物流售后">
          <a-table :data-source="aftersales" :columns="aftersaleCols" row-key="aftersaleId" size="middle"
            :pagination="{ current: afterPage.page, pageSize: afterPage.size, total: afterPage.total, onChange: loadAftersales }">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'action'">
              <a-space>
                <a v-if="record.status === 'pending'" @click="processAftersale(record, 'accept')">受理</a>
                <a v-if="record.status === 'processing'" @click="processAftersale(record, 'approve')">通过</a>
                <a-popconfirm v-if="record.status === 'processing'" title="确认驳回该售后申请？" ok-text="驳回" cancel-text="取消"
                              @confirm="processAftersale(record, 'reject')">
                  <a style="color:#d93025">驳回</a>
                </a-popconfirm>
                <a v-if="record.status === 'approved'" @click="processAftersale(record, 'done')">完结</a>
              </a-space>
            </template>
            </template>
          </a-table>
        </a-tab-pane>

        <a-tab-pane key="settlement" tab="物流结算">
          <a-button type="primary" @click="openGenerate()">生成对账单</a-button>
          <a-table :data-source="settlements" :columns="settleCols" row-key="settlementId" size="middle"
            :pagination="{ current: settlePage.page, pageSize: settlePage.size, total: settlePage.total, onChange: loadSettlements }">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'action'">
              <a-space>
                <a v-if="record.status === 'draft'" @click="reviewSettle(record, 'submit')">提交</a>
                <a v-if="record.status === 'pending_review'" @click="reviewSettle(record, 'approve')">通过</a>
                <a-popconfirm v-if="record.status === 'pending_review'" title="确认驳回该结算单？驳回后将退回草稿。" ok-text="驳回" cancel-text="取消"
                              @confirm="reviewSettle(record, 'reject')">
                  <a style="color:#d93025">驳回</a>
                </a-popconfirm>
                <a v-if="record.status === 'approved'" @click="reviewSettle(record, 'pay')">打款</a>
              </a-space>
            </template>
            </template>
          </a-table>
        </a-tab-pane>
      </a-tabs>
    </a-page-header>

    <!-- 服务商编辑弹窗 -->
    <a-modal v-model:open="providerVisible" title="物流服务商" @ok="saveProvider">
      <a-form layout="vertical">
        <a-form-item label="服务商名称" required><a-input v-model:value="providerForm.providerName" /></a-form-item>
        <a-form-item label="编码（SF/DB/JD/ZT/SELF）" required><a-input v-model:value="providerForm.providerCode" /></a-form-item>
        <a-form-item label="接口类型">
          <a-select v-model:value="providerForm.apiType">
            <a-select-option value="mock">沙箱模拟</a-select-option>
            <a-select-option value="api">真实接口</a-select-option>
            <a-select-option value="manual">手工录入</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="状态">
          <a-select v-model:value="providerForm.status">
            <a-select-option :value="1">启用</a-select-option>
            <a-select-option :value="0">停用</a-select-option>
          </a-select>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 运费模板编辑弹窗 -->
    <a-modal v-model:open="templateVisible" title="运费模板" @ok="saveTemplate">
      <a-form layout="vertical">
        <a-form-item label="模板名称" required><a-input v-model:value="templateForm.templateName" /></a-form-item>
        <a-form-item label="物流服务商" required>
          <a-select v-model:value="templateForm.providerId">
            <a-select-option v-for="p in providers" :key="p.providerId" :value="p.providerId">{{ p.providerName }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="计费方式">
          <a-select v-model:value="templateForm.pricingType">
            <a-select-option value="weight">按重量</a-select-option>
            <a-select-option value="piece">按件</a-select-option>
            <a-select-option value="fixed">固定运费</a-select-option>
          </a-select>
        </a-form-item>
        <a-row :gutter="12">
          <a-col :span="12"><a-form-item label="首重(kg)"><a-input-number v-model:value="templateForm.firstWeight" :min="0" style="width:100%" /></a-form-item></a-col>
          <a-col :span="12"><a-form-item label="首重费(元)"><a-input-number v-model:value="templateForm.firstPrice" :min="0" style="width:100%" /></a-form-item></a-col>
        </a-row>
        <a-row :gutter="12">
          <a-col :span="12"><a-form-item label="续重单位(kg)"><a-input-number v-model:value="templateForm.continuedWeight" :min="0" style="width:100%" /></a-form-item></a-col>
          <a-col :span="12"><a-form-item label="续重费(元)"><a-input-number v-model:value="templateForm.continuedPrice" :min="0" style="width:100%" /></a-form-item></a-col>
        </a-row>
        <a-row :gutter="12">
          <a-col :span="12"><a-form-item label="满额包邮(元)"><a-input-number v-model:value="templateForm.freeThreshold" :min="0" style="width:100%" /></a-form-item></a-col>
          <a-col :span="12"><a-form-item label="偏远附加(元)"><a-input-number v-model:value="templateForm.remoteSurcharge" :min="0" style="width:100%" /></a-form-item></a-col>
        </a-row>
      </a-form>
    </a-modal>

    <!-- 轨迹弹窗 -->
    <a-modal v-model:open="traceVisible" title="物流轨迹" :footer="null">
      <a-timeline>
        <a-timeline-item v-for="t in traces" :key="t.traceId" :color="t.isAbnormal ? 'red' : 'blue'">
          <p>{{ t.description }}</p>
          <small>{{ t.traceTime }}</small>
        </a-timeline-item>
      </a-timeline>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { message } from 'ant-design-vue'
import { logisticsApi } from '../../api'

const tab = ref('provider')
const providers = ref([])
const templates = ref([])
const waybills = ref([])
const alerts = ref([])
const aftersales = ref([])
const settlements = ref([])
const traces = ref([])
const waybillPage = ref({ page: 1, size: 10, total: 0 })
const alertPage = ref({ page: 1, size: 10, total: 0 })
const afterPage = ref({ page: 1, size: 10, total: 0 })
const settlePage = ref({ page: 1, size: 10, total: 0 })

const providerVisible = ref(false)
const templateVisible = ref(false)
const traceVisible = ref(false)
const providerForm = ref({})
const templateForm = ref({})

const providerCols = [
  { title: 'ID', dataIndex: 'providerId' },
  { title: '名称', dataIndex: 'providerName' },
  { title: '编码', dataIndex: 'providerCode' },
  { title: '接口类型', dataIndex: 'apiType' },
  { title: '状态', key: 'status' },
  { title: '操作', key: 'action' }
]
const templateCols = [
  { title: 'ID', dataIndex: 'templateId' },
  { title: '模板名称', dataIndex: 'templateName' },
  { title: '计费方式', dataIndex: 'pricingType' },
  { title: '首重费', dataIndex: 'firstPrice' },
  { title: '续重费', dataIndex: 'continuedPrice' },
  { title: '包邮门槛', dataIndex: 'freeThreshold' },
  { title: '操作', key: 'action' }
]
const waybillCols = [
  { title: '运单号', dataIndex: 'waybillNo' },
  { title: '订单号', dataIndex: 'orderNo' },
  { title: '快递单号', dataIndex: 'logisticsNo' },
  { title: '运费', dataIndex: 'freight' },
  { title: '状态', key: 'status' },
  { title: '操作', key: 'action' }
]
const alertCols = [
  { title: 'ID', dataIndex: 'alertId' },
  { title: '运单ID', dataIndex: 'waybillId' },
  { title: '类型', dataIndex: 'alertType' },
  { title: '级别', dataIndex: 'alertLevel' },
  { title: '内容', dataIndex: 'content' },
  { title: '状态', dataIndex: 'status' },
  { title: '操作', key: 'action' }
]
const aftersaleCols = [
  { title: '售后单号', dataIndex: 'aftersaleNo' },
  { title: '类型', dataIndex: 'aftersaleType' },
  { title: '描述', dataIndex: 'description' },
  { title: '赔付金额', dataIndex: 'claimAmount' },
  { title: '状态', dataIndex: 'status' },
  { title: '操作', key: 'action' }
]
const settleCols = [
  { title: '结算单号', dataIndex: 'settlementNo' },
  { title: '运单数', dataIndex: 'waybillCount' },
  { title: '运费合计', dataIndex: 'totalFreight' },
  { title: '应付', dataIndex: 'payableAmount' },
  { title: '状态', dataIndex: 'status' },
  { title: '操作', key: 'action' }
]

const statusText = (s) => ({ pending_ship: '待发货', picked_up: '已揽收', in_transit: '运输中', out_for_delivery: '派件中', delivered: '已签收', exception: '异常', returned: '已退回', lost: '丢失', cancelled: '已取消' }[s] || s)
const statusColor = (s) => ({ delivered: 'green', exception: 'red', returned: 'orange', lost: 'red', in_transit: 'blue' }[s] || 'default')

// request.js 拦截器已剥壳返回 data 层：List 直接用返回值，Page 读 records/total，不再读 .data
async function loadProviders() { providers.value = (await logisticsApi.adminProviders()) || [] }
async function loadTemplates() { templates.value = (await logisticsApi.adminTemplates()) || [] }
async function loadWaybills(page = 1) {
  waybillPage.value.page = page
  const r = await logisticsApi.waybillList({ page, size: 10 })
  waybills.value = r?.records || []
  waybillPage.value.total = Number(r?.total || 0)
}
async function loadAlerts(page = 1) {
  alertPage.value.page = page
  const r = await logisticsApi.alerts({ page, size: 10 })
  alerts.value = r?.records || []
  alertPage.value.total = Number(r?.total || 0)
}
async function loadAftersales(page = 1) {
  afterPage.value.page = page
  const r = await logisticsApi.aftersales({ page, size: 10 })
  aftersales.value = r?.records || []
  afterPage.value.total = Number(r?.total || 0)
}
async function loadSettlements(page = 1) {
  settlePage.value.page = page
  const r = await logisticsApi.settlements({ page, size: 10 })
  settlements.value = r?.records || []
  settlePage.value.total = Number(r?.total || 0)
}

function openProvider(record) { providerForm.value = record ? { ...record } : { status: 1, apiType: 'mock' }; providerVisible.value = true }
function openTemplate(record) { templateForm.value = record ? { ...record } : { pricingType: 'weight', status: 1, firstWeight: 1, continuedWeight: 1 }; templateVisible.value = true }

async function saveProvider() {
  await logisticsApi.saveProvider(providerForm.value)
  message.success('保存成功')
  providerVisible.value = false
  loadProviders()
}
async function saveTemplate() {
  await logisticsApi.saveTemplate(templateForm.value)
  message.success('保存成功')
  templateVisible.value = false
  loadTemplates()
}
async function showTrace(record) {
  const r = await logisticsApi.traces(record.waybillId)
  traces.value = Array.isArray(r) ? r : []
  traceVisible.value = true
}
async function handleAlert(record) {
  await logisticsApi.handleAlert(record.alertId, '已人工处理')
  message.success('告警已处理')
  loadAlerts()
}
async function processAftersale(record, action) {
  const note = action === 'reject' ? '平台驳回' : '已处理'
  await logisticsApi.processAftersale(record.aftersaleId, action, note)
  message.success(action === 'reject' ? '已驳回售后申请' : '售后处理成功')
  loadAftersales()
}
async function reviewSettle(record, action) {
  await logisticsApi.reviewSettlement(record.settlementId, action, action === 'reject' ? '平台驳回' : '')
  message.success(action === 'reject' ? '已驳回，结算单退回草稿' : '结算操作成功')
  loadSettlements()
}
function openGenerate() {
  message.info('生成对账单：请在结算页选择服务商与账期（示例入口）')
}

watch(tab, (t) => {
  if (t === 'provider') loadProviders()
  if (t === 'template') loadTemplates()
  if (t === 'waybill') loadWaybills()
  if (t === 'alert') loadAlerts()
  if (t === 'aftersale') loadAftersales()
  if (t === 'settlement') loadSettlements()
})

onMounted(() => {
  loadProviders()
  loadTemplates()
})
</script>

<style scoped>
.logistics-page {
  padding: 16px;
}
</style>

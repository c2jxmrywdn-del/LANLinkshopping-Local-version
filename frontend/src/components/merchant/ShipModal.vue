<template>
  <a-modal :open="open" :title="isSplitMode ? '工业品多包裹拆单发货' : '订单发货'" width="740px" :confirm-loading="submitting"
           ok-text="确认出库发货" cancel-text="取消" @ok="submit" @cancel="$emit('update:open', false)"
           destroy-on-close>
    <a-alert type="info" show-icon class="tip"
             :message="`订单 ${orderNo} 处于可履约状态。支持按工业品 SKU 拆单、分批多包裹出库，随货上传质保证书(CoA)与工业增值服务。`" />

    <a-form layout="vertical">
      <!-- 模式切换与包裹信息 -->
      <a-row :gutter="12">
        <a-col :span="8">
          <a-form-item label="运输方式" required>
            <a-select v-model:value="form.transportMode" placeholder="请选择运输方式">
              <a-select-option value="express">标准快递 (小件专送)</a-select-option>
              <a-select-option value="ltl">零担精准快运 (重货干线)</a-select-option>
              <a-select-option value="ftl">整车专线 (大宗直达)</a-select-option>
              <a-select-option value="direct">原厂直配 (工业直供)</a-select-option>
              <a-select-option value="self">客户自提 (仓库提货)</a-select-option>
            </a-select>
          </a-form-item>
        </a-col>
        <a-col :span="8">
          <a-form-item label="包裹编号" required>
            <a-input v-model:value="form.packageNo" placeholder="如 PKG-01" maxlength="32" />
          </a-form-item>
        </a-col>
        <a-col :span="8">
          <a-form-item label="分包序号 / 预估总包数">
            <a-input-group compact style="display:flex">
              <a-input-number v-model:value="form.packageIndex" :min="1" :max="99" style="width:50%" placeholder="当前包" />
              <a-input-number v-model:value="form.packageTotal" :min="1" :max="99" style="width:50%" placeholder="总包数" />
            </a-input-group>
          </a-form-item>
        </a-col>
      </a-row>

      <!-- SKU 发货件数拆分表格 -->
      <div class="sku-table-card">
        <div class="sku-table-title">
          <span style="display:inline-flex;align-items:center;gap:6px">
            <PackageCheck :size="16" class="text-sky-600" aria-hidden="true" />
            勾选并设定本次包裹发货商品明细
          </span>
          <span class="sub-tip" v-if="orderItems.length">共 {{ orderItems.length }} 款商品</span>
        </div>
        <div v-if="loadingItems" class="table-loading">正在拉取订单商品明细与发货进度...</div>
        <table v-else-if="orderItems.length" class="split-table">
          <thead>
            <tr>
              <th style="width: 44px; text-align: center;">发货</th>
              <th>商品名称 / 规格</th>
              <th style="width: 80px; text-align: center;">订购数</th>
              <th style="width: 80px; text-align: center;">已发数</th>
              <th style="width: 80px; text-align: center;">待发数</th>
              <th style="width: 110px; text-align: center;">本次发货</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in orderItems" :key="item.itemId" :class="{ 'row-done': itemRemain(item) <= 0 }">
              <td style="text-align: center;">
                <a-checkbox v-model:checked="item.selected" :disabled="itemRemain(item) <= 0" />
              </td>
              <td class="col-prod">
                <span class="prod-text">{{ item.prodName }}</span>
              </td>
              <td style="text-align: center;">{{ item.quantity }}</td>
              <td style="text-align: center; color: #16a34a;">{{ item.shippedQuantity || 0 }}</td>
              <td style="text-align: center; color: #ea580c; font-weight: 600;">{{ itemRemain(item) }}</td>
              <td style="text-align: center;">
                <a-input-number v-model:value="item.thisShipQty" :min="1" :max="itemRemain(item)"
                                :disabled="!item.selected || itemRemain(item) <= 0" size="small" style="width:85px" />
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- 随货同行单证 (CoA 质保书 / 材质单 / 合格证) -->
      <div class="sender-title" style="display:flex;justify-content:space-between;align-items:center">
        <span style="display:inline-flex;align-items:center;gap:6px">
          <FileText :size="15" class="text-indigo-600" aria-hidden="true" />
          随货同行质保证书 (CoA / 材质单 / 出厂报告)
        </span>
        <label class="upload-btn">
          <UploadCloud :size="13" class="inline-block mr-1 align-middle" />
          上传质检文件
          <input type="file" accept=".pdf,image/*" style="display:none" @change="handleCoaUpload" />
        </label>
      </div>
      <div class="coa-card">
        <div v-if="!form.coaDocs.length" class="coa-empty">
          <FileCheck2 :size="24" class="text-slate-300 mb-1" />
          <p class="text-xs text-slate-400">暂无随货单证。建议工业品出库附带原厂质保证书（PDF或图片），买方收货入库将在线验真。</p>
        </div>
        <div v-else class="coa-list">
          <div v-for="(doc, idx) in form.coaDocs" :key="idx" class="coa-item">
            <div class="coa-meta">
              <FileText :size="16" class="text-sky-500 mr-2 flex-shrink-0" />
              <div class="coa-name-box">
                <span class="coa-name" :title="doc.name">{{ doc.name }}</span>
                <span class="coa-tag">{{ doc.type.toUpperCase() }}</span>
              </div>
            </div>
            <div class="coa-batch">
              <a-input v-model:value="doc.batchNo" placeholder="批次号/炉号 (如 B202611-01)" size="small" style="width: 170px" />
            </div>
            <button type="button" class="del-doc-btn" @click="removeCoaDoc(idx)">
              <Trash2 :size="14" />
            </button>
          </div>
        </div>
      </div>

      <!-- 工业增值服务 (VAS Matrix) -->
      <div class="sender-title" style="display:flex;align-items:center;gap:6px">
        <Forklift :size="15" class="text-amber-500" aria-hidden="true" />
        工业增值服务池 (VAS Surcharges)
      </div>
      <div class="vas-box">
        <a-row :gutter="[12, 10]">
          <a-col :span="12">
            <a-checkbox v-model:checked="form.vas.forklift">
              <span class="vas-label">
                <Truck :size="13" class="text-amber-600 inline mr-1" />
                叉车装卸就位 (重货到厂叉车卸车)
              </span>
            </a-checkbox>
          </a-col>
          <a-col :span="12">
            <a-checkbox v-model:checked="form.vas.woodenCrate">
              <span class="vas-label">
                <Package :size="13" class="text-blue-600 inline mr-1" />
                工业木架/木箱包装 (防碰撞定制打木架)
              </span>
            </a-checkbox>
          </a-col>
          <a-col :span="12">
            <div style="display:inline-flex;align-items:center;gap:8px">
              <a-checkbox v-model:checked="form.vas.upstairs">
                <span class="vas-label">送货上楼 / 进厂就位</span>
              </a-checkbox>
              <a-input-number v-if="form.vas.upstairs" v-model:value="form.vas.floorCount" :min="1" :max="50" size="small" style="width:65px" addon-after="层" />
            </div>
          </a-col>
          <a-col :span="12">
            <a-checkbox v-model:checked="form.vas.oversize">
              <span class="vas-label">超长/超重特殊配载 (单件>2.5m或>300kg)</span>
            </a-checkbox>
          </a-col>
        </a-row>
      </div>

      <!-- Phase 3A: AI 运力多目标智能比价推荐卡片 -->
      <CarrierRecommendationCards
        :recommend-result="recommendResult"
        :loading="loadingRecommend"
        :selected-template-id="form.templateId"
        @select="handleSelectRecommendation"
      />

      <!-- 物流承运与运费 -->
      <div class="sender-title">物流承运与计费</div>
      <a-row :gutter="12">
        <a-col :span="12">
          <a-form-item label="物流服务商" required>
            <a-select v-model:value="form.providerId" placeholder="选择服务商" :loading="loadingRef"
                      @change="form.templateId = null">
              <a-select-option v-for="p in providers" :key="p.providerId" :value="p.providerId">
                {{ p.providerName }}
              </a-select-option>
            </a-select>
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="运费模板（选填）">
            <a-select v-model:value="form.templateId" placeholder="留空不计运费" allow-clear
                      :disabled="!form.providerId">
              <a-select-option v-for="t in templates" :key="t.templateId" :value="t.templateId">
                {{ t.templateName }}
              </a-select-option>
            </a-select>
          </a-form-item>
        </a-col>
      </a-row>
      <a-form-item label="第三方货运单号（选填）">
        <a-input v-model:value="form.logisticsNo" placeholder="如 SF1234567890 / DP987654321" maxlength="64" />
      </a-form-item>
      <a-row :gutter="12">
        <a-col :span="12">
          <a-form-item label="预估总重（kg，选填）">
            <a-input-number v-model:value="form.weight" :min="0.01" :max="99999" :precision="2" style="width:100%" />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="预估体积（m³，选填）">
            <a-input-number v-model:value="form.volume" :min="0.001" :max="9999" :precision="3" style="width:100%" />
          </a-form-item>
        </a-col>
      </a-row>
      <a-form-item>
        <a-checkbox v-model:checked="form.remote">偏远区域（加收附加费，按模板配置）</a-checkbox>
      </a-form-item>

      <div class="sender-title">发货仓及寄件人信息</div>
      <a-row :gutter="12">
        <a-col :span="12">
          <a-form-item label="寄件人">
            <a-input v-model:value="form.senderName" maxlength="32" placeholder="发货负责人 / 仓库调度" />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="联系电话">
            <a-input v-model:value="form.senderPhone" maxlength="20" placeholder="发货联系电话" />
          </a-form-item>
        </a-col>
      </a-row>
      <a-form-item label="发货地址（将自动匹配 GIS 地图发货枢纽）">
        <a-textarea v-model:value="form.senderAddress" :rows="2" maxlength="200" placeholder="发货仓库详细地址（如：江苏省苏州市工业园区...）" />
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup>
import { reactive, ref, watch, computed } from 'vue'
import { message } from 'ant-design-vue'
import { PackageCheck, FileText, UploadCloud, Trash2, FileCheck2, Forklift, Truck, Package } from 'lucide-vue-next'
import { logisticsApi } from '../../api'
import request from '../../api/request'
import CarrierRecommendationCards from '../logistics/CarrierRecommendationCards.vue'

const props = defineProps({
  open: Boolean,
  orderNo: { type: String, default: '' },
  sender: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['update:open', 'shipped'])

const loadingRef = ref(false)
const loadingItems = ref(false)
const submitting = ref(false)
const providers = ref([])
const templates = ref([])
const orderItems = ref([])
const existingWaybillsCount = ref(0)

// Phase 3A: AI 运力推荐状态
const recommendResult = ref(null)
const loadingRecommend = ref(false)
let recommendTimer = 0

const form = reactive({
  providerId: null, templateId: null, logisticsNo: '',
  weight: null, volume: null, remote: false,
  senderName: '', senderPhone: '', senderAddress: '',
  transportMode: 'ltl',
  packageNo: 'PKG-01',
  packageIndex: 1,
  packageTotal: 1,
  coaDocs: [],
  vas: {
    forklift: false,
    upstairs: false,
    floorCount: 1,
    woodenCrate: false,
    oversize: false
  }
})

const isSplitMode = computed(() => orderItems.value.length > 1 || existingWaybillsCount.value > 0)

function itemRemain(item) {
  const total = Number(item.quantity || 0)
  const shipped = Number(item.shippedQuantity || 0)
  return Math.max(0, total - shipped)
}

async function handleCoaUpload(e) {
  const file = e.target.files?.[0]
  if (!file) return
  if (file.size > 20 * 1024 * 1024) {
    message.error('质检单证文件大小不能超过 20MB')
    return
  }
  const hide = message.loading('正在上传质验证书...', 0)
  try {
    const fd = new FormData()
    fd.append('file', file)
    const res = await request.post('/attachment/upload?bizType=logistics-coa-doc', fd, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    const docData = res?.data || res
    form.coaDocs.push({
      name: file.name,
      url: docData.fileUrl || docData.url || `/attachment/file/${docData.attachNo || docData.attachmentId}`,
      batchNo: '',
      type: file.name.endsWith('.pdf') ? 'coa' : 'mtr',
      size: file.size,
      hash: docData.sha256 || ''
    })
    message.success('单证上传成功，已绑定出库包裹')
  } catch (err) {
    // 拦截器处理
  } finally {
    hide()
    e.target.value = ''
  }
}

function removeCoaDoc(idx) {
  form.coaDocs.splice(idx, 1)
}

watch(() => props.open, async (v) => {
  if (!v) return
  Object.assign(form, {
    providerId: null, templateId: null, logisticsNo: '',
    weight: null, volume: null, remote: false,
    senderName: props.sender?.name || '',
    senderPhone: props.sender?.phone || '',
    senderAddress: props.sender?.address || '',
    transportMode: 'ltl',
    packageNo: 'PKG-01',
    packageIndex: 1,
    packageTotal: 1,
    coaDocs: [],
    vas: {
      forklift: false,
      upstairs: false,
      floorCount: 1,
      woodenCrate: false,
      oversize: false
    }
  })
  loadingRef.value = true
  loadingItems.value = true
  try {
    const [pList, tList, agg] = await Promise.all([
      logisticsApi.providers().catch(() => []),
      logisticsApi.templates().catch(() => []),
      logisticsApi.orderWaybills(props.orderNo).catch(() => null)
    ])
    providers.value = pList || []
    templates.value = tList || []

    if (agg) {
      existingWaybillsCount.value = agg.totalWaybills || 0
      const nextIdx = (agg.totalWaybills || 0) + 1
      form.packageIndex = nextIdx
      form.packageNo = `PKG-${String(nextIdx).padStart(2, '0')}`
      form.packageTotal = Math.max(nextIdx, agg.totalWaybills ? agg.totalWaybills + 1 : 1)

      const items = (agg.orderItems || []).map(oi => {
        const remain = Math.max(0, Number(oi.quantity || 0) - Number(oi.shippedQuantity || 0))
        return {
          ...oi,
          selected: remain > 0,
          thisShipQty: remain
        }
      })
      orderItems.value = items
    }
    fetchRecommendations()
  } catch (e) { /* 拦截器已处理 */ }
  loadingRef.value = false
  loadingItems.value = false
})

function debounceRecommend() {
  clearTimeout(recommendTimer)
  recommendTimer = setTimeout(fetchRecommendations, 350)
}

async function fetchRecommendations() {
  if (!props.open) return
  loadingRecommend.value = true
  try {
    const res = await logisticsApi.recommendCarriers({
      weight: form.weight ? Number(form.weight) : 10,
      volume: form.volume ? Number(form.volume) : 0.05,
      receiverProvince: '',
      forklift: form.vas.forklift,
      upstairs: form.vas.upstairs,
      floorCount: form.vas.floorCount,
      woodenCrate: form.vas.woodenCrate,
      oversize: form.vas.oversize
    })
    recommendResult.value = res?.data || res
  } catch (e) {
    recommendResult.value = null
  } finally {
    loadingRecommend.value = false
  }
}

function handleSelectRecommendation(quote) {
  if (!quote) return
  form.providerId = quote.providerId
  form.templateId = quote.templateId
  form.transportMode = quote.transportMode || 'ltl'
  message.success(`已选用【${quote.providerName} · ${quote.templateName}】，预估运费 ¥${Number(quote.totalFreight || 0).toFixed(2)}`)
}

// 监听重量、体积、增值服务变化自动重新试算推荐
watch([() => form.weight, () => form.volume, () => form.vas], debounceRecommend, { deep: true })

async function submit() {
  if (!form.providerId) { message.error('请选择物流服务商'); return }
  if (!form.senderName || !form.senderPhone || !form.senderAddress) {
    message.error('请补全发货人与发货仓地址')
    return
  }

  const splitItems = orderItems.value
    .filter(i => i.selected && i.thisShipQty > 0)
    .map(i => ({
      orderItemId: i.itemId,
      quantity: i.thisShipQty
    }))

  if (orderItems.value.length > 0 && splitItems.length === 0) {
    message.error('请至少勾选一款本次发货商品，并填写大于 0 的发货数量')
    return
  }

  submitting.value = true
  try {
    let wb
    if (splitItems.length > 0) {
      wb = await logisticsApi.shipSplit({
        orderNo: props.orderNo,
        providerId: form.providerId,
        templateId: form.templateId ?? null,
        logisticsNo: form.logisticsNo || null,
        weight: form.weight,
        volume: form.volume,
        remote: form.remote,
        senderName: form.senderName,
        senderPhone: form.senderPhone,
        senderAddress: form.senderAddress,
        transportMode: form.transportMode,
        packageNo: form.packageNo,
        packageIndex: form.packageIndex,
        packageTotal: form.packageTotal,
        items: splitItems,
        coaDocs: form.coaDocs,
        vas: form.vas
      })
    } else {
      wb = await logisticsApi.ship({
        orderNo: props.orderNo,
        providerId: form.providerId,
        templateId: form.templateId ?? null,
        logisticsNo: form.logisticsNo || null,
        weight: form.weight,
        volume: form.volume,
        remote: form.remote,
        quantity: null,
        senderName: form.senderName,
        senderPhone: form.senderPhone,
        senderAddress: form.senderAddress
      })
    }
    message.success(`发货成功！包裹 [${wb.packageNo || wb.waybillNo}] 已就绪`)
    emit('shipped', wb)
    emit('update:open', false)
  } catch (e) { /* 拦截器已提示 */ }
  submitting.value = false
}
</script>

<style scoped>
.tip { margin-bottom: 14px; }
.sender-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--mc-ink, #1d1d1f);
  margin: 10px 0 10px;
  padding-top: 12px;
  border-top: 1px solid var(--mc-border-soft, #f0eee9);
}

.sku-table-card {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 12px;
  margin: 10px 0 16px;
}
.sku-table-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  font-weight: 600;
  color: #334155;
  margin-bottom: 8px;
}
.sub-tip {
  font-size: 12px;
  color: #64748b;
  font-weight: normal;
}
.table-loading {
  font-size: 12px;
  color: #94a3b8;
  padding: 12px 0;
  text-align: center;
}
.split-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
  background: #fff;
  border-radius: 6px;
  overflow: hidden;
}
.split-table th {
  background: #f1f5f9;
  color: #475569;
  font-weight: 600;
  padding: 8px 10px;
  border-bottom: 1px solid #e2e8f0;
}
.split-table td {
  padding: 8px 10px;
  border-bottom: 1px solid #f1f5f9;
  color: #1e293b;
}
.col-prod {
  max-width: 240px;
}
.prod-text {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.row-done {
  opacity: 0.55;
  background: #f8fafc;
}

.upload-btn {
  font-size: 12px;
  color: #0284c7;
  background: #f0f9ff;
  border: 1px solid #bae6fd;
  padding: 4px 10px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s ease;
}
.upload-btn:hover {
  background: #e0f2fe;
}
.coa-card {
  background: #f8fafc;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 14px;
}
.coa-empty {
  text-align: center;
  padding: 8px 0;
}
.coa-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.coa-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 6px;
  padding: 8px 10px;
  font-size: 12px;
}
.coa-meta {
  display: flex;
  align-items: center;
  flex: 1;
  min-width: 0;
}
.coa-name-box {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.coa-name {
  font-weight: 500;
  color: #1e293b;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.coa-tag {
  font-size: 10px;
  background: #eff6ff;
  color: #2563eb;
  padding: 1px 5px;
  border-radius: 4px;
  border: 1px solid #bfdbfe;
}
.del-doc-btn {
  background: transparent;
  border: none;
  color: #ef4444;
  cursor: pointer;
  padding: 4px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s ease;
}
.del-doc-btn:hover {
  background: #fee2e2;
}

.vas-box {
  background: #fafaf9;
  border: 1px solid #e7e5e4;
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 14px;
}
.vas-label {
  font-size: 12.5px;
  color: #44403c;
}
</style>

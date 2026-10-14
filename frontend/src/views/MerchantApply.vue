<template>
  <div class="wrap">
    <a-card title="商户入驻申请" class="left">
      <a-alert type="info" show-icon style="margin-bottom:16px"
               message="平台筛选机制"
               description="需满足：公司类型注册，或个体工商户注册资本 > 10 万元；且有稳定纳税记录。请上传工商信息（营业执照）与近期纳税记录作为审核材料，系统自动核验。" />
      <a-form :model="form" layout="vertical" @finish="submit">
        <a-form-item label="企业名称" :rules="[{ required: true }]"><a-input v-model:value="form.entName" /></a-form-item>
        <a-form-item label="统一社会信用代码"><a-input v-model:value="form.creditCode" /></a-form-item>
        <a-form-item label="注册类型">
          <a-select v-model:value="form.regType">
            <a-select-option value="公司">公司</a-select-option>
            <a-select-option value="个体工商户">个体工商户</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="注册资本（元）" :rules="[{ required: true }]"><a-input-number v-model:value="form.regCapital" :min="0" style="width:100%" /></a-form-item>
        <a-form-item label="是否有稳定纳税记录">
          <a-radio-group v-model:value="form.taxStatus">
            <a-radio :value="1">有</a-radio><a-radio :value="0">无</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item label="入驻方式">
          <a-select v-model:value="form.joinType">
            <a-select-option value="入驻">自主入驻</a-select-option>
            <a-select-option value="加盟">加盟</a-select-option>
            <a-select-option value="邀约">平台邀约</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="已具备资质（逗号分隔，选填）"><a-input v-model:value="form.qNames" /></a-form-item>

        <!-- 工商信息上传（营业执照） -->
        <a-form-item required label="工商信息（营业执照）">
          <div class="up-box" :class="{ done: license.status === 'done' }">
            <template v-if="!license.name">
              <a-upload accept="image/jpeg,image/png" :show-upload-list="false" :before-upload="pickLicense">
                <div class="up-trigger" :class="{ uploading: license.status === 'uploading' }">
                  <template v-if="license.status === 'uploading'">
                    <a-spin size="small" />
                    <div class="up-pct">{{ license.percent }}%</div>
                  </template>
                  <template v-else>
                    <plus-outlined />
                    <div class="up-text">上传执照</div>
                  </template>
                </div>
              </a-upload>
            </template>
            <template v-else>
              <div class="up-preview">
                <img v-if="license.isImg" :src="license.url || previewUrl" class="up-img" alt="营业执照" />
                <div v-else class="up-file"><file-pdf-outlined /> PDF 文件</div>
                <div class="up-meta">
                  <div class="up-name">{{ license.name }}</div>
                  <template v-if="license.status === 'uploading'">
                    <a-progress :percent="license.percent" size="small" style="width:160px" />
                  </template>
                  <template v-else-if="license.status === 'done'">
                    <a-tag color="success"><check-circle-outlined /> 上传成功</a-tag>
                  </template>
                  <template v-else-if="license.status === 'error'">
                    <a-tag color="error"><close-circle-outlined /> 上传失败</a-tag>
                    <a size="small" @click="retryLicense">重试</a>
                  </template>
                </div>
                <a-button v-if="license.status === 'done'" type="text" danger size="small" @click="removeLicense">删除</a-button>
              </div>
            </template>
          </div>
          <div class="up-hint">JPG/PNG 格式，≤ 10MB，需完整清晰可辨</div>
        </a-form-item>

        <!-- 近期纳税记录上传 -->
        <a-form-item label="近期纳税记录（选传，最多 3 份）">
          <a-upload accept=".pdf,.jpg,.jpeg,.png" :show-upload-list="false" :before-upload="pickTax">
            <a-button :loading="taxBusy"><upload-outlined /> 添加纳税记录（JPG/PNG/PDF）</a-button>
          </a-upload>
          <div class="up-hint" style="margin-top:6px">单份 ≤ 10MB；可上传完税证明、缴款书或电子税务局截图</div>
          <div v-for="(t, i) in taxFiles" :key="t.uid" class="tax-item">
            <file-text-outlined v-if="t.isPdf" class="tax-ico" />
            <file-image-outlined v-else class="tax-ico" />
            <span class="tax-name">{{ t.name }}</span>
            <template v-if="t.status === 'uploading'">
              <a-progress :percent="t.percent" size="small" style="width:120px" />
            </template>
            <template v-else-if="t.status === 'done'">
              <a-tag color="success">已上传</a-tag>
            </template>
            <template v-else-if="t.status === 'error'">
              <a-tag color="error">失败</a-tag>
              <a @click="retryTax(i)">重试</a>
            </template>
            <a class="tax-del" @click="removeTax(i)">移除</a>
          </div>
        </a-form-item>

        <a-button type="primary" html-type="submit" size="large" block :loading="loading">提交申请</a-button>
      </a-form>
    </a-card>
    <a-card title="我的商户状态" class="right">
      <template v-if="mine">
        <a-result :status="mine.reviewStatus === 1 ? 'success' : (mine.reviewStatus === 2 ? 'error' : 'info')"
                  :title="statusText">
          <template #subTitle>
            <div>注册资本：¥{{ mine.regCapital }}</div>
            <div class="mat-line">工商执照：<a-tag :color="mine.licenseUrl ? 'success' : 'default'">{{ mine.licenseUrl ? '已提交' : '未提交' }}</a-tag></div>
            <div class="mat-line">纳税记录：<a-tag :color="taxCount > 0 ? 'success' : 'default'">{{ taxCount > 0 ? taxCount + ' 份' : '未提交' }}</a-tag></div>
            <div v-if="mine.licenseUrl" class="license-view">营业执照：<a :href="mine.licenseUrl" target="_blank">查看</a></div>
            <div v-if="mine.rejectReason" style="color:#e4393c">原因：{{ mine.rejectReason }}</div>
          </template>
        </a-result>
      </template>
      <a-empty v-else description="尚未提交入驻申请" />
    </a-card>
  </div>
</template>

<script setup>
import { reactive, ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined, UploadOutlined, CheckCircleOutlined, CloseCircleOutlined,
         FilePdfOutlined, FileTextOutlined, FileImageOutlined } from '@ant-design/icons-vue'
import { merchantApi } from '../api'

const loading = ref(false)
const mine = ref(null)
const form = reactive({ entName: '', creditCode: '', regType: '公司', regCapital: 500000, taxStatus: 1, joinType: '入驻', qNames: '' })
const statusText = computed(() => ({ 0: '审核中', 1: '审核通过 · 已入驻', 2: '审核未通过' }[mine.value?.reviewStatus] || ''))

// ===== 材料上传：自管理状态（uploading/done/error + 进度），支持重试 =====
const MAX_MB = 10
const license = reactive({ name: '', file: null, status: '', percent: 0, url: '', previewUrl: '', isImg: true })
const taxFiles = ref([])
const taxBusy = ref(false)

const taxCount = computed(() => mine.value?.taxProofUrls ? mine.value.taxProofUrls.split(',').filter(Boolean).length : 0)

function checkBase(file, kinds) {
  if (!kinds.some(k => file.type === k || file.name.toLowerCase().endsWith(k))) {
    const tips = kinds.map(k => k.replace('.', '').toUpperCase()).join('/')
    message.error(`仅支持 ${tips} 格式`)
    return false
  }
  if (file.size > MAX_MB * 1024 * 1024) { message.error(`文件不能超过 ${MAX_MB}MB`); return false }
  return true
}

function pickLicense(file) {
  if (!checkBase(file, ['image/jpeg', 'image/png', '.jpg', '.png'])) return false
  license.name = file.name
  license.file = file
  license.previewUrl = URL.createObjectURL(file)
  license.isImg = true
  uploadLicense()
  return false // 阻断 a-upload 自动上传，改走自定义带进度的请求
}

async function uploadLicense() {
  license.status = 'uploading'
  license.percent = 0
  try {
    const data = await merchantApi.applyUpload('license', license.file,
      (p) => { license.percent = p })
    license.url = data.url
    license.status = 'done'
    message.success('营业执照上传成功')
  } catch (e) {
    license.status = 'error'
  }
}
function retryLicense() { if (license.file) uploadLicense() }
function removeLicense() {
  Object.assign(license, { name: '', file: null, status: '', percent: 0, url: '', previewUrl: '' })
}

function pickTax(file) {
  if (!checkBase(file, ['application/pdf', 'image/jpeg', 'image/png', '.pdf', '.jpg', '.png'])) return false
  if (taxFiles.value.length >= 3) { message.warning('最多上传 3 份纳税记录'); return false }
  const item = { uid: Date.now() + Math.random(), name: file.name, file,
                 status: 'uploading', percent: 0, url: '', isPdf: file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf') }
  taxFiles.value.push(item)
  taxBusy.value = true
  merchantApi.applyUpload('taxProof', file, (p) => { item.percent = p })
    .then(data => { item.url = data.url; item.status = 'done' })
    .catch(() => { item.status = 'error' })
    .finally(() => { taxBusy.value = false })
  return false
}
function retryTax(i) {
  const t = taxFiles.value[i]
  if (!t?.file) return
  t.status = 'uploading'; t.percent = 0
  merchantApi.applyUpload('taxProof', t.file, (p) => { t.percent = p })
    .then(data => { t.url = data.url; t.status = 'done' })
    .catch(() => { t.status = 'error' })
}
function removeTax(i) { taxFiles.value.splice(i, 1) }

async function submit() {
  if (!form.entName.trim()) { message.warning('请填写企业名称'); return }
  const uploading = license.status === 'uploading' || taxFiles.value.some(t => t.status === 'uploading')
  if (uploading) { message.warning('材料正在上传中，请稍候'); return }
  if (license.status === 'error' || taxFiles.value.some(t => t.status === 'error')) {
    message.warning('存在上传失败的材料，请重试或移除后再提交'); return
  }
  loading.value = true
  try {
    const payload = {
      ...form,
      licenseUrl: license.url || null,
      taxProofUrls: taxFiles.value.filter(t => t.status === 'done').map(t => t.url)
    }
    const r = await merchantApi.apply(payload)
    mine.value = r
    message.success(r.reviewStatus === 1 ? '恭喜，自动审核通过！' : '已提交，请等待审核')
  } finally { loading.value = false }
}
onMounted(async () => { try { mine.value = await merchantApi.my() } catch (e) {} })
</script>

<style scoped>
.wrap { display: flex; gap: 24px; align-items: flex-start; }
.left { flex: 1; } .right { width: 420px; }
.license-view { font-size: 13px; }
.mat-line { font-size: 13px; margin-top: 4px; }
/* 上传组件 */
.up-box { border: 1.5px dashed rgba(30, 110, 184, .35); border-radius: 10px; padding: 12px; min-height: 120px;
          display: flex; align-items: center; justify-content: center; background: rgba(30, 110, 184, .03); }
.up-box.done { border-style: solid; }
.up-trigger { cursor: pointer; text-align: center; color: var(--ll-primary, #1e6eb8); padding: 12px 28px; }
.up-trigger.uploading { cursor: wait; }
.up-text { font-size: 13px; margin-top: 6px; }
.up-pct { font-size: 13px; margin-top: 6px; font-weight: 600; }
.up-preview { display: flex; align-items: center; gap: 12px; width: 100%; }
.up-img { width: 72px; height: 54px; object-fit: cover; border-radius: 6px; border: 1px solid #e5e7eb; }
.up-file { font-size: 26px; color: #e4393c; }
.up-meta { flex: 1; min-width: 0; }
.up-name { font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.up-hint { font-size: 12px; color: var(--ll-muted, #64748b); margin-top: 6px; }
/* 纳税记录列表 */
.tax-item { display: flex; align-items: center; gap: 8px; padding: 6px 8px; margin-top: 8px;
            background: var(--ll-page, #f7f8fa); border-radius: 8px; }
.tax-ico { font-size: 16px; color: var(--ll-primary, #1e6eb8); flex-shrink: 0; }
.tax-name { font-size: 13px; flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tax-del { color: #e4393c; font-size: 12px; flex-shrink: 0; }
</style>

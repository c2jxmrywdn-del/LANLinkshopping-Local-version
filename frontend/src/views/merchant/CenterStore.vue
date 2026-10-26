<template>
  <div class="store-page">
    <section class="hero">
      <div class="hero-copy">
        <div class="eyebrow"><span class="dot"></span> 商户中心</div>
        <h1>店铺设置</h1>
        <p>维护店铺资料、资质证照与联系方式，保持信息合规有效。</p>
      </div>
    </section>

    <div class="grid">
      <!-- 店铺信息 -->
      <a-card :bordered="false" class="panel">
        <template #title>
          <div class="panel-title"><div><strong>店铺信息</strong><small>来自入驻申请，企业名称变更需联系平台</small></div></div>
        </template>
        <div class="store-head">
          <UserAvatar :src="user.user?.avatar || ''" :name="storeName" :initials="orgInitialsOf(storeName)"
                      role="merchant" :size="52" :ring="false" class="store-logo" />
          <div>
            <h3>{{ storeName }}</h3>
            <a-tag :color="MERCHANT_REVIEW[store.reviewStatus]?.color">{{ MERCHANT_REVIEW[store.reviewStatus]?.text }}</a-tag>
          </div>
        </div>
        <a-descriptions :column="1" size="small" bordered class="desc">
          <a-descriptions-item label="商户编号">#{{ store.merId }}</a-descriptions-item>
          <a-descriptions-item label="统一社会信用代码">{{ store.creditCode || '—' }}</a-descriptions-item>
          <a-descriptions-item label="注册类型">{{ store.regType || '—' }}</a-descriptions-item>
          <a-descriptions-item label="入驻方式">{{ store.joinType || '—' }}</a-descriptions-item>
          <a-descriptions-item label="入驻时间">{{ shortTime(store.createTime) }}</a-descriptions-item>
        </a-descriptions>
      </a-card>

      <!-- 联系方式 -->
      <a-card :bordered="false" class="panel">
        <template #title>
          <div class="panel-title">
            <div><strong>联系方式</strong><small>用于订单核验与平台通知</small></div>
            <a-button type="link" @click="openContact">编辑 →</a-button>
          </div>
        </template>
        <a-descriptions :column="1" size="small" bordered class="desc">
          <a-descriptions-item label="负责人电话">{{ store.contactPhone || '未填写' }}</a-descriptions-item>
          <a-descriptions-item label="联系邮箱">{{ store.contactEmail || '未填写' }}</a-descriptions-item>
          <a-descriptions-item label="经营地址">{{ store.businessAddress || '未填写' }}</a-descriptions-item>
        </a-descriptions>
        <p class="privacy-note"><Lock :size="12" class="inline-block mr-1 align-middle text-slate-400" /> 以上信息加密存储，仅平台运营可在核验场景下查看。</p>
      </a-card>
    </div>

    <!-- 资质证照 -->
    <a-card :bordered="false" class="panel">
      <template #title>
        <div class="panel-title"><div><strong>资质证照</strong><small>营业执照与完税证明，保证店铺合规经营</small></div></div>
      </template>
      <div class="cert-grid">
        <!-- 营业执照 -->
        <div class="cert-card">
          <div class="cert-head">
            <b>营业执照</b>
            <a-tag :color="store.licenseUrl ? 'green' : 'red'">{{ store.licenseUrl ? '已上传' : '未上传' }}</a-tag>
          </div>
          <div v-if="store.licenseUrl" class="cert-preview">
            <a-image :src="store.licenseUrl" alt="营业执照" fit="contain" :height="150" />
          </div>
          <p v-else class="cert-missing">尚未上传营业执照，请尽快补充以免影响店铺权益。</p>
          <a-upload :show-upload-list="false" accept="image/jpeg,image/png" :before-upload="beforeLicense">
            <a-button block :loading="licenseUploading">{{ store.licenseUrl ? '重新上传' : '上传营业执照' }}</a-button>
          </a-upload>
        </div>

        <!-- 税务缴纳证明 -->
        <div class="cert-card">
          <div class="cert-head">
            <b>税务缴纳证明</b>
            <a-tag :color="taxProofList.length ? 'green' : 'orange'">{{ taxProofList.length ? `已上传 ${taxProofList.length}/3` : '未上传' }}</a-tag>
          </div>
          <div v-if="taxProofList.length" class="cert-list">
            <div v-for="(u, i) in taxProofList" :key="u" class="cert-file">
              <FileText v-if="u.toLowerCase().endsWith('.pdf')" :size="15" class="cert-file-ico inline-block text-rose-500 mr-1.5 align-middle" />
              <Image v-else :size="15" class="cert-file-ico inline-block text-sky-500 mr-1.5 align-middle" />
              <a :href="u" target="_blank" rel="noopener" class="cert-file-name">纳税证明 #{{ i + 1 }}{{ periodOf(u) }}</a>
            </div>
          </div>
          <p v-else class="cert-missing">建议每月上传上月完税证明，最多保留 3 份。</p>
          <div class="cert-upload">
            <a-select v-model:value="taxPeriod" placeholder="所属期（选填）" class="period-select" allow-clear>
              <a-select-option v-for="p in recentPeriods" :key="p" :value="p">{{ p }}</a-select-option>
            </a-select>
            <a-upload :show-upload-list="false" accept="application/pdf,image/jpeg,image/png" :before-upload="beforeTaxProof">
              <a-button type="primary" block :loading="taxUploading">上传证明</a-button>
            </a-upload>
          </div>
        </div>

        <!-- 特殊经营许可 -->
        <div class="cert-card">
          <div class="cert-head">
            <b>特殊经营许可证</b>
            <a-tag :color="store.hasSpecialPermit === 1 ? 'green' : 'default'">{{ store.hasSpecialPermit === 1 ? '已备案' : '无' }}</a-tag>
          </div>
          <template v-if="store.hasSpecialPermit === 1">
            <p class="cert-name">{{ store.specialPermitName || '特殊经营许可' }}</p>
            <a v-if="store.specialPermitUrl" :href="store.specialPermitUrl" target="_blank" rel="noopener" class="cert-link">查看附件 →</a>
          </template>
          <p v-else class="cert-missing">如经营食品、危化品等特殊类目，请联系平台备案许可证。</p>
        </div>
      </div>
    </a-card>

    <!-- 税务登记核验 -->
    <a-card :bordered="false" class="panel">
      <template #title>
        <div class="panel-title"><div><strong>税务登记核验</strong><small>按登记号查询近 3 个月缴纳记录（演示数据源）</small></div></div>
      </template>
      <div class="tax-row">
        <a-input v-model:value="taxRegNo" placeholder="输入 15/18/20 位税务登记号" class="tax-input" maxlength="20" />
        <a-button type="primary" :loading="taxQuerying" @click="queryTax">查询缴纳记录</a-button>
      </div>
      <template v-if="taxResult">
        <a-alert type="success" show-icon class="tax-alert"
                 :message="`核验通过：近 3 个月累计缴纳 ¥${money(taxResult.totalAmount)}，登记号已绑定至店铺档案`" />
        <a-table :data-source="taxResult.records" :columns="taxCols" row-key="period" size="small" :pagination="false" class="mc-table" />
      </template>
    </a-card>

    <!-- L4：联系方式编辑（未修改内容时保存按钮禁用，点保存等同关闭） -->
    <a-modal v-model:open="contactOpen" title="编辑联系方式" :confirm-loading="contactSaving" ok-text="保存"
             cancel-text="取消" :ok-button-props="{ disabled: !contactDirty }" @ok="saveContact" destroy-on-close>
      <a-form layout="vertical" class="contact-form">
        <a-form-item label="负责人电话" :validate-status="phoneErr ? 'error' : ''" :help="phoneErr">
          <a-input v-model:value="contactForm.contactPhone" placeholder="手机号或座机号" maxlength="20" />
        </a-form-item>
        <a-form-item label="联系邮箱" :validate-status="emailErr ? 'error' : ''" :help="emailErr">
          <a-input v-model:value="contactForm.contactEmail" placeholder="name@company.com" maxlength="64" />
        </a-form-item>
        <a-form-item label="经营地址">
          <a-textarea v-model:value="contactForm.businessAddress" :rows="2" maxlength="200" placeholder="经营场所 / 注册地址" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { Lock, FileText, Image } from 'lucide-vue-next'
import { merchantApi, merchantCenterApi } from '../../api'
import { MERCHANT_REVIEW, fmtMoney } from '../../utils/merchantStatus'
import { useUserStore } from '../../store/user'
import UserAvatar from '../../components/ui/UserAvatar.vue'
import { orgInitialsOf } from '../../utils/avatarIdentity'

const user = useUserStore()
const store = ref({})
const taxProofList = computed(() =>
  (store.value.taxProofUrls || '').split(',').map(s => s.trim()).filter(Boolean))

/** 店铺名自动检测：企业名（summary 接口）→ 账号昵称 → 兜底文案 */
const storeName = computed(() => store.value.entName || user.user?.nickname || '我的店铺')
const money = fmtMoney
function shortTime(t) { return (t || '').replace('T', ' ').slice(0, 16) }
function periodOf(u) {
  const m = String(u).match(/_(\d{4}-\d{2})\./)
  return m ? `（${m[1]}）` : ''
}

// ===== 联系方式编辑 =====
const contactOpen = ref(false)
const contactSaving = ref(false)
const contactForm = reactive({ contactPhone: '', contactEmail: '', businessAddress: '' })
/** 打开弹窗时的初始值快照，用于判断是否修改过 */
const contactSnapshot = ref('')
const contactDirty = computed(() =>
  JSON.stringify([contactForm.contactPhone.trim(), contactForm.contactEmail.trim(), contactForm.businessAddress.trim()]) !== contactSnapshot.value)
const phoneErr = computed(() => {
  const v = contactForm.contactPhone.trim()
  if (!v) return ''
  return /^1[3-9]\d{9}$|^0\d{2,3}-?\d{7,8}$/.test(v) ? '' : '手机号（11 位）或座机号（区号-号码）'
})
const emailErr = computed(() => {
  const v = contactForm.contactEmail.trim()
  if (!v) return ''
  return /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(v) ? '' : '邮箱格式不正确'
})
function openContact() {
  Object.assign(contactForm, {
    contactPhone: store.value.contactPhone || '',
    contactEmail: store.value.contactEmail || '',
    businessAddress: store.value.businessAddress || ''
  })
  contactSnapshot.value = JSON.stringify([
    contactForm.contactPhone.trim(),
    contactForm.contactEmail.trim(),
    contactForm.businessAddress.trim()
  ])
  contactOpen.value = true
}
async function saveContact() {
  if (phoneErr.value || emailErr.value) { message.error('请修正格式错误后再保存'); return }
  // 未修改任何内容：不发请求、不弹提示，直接关闭
  if (!contactDirty.value) { contactOpen.value = false; return }
  contactSaving.value = true
  try {
    await merchantCenterApi.updateContact({
      contactPhone: contactForm.contactPhone.trim(),
      contactEmail: contactForm.contactEmail.trim(),
      businessAddress: contactForm.businessAddress.trim()
    })
    message.success('联系方式已更新')
    contactOpen.value = false
    load()
  } catch (e) { /* 拦截器已提示 */ }
  contactSaving.value = false
}

// ===== 资质上传 =====
const licenseUploading = ref(false)
const taxUploading = ref(false)
const taxPeriod = ref(null)
const recentPeriods = computed(() => {
  const out = []
  const d = new Date()
  for (let i = 1; i <= 3; i++) {
    const dt = new Date(d.getFullYear(), d.getMonth() - i, 1)
    out.push(`${dt.getFullYear()}-${String(dt.getMonth() + 1).padStart(2, '0')}`)
  }
  return out
})

function beforeLicense(file) {
  if (!['image/jpeg', 'image/png'].includes(file.type)) { message.error('营业执照仅支持 JPG/PNG'); return false }
  if (file.size > 5 * 1024 * 1024) { message.error('图片不能超过 5MB'); return false }
  licenseUploading.value = true
  const fd = new FormData()
  fd.append('file', file)
  merchantApi.uploadLicense(fd)
    .then(() => { message.success('营业执照已更新'); load() })
    .catch(() => {})
    .finally(() => { licenseUploading.value = false })
  return false
}
function beforeTaxProof(file) {
  if (!['application/pdf', 'image/jpeg', 'image/png'].includes(file.type)) {
    message.error('税务证明仅支持 PDF/JPG/PNG'); return false
  }
  if (file.size > 5 * 1024 * 1024) { message.error('文件不能超过 5MB'); return false }
  taxUploading.value = true
  merchantApi.submitTaxProof(file, taxPeriod.value || '')
    .then(() => { message.success('税务证明已上传'); taxPeriod.value = null; load() })
    .catch(() => {})
    .finally(() => { taxUploading.value = false })
  return false
}

// ===== 税务登记核验 =====
const taxRegNo = ref(store.value.taxRegNo || '')
const taxQuerying = ref(false)
const taxResult = ref(null)
const taxCols = [
  { title: '所属期', dataIndex: 'period' },
  { title: '缴纳金额（元）', dataIndex: 'amount' },
  { title: '税种', dataIndex: 'taxType' },
  { title: '状态', dataIndex: 'status' }
]
async function queryTax() {
  if (!taxRegNo.value.trim()) { message.error('请输入税务登记号'); return }
  taxQuerying.value = true
  try {
    taxResult.value = await merchantApi.taxQuery(taxRegNo.value.trim())
  } catch (e) { /* 拦截器已提示 */ }
  taxQuerying.value = false
}

async function load() {
  // my()：商户档案（联系方式/证照）；summary().store：企业名与信用代码（档案实体不含企业表字段）
  try {
    const [m, s] = await Promise.all([
      merchantApi.my(),
      merchantCenterApi.summary().catch(() => null)
    ])
    store.value = { ...(m || {}), ...(s?.store ? { entName: s.store.entName, creditCode: s.store.creditCode } : {}) }
  } catch (e) { /* 拦截器已提示 */ }
}
onMounted(load)
</script>

<style scoped>
.store-page { display: flex; flex-direction: column; gap: 16px; }
.hero { display: flex; justify-content: space-between; align-items: flex-end; gap: 14px; padding: 6px 2px 2px; }
.eyebrow { font-size: 12px; color: var(--mc-muted, #6e6e73); margin-bottom: 7px; letter-spacing: .04em; }
.dot { display: inline-block; width: 7px; height: 7px; border-radius: 50%; background: #9b2d20; margin-right: 7px; }
.hero h1 { margin: 0; font-size: 28px; font-weight: 750; letter-spacing: -.03em; color: var(--mc-ink, #1d1d1f); }
.hero p { margin: 8px 0 0; color: var(--mc-muted, #6e6e73); font-size: 14px; }

.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; align-items: start; }
.panel { border-radius: 16px; border: 1px solid var(--mc-border, #e8e6e1); box-shadow: 0 2px 14px rgba(20, 24, 34, .04); }
.panel :deep(.ant-card-head) { border-bottom: 1px solid var(--mc-border-soft, #f0eee9); padding: 0 20px; min-height: 58px; }
.panel :deep(.ant-card-body) { padding: 18px 20px; }
.panel-title { display: flex; align-items: center; justify-content: space-between; width: 100%; }
.panel-title strong { display: block; font-size: 15px; color: var(--mc-ink, #1d1d1f); letter-spacing: -.01em; }
.panel-title small { display: block; color: var(--mc-faint, #a1a1a6); font-size: 11px; margin-top: 3px; }

.store-head { display: flex; gap: 14px; align-items: center; margin-bottom: 16px; }
 .store-logo { flex-shrink: 0; }
.store-head h3 { margin: 0 0 6px; font-size: 17px; letter-spacing: -.01em; color: var(--mc-ink, #1d1d1f); }
.desc { margin-top: 6px; }
.privacy-note { margin: 12px 2px 0; font-size: 11.5px; color: var(--mc-faint, #a1a1a6); }

.cert-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; }
.cert-card { border: 1px solid var(--mc-border-soft, #f0eee9); border-radius: 14px; padding: 15px; display: flex; flex-direction: column; gap: 11px; }
.cert-head { display: flex; justify-content: space-between; align-items: center; }
.cert-head b { font-size: 13.5px; color: var(--mc-ink, #1d1d1f); }
.cert-preview :deep(img) { border-radius: 10px; }
.cert-missing { margin: 0; font-size: 12px; color: var(--mc-muted2, #86868b); line-height: 1.6; flex: 1; }
.cert-list { display: flex; flex-direction: column; gap: 7px; flex: 1; }
.cert-file { display: flex; align-items: center; gap: 8px; background: var(--mc-tint, #faf9f6); border-radius: 9px; padding: 8px 10px; }
.cert-file-ico { font-size: 15px; }
.cert-file-name { font-size: 12px; color: var(--mc-ink, #1d1d1f); }
.cert-name { margin: 0; font-size: 13px; color: var(--mc-ink, #1d1d1f); }
.cert-link { font-size: 12px; color: var(--mc-accent, #9b2d20); }
.cert-upload { display: flex; gap: 8px; }
.period-select { width: 140px; }

.tax-row { display: flex; gap: 10px; margin-bottom: 14px; }
.tax-input { max-width: 360px; }
.tax-alert { margin-bottom: 12px; border-radius: 12px; }

.contact-form :deep(.ant-form-item) { margin-bottom: 14px; }

@media (max-width: 900px) {
  .hero h1 { font-size: 22px; }
  .grid { grid-template-columns: 1fr; }
  .cert-grid { grid-template-columns: 1fr; }
  .tax-row { flex-direction: column; }
  .tax-input { max-width: none; }
}
</style>

<template>
  <div class="edit-page">
    <!-- 面包屑 + 操作 -->
    <div class="crumb-row">
      <a-breadcrumb class="crumb">
        <a-breadcrumb-item><a @click="$router.push('/merchant/center')">商户中心</a></a-breadcrumb-item>
        <a-breadcrumb-item><a @click="$router.push('/merchant/center/products')">商品管理</a></a-breadcrumb-item>
        <a-breadcrumb-item>编辑商品</a-breadcrumb-item>
      </a-breadcrumb>
      <a-button @click="$router.push('/merchant/center/products')">返回列表</a-button>
    </div>

    <a-spin :spinning="loading">
      <!-- 状态横幅 -->
      <a-alert v-if="form.reviewStatus === 2" type="error" show-icon class="state-banner"
               :message="'该商品审核被驳回：' + (form.rejectReason || '不符合平台规范')"
               description="修改商品内容并重新提交后，将再次进入平台审核。" />
      <a-alert v-else-if="form.reviewStatus === 0" type="info" show-icon class="state-banner"
               message="该商品正在审核中"
               description="如现在修改内容，将以修改后的版本重新提交平台审核。" />

      <a-form ref="formRef" :model="form" :rules="rules" layout="vertical" class="edit-form">
        <!-- 基础信息（修改后重新送审） -->
        <div class="section">
          <div class="section-head">
            <div><b>基础信息</b><span>修改价格、标题等内容后需重新提交平台审核</span></div>
          </div>
          <a-form-item label="商品名称" name="title">
            <a-input v-model:value="form.title" placeholder="2-100 个字符" maxlength="100" show-count />
          </a-form-item>
          <a-row :gutter="12">
            <a-col :xs="24" :sm="12">
              <a-form-item label="所属行业" name="indId">
                <a-select v-model:value="form.indId" placeholder="选择行业" @change="onIndustryChange">
                  <a-select-option v-for="i in industries" :key="i.indId" :value="i.indId">{{ i.name }}</a-select-option>
                </a-select>
              </a-form-item>
            </a-col>
            <a-col :xs="24" :sm="12">
              <a-form-item label="商品分类" name="catId">
                <a-select v-model:value="form.catId" placeholder="先选行业" :disabled="!form.indId">
                  <a-select-option v-for="c in categories" :key="c.catId" :value="c.catId">{{ c.name }}</a-select-option>
                </a-select>
              </a-form-item>
            </a-col>
          </a-row>
          <a-row :gutter="12">
            <a-col :xs="24" :sm="12">
              <a-form-item label="价格（元）" name="price">
                <a-input-number v-model:value="form.price" :min="0.01" :max="9999999" :precision="2"
                                style="width:100%" placeholder="0.01 - 9999999" />
              </a-form-item>
            </a-col>
            <a-col :xs="24" :sm="12">
              <a-form-item label="库存" name="stock">
                <a-input-number v-model:value="form.stock" :min="1" :max="999999" :precision="0"
                                style="width:100%" placeholder="1 - 999999" />
              </a-form-item>
            </a-col>
          </a-row>
          <a-form-item label="品牌（选填）" name="brand">
            <a-input v-model:value="form.brand" placeholder="如：华晨" maxlength="64" />
          </a-form-item>

          <!-- 规格参数 -->
          <a-form-item label="规格参数（选填）">
            <div v-for="(row, idx) in specRows" :key="idx" class="spec-row">
              <a-input v-model:value="row.k" placeholder="参数名（如：型号）" maxlength="20" style="width:38%" />
              <a-input v-model:value="row.v" placeholder="参数值（如：DN25）" maxlength="40" style="flex:1" />
              <a-button type="text" danger @click="specRows.splice(idx, 1)">删除</a-button>
            </div>
            <a-button type="dashed" block @click="specRows.push({ k: '', v: '' })">＋ 添加规格参数</a-button>
          </a-form-item>

          <!-- 主图 -->
          <a-form-item label="商品主图" name="coverUrl" :rules="[{ required: true, message: '请上传商品主图' }]">
            <a-upload v-model:file-list="imgFiles" list-type="picture-card" accept="image/jpeg,image/png,image/webp"
                      :max-count="1" :before-upload="beforeImg" :custom-request="doUploadImg" @remove="onRemoveImg">
              <div v-if="imgFiles.length === 0">
                <plus-outlined />
                <div class="up-hint">更换主图</div>
              </div>
            </a-upload>
            <div class="hint">JPG/PNG/WebP，≤5MB，建议 ≥100×100</div>
          </a-form-item>

          <a-form-item label="商品描述（选填）" name="detail">
            <a-textarea v-model:value="form.detail" :rows="4" maxlength="2000" show-count
                        placeholder="材质、用途、交付说明等" />
          </a-form-item>
        </div>

        <div class="actions">
          <a-button size="large" @click="$router.push('/merchant/center/products')">取消</a-button>
          <a-button size="large" type="primary" :loading="submitting" @click="submit">保存修改</a-button>
        </div>
      </a-form>
    </a-spin>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message, Modal } from 'ant-design-vue'
import { PlusOutlined } from '@ant-design/icons-vue'
import { homeApi, productApi } from '../../api'

const route = useRoute()
const router = useRouter()
const prodId = route.params.id

const formRef = ref(null)
const loading = ref(true)
const submitting = ref(false)
const imgFiles = ref([])
const industries = ref([])
const categories = ref([])
const original = ref({})

const form = reactive({
  title: '', indId: null, catId: null, price: null, stock: null,
  brand: '', spec: '', coverUrl: '', detail: '',
  reviewStatus: null, rejectReason: ''
})
const specRows = ref([{ k: '', v: '' }])

const rules = {
  title: [
    { required: true, message: '请输入商品名称', trigger: 'blur' },
    { min: 2, max: 100, message: '商品名称需 2-100 个字符', trigger: 'blur' }
  ],
  indId: [{ required: true, message: '请选择行业', trigger: 'change' }],
  catId: [{ required: true, message: '请选择商品分类', trigger: 'change' }],
  price: [{ required: true, message: '请输入价格', trigger: 'blur' }],
  stock: [{ required: true, message: '请输入库存', trigger: 'blur' }]
}

/** 解析 "k:v；k2:v2" 规格串为可编辑行 */
function parseSpec(spec) {
  const rows = String(spec || '').split('；').map(s => {
    const i = s.indexOf(':')
    return i > 0 ? { k: s.slice(0, i), v: s.slice(i + 1) } : null
  }).filter(Boolean)
  return rows.length ? rows : [{ k: '', v: '' }]
}
function buildSpec() {
  return specRows.value
    .filter(r => r.k.trim() && r.v.trim())
    .map(r => `${r.k.trim()}:${r.v.trim()}`)
    .join('；')
}

async function loadIndustries(preselect) {
  try {
    industries.value = (await homeApi.industries()) || []
    if (preselect) await loadCategories(preselect)
  } catch (e) { /* 拦截器已提示 */ }
}
async function loadCategories(indId) {
  try { categories.value = (await homeApi.categories(indId)) || [] } catch (e) { categories.value = [] }
}
async function onIndustryChange(indId) {
  form.catId = null
  categories.value = []
  if (indId) loadCategories(indId)
}

// ===== 主图上传 =====
function beforeImg(file) {
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) { message.error('仅支持 JPG/PNG/WebP 图片'); return false }
  if (file.size > 5 * 1024 * 1024) { message.error('图片不能超过 5MB'); return false }
  return true
}
function doUploadImg({ file, onSuccess, onError }) {
  const fd = new FormData()
  fd.append('file', file)
  productApi.uploadImage(fd)
    .then(data => { form.coverUrl = data.url; onSuccess(data) })
    .catch(e => onError(e))
}
function onRemoveImg() { form.coverUrl = '' }

async function load() {
  loading.value = true
  try {
    const p = await productApi.myDetail(prodId)
    original.value = p
    Object.assign(form, {
      title: p.title, indId: p.indId, catId: p.catId,
      price: Number(p.price), stock: p.stock,
      brand: p.brand || '', spec: p.spec || '', coverUrl: p.coverUrl || '',
      detail: p.detail || '',
      reviewStatus: p.reviewStatus, rejectReason: p.rejectReason
    })
    specRows.value = parseSpec(p.spec)
    if (form.coverUrl) {
      imgFiles.value = [{ uid: '-1', name: 'cover', status: 'done', url: form.coverUrl }]
    }
    await loadIndustries(p.indId)
  } catch (e) {
    message.error('商品加载失败，请返回列表重试')
  }
  loading.value = false
}

/** 内容字段是否被修改（库存调整除外）——用于在售商品保存前的下架重审确认 */
const contentDirty = computed(() => {
  const o = original.value
  if (!o || !o.prodId) return false
  return form.title.trim() !== (o.title || '')
    || Number(form.price) !== Number(o.price)
    || (form.brand || '').trim() !== (o.brand || '')
    || buildSpec() !== (o.spec || '')
    || form.indId !== o.indId
    || form.catId !== o.catId
    || form.coverUrl !== (o.coverUrl || '')
    || (form.detail || '').trim() !== (o.detail || '')
})

async function submit() {
  if (!form.coverUrl) { message.error('请上传商品主图'); return }
  try { await formRef.value?.validateFields?.() } catch (e) { return }
  // 在售商品修改内容 → 重新送审期间会临时下架，保存前明确告知（避免"改个标题商品突然消失"）
  const o = original.value
  if (contentDirty.value && o.reviewStatus === 1 && o.status === 1) {
    const confirmed = await new Promise(resolve => {
      Modal.confirm({
        title: '修改内容将重新提交平台审核',
        content: '审核期间商品会临时下架，买家暂时无法购买；平台复审通过后自动恢复在售。确定保存吗？',
        okText: '保存并送审', cancelText: '再改改',
        onOk: () => resolve(true),
        onCancel: () => resolve(false)
      })
    })
    if (!confirmed) return
  }
  submitting.value = true
  try {
    await productApi.myUpdate(prodId, {
      title: form.title.trim(),
      indId: form.indId, catId: form.catId,
      price: form.price, stock: form.stock,
      brand: (form.brand || '').trim(),
      spec: buildSpec(),
      coverUrl: form.coverUrl,
      detail: (form.detail || '').trim()
    })
    message.success('保存成功', 2)
    router.push('/merchant/center/products')
  } catch (e) { /* 拦截器已提示 */ }
  submitting.value = false
}

onMounted(load)
</script>

<style scoped>
.edit-page { display: flex; flex-direction: column; gap: 14px; }
.crumb-row { display: flex; justify-content: space-between; align-items: center; gap: 10px; padding-top: 4px; }
.crumb { font-size: 12.5px; }
.state-banner { border-radius: 14px; }
.edit-form { background: var(--mc-card, #fff); border: 1px solid var(--mc-border, #e8e6e1); border-radius: 16px; padding: 22px 24px 18px; box-shadow: 0 2px 14px rgba(20, 24, 34, .04); }
.section-head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 16px; padding-bottom: 12px; border-bottom: 1px solid var(--mc-border-soft, #f0eee9); }
.section-head b { font-size: 15px; color: var(--mc-ink, #1d1d1f); letter-spacing: -.01em; }
.section-head span { font-size: 12px; color: var(--mc-muted2, #86868b); }
.spec-row { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; }
.hint { font-size: 12px; color: var(--mc-muted2, #86868b); margin-top: 4px; }
.up-hint { font-size: 12px; margin-top: 4px; }
.actions { display: flex; justify-content: flex-end; gap: 12px; margin-top: 6px; }
@media (max-width: 700px) {
  .edit-form { padding: 16px 14px; }
  .actions { flex-direction: column-reverse; }
  .actions .ant-btn { width: 100%; }
}
</style>

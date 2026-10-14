<template>
  <a-modal :open="open" title="发布商品" :footer="null" width="640px"
           @cancel="$emit('update:open', false)" destroy-on-close>
    <a-alert type="info" show-icon class="tip"
             message="发布后商品将进入平台审核（待审核），审核通过后自动上架并可售。" />
    <a-form ref="formRef" :model="form" :rules="rules" layout="vertical" @finish="submit">
      <!-- ===== 基础信息 ===== -->
      <a-form-item label="商品名称" name="title">
        <a-input v-model:value="form.title" placeholder="2-100 个字符" maxlength="100" show-count />
      </a-form-item>
      <a-row :gutter="12">
        <a-col :span="12">
          <a-form-item label="所属行业" name="indId">
            <a-select v-model:value="form.indId" placeholder="选择行业" @change="onIndustryChange">
              <a-select-option v-for="i in industries" :key="i.indId" :value="i.indId">{{ i.name }}</a-select-option>
            </a-select>
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="商品分类" name="catId">
            <a-select v-model:value="form.catId" placeholder="先选行业" :disabled="!form.indId">
              <a-select-option v-for="c in categories" :key="c.catId" :value="c.catId">{{ c.name }}</a-select-option>
            </a-select>
          </a-form-item>
        </a-col>
      </a-row>
      <a-row :gutter="12">
        <a-col :span="12">
          <a-form-item label="价格（元）" name="price">
            <a-input-number v-model:value="form.price" :min="0.01" :max="9999999" :precision="2"
                            style="width:100%" placeholder="0.01 - 9999999" />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="库存" name="stock">
            <a-input-number v-model:value="form.stock" :min="1" :max="999999" :precision="0"
                            style="width:100%" placeholder="1 - 999999" />
          </a-form-item>
        </a-col>
      </a-row>
      <a-form-item label="品牌（选填）" name="brand">
        <a-input v-model:value="form.brand" placeholder="如：华晨" maxlength="64" />
      </a-form-item>

      <!-- ===== 规格参数（动态 KV，可扩展属性） ===== -->
      <a-form-item label="规格参数（选填）">
        <div v-for="(row, idx) in specRows" :key="idx" class="spec-row">
          <a-input v-model:value="row.k" placeholder="参数名（如：型号）" maxlength="20" style="width:38%" />
          <a-input v-model:value="row.v" placeholder="参数值（如：DN25）" maxlength="40" style="flex:1" />
          <a-button type="text" danger @click="specRows.splice(idx, 1)">删除</a-button>
        </div>
        <a-button type="dashed" block @click="specRows.push({ k: '', v: '' })">＋ 添加规格参数</a-button>
      </a-form-item>

      <!-- ===== 商品主图 ===== -->
      <a-form-item label="商品主图" name="coverUrl" :rules="[{ required: true, message: '请上传商品主图' }]">
        <a-upload v-model:file-list="imgFiles" list-type="picture-card" accept="image/jpeg,image/png,image/webp"
                  :max-count="1" :before-upload="beforeImg" :custom-request="doUploadImg" @remove="onRemoveImg">
          <div v-if="imgFiles.length === 0">
            <plus-outlined />
            <div class="up-hint">上传主图</div>
          </div>
        </a-upload>
        <div class="hint">JPG/PNG/WebP，≤5MB，建议 ≥100×100</div>
      </a-form-item>

      <!-- ===== 商品详情 ===== -->
      <a-form-item label="商品描述（选填）" name="detail">
        <a-textarea v-model:value="form.detail" :rows="4" maxlength="2000" show-count
                    placeholder="材质、用途、交付说明等" />
      </a-form-item>

      <!-- ===== 操作区：草稿 / 提交 ===== -->
      <div class="actions">
        <a-button @click="saveDraft" :disabled="submitting">存草稿</a-button>
        <a-button v-if="hasDraft && !draftLoaded" type="link" @click="restoreDraft">恢复上次草稿</a-button>
        <a-button type="primary" html-type="submit" :loading="submitting">提交审核</a-button>
      </div>
    </a-form>
  </a-modal>
</template>

<script setup>
import { reactive, ref, watch, computed } from 'vue'
import { message } from 'ant-design-vue'
import { PlusOutlined } from '@ant-design/icons-vue'
import { productApi, homeApi } from '../../api'

/**
 * 商户商品发布组件（Modal 形态，父组件 v-model:open 控制）。
 * 发布 → 待审核（后端自动置 status=0/review_status=0）。
 * 草稿：localStorage 暂存，支持保存/恢复/继续编辑。
 * 可扩展性：新增商品属性只需在 form + rules + 模板对应区块各加一项。
 */
const props = defineProps({ open: Boolean })
const emit = defineEmits(['update:open', 'published'])

const DRAFT_KEY = 'll_product_draft'

const formRef = ref(null)
const submitting = ref(false)
const imgFiles = ref([])

const form = reactive({
  title: '', indId: null, catId: null, price: null, stock: null,
  brand: '', detail: ''
})
const specRows = ref([{ k: '', v: '' }])   // 规格参数动态行 → 拼接为 spec

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

// ===== 行业/分类级联 =====
const industries = ref([])
const categories = ref([])

async function loadIndustries() {
  try { industries.value = (await homeApi.industries()) || [] } catch (e) { /* 拦截器已提示 */ }
}
async function onIndustryChange(indId) {
  form.catId = null
  categories.value = []
  if (!indId) return
  try { categories.value = (await homeApi.categories(indId)) || [] } catch (e) { /* 拦截器已提示 */ }
}

// ===== 主图上传（选择即上传，返回 URL） =====
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

// ===== 草稿：本地暂存 / 恢复 / 清空 =====
const hasDraft = ref(false)
const draftLoaded = ref(false)

function draftPayload() {
  return {
    form: { ...form }, specRows: specRows.value.filter(r => r.k || r.v),
    imgFiles: imgFiles.value.length ? [{ uid: '-1', name: 'cover', status: 'done', url: form.coverUrl }] : []
  }
}
function saveDraft() {
  try {
    localStorage.setItem(DRAFT_KEY, JSON.stringify(draftPayload()))
    hasDraft.value = true
    draftLoaded.value = true
    message.success('草稿已保存，可随时回来继续编辑', 3)
  } catch (e) { message.error('草稿保存失败（本地存储不可用）') }
}
function restoreDraft() {
  try {
    const d = JSON.parse(localStorage.getItem(DRAFT_KEY) || 'null')
    if (!d) return
    Object.assign(form, d.form || {})
    specRows.value = (d.specRows && d.specRows.length) ? d.specRows : [{ k: '', v: '' }]
    imgFiles.value = d.imgFiles || []
    draftLoaded.value = true
    message.success('已恢复上次草稿')
  } catch (e) { message.error('草稿解析失败') }
}
function clearDraft() { localStorage.removeItem(DRAFT_KEY); hasDraft.value = false; draftLoaded.value = false }

// 打开时：加载行业 + 检测草稿
watch(() => props.open, (v) => {
  if (v) {
    loadIndustries()
    hasDraft.value = !!localStorage.getItem(DRAFT_KEY)
    if (hasDraft.value && !draftLoaded.value) {
      // 有草稿时静默提示，由用户选择恢复
      message.info('检测到未完成的草稿，可点击「恢复上次草稿」继续编辑', 4)
    }
    if (form.indId && !categories.value.length) onIndustryChange(form.indId)
  }
})

// ===== 提交 =====
function buildSpec() {
  return specRows.value
    .filter(r => r.k.trim() && r.v.trim())
    .map(r => `${r.k.trim()}:${r.v.trim()}`)
    .join('；')
}

async function submit() {
  if (!form.coverUrl) { message.error('请上传商品主图'); return }
  submitting.value = true
  try {
    await productApi.publish({
      title: form.title.trim(),
      indId: form.indId, catId: form.catId,
      price: form.price, stock: form.stock,
      brand: (form.brand || '').trim(),
      spec: buildSpec(),
      coverUrl: form.coverUrl,
      detail: (form.detail || '').trim()
    })
    message.success('已提交，商品进入「待审核」状态', 4)
    clearDraft()
    resetForm()
    emit('published')
    emit('update:open', false)
  } catch (e) {
    /* 拦截器已提示 */
  } finally { submitting.value = false }
}

function resetForm() {
  Object.assign(form, { title: '', indId: null, catId: null, price: null, stock: null, brand: '', detail: '', coverUrl: '' })
  specRows.value = [{ k: '', v: '' }]
  imgFiles.value = []
  categories.value = []
  draftLoaded.value = false
  formRef.value?.clearValidate?.()
}
</script>

<style scoped>
.tip { margin-bottom: 16px; }
.hint { font-size: 12px; color: var(--ll-gray2, #4b5563); margin-top: 4px; }
.up-hint { font-size: 12px; margin-top: 4px; }
.spec-row { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; }
.actions { display: flex; justify-content: flex-end; gap: 12px; margin-top: 8px; }
</style>

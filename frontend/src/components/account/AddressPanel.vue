<template>
  <div class="addr-panel">
    <div class="toolbar">
      <span class="tip">管理你的收货地址，最多可保存多条，并可将任意一条设为默认。</span>
      <a-button type="primary" @click="openAdd">＋ 新增地址</a-button>
    </div>

    <a-spin :spinning="loading">
      <a-empty v-if="!loading && list.length === 0" description="还没有收货地址" class="empty" />
      <div v-else class="grid">
        <div v-for="a in list" :key="a.id" class="card" :class="{ default: a.isDefault === 1 }">
          <div class="card-head">
            <span class="receiver">{{ a.receiver }}</span>
            <span class="phone">{{ a.phone }}</span>
            <a-tag v-if="a.isDefault === 1" color="red" class="dtag">默认</a-tag>
          </div>
          <div class="addr-line">{{ a.region ? a.region + ' ' : '' }}{{ a.detail }}</div>
          <div class="card-actions">
            <a-button type="link" size="small" @click="openEdit(a)">编辑</a-button>
            <a-button v-if="a.isDefault !== 1" type="link" size="small" @click="setDefault(a)">设为默认</a-button>
            <a-popconfirm title="确认删除该地址？" ok-text="删除" cancel-text="取消" @confirm="remove(a)">
              <a-button type="link" size="small" danger>删除</a-button>
            </a-popconfirm>
          </div>
        </div>
      </div>
    </a-spin>

    <a-modal v-model:open="modalOpen" :title="editingId ? '编辑地址' : '新增地址'" :confirm-loading="saving"
             @ok="submit" @cancel="closeModal" ok-text="保存" cancel-text="取消">
      <a-form ref="formRef" :model="form" :rules="rules" layout="vertical" class="form">
        <a-form-item label="收货人" name="receiver">
          <a-input v-model:value="form.receiver" placeholder="请输入收货人姓名" maxlength="32" />
        </a-form-item>
        <a-form-item label="联系电话" name="phone">
          <a-input v-model:value="form.phone" placeholder="11 位手机号" maxlength="11" inputmode="numeric" />
        </a-form-item>
        <a-form-item label="省市区" name="region">
          <a-input v-model:value="form.region" placeholder="如：广东省 深圳市 南山区（选填）" maxlength="64" />
        </a-form-item>
        <a-form-item label="详细地址" name="detail">
          <a-textarea v-model:value="form.detail" :rows="2" placeholder="街道、门牌号等" maxlength="255" />
        </a-form-item>
        <a-form-item name="isDefault">
          <a-checkbox v-model:checked="form.isDefault">设为默认收货地址</a-checkbox>
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { userApi } from '../../api'

const loading = ref(false)
const saving = ref(false)
const list = ref([])

const modalOpen = ref(false)
const editingId = ref(null)
const formRef = ref(null)
const form = reactive({ receiver: '', phone: '', region: '', detail: '', isDefault: false })

const rules = {
  receiver: [{ required: true, message: '请输入收货人', trigger: 'blur' }],
  phone: [
    { required: true, message: '请输入联系电话', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
  ],
  detail: [{ required: true, message: '请输入详细地址', trigger: 'blur' }]
}

async function load() {
  loading.value = true
  try {
    list.value = await userApi.addressList() || []
  } catch (e) {
    /* 拦截器已提示 */
  } finally {
    loading.value = false
  }
}

function resetForm(src) {
  form.receiver = src?.receiver || ''
  form.phone = src?.phone || ''
  form.region = src?.region || ''
  form.detail = src?.detail || ''
  form.isDefault = src ? src.isDefault === 1 : list.value.length === 0
}

function openAdd() {
  editingId.value = null
  resetForm(null)
  modalOpen.value = true
}

function openEdit(a) {
  editingId.value = a.id
  resetForm(a)
  modalOpen.value = true
}

function closeModal() {
  modalOpen.value = false
  formRef.value?.clearValidate?.()
}

async function submit() {
  try {
    await formRef.value.validate()
  } catch (e) {
    return // 校验未通过
  }
  const payload = {
    receiver: form.receiver.trim(),
    phone: form.phone.trim(),
    region: form.region.trim(),
    detail: form.detail.trim(),
    isDefault: form.isDefault ? 1 : 0
  }
  saving.value = true
  try {
    if (editingId.value) {
      await userApi.addressUpdate(editingId.value, payload)
      message.success('地址已更新')
    } else {
      await userApi.addressAdd(payload)
      message.success('地址已添加')
    }
    modalOpen.value = false
    await load()
  } catch (e) {
    /* 拦截器已提示（如后端唯一性/格式校验） */
  } finally {
    saving.value = false
  }
}

async function remove(a) {
  try {
    await userApi.addressDelete(a.id)
    message.success('已删除')
    await load()
  } catch (e) { /* ignore */ }
}

async function setDefault(a) {
  try {
    await userApi.addressSetDefault(a.id)
    await load()
  } catch (e) { /* ignore */ }
}

onMounted(load)
</script>

<style scoped>
.addr-panel { display: flex; flex-direction: column; gap: 14px; }
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.tip { font-size: 13px; color: var(--ll-gray2); }
.empty { padding: 32px 0; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
.card { border: 1px solid var(--ll-border, #e5e7eb); border-radius: 12px; padding: 14px 16px; background: var(--ll-card, #fff); }
.card.default { border-color: var(--ll-price, #e4393c); box-shadow: 0 0 0 1px var(--ll-price, #e4393c) inset; }
.card-head { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.receiver { font-weight: 700; color: var(--ll-ink); }
.phone { color: var(--ll-muted); font-size: 14px; }
.dtag { margin-left: auto; }
.addr-line { color: var(--ll-ink); font-size: 14px; line-height: 1.5; min-height: 42px; }
.card-actions { display: flex; gap: 4px; margin-top: 6px; }
.form { padding-top: 4px; }
</style>

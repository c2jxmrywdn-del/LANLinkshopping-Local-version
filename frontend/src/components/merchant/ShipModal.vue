<template>
  <a-modal :open="open" title="订单发货" width="560px" :confirm-loading="submitting"
           ok-text="确认发货" cancel-text="取消" @ok="submit" @cancel="$emit('update:open', false)"
           destroy-on-close>
    <a-alert type="info" show-icon class="tip"
             :message="`订单 ${orderNo} 已支付，填写物流信息后买家可在订单页跟踪配送。`" />
    <a-form layout="vertical">
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
      <a-form-item label="第三方快递单号（选填）">
        <a-input v-model:value="form.logisticsNo" placeholder="如 SF1234567890" maxlength="64" />
      </a-form-item>
      <a-row :gutter="12">
        <a-col :span="12">
          <a-form-item label="重量（kg，选填）">
            <a-input-number v-model:value="form.weight" :min="0.01" :max="99999" :precision="2" style="width:100%" />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="体积（m³，选填）">
            <a-input-number v-model:value="form.volume" :min="0.001" :max="9999" :precision="3" style="width:100%" />
          </a-form-item>
        </a-col>
      </a-row>
      <a-form-item>
        <a-checkbox v-model:checked="form.remote">偏远地区（加收附加费，按模板配置）</a-checkbox>
      </a-form-item>

      <div class="sender-title">寄件人信息</div>
      <a-row :gutter="12">
        <a-col :span="12">
          <a-form-item label="寄件人">
            <a-input v-model:value="form.senderName" maxlength="32" placeholder="店铺 / 负责人姓名" />
          </a-form-item>
        </a-col>
        <a-col :span="12">
          <a-form-item label="联系电话">
            <a-input v-model:value="form.senderPhone" maxlength="20" placeholder="寄件联系电话" />
          </a-form-item>
        </a-col>
      </a-row>
      <a-form-item label="寄件地址">
        <a-textarea v-model:value="form.senderAddress" :rows="2" maxlength="200" placeholder="发货地址" />
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { logisticsApi } from '../../api'

const props = defineProps({
  open: Boolean,
  orderNo: { type: String, default: '' },
  /** 预填寄件人信息（店铺设置中的联系方式） */
  sender: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['update:open', 'shipped'])

const loadingRef = ref(false)
const submitting = ref(false)
const providers = ref([])
const templates = ref([])

const form = reactive({
  providerId: null, templateId: null, logisticsNo: '',
  weight: null, volume: null, remote: false,
  senderName: '', senderPhone: '', senderAddress: ''
})

watch(() => props.open, async (v) => {
  if (!v) return
  Object.assign(form, {
    providerId: null, templateId: null, logisticsNo: '',
    weight: null, volume: null, remote: false,
    senderName: props.sender?.name || '',
    senderPhone: props.sender?.phone || '',
    senderAddress: props.sender?.address || ''
  })
  loadingRef.value = true
  try {
    providers.value = (await logisticsApi.providers()) || []
    templates.value = (await logisticsApi.templates()) || []
  } catch (e) { /* 拦截器已提示 */ }
  loadingRef.value = false
})

async function submit() {
  if (!form.providerId) { message.error('请选择物流服务商'); return }
  if (!form.senderName || !form.senderPhone || !form.senderAddress) {
    message.error('请补全寄件人信息')
    return
  }
  submitting.value = true
  try {
    const wb = await logisticsApi.ship({
      orderNo: props.orderNo,
      providerId: form.providerId,
      templateId: form.templateId ?? null,
      logisticsNo: form.logisticsNo || null,
      weight: form.weight, volume: form.volume, remote: form.remote,
      quantity: null,
      senderName: form.senderName, senderPhone: form.senderPhone, senderAddress: form.senderAddress
    })
    message.success(`发货成功，运单号 ${wb.waybillNo}`)
    emit('shipped', wb)
    emit('update:open', false)
  } catch (e) { /* 拦截器已提示 */ }
  submitting.value = false
}
</script>

<style scoped>
.tip { margin-bottom: 16px; }
.sender-title { font-size: 13px; font-weight: 600; color: var(--mc-ink, #1d1d1f); margin: 4px 0 10px; padding-top: 12px; border-top: 1px solid var(--mc-border-soft, #f0eee9); }
</style>

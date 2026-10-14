<template>
  <a-modal :open="open" :title="title" :footer="null" @cancel="$emit('close')" destroy-on-close>
    <a-form layout="vertical" @submit.prevent="submit">
      <a-form-item :label="targetLabel" :validate-status="err.target ? 'error' : (touched.target && !err.target ? 'success' : '')" :help="err.target">
        <a-input v-model:value="target" :placeholder="targetPlaceholder" size="large" @blur="onBlur" />
      </a-form-item>
      <a-form-item :label="t('acct.profile.code')" :validate-status="err.code ? 'error' : ''" :help="err.code">
        <div class="code-row">
          <a-input v-model:value="code" :placeholder="t('acct.profile.codePlaceholder')" maxlength="6" size="large" @blur="touched.code = true; validateCode()" />
          <a-button size="large" class="send-btn" :disabled="cd > 0 || sending" :loading="sending" @click="send">
            {{ cd > 0 ? t('acct.profile.resendIn', { s: cd }) : t('acct.profile.sendCode') }}
          </a-button>
        </div>
      </a-form-item>
      <div v-if="devCode" class="dev-code">{{ t('acct.profile.devCode', { code: devCode }) }}</div>
      <a-button type="primary" html-type="submit" block size="large" class="touch" :loading="submitting" :disabled="submitting">
        {{ t('acct.profile.submit') }}
      </a-button>
    </a-form>
  </a-modal>
</template>

<script setup>
import { ref, reactive, computed, watch } from 'vue'
import { message } from 'ant-design-vue'
import { userApi } from '../../api'
import { t } from '../../i18n'
import { REG_PHONE, REG_EMAIL } from '../../utils/validators'

const props = defineProps({
  open: Boolean,
  type: { type: String, default: 'phone' } // 'phone' | 'email'
})
const emit = defineEmits(['close', 'done'])

const target = ref('')
const code = ref('')
const devCode = ref('')
const err = reactive({ target: '', code: '' })
const touched = reactive({ target: false, code: false })
const sending = ref(false)
const submitting = ref(false)
const cd = ref(0)
let timer = null

const isPhone = computed(() => props.type === 'phone')
const title = computed(() => t(isPhone.value ? 'acct.profile.changePhone' : 'acct.profile.changeEmail'))
const targetLabel = computed(() => t(isPhone.value ? 'acct.profile.newPhone' : 'acct.profile.newEmail'))
const targetPlaceholder = computed(() => (isPhone.value ? '13800000000' : 'name@example.com'))

// 打开时重置
watch(() => props.open, (v) => {
  if (v) {
    target.value = ''; code.value = ''; devCode.value = ''
    err.target = ''; err.code = ''
    touched.target = false; touched.code = false
    cd.value = 0
    if (timer) { clearInterval(timer); timer = null }
  }
})

// 实时校验（离开输入框触发）
function validateTarget() {
  const ok = isPhone.value ? REG_PHONE.test(target.value) : REG_EMAIL.test(target.value)
  err.target = ok ? '' : t(isPhone.value ? 'acct.profile.invalidPhone' : 'acct.profile.invalidEmail')
  return ok
}
function validateCode() {
  const ok = /^\d{6}$/.test(code.value)
  err.code = ok ? '' : t('acct.profile.codeRequired')
  return ok
}
function onBlur() { touched.target = true; validateTarget() }

async function send() {
  touched.target = true
  if (!validateTarget()) return
  sending.value = true
  try {
    const data = isPhone.value ? await userApi.sendPhoneCode(target.value) : await userApi.sendEmailCode(target.value)
    // SMTP 已配置：验证码真实发往邮箱，接口不回显；仅演示回退时展示 devCode
    devCode.value = (data && data.devCode) || ''
    if (!isPhone.value && !devCode.value) {
      message.success(t('acct.profile.codeSent'), 4)
    }
    cd.value = 60
    timer = setInterval(() => { cd.value--; if (cd.value <= 0) { clearInterval(timer); timer = null } }, 1000)
  } catch (e) {
    // 失败提示已由请求拦截器统一弹出，这里确保不启动倒计时
    devCode.value = ''
  } finally { sending.value = false }
}

async function submit() {
  touched.target = true; touched.code = true
  if (!validateTarget() || !validateCode()) return
  submitting.value = true
  try {
    const data = isPhone.value ? await userApi.bindPhone(target.value, code.value) : await userApi.bindEmail(target.value, code.value)
    message.success(t('acct.profile.bindSuccess'), 3)
    emit('done', data)
    emit('close')
  } finally { submitting.value = false }
}
</script>

<style scoped>
.code-row { display: flex; gap: 8px; }
.code-row :deep(.ant-input) { flex: 1; }
.send-btn { min-width: 118px; min-height: 40px; }
.dev-code { margin: -8px 0 12px; font-size: 12px; color: var(--ll-gray2); }
.touch { min-height: 44px; }
</style>

<template>
  <a-modal :open="open" :title="t('acct.settings.twofaSetupTitle')" :footer="null" @cancel="$emit('close')" destroy-on-close>
    <div v-if="!setup" class="start">
      <p class="desc">{{ t('acct.settings.twofaDesc') }}</p>
      <a-button type="primary" block class="touch" :loading="loading" @click="start">{{ t('acct.settings.twofaStart') }}</a-button>
    </div>
    <template v-else>
      <p class="desc">{{ t('acct.settings.twofaScan') }}</p>
      <div class="qr"><canvas ref="qrCanvas" aria-label="TOTP QR code"></canvas></div>
      <div class="secret-row">
        <span class="secret-label">{{ t('acct.settings.twofaSecret') }}</span>
        <code class="secret">{{ setup.secret }}</code>
        <a-button size="small" class="copy-btn" @click="copy">{{ copied ? t('acct.settings.copied') : t('acct.settings.copy') }}</a-button>
      </div>
      <a-form layout="vertical" @submit.prevent="enable">
        <a-form-item :label="t('acct.settings.twofaCodeLabel')" :validate-status="codeErr ? 'error' : ''" :help="codeErr">
          <a-input v-model:value="code" :placeholder="t('acct.profile.codePlaceholder')" maxlength="6" size="large" @blur="validateCode" />
        </a-form-item>
        <a-button type="primary" html-type="submit" block size="large" class="touch" :loading="enabling" :disabled="enabling">
          {{ t('acct.settings.twofaVerifyEnable') }}
        </a-button>
      </a-form>
    </template>
  </a-modal>
</template>

<script setup>
import { ref, nextTick } from 'vue'
import { message } from 'ant-design-vue'
import QRCode from 'qrcode'
import { userApi } from '../../api'
import { t } from '../../i18n'

defineProps({ open: Boolean })
const emit = defineEmits(['close', 'enabled'])

const setup = ref(null) // { secret, otpauthUrl }
const loading = ref(false)
const enabling = ref(false)
const code = ref('')
const codeErr = ref('')
const copied = ref(false)
const qrCanvas = ref(null)

async function start() {
  loading.value = true
  try {
    setup.value = await userApi.totpSetup()
    code.value = ''; codeErr.value = ''
    await nextTick()
    // 生成 TOTP 二维码
    if (qrCanvas.value) await QRCode.toCanvas(qrCanvas.value, setup.value.otpauthUrl, { width: 200, margin: 1 })
  } finally { loading.value = false }
}

async function copy() {
  try {
    await navigator.clipboard.writeText(setup.value.secret)
    copied.value = true
    setTimeout(() => { copied.value = false }, 1500)
  } catch (e) { /* 剪贴板不可用时静默 */ }
}

function validateCode() {
  const ok = /^\d{6}$/.test(code.value)
  codeErr.value = ok ? '' : t('acct.settings.codeRequired')
  return ok
}

async function enable() {
  if (!validateCode()) return
  enabling.value = true
  try {
    await userApi.totpEnable(code.value)
    message.success(t('acct.settings.twofaEnabled'), 3)
    emit('enabled')
    emit('close')
  } finally { enabling.value = false }
}
</script>

<style scoped>
.start { padding: 8px 0 4px; }
.desc { color: var(--ll-muted); margin-top: 0; }
.qr { display: flex; justify-content: center; margin: 12px 0; }
.secret-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 16px; }
.secret-label { color: var(--ll-muted); font-size: 13px; }
.secret { background: var(--ll-thumb-bg); padding: 4px 8px; border-radius: 6px; font-size: 13px; word-break: break-all; }
.copy-btn { min-height: 32px; }
.touch { min-height: 44px; }
</style>

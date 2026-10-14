<template>
  <div class="panel">
    <!-- 账户安全 -->
    <a-card :bordered="false" class="sec" :title="t('acct.settings.security')">
      <div class="row">
        <div class="row-main">
          <div class="row-title">{{ t('acct.settings.changePwd') }}</div>
        </div>
        <a-button class="touch-sm" @click="pwdOpen = true">{{ t('acct.settings.changePwd') }}</a-button>
      </div>
      <div class="row">
        <div class="row-main">
          <div class="row-title">{{ t('acct.settings.twofa') }}</div>
          <div class="row-desc">{{ security.totpEnabled ? t('acct.settings.twofaOn') : t('acct.settings.twofaOff') }}</div>
          <div v-if="security.totpEnabled && security.firstVerifyTime" class="row-desc">
            {{ t('acct.settings.firstTime') }}：{{ security.firstVerifyTime }}
          </div>
        </div>
        <a-button v-if="!security.totpEnabled" class="touch-sm" @click="totpOpen = true">{{ t('acct.settings.enable2fa') }}</a-button>
        <a-button v-else danger class="touch-sm" @click="disableOpen = true">{{ t('acct.settings.disable2fa') }}</a-button>
      </div>
      <a-divider style="margin: 4px 0 12px" />
      <div class="desc muted">{{ t('acct.settings.twofaDesc') }}</div>
    </a-card>

    <!-- 通知偏好 -->
    <a-card :bordered="false" class="sec" :title="t('acct.settings.notify')">
      <div class="switch-row">
        <span>{{ t('acct.settings.notifyEmail') }}</span>
        <a-switch :checked="n.email" @change="(v) => patchNotify({ email: v })" />
      </div>
      <div class="switch-row">
        <span>{{ t('acct.settings.notifyPush') }}</span>
        <a-switch :checked="n.push" @change="(v) => patchNotify({ push: v })" />
      </div>
      <div class="switch-row">
        <span>{{ t('acct.settings.notifySms') }}</span>
        <a-switch :checked="n.sms" @change="(v) => patchNotify({ sms: v })" />
      </div>
      <a-divider style="margin: 4px 0 12px">{{ t('acct.settings.notifyGroup') }}</a-divider>
      <div class="switch-row">
        <span>{{ t('acct.settings.notifyOrder') }}</span>
        <a-switch :checked="!!n.groups.order" @change="(v) => patchNotify({ groups: { ...n.groups, order: v } })" />
      </div>
      <div class="switch-row">
        <span>{{ t('acct.settings.notifyPromotion') }}</span>
        <a-switch :checked="!!n.groups.promotion" @change="(v) => patchNotify({ groups: { ...n.groups, promotion: v } })" />
      </div>
      <div class="switch-row">
        <span>{{ t('acct.settings.notifySystem') }}</span>
        <a-switch :checked="!!n.groups.system" @change="(v) => patchNotify({ groups: { ...n.groups, system: v } })" />
      </div>
      <a-button class="touch-sm notify-all" @click="toggleAll">{{ t('acct.settings.notifyAll') }}</a-button>
    </a-card>

    <!-- 界面个性化 -->
    <a-card :bordered="false" class="sec" :title="t('acct.settings.appearance')">
      <div class="field">
        <div class="field-label">{{ t('acct.settings.theme') }}</div>
        <a-segmented :value="ap.theme" :options="themeOptions" class="full" @change="patchAppearance({ theme: $event })" />
      </div>
      <div class="field">
        <div class="field-label">{{ t('acct.settings.language') }}</div>
        <a-select :value="ap.language" class="full" @change="patchAppearance({ language: $event })">
          <a-select-option value="zh-CN">简体中文</a-select-option>
          <a-select-option value="en-US">English</a-select-option>
        </a-select>
      </div>
      <div class="field">
        <div class="field-label">{{ t('acct.settings.fontSize') }}</div>
        <a-segmented :value="ap.fontSize" :options="fontOptions" class="full" @change="patchAppearance({ fontSize: $event })" />
      </div>
    </a-card>

    <!-- 隐私权限 -->
    <a-card :bordered="false" class="sec" :title="t('acct.settings.privacy')">
      <div class="switch-row">
        <div class="row-main">
          <div class="row-title">{{ t('acct.settings.recommend') }}</div>
          <div class="row-desc">{{ t('acct.settings.recommendTip') }}</div>
        </div>
        <a-switch :checked="pr.recommend" @change="(v) => patchPrivacy({ recommend: v })" />
      </div>
      <div class="switch-row">
        <div class="row-main">
          <div class="row-title">{{ t('acct.settings.ads') }}</div>
          <div class="row-desc">{{ t('acct.settings.adsTip') }}</div>
        </div>
        <a-switch :checked="pr.ads" @change="(v) => patchPrivacy({ ads: v })" />
      </div>

      <a-divider style="margin: 4px 0 12px">{{ t('acct.settings.thirdAuth') }}</a-divider>
      <div v-if="thirdAuths.length === 0" class="empty">{{ t('acct.settings.thirdEmpty') }}</div>
      <div v-for="ta in thirdAuths" :key="ta.id" class="third-row">
        <span class="third-icon">{{ (ta.appName || 'A').slice(0, 1) }}</span>
        <div class="row-main">
          <div class="row-title">{{ ta.appName }}</div>
          <div class="row-desc">
            {{ t('acct.settings.scopes') }}：{{ ta.scopes }} · {{ t('acct.settings.authTime') }}：{{ (ta.authTime || '').replace('T', ' ') }}
          </div>
        </div>
        <a-button size="small" danger class="touch-xs" @click="confirmRevoke(ta)">{{ t('acct.settings.revoke') }}</a-button>
      </div>

      <a-divider style="margin: 12px 0" />
      <div class="cookie-setting-row">
        <div class="row-main">
          <div class="row-title">Cookie 与隐私偏好</div>
          <div class="row-desc">管理必要、偏好、分析和营销 Cookie。必要 Cookie 始终启用。</div>
          <div v-if="cookieSummary" class="row-desc">当前：{{ cookieSummary }}</div>
          <div v-if="cookieConsent?.updatedAt" class="row-desc">更新时间：{{ formatCookieTime(cookieConsent.updatedAt) }}</div>
        </div>
        <a-button class="touch-sm" @click="openCookieSettings">管理 Cookie</a-button>
      </div>
      <a-divider style="margin: 12px 0" />
      <a-button danger class="touch-sm" @click="confirmReset">{{ t('acct.settings.reset') }}</a-button>
    </a-card>

    <!-- 修改密码弹窗 -->
    <a-modal :open="pwdOpen" :title="t('acct.settings.changePwd')" :footer="null" @cancel="pwdOpen = false" destroy-on-close>
      <a-form layout="vertical" @submit.prevent="submitPwd">
        <a-form-item :label="t('acct.settings.oldPwd')" :validate-status="pe.old ? 'error' : ''" :help="pe.old">
          <a-input-password v-model:value="pwd.old" size="large" @blur="pv('old')" />
        </a-form-item>
        <a-form-item :label="t('acct.settings.newPwd')" :validate-status="pe.new ? 'error' : ''" :help="pe.new">
          <a-input-password v-model:value="pwd.new" size="large" @blur="pv('new')" />
          <div class="strength" v-if="pwd.new">
            <span class="bars">
              <i v-for="i in 4" :key="i" :class="['bar', i <= strength.score ? 'on s' + strength.score : '']"></i>
            </span>
            <em>{{ strength.label }} · {{ t('acct.settings.strength') }}</em>
          </div>
        </a-form-item>
        <a-form-item :label="t('acct.settings.confirmPwd')" :validate-status="pe.confirm ? 'error' : ''" :help="pe.confirm">
          <a-input-password v-model:value="pwd.confirm" size="large" @blur="pv('confirm')" />
        </a-form-item>
        <a-button type="primary" html-type="submit" block size="large" :loading="saving" class="touch">{{ t('acct.settings.submit') }}</a-button>
      </a-form>
    </a-modal>

    <!-- 关闭两步验证弹窗（需输入当前动态码） -->
    <a-modal :open="disableOpen" :title="t('acct.settings.twofaDisableTitle')" :footer="null" @cancel="disableOpen = false" destroy-on-close>
      <p class="desc">{{ t('acct.settings.twofaDisableTip') }}</p>
      <a-form layout="vertical" @submit.prevent="submitDisable">
        <a-form-item :label="t('acct.settings.twofaCodeLabel')" :validate-status="dcErr ? 'error' : ''" :help="dcErr">
          <a-input v-model:value="dcode" maxlength="6" size="large" placeholder="6 位数字" />
        </a-form-item>
        <a-button type="primary" danger html-type="submit" block size="large" :loading="disabling" class="touch">
          {{ t('acct.settings.twofaConfirmDisable') }}
        </a-button>
      </a-form>
    </a-modal>

    <TotpSetup :open="totpOpen" @close="totpOpen = false" @enabled="refreshSecurity" />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { message, Modal } from 'ant-design-vue'
import TotpSetup from './TotpSetup.vue'
import { useSettingsStore } from '../../store/settings'
import { userApi } from '../../api'
import { t } from '../../i18n'
import { passwordStrength } from '../../utils/validators'
import { openCookiePreferences, readCookieConsent } from '../../utils/cookieConsent'

const settings = useSettingsStore()
const ap = computed(() => settings.settings?.appearance || { theme: 'system', language: 'zh-CN', fontSize: 'standard' })
const n = computed(() => settings.settings?.notify || { email: true, push: true, sms: false, groups: { order: true, promotion: false, system: true } })
const pr = computed(() => settings.settings?.privacy || { recommend: true, ads: false })
const cookieConsent = ref(readCookieConsent())
const cookieSummary = computed(() => {
  const c = cookieConsent.value?.categories
  if (!c) return '尚未设置'
  return ['preferences','analytics','marketing'].filter(k => c[k]).length
    ? ['preferences','analytics','marketing'].filter(k => c[k]).map(k => ({ preferences:'偏好', analytics:'分析', marketing:'营销' }[k])).join('、')
    : '仅必要'
})
function openCookieSettings() {
  openCookiePreferences()
}

function formatCookieTime(value) {
  if (!value) return ''
  try {
    return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
  } catch (e) {
    return value
  }
}

function handleCookieConsentChanged(event) {
  cookieConsent.value = event.detail || readCookieConsent()
}

// ===== 设置保存（乐观更新 + 回滚提示） =====
let toastTimer = null
function patchNotify(patch) { saveWithToast({ notify: { ...n.value, ...patch } }) }
function patchAppearance(patch) { saveWithToast({ appearance: { ...ap.value, ...patch } }) }
function patchPrivacy(patch) { saveWithToast({ privacy: { ...pr.value, ...patch } }) }
async function saveWithToast(patch) {
  try {
    await settings.update(patch)
    message.success(t('acct.settings.saved'), 3)
  } catch (e) {
    message.error(t('acct.settings.saveFail'), 3)
  }
}
function toggleAll() {
  const cur = n.value.groups
  const next = !(cur.order && cur.promotion && cur.system)
  patchNotify({ groups: { order: next, promotion: next, system: next } })
}

// ===== 主题/字号选项 =====
const themeOptions = computed(() => [
  { label: t('acct.settings.themeLight'), value: 'light' },
  { label: t('acct.settings.themeDark'), value: 'dark' },
  { label: t('acct.settings.themeSystem'), value: 'system' }
])
const fontOptions = computed(() => [
  { label: t('acct.settings.fontStandard'), value: 'standard' },
  { label: t('acct.settings.fontLarge'), value: 'large' },
  { label: t('acct.settings.fontSmall'), value: 'small' }
])

// ===== 账户安全：TOTP =====
const security = reactive({ totpEnabled: false, firstVerifyTime: null })
const totpOpen = ref(false)
const disableOpen = ref(false)
const dcode = ref('')
const dcErr = ref('')
const disabling = ref(false)
async function refreshSecurity() {
  try {
    const data = await userApi.getSecurity()
    security.totpEnabled = !!data.totpEnabled
    security.firstVerifyTime = data.firstVerifyTime || null
  } catch (e) { /* 静默 */ }
}
async function submitDisable() {
  if (!/^\d{6}$/.test(dcode.value)) { dcErr.value = t('acct.settings.codeRequired'); return }
  dcErr.value = ''
  disabling.value = true
  try {
    await userApi.totpDisable(dcode.value)
    message.success(t('acct.settings.twofaDisabled'), 3)
    disableOpen.value = false
    await refreshSecurity()
  } finally { disabling.value = false }
}

// ===== 账户安全：修改密码 =====
const pwdOpen = ref(false)
const saving = ref(false)
const pwd = reactive({ old: '', new: '', confirm: '' })
const pe = reactive({ old: '', new: '', confirm: '' })
const strength = computed(() => passwordStrength(pwd.new))
function pv(field) {
  if (field === 'old') pe.old = pwd.old ? '' : t('acct.settings.ruleOldRequired')
  if (field === 'new') {
    if (!pwd.new) pe.new = t('acct.settings.ruleNewRequired')
    else if (!/^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z0-9]).{8,20}$/.test(pwd.new)) pe.new = t('acct.settings.ruleNewPattern')
    else if (pwd.new === pwd.old) pe.new = t('acct.settings.ruleNewSameAsOld')
    else pe.new = ''
  }
  if (field === 'confirm') {
    if (!pwd.confirm) pe.confirm = t('acct.settings.ruleConfirmRequired')
    else if (pwd.confirm !== pwd.new) pe.confirm = t('acct.settings.ruleConfirmMismatch')
    else pe.confirm = ''
  }
}
function submitPwd() {
  pv('old'); pv('new'); pv('confirm')
  if (pe.old || pe.new || pe.confirm) return
  Modal.confirm({
    title: t('acct.settings.pwdConfirmTitle'),
    content: t('acct.settings.pwdConfirmContent'),
    okText: t('acct.ok'), cancelText: t('acct.cancelText'),
    onOk: async () => {
      saving.value = true
      try {
        await userApi.changePassword(pwd.old, pwd.new)
        message.success(t('acct.settings.pwdSuccess'), 3)
        pwdOpen.value = false
      } finally { saving.value = false }
    }
  })
}

// ===== 隐私：第三方授权 =====
const thirdAuths = ref([])
async function loadThirdAuths() {
  try { thirdAuths.value = (await userApi.thirdAuthList()) || [] } catch (e) { thirdAuths.value = [] }
}
function confirmRevoke(ta) {
  Modal.confirm({
    title: t('acct.settings.revokeTitle'),
    content: `${t('acct.settings.revokeContent')}（${ta.appName}）`,
    okText: t('acct.ok'), cancelText: t('acct.cancelText'),
    okButtonProps: { danger: true },
    onOk: async () => {
      await userApi.revokeThirdAuth(ta.id)
      message.success(t('acct.settings.revoked'), 3)
      loadThirdAuths()
    }
  })
}

// ===== 恢复默认设置 =====
function confirmReset() {
  Modal.confirm({
    title: t('acct.settings.resetTitle'),
    content: t('acct.settings.resetContent'),
    okText: t('acct.ok'), cancelText: t('acct.cancelText'),
    okButtonProps: { danger: true },
    onOk: async () => {
      await settings.reset()
      message.success(t('acct.settings.resetDone'), 3)
    }
  })
}

onMounted(() => {
  refreshSecurity()
  loadThirdAuths()
  window.addEventListener('ll-cookie-consent-changed', handleCookieConsentChanged)
})

onBeforeUnmount(() => {
  window.removeEventListener('ll-cookie-consent-changed', handleCookieConsentChanged)
})
</script>

<style scoped>
.panel { display: flex; flex-direction: column; gap: 16px; }
.sec { border-radius: 12px; }
.row { display: flex; align-items: center; gap: 12px; min-height: 48px; }
.row-main { flex: 1; min-width: 0; }
.row-title { color: var(--ll-ink); }
.row-desc { font-size: 12px; color: var(--ll-muted); margin-top: 2px; word-break: break-all; }
.desc.muted { color: var(--ll-muted); font-size: 13px; }
.switch-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 48px; }
.notify-all { margin-top: 8px; }
.field { margin-bottom: 14px; }
.field-label { margin-bottom: 6px; color: var(--ll-muted); font-size: 13px; }
.full { width: 100%; }
.empty { color: var(--ll-gray2); padding: 12px 0; text-align: center; }
.third-row { display: flex; align-items: center; gap: 10px; min-height: 56px; }
.third-icon { width: 34px; height: 34px; border-radius: 8px; background: var(--ll-ring-gradient); color: #fff; display: flex; align-items: center; justify-content: center; font-weight: 600; flex-shrink: 0; }
.strength { display: flex; align-items: center; gap: 8px; margin-top: 6px; }
.strength .bars { display: inline-flex; gap: 4px; }
.strength .bar { width: 26px; height: 5px; border-radius: 3px; background: #e5e7eb; }
.strength .bar.on.s1 { background: #ef4444; }
.strength .bar.on.s2 { background: #f59e0b; }
.strength .bar.on.s3 { background: #fbbf24; }
.strength .bar.on.s4 { background: #10b981; }
.strength em { font-style: normal; font-size: 12px; color: var(--ll-gray2); }
.desc { color: var(--ll-muted); margin-top: 0; }
.cookie-setting-row { display: flex; align-items: center; gap: 12px; padding: 6px 0; }
.touch { min-height: 44px; }
.touch-sm { min-height: 40px; min-width: 96px; }
.touch-xs { min-height: 36px; }
@media (max-width: 767px) {
  .touch-sm { flex: 1; min-height: 48px; }
}
</style>
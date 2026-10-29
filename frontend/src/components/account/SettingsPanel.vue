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

    <!-- 人工客服 -->
    <a-card :bordered="false" class="sec" :title="t('acct.settings.humanCs')">
      <div class="desc muted">{{ t('acct.settings.humanCsIntro') }}</div>
      <div class="cs-channels">
        <div class="cs-channel">
          <span class="cs-ch-ic"><AppleIcon name="phone" :size="17" /></span>
          <div class="row-main">
            <div class="row-title">{{ t('acct.settings.csHotline') }}</div>
            <div class="row-desc">{{ t('acct.settings.csHotlineValue') }}</div>
          </div>
          <a-button size="small" class="touch-xs" @click="copyText(t('acct.settings.csHotlineValue'))">{{ t('acct.settings.copy') }}</a-button>
        </div>
        <div class="cs-channel">
          <span class="cs-ch-ic"><AppleIcon name="mail" :size="17" /></span>
          <div class="row-main">
            <div class="row-title">{{ t('acct.settings.csEmail') }}</div>
            <div class="row-desc">{{ t('acct.settings.csEmailValue') }}</div>
          </div>
          <a-button size="small" class="touch-xs" @click="copyText(t('acct.settings.csEmailValue'))">{{ t('acct.settings.copy') }}</a-button>
        </div>
        <div class="cs-channel">
          <span class="cs-ch-ic"><AppleIcon name="timer" :size="17" /></span>
          <div class="row-main">
            <div class="row-title">{{ t('acct.settings.csHours') }}</div>
            <div class="row-desc">{{ t('acct.settings.csHoursValue') }}</div>
          </div>
        </div>
      </div>

      <a-divider style="margin: 14px 0 12px" />
      <div class="cs-block-title">{{ t('acct.settings.csNewTicket') }}</div>
      <a-form layout="vertical" @submit.prevent="submitTicket">
        <a-form-item :label="t('acct.settings.csCategory')">
          <a-select v-model:value="csForm.category" class="full">
            <a-select-option v-for="o in csCatOptions" :key="o.value" :value="o.value">{{ o.label }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item :label="t('acct.settings.csTitle')" :validate-status="csErr.title ? 'error' : ''" :help="csErr.title">
          <a-input v-model:value="csForm.title" :maxlength="100" :placeholder="t('acct.settings.csTitlePh')" allow-clear @blur="csValidate('title')" />
        </a-form-item>
        <a-form-item :label="t('acct.settings.csContent')" :validate-status="csErr.content ? 'error' : ''" :help="csErr.content">
          <a-textarea v-model:value="csForm.content" :rows="4" :maxlength="2000" :placeholder="t('acct.settings.csContentPh')" @blur="csValidate('content')" />
        </a-form-item>
        <a-form-item :label="t('acct.settings.csContact')">
          <a-input v-model:value="csForm.contact" :maxlength="120" :placeholder="t('acct.settings.csContactPh')" allow-clear />
        </a-form-item>
        <a-button type="primary" html-type="submit" block size="large" :loading="csSubmitting" class="touch">{{ t('acct.settings.csSubmit') }}</a-button>
      </a-form>

      <a-divider style="margin: 18px 0 12px" />
      <div class="cs-block-title">{{ t('acct.settings.csMyTickets') }}</div>
      <div v-if="csLoading" class="empty">…</div>
      <template v-else>
        <div v-if="csTickets.length === 0" class="empty">{{ t('acct.settings.csEmpty') }}</div>
        <div v-for="tk in csTickets" :key="tk.id" class="cs-ticket">
          <div class="cs-ticket-head">
            <span class="cs-ticket-title">{{ tk.title }}</span>
            <a-tag :color="csStatusColor(tk.status)">{{ csStatusLabel(tk.status) }}</a-tag>
          </div>
          <div class="cs-ticket-meta">{{ csCatLabel(tk.category) }} · {{ t('acct.settings.csSubmittedAt') }} {{ fmtDateTime(tk.createTime) }}</div>
          <div class="cs-ticket-content">{{ tk.content }}</div>
          <div v-if="tk.reply" class="cs-ticket-reply">
            <div class="cs-reply-label">{{ t('acct.settings.csReply') }}</div>
            <div class="cs-reply-text">{{ tk.reply }}</div>
          </div>
          <div v-else class="cs-ticket-reply cs-reply-none">{{ t('acct.settings.csNoReply') }}</div>
        </div>
      </template>
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
import AppleIcon from '../ui/AppleIcon.vue'
import { useSettingsStore } from '../../store/settings'
import { userApi, supportTicketApi } from '../../api'
import { t, locale } from '../../i18n'
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

// ===== 人工客服：工单 =====
const CS_STATUS = { OPEN: 'Open', PROCESSING: 'Processing', REPLIED: 'Replied', CLOSED: 'Closed' }
const CS_STATUS_COLOR = { OPEN: 'orange', PROCESSING: 'blue', REPLIED: 'green', CLOSED: 'default' }
const csForm = reactive({ category: 'other', title: '', content: '', contact: '' })
const csErr = reactive({ title: '', content: '' })
const csSubmitting = ref(false)
const csLoading = ref(false)
const csTickets = ref([])
const csCatOptions = computed(() => [
  { value: 'account', label: t('acct.settings.csCatAccount') },
  { value: 'order', label: t('acct.settings.csCatOrder') },
  { value: 'payment', label: t('acct.settings.csCatPayment') },
  { value: 'merchant', label: t('acct.settings.csCatMerchant') },
  { value: 'other', label: t('acct.settings.csCatOther') }
])
function csCatLabel(c) {
  return (csCatOptions.value.find((o) => o.value === c) || {}).label || t('acct.settings.csCatOther')
}
function csStatusLabel(s) {
  return t('acct.settings.csStatus' + (CS_STATUS[s] || 'Open'))
}
function csStatusColor(s) {
  return CS_STATUS_COLOR[s] || 'default'
}
function fmtDateTime(value) {
  if (!value) return ''
  try {
    return new Intl.DateTimeFormat(locale.value === 'en-US' ? 'en-US' : 'zh-CN', { dateStyle: 'medium', timeStyle: 'short' })
      .format(new Date(String(value).replace('T', ' ').replace(/-/g, '/')))
  } catch (e) {
    return String(value).replace('T', ' ')
  }
}
async function copyText(text) {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
    } else {
      const ta = document.createElement('textarea')
      ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0'
      document.body.appendChild(ta); ta.select()
      document.execCommand('copy'); document.body.removeChild(ta)
    }
    message.success(t('acct.settings.copied'), 2)
  } catch (e) { /* 静默 */ }
}
function csValidate(field) {
  if (field === 'title') {
    const v = csForm.title.trim()
    csErr.title = (v.length < 2 || v.length > 100) ? t('acct.settings.csTitleRequired') : ''
  }
  if (field === 'content') {
    const v = csForm.content.trim()
    csErr.content = (v.length < 5 || v.length > 2000) ? t('acct.settings.csContentRequired') : ''
  }
}
async function loadTickets() {
  csLoading.value = true
  try {
    const data = await supportTicketApi.my()
    csTickets.value = Array.isArray(data) ? data : []
  } catch (e) {
    csTickets.value = []
  } finally {
    csLoading.value = false
  }
}
async function submitTicket() {
  csValidate('title'); csValidate('content')
  if (csErr.title || csErr.content) return
  csSubmitting.value = true
  try {
    await supportTicketApi.submit({
      category: csForm.category,
      title: csForm.title.trim(),
      content: csForm.content.trim(),
      contact: csForm.contact.trim()
    })
    message.success(t('acct.settings.csSubmitted'), 3)
    csForm.title = ''; csForm.content = ''; csForm.contact = ''; csForm.category = 'other'
    await loadTickets()
  } catch (e) { /* 拦截器已提示 */ } finally {
    csSubmitting.value = false
  }
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
  loadTickets()
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
/* ===== 人工客服 ===== */
.cs-channels { display: flex; flex-direction: column; gap: 10px; margin-top: 12px; }
.cs-channel { display: flex; align-items: center; gap: 12px; min-height: 48px; }
.cs-ch-ic { width: 34px; height: 34px; border-radius: 8px; background: var(--ll-ring-gradient); color: #fff; display: flex; align-items: center; justify-content: center; font-size: 16px; flex-shrink: 0; }
.cs-block-title { font-weight: 600; color: var(--ll-ink); margin-bottom: 10px; font-size: 15px; }
.cs-ticket { border: 1px solid var(--ll-border); border-radius: 12px; padding: 12px 14px; margin-bottom: 12px; background: var(--ll-card-soft); }
.cs-ticket-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.cs-ticket-title { font-weight: 600; color: var(--ll-ink); }
.cs-ticket-meta { font-size: 12px; color: var(--ll-muted); margin-top: 2px; }
.cs-ticket-content { margin-top: 8px; color: var(--ll-ink); white-space: pre-wrap; word-break: break-word; }
.cs-ticket-reply { margin-top: 10px; padding: 10px 12px; border-radius: 10px; background: var(--ll-brand-gold-soft); border-left: 3px solid var(--ll-brand-gold); }
.cs-reply-label { font-size: 12px; color: var(--ll-brand-gold-strong); font-weight: 600; margin-bottom: 4px; }
.cs-reply-text { color: var(--ll-ink); white-space: pre-wrap; word-break: break-word; }
.cs-reply-none { color: var(--ll-muted); font-size: 13px; border-left-color: var(--ll-gray2); background: transparent; padding: 8px 0 0; }
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

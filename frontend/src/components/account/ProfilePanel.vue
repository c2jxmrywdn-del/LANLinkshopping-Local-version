<template>
  <div class="panel">
    <!-- 头像区 -->
    <div class="avatar-row">
      <UserAvatar
        :size="72"
        :src="profile && profile.avatar"
        :name="profile && profile.nickname"
        :gender="profile && profile.gender"
        :role="user.isAdmin ? 'admin' : user.isMerchant ? 'merchant' : 'user'"
        class="avatar"
      />
      <a-button class="touch" @click="avatarOpen = true">{{ t('acct.profile.changeAvatar') }}</a-button>
    </div>

    <!-- 基础信息：只读 / 编辑 双模式 -->
    <a-form layout="vertical" class="form">
      <div class="grid">
        <a-form-item :label="t('acct.profile.realName')"
          :validate-status="err.realName ? 'error' : (editing && ok.realName ? 'success' : '')" :help="editing ? err.realName : ''">
          <a-input v-if="editing" v-model:value="form.realName" maxlength="32" @blur="validate('realName')" />
          <div v-else class="ro">{{ profile.realName || t('acct.profile.notSet') }}</div>
        </a-form-item>

        <a-form-item :label="t('acct.profile.nickname')"
          :validate-status="err.nickname ? 'error' : (editing && ok.nickname ? 'success' : '')" :help="editing ? err.nickname : ''">
          <a-input v-if="editing" v-model:value="form.nickname" maxlength="32" @blur="validate('nickname')" />
          <div v-else class="ro">{{ profile.nickname || t('acct.profile.notSet') }}</div>
        </a-form-item>

        <a-form-item :label="t('acct.profile.gender')">
          <a-select v-if="editing" v-model:value="form.gender" :options="genderOptions" />
          <div v-else class="ro">{{ genderText }}</div>
        </a-form-item>

        <a-form-item :label="t('acct.profile.birthday')"
          :validate-status="err.birthday ? 'error' : ''" :help="editing ? err.birthday : ''">
          <a-date-picker v-if="editing" v-model:value="birthdayVal" :disabled-date="disabledBirthday"
            value-format="YYYY-MM-DD" class="full" :allow-clear="true" @change="validate('birthday')" @blur="validate('birthday')" />
          <div v-else class="ro">{{ profile.birthday || t('acct.profile.notSet') }}</div>
        </a-form-item>
      </div>

      <a-form-item :label="t('acct.profile.bio')">
        <template v-if="editing">
          <a-textarea v-model:value="form.bio" :rows="4" maxlength="500" :placeholder="t('acct.profile.bioPlaceholder')" />
          <div class="bio-help">{{ t('acct.profile.bioHelp', { n: 500 - (form.bio || '').length }) }}</div>
        </template>
        <div v-else class="ro bio">{{ profile.bio || t('acct.profile.notSet') }}</div>
      </a-form-item>
    </a-form>

    <!-- 联系方式（脱敏展示 + 换绑） -->
    <a-divider class="div" />
    <div class="contact-list">
      <div class="contact-row">
        <span class="c-label">{{ t('acct.profile.phone') }}</span>
        <span class="c-value">{{ maskPhone(profile.phone) || t('acct.profile.notSet') }}</span>
        <a-button type="link" class="c-btn" @click="bindType = 'phone'">{{ t('acct.profile.change') }}</a-button>
      </div>
      <div class="contact-row">
        <span class="c-label">{{ t('acct.profile.email') }}</span>
        <span class="c-value">{{ maskEmail(profile.email) || t('acct.profile.notSet') }}</span>
        <a-button type="link" class="c-btn" @click="bindType = 'email'">{{ t('acct.profile.change') }}</a-button>
      </div>
    </div>

    <!-- 固定操作条：保存 / 取消 -->
    <div class="action-bar">
      <a-button v-if="!editing" type="primary" class="touch" @click="startEdit">{{ t('acct.profile.edit') }}</a-button>
      <template v-else>
        <a-button type="primary" class="touch" :disabled="!dirty" :loading="saving" @click="save">{{ t('acct.profile.save') }}</a-button>
        <a-button class="touch" @click="cancel">{{ t('acct.profile.cancel') }}</a-button>
      </template>
    </div>

    <AvatarEditor :open="avatarOpen" @close="avatarOpen = false" @done="onAvatarDone" />
    <ContactBindModal v-if="bindType" :open="!!bindType" :type="bindType" @close="bindType = ''" @done="onBindDone" />
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { message, Modal } from 'ant-design-vue'
import dayjs from 'dayjs'
import AvatarEditor from './AvatarEditor.vue'
import ContactBindModal from './ContactBindModal.vue'
import UserAvatar from '../ui/UserAvatar.vue'
import { useProfileStore } from '../../store/profile'
import { useUserStore } from '../../store/user'
import { t } from '../../i18n'
import { checkBirthday } from '../../utils/validators'

const emit = defineEmits(['dirty-change'])
const store = useProfileStore()
const user = useUserStore()

const profile = computed(() => store.profile || {})
const editing = ref(false)
const saving = ref(false)
const avatarOpen = ref(false)
const bindType = ref('')

const form = reactive({ realName: '', nickname: '', gender: 'secret', birthday: '', bio: '' })
const err = reactive({ realName: '', nickname: '', birthday: '' })
const ok = reactive({ realName: false, nickname: false })

const genderOptions = computed(() => [
  { value: 'male', label: t('acct.profile.male') },
  { value: 'female', label: t('acct.profile.female') },
  { value: 'secret', label: t('acct.profile.secret') }
])
const genderText = computed(() => {
  const g = profile.value.gender
  return g === 'male' ? t('acct.profile.male') : g === 'female' ? t('acct.profile.female') : t('acct.profile.secret')
})

// DatePicker 绑定（字符串 yyyy-MM-dd）
const birthdayVal = computed({
  get: () => (form.birthday ? dayjs(form.birthday) : null),
  set: (v) => { form.birthday = v ? dayjs(v).format('YYYY-MM-DD') : '' }
})
// 合法范围：1900-01-01 ~ 14 年前的今天（年龄满 14 岁、不晚于今天）
function disabledBirthday(d) {
  if (!d) return false
  const latest = dayjs().subtract(14, 'year').endOf('day')
  return d.isBefore(dayjs('1900-01-01')) || d.isAfter(latest)
}

// ===== 脱敏 =====
function maskPhone(p) { return p ? String(p).replace(/^(\d{3})\d{4}(\d{4})$/, '$1****$2') : '' }
function maskEmail(e) {
  if (!e) return ''
  const [local, domain] = String(e).split('@')
  if (!domain) return '***'
  return '***' + local.slice(3) + '@' + domain
}

// ===== 编辑状态 =====
const dirty = computed(() =>
  ['realName', 'nickname', 'gender', 'birthday', 'bio'].some((k) => (form[k] ?? '') !== (profile.value[k] ?? ''))
)
watch(dirty, (v) => emit('dirty-change', v), { immediate: true })

function fill(src) {
  form.realName = src.realName || ''
  form.nickname = src.nickname || ''
  form.gender = src.gender || 'secret'
  form.birthday = src.birthday || ''
  form.bio = src.bio || ''
}

function startEdit() {
  fill(profile.value) // 保留原始数据作为修改基准
  err.realName = ''; err.nickname = ''; err.birthday = ''
  ok.realName = false; ok.nickname = false
  editing.value = true
}

/** 放弃修改：恢复基准数据并清草稿（供父组件在切页/离开确认后调用） */
function discardChanges() {
  editing.value = false
  store.clearDraft()
}
defineExpose({ discardChanges, dirty })

// ===== 校验（blur 实时触发，通过标记 success） =====
function validate(field) {
  if (field === 'realName') {
    if (!form.realName.trim()) err.realName = t('acct.profile.ruleRealNameRequired')
    else if (form.realName.length > 32) err.realName = t('acct.profile.ruleTooLong')
    else err.realName = ''
    ok.realName = !err.realName
  }
  if (field === 'nickname') {
    if (!form.nickname.trim()) err.nickname = t('acct.profile.ruleNicknameRequired')
    else if (form.nickname.length > 32) err.nickname = t('acct.profile.ruleTooLong')
    else err.nickname = ''
    ok.nickname = !err.nickname
  }
  if (field === 'birthday') {
    const r = checkBirthday(form.birthday)
    err.birthday = r.ok ? '' : r.msg
  }
  return !err[field]
}
function validateAll() {
  return ['realName', 'nickname', 'birthday'].map(validate).every(Boolean)
}

// ===== 草稿：编辑期间 AES 加密落盘，刷新后可恢复 =====
let draftTimer = null
watch(form, () => {
  if (!editing.value) return
  clearTimeout(draftTimer)
  draftTimer = setTimeout(() => store.saveDraft({ ...form }), 400)
})

onMounted(async () => {
  const draft = await store.loadDraft()
  if (draft && JSON.stringify(draft) !== JSON.stringify({ ...form })) {
    Modal.confirm({
      title: t('acct.profile.draftTitle'),
      content: t('acct.profile.draftContent'),
      okText: t('acct.profile.restore'),
      cancelText: t('acct.profile.discardDraft'),
      onOk: () => { fill(draft); editing.value = true },
      onCancel: () => store.clearDraft()
    })
  }
})

// ===== 保存 / 取消 =====
async function save() {
  if (!validateAll()) return
  saving.value = true
  try {
    await store.save(form)
    editing.value = false
    message.success(t('acct.profile.saveSuccess'), 3)
  } finally { saving.value = false }
}

function cancel() {
  if (!dirty.value) { discardChanges(); return }
  Modal.confirm({
    title: t('acct.discardTitle'),
    content: t('acct.discardContent'),
    okText: t('acct.ok'),
    cancelText: t('acct.cancelText'),
    onOk: discardChanges
  })
}

// ===== 头像 / 换绑回调 =====
function onAvatarDone(url) {
  store.applyAvatar(url)
  // Keep the shared navigation avatar in sync without requiring another login.
  if (user.user) {
    user.user.avatar = url
    localStorage.setItem('ll_user', JSON.stringify(user.user))
  }
}
function onBindDone(data) { store.applyContact(data) }
</script>

<style scoped>
.avatar-row { display: flex; align-items: center; gap: 16px; margin-bottom: 20px; }
.avatar { flex-shrink: 0; }
.grid { display: grid; grid-template-columns: 1fr 1fr; column-gap: 20px; }
.full { width: 100%; }
.ro { padding: 5px 0; min-height: 32px; color: var(--ll-ink); }
.ro.bio { white-space: pre-wrap; word-break: break-word; }
.bio-help { text-align: right; font-size: 12px; color: var(--ll-gray2); margin-top: 4px; }
.div { margin: 4px 0 12px; }
.contact-list { display: flex; flex-direction: column; gap: 4px; }
.contact-row { display: flex; align-items: center; gap: 8px; min-height: 48px; }
.c-label { width: 72px; color: var(--ll-muted); flex-shrink: 0; }
.c-value { flex: 1; color: var(--ll-ink); word-break: break-all; }
.c-btn { padding: 4px 8px; min-height: 40px; }
.action-bar { position: sticky; bottom: 0; display: flex; gap: 12px; padding: 12px 0 4px; margin-top: 16px; background: inherit; border-top: 1px solid rgba(128,128,128,.15); }
.touch { min-width: 120px; min-height: 44px; }
@media (max-width: 767px) {
  .grid { grid-template-columns: 1fr; column-gap: 0; }
  .touch { flex: 1; min-height: 48px; }
}
</style>

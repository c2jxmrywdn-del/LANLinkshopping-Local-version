<template>
  <!--
    不用 `ll-glass--day`：该材质用 140deg 四段线性渐变模拟玻璃斜面，
    在邀请卡这种大面积浅底上会呈现明显的斜向明暗分界（像一道斜光栅），
    且其 ::before 还有 6s 循环扫光，视觉上「一直在动」。
    邀请卡是信息型卡片（链接/二维码/表单），需要稳定可读的平面底色，
    因此改用组件自己的纯色底 + 细边（见 .invite-card 样式）。
  -->
  <section class="invite-card">
    <header class="invite-head">
      <div class="invite-head-copy">
        <div class="invite-title-row">
          <h3 class="invite-title"><slot name="title">{{ tx('邀请好友', 'Invite friends') }}</slot></h3>
          <!-- 身份由服务端按发起人角色判定，前端只展示，不提供任何切换入口 -->
          <span
            v-if="link"
            class="invite-role-badge"
            :class="isMerchantInvite ? 'is-merchant' : 'is-buyer'"
          >{{ roleBadgeText }}</span>
        </div>
        <p class="invite-sub">{{ tx('分享链接或二维码，好友注册后自动绑定邀请关系', 'Share the link or QR code; new sign-ups are linked to you automatically.') }}</p>
      </div>
      <div class="invite-head-extra"><slot name="extra" /></div>
    </header>

    <p v-if="loading" class="invite-state">{{ tx('邀请链接加载中…', 'Loading invite link…') }}</p>

    <div v-else-if="loadError" class="invite-state invite-state--error">
      <span>{{ loadErrorMessage || tx('邀请链接加载失败，请稍后重试', 'Failed to load the invite link. Please try again.') }}</span>
      <a-button size="small" @click="load">{{ tx('重试', 'Retry') }}</a-button>
    </div>

    <div v-else-if="link" class="invite-grid">
      <div class="invite-info">
        <label class="invite-label" :for="linkInputId">{{ tx('邀请链接', 'Invite link') }}</label>
        <div class="invite-link-row">
          <a-input
            :id="linkInputId"
            class="invite-link-input"
            :value="link.url"
            readonly
            :aria-label="tx('邀请链接', 'Invite link')"
          />
          <a-button
            class="invite-copy-btn"
            type="primary"
            :disabled="copyDisabled"
            @click="copyLink"
          >{{ tx('复制链接', 'Copy link') }}</a-button>
        </div>

        <!--
          邀请码明文只在运营后台展示（show-code）。
          前台（买家/商户/运营的前台入口）一律不显示 code：
          ① 邀请码是可注册的凭据，明文外露等于把"可分享凭据"直接摆在页面上；
          ② 前台用户只需要「复制链接 / 扫码」，不需要看到码本身。
        -->
        <p v-if="showCode" class="invite-code">
          <span class="invite-code-label">{{ tx('邀请码', 'Invite code') }}</span>
          <code>{{ link.code }}</code>
        </p>

        <p class="invite-validity" :class="{ 'is-expired': expired }" role="status">
          {{ validityText }}
        </p>
        <p class="invite-usage" role="status">{{ usageText }}</p>
        <p v-if="expired" class="invite-expired-hint" role="alert">
          {{ tx('邀请链接已过期，请重新生成', 'This invite link has expired. Please generate a new one.') }}
        </p>
        <p v-if="exhausted" class="invite-exhausted-hint" role="alert">
          {{ tx('邀请链接已用尽，请重新生成', 'This invite link has reached its usage limit. Please generate a new one.') }}
        </p>

        <div class="invite-actions">
          <label class="invite-uses-field" :for="usesInputId">
            <span class="invite-uses-label">{{ tx('次数上限', 'Max uses') }}</span>
            <a-input-number
              :id="usesInputId"
              v-model:value="maxUsesInput"
              class="invite-uses-input"
              :min="0"
              :max="10000"
              :precision="0"
              :placeholder="tx('0 = 不限', '0 = unlimited')"
            />
          </label>
          <a-button v-if="showRotate" :loading="rotating" @click="rotate">{{ tx('重新生成', 'Generate new link') }}</a-button>
        </div>
        <p class="invite-uses-hint">{{ tx('次数上限填 0 或留空则使用平台默认值（默认不限次数），最多 10000', 'Max uses: 0 or blank uses the platform default (unlimited by default), up to 10000') }}</p>
        <p v-if="rotateError" class="invite-rotate-error" role="alert">{{ rotateError }}</p>
      </div>

      <div class="invite-qr">
        <canvas
          v-show="!qrError"
          ref="canvasRef"
          class="invite-qr-canvas"
          role="img"
          :aria-label="tx('邀请二维码', 'Invite QR code')"
        ></canvas>
        <p v-if="qrError" class="invite-qr-fallback" role="status">
          {{ tx('二维码生成失败，可复制链接分享', 'QR code failed to render. You can still copy the link.') }}
        </p>
        <a-button
          class="invite-save-btn"
          size="small"
          :disabled="saveDisabled"
          @click="saveQr"
        >{{ tx('保存二维码', 'Save QR code') }}</a-button>
      </div>
    </div>

    <footer v-if="$slots.footer" class="invite-foot"><slot name="footer" /></footer>
  </section>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import { message } from 'ant-design-vue'
import QRCode from 'qrcode'
import { locale } from '../../i18n'
import { getInviteLink, rotateInviteLink } from '../../api/invite'

const props = defineProps({
  // 二维码边长（px）
  size: { type: Number, default: 180 },
  // 二维码深色模块 / 浅色底
  dark: { type: String, default: '#13233A' },
  light: { type: String, default: '#FFFFFF' },
  // 是否挂载后自动拉取邀请链接；父级自带数据时可关掉
  autoLoad: { type: Boolean, default: true },
  /**
   * 父级已拿到的链接数据（与 getInviteLink 返回结构一致）。
   *
   * <p>用于运营后台这类「链接来源不是 /invite/link」的场景：父级自行请求
   * /invite/admin/merchant-apply 后把结果传进来，组件只负责展示/复制/二维码/保存。
   */
  initialLink: { type: Object, default: null },
  /** 是否显示组件内的「重新生成」按钮（父级自己管按钮时关掉，避免两个入口） */
  showRotate: { type: Boolean, default: true },
  /**
   * 是否展示邀请码明文。
   *
   * <p>默认 false：前台（买家/商户/运营的前台入口）只展示链接与二维码，
   * 不暴露可直接注册的邀请码。仅运营后台（{@code AdminMerchantInvite}）显式传 true，
   * 因为运营需要把码口述/录入到线下渠道。
   */
  showCode: { type: Boolean, default: false }
})

const emit = defineEmits([
  'loaded', 'rotated', 'load-error', 'rotate-error',
  'qr-error', 'copy-success', 'copy-error', 'save-success', 'save-error'
])

const isEn = computed(() => String(locale.value || '').toLowerCase().startsWith('en'))
const tx = (zh, en) => (isEn.value ? en : zh)
// 页面上可能同时放多张邀请卡，label/for 必须唯一
const linkInputId = `invite-link-${String(useId()).replace(/[^a-zA-Z0-9]/g, '')}`
const usesInputId = `invite-uses-${String(useId()).replace(/[^a-zA-Z0-9]/g, '')}`

/** 次数上限输入：最小 0，最大 10000；空值表示交给后端默认 */
const MAX_USES_LIMIT = 10000

// 业务失败时拦截器 reject 的是 new Error(后端 message)；网络/超时则是原始 axios 错误，
// 其 message 为英文（Network Error 等），不应直接展示给用户。
function errorText(e, fallback) {
  if (!e || e.isAxiosError || e.name === 'AxiosError') return fallback
  const msg = typeof e.message === 'string' ? e.message.trim() : ''
  return msg || fallback
}

const link = ref(null)
// autoLoad 时首帧即显示加载态，避免"空卡片闪一下"
const loading = ref(props.autoLoad)
// 并发守卫与展示态分离：loading 初值为 true 时不能把 onMounted 的首个 load 挡掉
let inFlight = false
const loadError = ref(false)
const loadErrorMessage = ref('')
const rotating = ref(false)
const rotateError = ref('')
const qrError = ref(false)
const canvasRef = ref(null)
// 重新生成时提交的次数上限；undefined 表示不传参（后端按配置默认）
const maxUsesInput = ref(undefined)
// 渲染序号：快速切换链接/尺寸时丢弃过期的绘制结果，避免旧码覆盖新码
let qrSeq = 0

// 有效期以加载时刻换算成绝对时间戳，避免 expiresInSeconds 快照随时间漂移
const deadline = ref(null) // null = 永久有效
const now = ref(Date.now())
let clockTimer = null

const expired = computed(() => deadline.value !== null && now.value >= deadline.value)

// 次数语义（与后端 InviteService 对齐）：maxUses<=0 不限；remainingUses=-1 不限
const maxUses = computed(() => {
  const n = Number(link.value?.maxUses)
  return Number.isFinite(n) && n > 0 ? n : 0
})
const usedCount = computed(() => {
  const n = Number(link.value?.usedCount)
  return Number.isFinite(n) && n > 0 ? n : 0
})
const remainingUses = computed(() => {
  if (!maxUses.value) return -1
  const n = Number(link.value?.remainingUses)
  // 后端约定 -1 = 不限；仅在 maxUses>0 时才有意义。缺失/负值一律按 max-used 兜底，
  // 避免脏数据把仍可用的链接误判成"已用尽"。
  return Number.isFinite(n) && n >= 0 ? n : Math.max(0, maxUses.value - usedCount.value)
})
const exhausted = computed(() => maxUses.value > 0 && remainingUses.value === 0)
// 过期与用尽都视为不可再用：禁用复制/保存，但保留「重新生成」
const unusable = computed(() => expired.value || exhausted.value)
const copyDisabled = computed(() => !link.value?.url || unusable.value)
const saveDisabled = computed(() => !link.value?.url || unusable.value || qrError.value)

/**
 * 商家入驻邀约码（inviteType=merchant_apply）。
 *
 * <p>与「注册邀请」区分：注册邀请一律给买家身份；入驻邀约码不能直接注册成商户，
 * 被邀请人必须提交入驻申请并通过审核，所以文案要写清"需审核"。
 */
const isMerchantApply = computed(() => link.value?.inviteType === 'merchant_apply')
const isMerchantInvite = computed(() => isMerchantApply.value
  || link.value?.inviteType === 'merchant'
  || link.value?.grantedRoleCode === 'merchant')
const roleBadgeText = computed(() => {
  if (isMerchantApply.value) {
    return tx('商家入驻邀约：审核通过后成为商户', 'Merchant onboarding invite: merchant after review')
  }
  return isMerchantInvite.value
    ? tx('邀请后成为：商户', 'Joins as: Merchant')
    : tx('邀请后成为：普通用户', 'Joins as: Buyer')
})

const usageText = computed(() => {
  if (!link.value) return ''
  if (!maxUses.value) return tx('使用次数：不限次数', 'Uses: unlimited')
  return tx(
    `使用次数：已用 ${usedCount.value} / ${maxUses.value}，剩余 ${remainingUses.value}`,
    `Uses: ${usedCount.value} / ${maxUses.value} used, ${remainingUses.value} left`
  )
})

const remainingDays = computed(() => {
  if (deadline.value === null) return 0
  // 减一个极小量：刚拿到 30 天有效期时避免浮点毫秒误差被 ceil 成 31 天
  return Math.max(0, Math.ceil((deadline.value - now.value) / 86400000 - 1e-9))
})

function formatDateTime(ms) {
  const d = new Date(ms)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const validityText = computed(() => {
  if (!link.value) return ''
  if (expired.value) return tx('邀请链接已过期', 'Invite link expired')
  if (deadline.value === null) return tx('永久有效', 'Never expires')
  return tx(
    `剩余 ${remainingDays.value} 天 · ${formatDateTime(deadline.value)} 到期`,
    `${remainingDays.value} day(s) left · expires ${formatDateTime(deadline.value)}`
  )
})

function applyLink(data) {
  link.value = data || null
  const parsed = data?.expiresAt ? Date.parse(data.expiresAt) : NaN
  if (Number.isFinite(parsed)) {
    deadline.value = parsed
  } else {
    const secs = Number(data?.expiresInSeconds)
    // 后端约定：-1 = 永不过期；0 = 已过期
    deadline.value = Number.isFinite(secs) && secs >= 0 ? Date.now() + secs * 1000 : null
  }
  now.value = Date.now()
  // 次数输入跟随当前链接的上限，便于在原有基础上调整后重新生成
  const limit = Number(data?.maxUses)
  maxUsesInput.value = Number.isFinite(limit) && limit > 0 ? limit : 0
}

async function load() {
  if (inFlight) return
  inFlight = true
  loading.value = true
  loadError.value = false
  loadErrorMessage.value = ''
  try {
    const data = await getInviteLink()
    applyLink(data)
    emit('loaded', data)
  } catch (e) {
    loadError.value = true
    // 直接展示后端 message（拦截器已弹出同一文案的 toast，这里作为页面内持久提示）
    loadErrorMessage.value = errorText(e, '')
    link.value = null
    emit('load-error', e)
  } finally {
    loading.value = false
    inFlight = false
  }
  // canvas 只在 loading=false 且 link 存在时渲染，必须等状态落地后再画码
  if (!loadError.value) await renderQr()
}

async function rotate() {
  if (rotating.value) return
  rotating.value = true
  rotateError.value = ''
  try {
    const data = await rotateInviteLink(normalizedMaxUses())
    applyLink(data)
    await renderQr()
    message.success(tx('已生成新的邀请链接', 'New invite link generated'), 3)
    emit('rotated', data)
  } catch (e) {
    // toast 由 request.js 拦截器统一弹出（含 401 跳登录），这里只留卡片内持久提示，避免重复弹窗
    rotateError.value = errorText(e, tx('重新生成失败，请稍后重试', 'Failed to generate a new link. Please try again.'))
    emit('rotate-error', e)
  } finally {
    rotating.value = false
  }
}

/** 归一化次数上限：空/NaN → undefined（不传参）；其余夹到 0..10000 的整数 */
function normalizedMaxUses() {
  const raw = maxUsesInput.value
  if (raw === undefined || raw === null || raw === '') return undefined
  const n = Number(raw)
  if (!Number.isFinite(n)) return undefined
  return Math.min(MAX_USES_LIMIT, Math.max(0, Math.trunc(n)))
}

/** 生成二维码：放到 nextTick 后异步执行，避免阻塞首帧与主线程渲染 */
async function renderQr() {
  if (!link.value?.url) return
  const seq = ++qrSeq
  qrError.value = false
  await nextTick()
  const canvas = canvasRef.value
  if (!canvas || seq !== qrSeq) return
  try {
    await QRCode.toCanvas(canvas, link.value.url, {
      width: props.size,
      margin: 1,
      errorCorrectionLevel: 'M',
      color: { dark: props.dark, light: props.light }
    })
  } catch (e) {
    if (seq !== qrSeq) return
    qrError.value = true
    emit('qr-error', e)
  }
}

function fallbackCopy(text) {
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.setAttribute('readonly', '')
    ta.style.position = 'fixed'
    ta.style.top = '-9999px'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    ta.setSelectionRange(0, text.length)
    const ok = typeof document.execCommand === 'function' && document.execCommand('copy')
    document.body.removeChild(ta)
    return !!ok
  } catch (e) {
    return false
  }
}

async function copyLink() {
  if (copyDisabled.value) return
  const text = link.value.url
  try {
    if (!navigator.clipboard?.writeText) throw new Error('clipboard unavailable')
    await navigator.clipboard.writeText(text)
    message.success(tx('邀请链接已复制', 'Invite link copied'), 2)
    emit('copy-success', text)
  } catch (e) {
    if (fallbackCopy(text)) {
      message.success(tx('邀请链接已复制', 'Invite link copied'), 2)
      emit('copy-success', text)
      return
    }
    message.error(tx('复制失败，请手动选择链接复制', 'Copy failed. Please select and copy the link manually.'))
    emit('copy-error', e)
  }
}

function canvasToBlob(canvas) {
  return new Promise((resolve) => {
    if (typeof canvas.toBlob !== 'function') return resolve(null)
    canvas.toBlob((blob) => resolve(blob), 'image/png')
  })
}

async function saveQr() {
  if (saveDisabled.value) return
  const canvas = canvasRef.value
  try {
    const blob = canvas ? await canvasToBlob(canvas) : null
    if (!blob) throw new Error('canvas toBlob unavailable')
    const filename = `invite-${link.value.code || 'link'}.png`
    if (typeof URL.createObjectURL !== 'function') throw new Error('createObjectURL unavailable')
    const objectUrl = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = objectUrl
    a.download = filename
    a.rel = 'noopener'
    a.style.display = 'none'
    document.body.appendChild(a)
    a.click()
    a.remove()
    // 立即 revoke 在部分浏览器会让下载中断，推迟到下一轮事件循环
    setTimeout(() => URL.revokeObjectURL(objectUrl), 0)
    message.success(tx('二维码已保存', 'QR code saved'), 2)
    emit('save-success', filename)
  } catch (e) {
    message.error(tx('二维码保存失败，请稍后重试', 'Failed to save the QR code. Please try again.'))
    emit('save-error', e)
  }
}

watch([() => props.size, () => props.dark, () => props.light], () => {
  // 样式变更后重绘（此前生成失败也重试一次，给降级态一个恢复机会）
  if (link.value?.url) renderQr()
})

onMounted(() => {
  // 首帧之后再做网络与二维码：mounted 前不发起任何重活
  if (props.initialLink) {
    // 父级已带数据（如运营后台的入驻邀约码）：直接用，不再请求 /invite/link
    link.value = props.initialLink
    nextTick(() => renderQr())
  } else if (props.autoLoad) {
    nextTick(() => load())
  }
  clockTimer = setInterval(() => { now.value = Date.now() }, 60000)
})

// 父级异步拿到数据后再传入：同步展示并重绘二维码
watch(() => props.initialLink, (val) => {
  if (!val) return
  link.value = val
  nextTick(() => renderQr())
})

onBeforeUnmount(() => {
  if (clockTimer) { clearInterval(clockTimer); clockTimer = null }
})

defineExpose({ reload: load, rotate })
</script>

<style scoped>
/* 邀请卡：平面浅底 + 细边 + 轻投影。
   刻意不使用 ll-glass--day 的斜向渐变与扫光动画——信息型卡片要稳定可读，
   斜向明暗分界在浅底上会像"光栅"，也容易被误认为渲染错误。 */
.invite-card{
  box-sizing:border-box;
  width:100%;
  padding:20px;
  color:var(--ll-ink, #1d1d1f);
  background:var(--ll-card, #fff);
  border:1px solid var(--ll-border, #e8e6e1);
  border-radius:14px;
  box-shadow:0 1px 2px rgba(16, 24, 40, .04);
}
[data-theme='dark'] .invite-card{
  background:var(--ll-card, #16202e);
  border-color:var(--ll-border, #263349);
  color:var(--ll-ink, #e8edf5);
}
.invite-head{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;flex-wrap:wrap}
.invite-head-copy{min-width:0}
.invite-title-row{display:flex;align-items:center;gap:8px;flex-wrap:wrap}
.invite-title{margin:0;font-size:16px;font-weight:700;color:inherit}
.invite-role-badge{display:inline-flex;align-items:center;padding:2px 9px;border-radius:999px;border:1px solid var(--ll-separator-strong);font-size:11px;font-weight:600;line-height:1.6}
.invite-role-badge.is-merchant{color:#7A4B00;background:rgba(255, 193, 7, .16);border-color:rgba(180, 130, 0, .32)}
.invite-role-badge.is-buyer{color:var(--ll-primary);background:var(--ll-surface-inset)}
.invite-sub{margin:4px 0 0;font-size:12px;line-height:1.6;color:var(--ll-slate-muted)}
.invite-state{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:16px 0 0;font-size:13px;color:var(--ll-slate-muted)}
.invite-state--error{color:#B42318}
.invite-grid{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:24px;align-items:start;margin-top:16px}
.invite-info{min-width:0}
.invite-label{display:block;margin-bottom:6px;font-size:12px;color:var(--ll-slate-muted)}
.invite-link-row{display:flex;gap:8px;align-items:center;flex-wrap:wrap}
.invite-link-input{flex:1 1 220px;min-width:0}
.invite-copy-btn{flex:0 0 auto}
.invite-code{margin:12px 0 0;font-size:12px;color:var(--ll-slate-muted)}
.invite-code-label{margin-right:6px}
.invite-code code{padding:2px 8px;border-radius:6px;background:var(--ll-surface-inset);font-size:12px;color:var(--ll-ink-on-glass);word-break:break-all}
.invite-validity{margin:10px 0 0;font-size:12px;color:var(--ll-slate-muted)}
.invite-validity.is-expired{color:#B42318;font-weight:600}
.invite-usage{margin:6px 0 0;font-size:12px;color:var(--ll-slate-muted)}
.invite-expired-hint{margin:6px 0 0;font-size:12px;color:#B42318}
.invite-exhausted-hint{margin:6px 0 0;font-size:12px;color:#B42318}
.invite-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:14px}
.invite-uses-field{display:inline-flex;align-items:center;gap:8px}
.invite-uses-label{font-size:12px;color:var(--ll-slate-muted);white-space:nowrap}
.invite-uses-input{width:130px}
.invite-uses-hint{margin:6px 0 0;font-size:11px;color:var(--ll-slate-muted)}
.invite-rotate-error{margin:8px 0 0;font-size:12px;color:#B42318}
.invite-qr{display:flex;flex-direction:column;align-items:center;gap:10px}
.invite-qr-canvas{display:block;max-width:100%;height:auto;border-radius:10px;background:#fff}
.invite-qr-fallback{max-width:220px;margin:0;padding:18px 12px;border:1px dashed var(--ll-separator-strong);border-radius:10px;font-size:12px;line-height:1.6;color:var(--ll-slate-muted);text-align:center}
.invite-save-btn{min-width:120px}
.invite-foot{margin-top:16px;font-size:12px;color:var(--ll-slate-muted)}

@media (max-width:767px){
  .invite-grid{grid-template-columns:1fr;gap:18px}
  .invite-qr{align-items:stretch}
  .invite-qr-canvas{align-self:center}
  .invite-save-btn{width:100%;min-height:40px}
  .invite-copy-btn{min-height:40px}
  .invite-actions{align-items:stretch}
  .invite-uses-field{flex:1 1 100%;justify-content:space-between}
  .invite-uses-input{flex:1 1 auto;width:auto}
}
</style>

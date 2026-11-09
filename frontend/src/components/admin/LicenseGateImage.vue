<template>
  <div class="license-gate">
    <!-- 未上传执照：与 CertImage 的空态口径一致 -->
    <div v-if="!hasLicense" class="lg-empty" role="img" :aria-label="emptyText">
      <svg class="lg-empty__glyph" viewBox="0 0 24 24" aria-hidden="true">
        <path d="M6 2.8h8.2l3.8 3.8v14.6H6z" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round" />
        <path d="M14.2 2.8v3.8h3.8" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round" />
        <path d="M8.6 11.4h6.8M8.6 14.2h6.8M8.6 17h3.6" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" />
        <circle cx="16.1" cy="16.6" r="2.7" fill="none" stroke="currentColor" stroke-width="1.2" stroke-dasharray="1.6 1.3" />
      </svg>
      <span class="lg-empty__label">{{ emptyText }}</span>
    </div>

    <!-- 验证闸门：验证码 → 票据 -->
    <div v-else-if="stage === 'gate'" class="lg-gate">
      <p class="lg-gate__title"><SafetyCertificateOutlined class="lg-gate__icon" />{{ gateTitle }}</p>
      <p v-if="merchantName" class="lg-gate__sub">{{ gateSubPrefix }}{{ merchantName }}</p>
      <div class="lg-gate__row">
        <img
          v-if="captchaImage"
          :src="captchaImage"
          class="lg-gate__captcha"
          :alt="captchaAlt"
          title=""
          @click="loadCaptcha"
        />
        <a-spin v-else size="small" />
        <a class="lg-gate__refresh" @click="loadCaptcha">看不清？点击刷新</a>
      </div>
      <div class="lg-gate__form">
        <a-input
          v-model:value="code"
          class="lg-gate__input"
          :placeholder="inputPlaceholder"
          :maxlength="4"
          allow-clear
          @pressEnter="submit"
        />
        <a-button type="primary" :loading="submitting" :disabled="!code" @click="submit">验证并查看</a-button>
      </div>
      <p class="lg-gate__hint">{{ gateHint }}</p>
    </div>

    <!-- 已解锁：加载水印图 -->
    <div v-else class="lg-view">
      <div v-if="loadFailed" class="lg-error">
        <p>{{ errorText }}</p>
        <a-button size="small" @click="resetToGate">重新验证</a-button>
      </div>
      <a-spin v-else-if="!imgReady" class="lg-view__spin" />
      <img
        v-show="imgReady"
        :src="watermarkedSrc"
        :alt="alt"
        class="lg-view__img"
        :style="imgStyle"
        @load="imgReady = true"
        @error="onImgError"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { SafetyCertificateOutlined } from '@ant-design/icons-vue'
import { merchantApi } from '../../api'

/**
 * 营业执照门控查看器：人机验证 → 单次票据 → 服务端斜排水印 PNG。
 *
 * 后端语义（LicenseViewGateService）：票据 5 分钟 TTL、绑定当前运营账号与商户、
 * 单次使用——一次验证一次查看；弹窗重开/图挂载失败都会回到验证闸门重新走一遍，
 * 不做票据复用，避免把一次性凭证变成可分享的直链。
 */
const props = defineProps({
  merId: { type: Number, required: true },
  /** 商户企业名（仅作查看对象提示；水印内的商户名由后端按 entId 实时取） */
  merchantName: { type: String, default: '' },
  hasLicense: { type: Boolean, default: false },
  /** 图片描述（img alt） */
  alt: { type: String, default: '营业执照（含水印）' },
  emptyText: { type: String, default: '商户暂未上传营业执照原件' },
  errorText: { type: String, default: '水印图加载失败，请重新验证' },
  /** 透传给水印 img 的额外样式（如双屏核验的旋转） */
  imgStyle: { type: Object, default: () => ({}) }
})

const API_BASE = import.meta.env.VITE_API_BASE_URL || '/api'

const stage = ref('gate')
const captchaImage = ref('')
const captchaId = ref('')
const code = ref('')
const submitting = ref(false)
const watermarkedSrc = ref('')
const loadFailed = ref(false)
const imgReady = ref(false)

// 文案走 i18n 整句词典（zh-en-extra.js），动态前后缀单独拼
const gateTitle = '营业执照查看需先通过人机验证'
const gateSubPrefix = '查看对象：'
const captchaAlt = '人机验证码，点击刷新'
const inputPlaceholder = '请输入图中的验证码'
const gateHint = '验证后展示的是带溯源水印的执照副本，仅供对外展示时使用'

onMounted(() => {
  if (props.hasLicense) loadCaptcha()
})

async function loadCaptcha() {
  captchaImage.value = ''
  captchaId.value = ''
  code.value = ''
  try {
    const res = await merchantApi.licenseCaptcha(props.merId)
    captchaId.value = res?.captchaId || ''
    captchaImage.value = res?.image || ''
  } catch (e) {
    // 请求层已统一提示；闸门停留原状，用户可点击刷新重试
  }
}

async function submit() {
  if (!code.value || submitting.value) return
  submitting.value = true
  try {
    const res = await merchantApi.licenseVerify({
      merId: props.merId,
      captchaId: captchaId.value,
      captchaCode: code.value
    })
    const ticket = res?.ticket
    if (!ticket) throw new Error('未获取到查看凭证')
    watermarkedSrc.value = `${API_BASE}/merchant/admin/license-watermarked/${props.merId}?ticket=${encodeURIComponent(ticket)}`
    stage.value = 'view'
    loadFailed.value = false
    imgReady.value = false
  } catch (e) {
    // 验证码失效/被使用/超限：本地换新码，用户直接重输；其余错误由请求层 toast
    // 豁免收口：e.response.data 是 axios 原始错误响应体（错误通道，非业务数据双重剥壳）
    const msg = e?.response?.data?.message || e?.message || ''
    if (/失效|过期|已被使用|超限/.test(msg)) loadCaptcha()
  } finally {
    submitting.value = false
  }
}

function onImgError() {
  // 票据已被消费/过期等原因导致 4xx：本次解锁作废，回到闸门重新验证
  loadFailed.value = true
}

function resetToGate() {
  stage.value = 'gate'
  loadFailed.value = false
  imgReady.value = false
  loadCaptcha()
}
</script>

<style scoped>
.license-gate { width: 100%; }

.lg-empty {
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px;
  width: 100%; min-height: 160px; padding: 12px;
  color: #94a3b8; background:
    repeating-linear-gradient(45deg, #f8fafc 0 10px, #f1f5f9 10px 20px);
  border-radius: 8px; text-align: center;
}
.lg-empty__glyph { width: 32px; height: 32px; opacity: .85; }
.lg-empty__label { font-size: 12px; color: #64748b; }

.lg-gate {
  display: flex; flex-direction: column; gap: 8px;
  padding: 16px; border-radius: 8px;
  background: #f8fafc; border: 1px dashed #cbd5e1;
}
.lg-gate__title { margin: 0; font-size: 13px; font-weight: 600; color: #334155; }
.lg-gate__icon { color: #0284c7; margin-right: 6px; }
.lg-gate__sub { margin: 0; font-size: 12px; color: #64748b; }
.lg-gate__row { display: flex; align-items: center; gap: 10px; }
.lg-gate__captcha {
  height: 40px; border-radius: 4px; cursor: pointer;
  border: 1px solid #e2e8f0; background: #fff;
}
.lg-gate__refresh { font-size: 12px; color: #0284c7; white-space: nowrap; }
.lg-gate__form { display: flex; gap: 8px; }
.lg-gate__input { max-width: 180px; }
.lg-gate__hint { margin: 0; font-size: 11px; color: #94a3b8; }

.lg-view { position: relative; display: flex; align-items: center; justify-content: center; }
.lg-view__img {
  display: block; max-width: 100%; max-height: 460px;
  object-fit: contain; border-radius: 8px; background: #f1f5f9;
}
.lg-view__spin { padding: 60px 0; }
.lg-error {
  display: flex; flex-direction: column; align-items: center; gap: 8px;
  width: 100%; min-height: 160px; padding: 16px;
  color: #64748b; background: #f8fafc; border-radius: 8px; font-size: 12px;
}
</style>

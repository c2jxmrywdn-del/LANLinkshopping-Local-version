<template>
  <div class="qrsl" :data-status="status" :data-playing="playing ? 'on' : 'off'">
    <!-- 顶部：标题 + 场景徽标 -->
    <div class="qrsl__head">
      <div class="qrsl__head-copy">
        <strong>{{ t('qrsl.title') }}</strong>
        <p>{{ t('qrsl.subtitle', { app: appName }) }}</p>
      </div>
      <span class="qrsl__badge">{{ t('qrsl.badge') }}</span>
    </div>

    <!-- 二维码舞台 -->
    <div class="qrsl__stage">
      <div class="qrsl__plate" :class="{ 'is-dim': status === 'expired' }">
        <canvas ref="canvasRef" class="qrsl__canvas" role="img" :aria-label="t('qrsl.qrAria')"></canvas>
        <span class="qrsl__corner is-tl" aria-hidden="true"></span>
        <span class="qrsl__corner is-tr" aria-hidden="true"></span>
        <span class="qrsl__corner is-bl" aria-hidden="true"></span>
        <span class="qrsl__corner is-br" aria-hidden="true"></span>
        <span v-if="status === 'waiting' && !reduced" class="qrsl__scanline" aria-hidden="true"></span>
        <span class="qrsl__logo" aria-hidden="true">
          <slot name="logo">
            <img src="/logo.svg" alt="" class="qrsl__logo-img" @error="logoBroken = true" v-if="!logoBroken" />
            <span v-else class="qrsl__logo-fallback">LL</span>
          </slot>
        </span>
      </div>

      <!-- 状态覆盖层 -->
      <div v-if="status === 'expired'" class="qrsl__overlay">
        <p class="qrsl__overlay-title">{{ t('qrsl.expired') }}</p>
        <a-button type="primary" size="small" @click="replay">{{ t('qrsl.refreshNow') }}</a-button>
      </div>
      <div v-else-if="status === 'scanned'" class="qrsl__overlay is-scanned">
        <span class="qrsl__tick" aria-hidden="true"><AppleIcon name="confirm" :size="18" /></span>
        <p class="qrsl__overlay-title">{{ t('qrsl.scanned') }}</p>
      </div>
      <div v-else-if="status === 'confirmed'" class="qrsl__overlay is-confirmed">
        <span class="qrsl__tick" aria-hidden="true"><AppleIcon name="confirm" :size="18" /></span>
        <p class="qrsl__overlay-title">{{ t('qrsl.confirming') }}</p>
      </div>
    </div>

    <!-- 状态播报（读屏友好） -->
    <p class="qrsl__status" role="status" aria-live="polite">{{ statusText }}</p>

    <!-- 操作行 -->
    <div class="qrsl__actions">
      <button type="button" class="qrsl__link" @click="replay">{{ t('qrsl.refresh') }}</button>
      <span class="qrsl__divider" aria-hidden="true"></span>
      <button type="button" class="qrsl__link" @click="emit('use-password')">{{ t('qrsl.usePassword') }}</button>
    </div>

    <!-- 演示操作（本地无扫码后端时用于验证完整交互；不会自动登录） -->
    <div v-if="showDemo" class="qrsl__demo">
      <span class="qrsl__demo-tag">{{ t('qrsl.demo') }}</span>
      <a-button size="small" :disabled="status !== 'waiting'" @click="simulateScan">{{ t('qrsl.simScan') }}</a-button>
      <a-button size="small" type="primary" :disabled="status !== 'scanned'" @click="simulateConfirm">{{ t('qrsl.simConfirm') }}</a-button>
      <a-button v-if="autoplay && !reduced" size="small" @click="togglePlay">
        {{ playing ? t('qrsl.pause') : t('qrsl.play') }}
      </a-button>
      <a-button size="small" @click="replay">{{ t('qrsl.replay') }}</a-button>
    </div>

    <p class="qrsl__note">
      {{ status === 'expired'
        ? t('qrsl.noteExpired')
        : t('qrsl.noteCountdown', { s: expiresIn, total: expireSeconds }) }}
    </p>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { buildLoginQrPayload, renderQrToCanvas } from '../../utils/qrCode'
import AppleIcon from './AppleIcon.vue'
import { t } from '../../i18n'

const props = defineProps({
  appName: { type: String, default: 'LANLinkshopping' },
  /** 演示自动播放：仅推进"已扫码→失效刷新"的视觉状态，不会触发真实登录 */
  autoplay: { type: Boolean, default: true },
  expireSeconds: { type: Number, default: 120 },
  deviceName: { type: String, default: '本机浏览器' },
  welcomeMessage: { type: String, default: '欢迎回来' },
  status: { type: String, default: 'waiting' },
  showDemo: { type: Boolean, default: true }
})
const emit = defineEmits(['update:status', 'confirmed', 'refresh', 'use-password'])

const canvasRef = ref(null)
const payload = ref('')
const innerStatus = ref(props.status)
const expiresIn = ref(props.expireSeconds)
const paused = ref(false)
const reduced = ref(false)
const logoBroken = ref(false)

const status = computed({
  get: () => innerStatus.value,
  set: (v) => { innerStatus.value = v; emit('update:status', v) }
})
const playing = computed(() => props.autoplay && !reduced.value && !paused.value)

const statusText = computed(() => ({
  waiting: t('qrsl.stWaiting'),
  scanned: t('qrsl.stScanned', { device: props.deviceName }),
  confirmed: t('qrsl.stConfirmed', { msg: props.welcomeMessage }),
  expired: t('qrsl.stExpired')
}[status.value] || ''))

let tickTimer = 0
let cycleTimers = []
let media = null

function clearTimers() {
  if (tickTimer) { clearInterval(tickTimer); tickTimer = 0 }
  cycleTimers.forEach((id) => clearTimeout(id))
  cycleTimers = []
}

/** 重新生成随机 payload 并渲染新二维码（二维码随机生成器入口） */
async function regenerate() {
  status.value = 'waiting'
  payload.value = buildLoginQrPayload(props.appName)
  expiresIn.value = props.expireSeconds
  await new Promise((r) => requestAnimationFrame(() => r()))
  if (canvasRef.value) {
    try {
      await renderQrToCanvas(canvasRef.value, payload.value, { width: 220, errorCorrectionLevel: 'Q' })
    } catch (e) { /* 渲染失败不阻塞交互，canvas 保留空态 */ }
  }
  emit('refresh', payload.value)
}

function startCountdown() {
  if (tickTimer) clearInterval(tickTimer)
  tickTimer = setInterval(() => {
    if (status.value !== 'waiting') return
    expiresIn.value -= 1
    if (expiresIn.value <= 0) {
      expiresIn.value = 0
      status.value = 'expired'
      clearInterval(tickTimer)
      tickTimer = 0
    }
  }, 1000)
}

/** 演示自动播放：waiting → scanned → 自动刷新，纯视觉，不触发 confirmed */
function startCycle() {
  cycleTimers.forEach((id) => clearTimeout(id))
  cycleTimers = []
  if (!playing.value) return
  cycleTimers.push(setTimeout(() => { if (playing.value && status.value === 'waiting') status.value = 'scanned' }, 2600))
  cycleTimers.push(setTimeout(() => {
    if (!playing.value) return
    replay()
  }, 6200))
}

function replay() {
  paused.value = false
  clearTimers()
  regenerate()
  startCountdown()
  startCycle()
}

function togglePlay() {
  paused.value = !paused.value
  if (paused.value) {
    cycleTimers.forEach((id) => clearTimeout(id))
    cycleTimers = []
  } else {
    startCycle()
  }
}

function simulateScan() {
  if (status.value === 'waiting') status.value = 'scanned'
}
function simulateConfirm() {
  if (status.value !== 'scanned') return
  status.value = 'confirmed'
  emit('confirmed', payload.value)
}

watch(() => props.status, (v) => { if (v && v !== innerStatus.value) innerStatus.value = v })
watch(playing, (on) => { if (on) startCycle() })

onMounted(() => {
  if (typeof window !== 'undefined' && window.matchMedia) {
    media = window.matchMedia('(prefers-reduced-motion: reduce)')
    reduced.value = media.matches
    media.addEventListener?.('change', onChangeMotion)
  }
  replay()
})
function onChangeMotion() { reduced.value = !!media?.matches }
onBeforeUnmount(() => {
  clearTimers()
  media?.removeEventListener?.('change', onChangeMotion)
})
</script>

<style scoped>
.qrsl {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 18px 16px 14px;
  border: 1px solid var(--ll-border, #e5e7eb);
  border-radius: var(--ll-radius-lg, 14px);
  background: var(--ll-card-soft, #fbfcfe);
}
.qrsl__head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; width: 100%; }
.qrsl__head-copy strong { display: block; font-size: 14px; color: var(--ll-ink, #0f172a); }
.qrsl__head-copy p { margin: 3px 0 0; font-size: 11px; color: var(--ll-muted, #64748b); }
.qrsl__badge {
  flex: 0 0 auto; padding: 3px 8px; border: 1px solid var(--ll-border, #e5e7eb); border-radius: 999px;
  font-size: 9px; font-weight: 800; letter-spacing: .14em; text-transform: uppercase;
  color: var(--ll-brand-gold-strong, #80601F); background: var(--ll-white, #fff);
}

/* 舞台：白底二维码 + 四角括号 + 扫描线 + 中心标识 */
.qrsl__stage { position: relative; }
.qrsl__plate {
  position: relative; display: grid; place-items: center;
  padding: 12px; border-radius: var(--ll-radius-md, 10px);
  background: #fff; box-shadow: var(--ll-shadow-sm, 0 2px 8px rgba(19, 35, 58, .07));
  transition: opacity .25s ease, filter .25s ease;
}
.qrsl__plate.is-dim { opacity: .35; filter: blur(2px); }
.qrsl__canvas { display: block; width: 220px; height: 220px; }
.qrsl__corner { position: absolute; width: 20px; height: 20px; border: 2px solid var(--ll-cyan, #22D3EE); border-radius: 4px; }
.qrsl__corner.is-tl { top: 5px; left: 5px; border-right: 0; border-bottom: 0; }
.qrsl__corner.is-tr { top: 5px; right: 5px; border-left: 0; border-bottom: 0; }
.qrsl__corner.is-bl { bottom: 5px; left: 5px; border-right: 0; border-top: 0; }
.qrsl__corner.is-br { bottom: 5px; right: 5px; border-left: 0; border-top: 0; }
.qrsl__scanline {
  position: absolute; left: 10px; right: 10px; height: 2px; border-radius: 2px;
  background: linear-gradient(90deg, transparent, var(--ll-cyan, #22D3EE), transparent);
  box-shadow: 0 0 12px rgba(34, 211, 238, .6);
  animation: qrsl-scan 2.4s ease-in-out infinite;
}
.qrsl__logo {
  position: absolute; display: grid; place-items: center;
  width: 44px; height: 44px; border-radius: 10px; background: #fff;
  box-shadow: 0 0 0 3px #fff, 0 1px 6px rgba(19, 35, 58, .18);
}
.qrsl__logo-img { width: 32px; height: 32px; object-fit: contain; }
.qrsl__logo-fallback { font: 800 13px/1 system-ui, sans-serif; color: var(--ll-navy, #1F3864); }

/* 状态覆盖层 */
.qrsl__overlay {
  position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 8px; border-radius: var(--ll-radius-md, 10px);
  background: rgba(255, 255, 255, .86); backdrop-filter: blur(3px);
}
.qrsl__overlay-title { margin: 0; font-size: 12px; font-weight: 700; color: var(--ll-ink, #0f172a); }
.qrsl__tick {
  display: grid; place-items: center; width: 34px; height: 34px; border-radius: 50%;
  color: #fff; font-size: 17px; font-weight: 800; background: var(--ll-primary, #1E6EB8);
}
.qrsl__overlay.is-confirmed .qrsl__tick { background: #0f766e; }

.qrsl__status { margin: 0; min-height: 17px; font-size: 12px; color: var(--ll-muted, #64748b); text-align: center; }

.qrsl__actions { display: flex; align-items: center; gap: 10px; }
.qrsl__divider { width: 1px; height: 11px; background: var(--ll-border-strong, #d5dae2); }
.qrsl__link {
  border: 0; padding: 2px 0; background: transparent; cursor: pointer;
  font: inherit; font-size: 12px; color: var(--ll-primary, #1E6EB8); border-radius: 4px;
}
.qrsl__link:hover { color: var(--ll-primary-hover, #2774BD); text-decoration: underline; }
.qrsl__link:focus-visible { outline: none; box-shadow: var(--ll-focus-ring, 0 0 0 3px rgba(30, 110, 184, .28)); }

.qrsl__demo {
  display: flex; flex-wrap: wrap; align-items: center; gap: 6px; padding-top: 9px;
  border-top: 1px dashed var(--ll-border, #e5e7eb); width: 100%;
}
.qrsl__demo-tag {
  padding: 2px 6px; border-radius: 4px; font-size: 9px; font-weight: 800; letter-spacing: .12em;
  color: var(--ll-gray, #4b5563); background: var(--ll-thumb-bg, #eef1f6);
}
.qrsl__note { margin: 0; font-size: 10px; color: var(--ll-muted, #64748b); text-align: center; }

@keyframes qrsl-scan { 0%, 100% { top: 12px; } 50% { top: calc(100% - 14px); } }
@media (prefers-reduced-motion: reduce) {
  .qrsl__scanline { animation: none; display: none; }
  .qrsl__plate { transition: none; }
}
</style>

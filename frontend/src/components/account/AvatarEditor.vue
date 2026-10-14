<template>
  <a-modal :open="open" :title="t('acct.avatar.title')" :footer="null" @cancel="onClose" destroy-on-close>
    <!-- 未选择图片：上传区 -->
    <div v-if="!img" class="drop" @click="pick" @dragover.prevent @drop.prevent="onDrop" role="button" :aria-label="t('acct.avatar.dropHint')" tabindex="0" @keydown.enter="pick">
      <div class="drop-icon">🖼</div>
      <div>{{ t('acct.avatar.dropHint') }}</div>
      <div class="hint">{{ t('acct.avatar.typeHint') }}</div>
    </div>

    <!-- 已选择：预览 + 编辑 -->
    <template v-else>
      <div class="preview">
        <canvas ref="cv" width="256" height="256" class="cv" aria-label="avatar preview"></canvas>
      </div>
      <div class="ops">
        <span class="op-label">{{ t('acct.avatar.zoom') }}</span>
        <a-slider v-model:value="zoom" :min="1" :max="3" :step="0.05" class="zoom" @change="draw" />
        <a-button class="op-btn" @click="rotate">⟳ {{ t('acct.avatar.rotate') }}</a-button>
        <a-button class="op-btn" @click="reset">{{ t('acct.avatar.reselect') }}</a-button>
      </div>
      <a-progress v-if="uploading || fail" :percent="percent" :status="fail ? 'exception' : (percent >= 100 ? 'success' : 'active')" />
      <div v-if="fail" class="fail-row">
        <span class="fail-text">{{ t('acct.avatar.uploadFail') }}</span>
        <a-button size="small" danger @click="upload">{{ t('acct.avatar.retry') }}</a-button>
      </div>
      <a-button type="primary" block class="submit touch" :loading="uploading" :disabled="uploading" @click="upload">
        {{ t('acct.avatar.upload') }}
      </a-button>
    </template>

    <input ref="fileInput" type="file" accept="image/jpeg,image/png,image/webp" hidden @change="onFile" />
  </a-modal>
</template>

<script setup>
import { ref } from 'vue'
import { message } from 'ant-design-vue'
import { userApi } from '../../api'
import { t } from '../../i18n'

const props = defineProps({ open: Boolean })
const emit = defineEmits(['close', 'done'])

const MAX_SIZE = 5 * 1024 * 1024
const TYPES = ['image/jpeg', 'image/png', 'image/webp']

const fileInput = ref(null)
const cv = ref(null)
const img = ref(null)      // HTMLImageElement
const zoom = ref(1)
const deg = ref(0)         // 旋转角度（0/90/180/270）
const uploading = ref(false)
const percent = ref(0)
const fail = ref(false)

function pick() { fileInput.value && fileInput.value.click() }

function checkFile(f) {
  if (!TYPES.includes(f.type)) { message.error(t('acct.avatar.invalidType')); return false }
  if (f.size > MAX_SIZE) { message.error(t('acct.avatar.invalidSize')); return false }
  return true
}

function onFile(e) {
  const f = e.target.files && e.target.files[0]
  e.target.value = ''
  if (f && checkFile(f)) loadImg(f)
}
function onDrop(e) {
  const f = e.dataTransfer.files && e.dataTransfer.files[0]
  if (f && checkFile(f)) loadImg(f)
}

function loadImg(f) {
  const url = URL.createObjectURL(f)
  const image = new Image()
  image.onload = () => { img.value = image; zoom.value = 1; deg.value = 0; fail.value = false; draw() }
  image.src = url
}

/** 画布绘制：中心裁剪 + 缩放 + 旋转，输出 256x256 */
function draw() {
  const c = cv.value
  const image = img.value
  if (!c || !image) return
  const ctx = c.getContext('2d')
  const size = 256
  ctx.clearRect(0, 0, size, size)
  ctx.save()
  ctx.translate(size / 2, size / 2)
  ctx.rotate((deg.value * Math.PI) / 180)
  // 让图片旋转后仍能覆盖画布：按对角线比例取基准缩放
  const rotated = deg.value % 180 !== 0
  const cover = rotated
    ? Math.max(size / image.height, size / image.width) // 交换宽高
    : Math.max(size / image.width, size / image.height)
  const w = image.width * cover * zoom.value
  const h = image.height * cover * zoom.value
  ctx.drawImage(image, -w / 2, -h / 2, w, h)
  ctx.restore()
}

function rotate() { deg.value = (deg.value + 90) % 360; draw() }
function reset() { img.value = null; fail.value = false; percent.value = 0 }

function toBlob() {
  return new Promise((resolve) => {
    const c = cv.value
    if (!c) return resolve(null)
    c.toBlob((b) => resolve(b), 'image/png')
  })
}

async function upload() {
  const blob = await toBlob()
  if (!blob) return
  uploading.value = true
  fail.value = false
  percent.value = 0
  try {
    const file = new File([blob], 'avatar.png', { type: 'image/png' })
    const data = await userApi.uploadAvatar(file, (p) => { percent.value = p })
    message.success(t('acct.avatar.done'), 3)
    emit('done', data.url)
    onClose()
  } catch (e) {
    fail.value = true // 拦截器已弹出错误提示，这里提供重试入口
  } finally {
    uploading.value = false
  }
}

function onClose() {
  reset()
  emit('close')
}
</script>

<style scoped>
.drop { border: 1.5px dashed var(--ll-thumb-fg); border-radius: 12px; padding: 36px 16px; text-align: center; cursor: pointer; color: var(--ll-muted); transition: border-color .2s; }
.drop:hover, .drop:focus-visible { border-color: var(--ll-indigo); color: var(--ll-indigo); outline: none; }
.drop-icon { font-size: 30px; margin-bottom: 8px; }
.hint { font-size: 12px; color: var(--ll-gray2); margin-top: 6px; }
.preview { display: flex; justify-content: center; margin-bottom: 12px; }
.cv { border-radius: 50%; background: var(--ll-thumb-bg); width: 180px; height: 180px; }
.ops { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.op-label { color: var(--ll-muted); font-size: 13px; }
.zoom { flex: 1; min-width: 120px; }
.op-btn { min-height: 36px; }
.fail-row { display: flex; align-items: center; justify-content: space-between; margin: 8px 0; }
.fail-text { color: #ef4444; font-size: 13px; }
.submit { margin-top: 12px; }
.touch { min-height: 44px; }
</style>

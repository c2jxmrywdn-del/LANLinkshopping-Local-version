<template>
  <div class="mm">
    <!-- 图片：a-image 点击放大预览 -->
    <a-image v-if="kind === 'image' && src" class="mm-image" :src="src" :alt="msg.mediaName || '聊天图片'" />
    <!-- 文件：图标块 + 名称 + 大小 + 下载 -->
    <a v-else-if="kind === 'file' && src" class="mm-file" :href="src" :download="msg.mediaName || ''">
      <span class="mm-file-ico" aria-hidden="true">
        <FileText :size="20" :stroke-width="1.8" />
      </span>
      <span class="mm-file-meta">
        <b class="mm-file-name">{{ msg.mediaName || '未命名文件' }}</b>
        <small class="mm-file-size">{{ formatBytes(msg.mediaSize) || '未知大小' }}</small>
      </span>
      <Download class="mm-file-dl" :size="16" :stroke-width="2" aria-hidden="true" />
    </a>
    <!-- 语音：自定义播放条（隐藏 audio 驱动 timeupdate/ended，不用原生 controls） -->
    <div v-else-if="kind === 'voice' && src" class="mm-voice">
      <button type="button" class="mm-voice-btn" :aria-label="playing ? '暂停播放' : '播放语音'" @click="togglePlay">
        <Pause v-if="playing" :size="13" :stroke-width="2.6" />
        <Play v-else :size="13" :stroke-width="2.6" />
      </button>
      <b class="mm-voice-sec">{{ secLabel }}</b>
      <span class="mm-voice-track" aria-hidden="true">
        <i class="mm-voice-bar" :style="{ width: progress + '%' }"></i>
      </span>
      <audio ref="audioRef" :src="src" preload="metadata" class="mm-audio"
             @play="onPlay" @pause="onPause" @ended="onEnded"
             @timeupdate="onTime" @error="onAudioError"></audio>
    </div>
    <!-- 媒体字段缺失兜底：按类型给占位文本 -->
    <span v-else class="mm-fallback">{{ mediaPlaceholder(msg) || '[消息]' }}</span>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { message } from 'ant-design-vue'
import { Download, FileText, Pause, Play } from 'lucide-vue-next'
import { formatBytes, mediaPlaceholder } from '../../utils/chatMedia'
import { normalizeUploadUrl } from '../../utils/uploadUrl'

const props = defineProps({
  msg: { type: Object, required: true }
})

const kind = computed(() => {
  const t = props.msg?.msgType
  return t === 'image' || t === 'file' || t === 'voice' ? t : ''
})

// 媒体 URL 入口防御：历史 /uploads/... 形态统一补齐 /api 前缀
const src = computed(() => normalizeUploadUrl(props.msg?.mediaUrl || ''))

// ===== 语音播放状态（配 currentColor 系，白/渐变两种气泡底色都可读） =====
const audioRef = ref(null)
const playing = ref(false)
const current = ref(0)
const duration = ref(0)

const voiceSeconds = computed(() => {
  const s = Number(props.msg?.voiceSeconds)
  return Number.isFinite(s) && s > 0 ? s : 0
})

const secLabel = computed(() => `${Math.round(duration.value || voiceSeconds.value)}″`)

const progress = computed(() => {
  const d = duration.value || voiceSeconds.value
  if (!d || !current.value) return 0
  return Math.min(100, Math.round((current.value / d) * 100))
})

function togglePlay() {
  const el = audioRef.value
  if (!el) return
  if (el.paused) {
    el.play().catch(() => message.error('语音播放失败，请稍后重试'))
  } else {
    el.pause()
  }
}

function onPlay() { playing.value = true }
function onPause() { playing.value = false }
function onEnded() { playing.value = false; current.value = 0 }
function onTime(e) {
  current.value = e.target?.currentTime || 0
  if (e.target?.duration && Number.isFinite(e.target.duration)) duration.value = e.target.duration
}
// 加载失败静默复位（自动提示会在每条坏消息渲染时重复弹出，只在播放动作失败时提示）
function onAudioError() { playing.value = false; current.value = 0 }
</script>

<style scoped>
.mm { min-width: 0; }
.mm-fallback { font-size: 13.5px; opacity: .85; }

/* ===== 图片 ===== */
.mm-image { display: block; max-width: 200px; border-radius: 10px; overflow: hidden; line-height: 0; }
.mm-image :deep(img) { display: block; max-width: 200px; max-height: 260px; width: auto; height: auto;
  border-radius: 10px; }

/* ===== 文件卡片：currentColor 系，白/渐变气泡底色都可读 ===== */
.mm-file { display: flex; align-items: center; gap: 10px; width: 236px; max-width: 100%;
  padding: 10px 12px; border-radius: 12px; text-decoration: none; color: inherit;
  background: rgba(127, 127, 127, .1);
  border: 1px solid rgba(127, 127, 127, .3);
  border-color: color-mix(in srgb, currentColor 30%, transparent);
  transition: background .15s ease; }
.mm-file:hover { background: color-mix(in srgb, currentColor 14%, transparent); }
.mm-file-ico { position: relative; width: 38px; height: 38px; border-radius: 10px; flex-shrink: 0;
  display: grid; place-items: center; color: inherit;
  background: color-mix(in srgb, currentColor 12%, transparent); }
.mm-file-meta { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.mm-file-name { font-size: 12.5px; font-weight: 600; line-height: 1.4;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mm-file-size { font-size: 11px; opacity: .68; }
.mm-file-dl { flex-shrink: 0; opacity: .72; }

/* ===== 语音播放条 ===== */
.mm-voice { display: flex; align-items: center; gap: 10px; width: 208px; max-width: 100%;
  padding: 8px 12px; }
.mm-voice-btn { width: 28px; height: 28px; padding: 0; border-radius: 50%; flex-shrink: 0;
  border: 1.5px solid currentColor; background: transparent; color: inherit;
  display: grid; place-items: center; cursor: pointer; transition: background .15s ease; }
.mm-voice-btn:hover { background: color-mix(in srgb, currentColor 10%, transparent); }
.mm-voice-sec { font-size: 12px; font-weight: 700; flex-shrink: 0; font-variant-numeric: tabular-nums; }
.mm-voice-track { position: relative; flex: 1; height: 4px; border-radius: 999px; overflow: hidden;
  background: rgba(127, 127, 127, .25);
  background: color-mix(in srgb, currentColor 18%, transparent); }
.mm-voice-bar { position: absolute; left: 0; top: 0; bottom: 0; border-radius: 999px;
  background: currentColor; transition: width .12s linear; }
.mm-audio { display: none; }
</style>

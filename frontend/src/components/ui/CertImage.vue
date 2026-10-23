<template>
  <span class="cert-image" :class="[`cert-image--${kind}`, `cert-image--${size}`, { 'is-fallback': !showImage }]">
    <img
      v-if="showImage"
      :src="src"
      :alt="alt"
      class="cert-image__img"
      loading="lazy"
      decoding="async"
      @error="onError"
    />
    <!-- 回退层：无原件 / 原件加载失败时替代破图，明确区分两种状态 -->
    <span v-else class="cert-image__fallback" role="img" :aria-label="failure ? errorText : emptyText">
      <svg class="cert-image__glyph" viewBox="0 0 24 24" aria-hidden="true">
        <path d="M6 2.8h8.2l3.8 3.8v14.6H6z" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round" />
        <path d="M14.2 2.8v3.8h3.8" fill="none" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round" />
        <path v-if="kind === 'tax'" d="M8.6 11.4h6.8M8.6 14.2h6.8M8.6 17h4.2" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" />
        <path v-else-if="kind === 'permit'" d="M8.6 11.4h6.8M8.6 14.2h4.6" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" />
        <path v-else d="M8.6 11.4h6.8M8.6 14.2h6.8M8.6 17h3.6" fill="none" stroke="currentColor" stroke-width="1.2" stroke-linecap="round" />
        <circle cx="16.1" cy="16.6" r="2.7" fill="none" stroke="currentColor" stroke-width="1.2" stroke-dasharray="1.6 1.3" />
      </svg>
      <span class="cert-image__label">{{ failure ? errorText : emptyText }}</span>
    </span>
  </span>
</template>

<script setup>
import { computed, ref, watch } from 'vue'

/**
 * 证照图片 + 回退机制。
 *  - src 为空（未上传）→ 渲染占位块，文案取 emptyText；
 *  - src 加载失败（破图 / 404 / 存储不可用）→ 切换为占位块，文案取 errorText；
 *  - src 变化（重新上传 / 换商户）时自动复位失败标记，避免一次失败永久兜底。
 */
const props = defineProps({
  src: { type: String, default: '' },
  alt: { type: String, default: '证照原件' },
  /** license 营业执照 | tax 完税凭证 | permit 特殊经营许可证 */
  kind: { type: String, default: 'license' },
  /** thumb 缩略图 | medium 中图 | large 大图 */
  size: { type: String, default: 'medium' },
  emptyText: { type: String, default: '原件未上传' },
  errorText: { type: String, default: '原件加载失败' },
  /** 透传给 img 的额外样式（如管理员双屏核验的旋转） */
  imgStyle: { type: Object, default: () => ({}) }
})

const failure = ref(false)
const showImage = computed(() => Boolean(props.src) && !failure.value)

watch(() => props.src, () => { failure.value = false })

function onError() { failure.value = true }
</script>

<style scoped>
.cert-image { display: block; position: relative; overflow: hidden; border-radius: 8px; background: #f1f5f9; }
.cert-image__img { display: block; width: 100%; height: 100%; object-fit: contain; }
.cert-image--thumb { width: 44px; height: 44px; flex: 0 0 44px; }
.cert-image--thumb .cert-image__img { object-fit: cover; }
.cert-image--medium { width: 90px; height: 68px; }
.cert-image--large { width: 100%; min-height: 220px; }

.cert-image__fallback {
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 4px;
  width: 100%; height: 100%; min-height: inherit; padding: 6px;
  color: #94a3b8; background:
    repeating-linear-gradient(45deg, #f8fafc 0 10px, #f1f5f9 10px 20px);
  text-align: center;
}
.cert-image__glyph { width: 26px; height: 26px; flex: none; opacity: .85; }
.cert-image__label { font-size: 11px; line-height: 1.35; color: #64748b; }
.cert-image--thumb .cert-image__glyph { width: 18px; height: 18px; }
.cert-image--thumb .cert-image__label { display: none; }
.cert-image--large .cert-image__glyph { width: 40px; height: 40px; }
.cert-image--large .cert-image__label { font-size: 12px; }
</style>

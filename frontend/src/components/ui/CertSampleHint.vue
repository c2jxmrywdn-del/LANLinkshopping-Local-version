<template>
  <div class="cert-sample" :class="`cert-sample--${kind}`">
    <!-- 示例照片：点击可放大查看，用于指导申请人「合格原件长什么样」 -->
    <div class="cert-sample__photo">
      <a-image :src="sample.src" :alt="sample.alt" :preview="sample.fullSrc ? { src: sample.fullSrc } : true" />
    </div>
    <div class="cert-sample__body">
      <div class="cert-sample__title">
        <span class="cert-sample__badge">示例</span>
        {{ sample.title }}
      </div>
      <p class="cert-sample__desc">{{ sample.desc }}</p>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { certSampleOf } from '../../utils/certSamples'

/**
 * 证照示例照片（营业执照 / 完税凭证 / 特殊经营许可证）。
 * 只做填写引导，不参与真实材料校验；图片为本地静态资源，离线可用。
 */
const props = defineProps({
  /** license | tax | permit */
  kind: { type: String, default: 'license' }
})

const sample = computed(() => certSampleOf(props.kind))
</script>

<style scoped>
.cert-sample {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 12px; margin-top: 10px;
  border: 1px dashed #cbd5e1; border-radius: 10px;
  background: #fff;
}
.cert-sample__photo {
  flex: none; width: 150px; height: 112px;
  border-radius: 6px; overflow: hidden;
}
/* 用外层容器定尺寸：竖版凭证与横版执照都按 cover 裁切，避免默认 fill 造成压扁 */
.cert-sample__photo :deep(.ant-image) { display: block; width: 100%; height: 100%; }
.cert-sample__photo :deep(.ant-image-img) { width: 100% !important; height: 100% !important; object-fit: cover; }
/* 完税证明示例用 contain：完整保留票面与「企业所得税」行，避免 cover 裁切 */
.cert-sample--tax .cert-sample__photo :deep(.ant-image-img) {
  object-fit: contain;
  background: #fff;
}
.cert-sample__body { min-width: 0; }
.cert-sample__title { font-size: 13px; font-weight: 700; color: #334155; display: flex; align-items: center; gap: 6px; }
.cert-sample__badge {
  flex: none; padding: 1px 6px; border-radius: 999px;
  font-size: 10px; font-weight: 800; letter-spacing: .4px;
  color: #92400e; background: #fef3c7; border: 1px solid #fde68a;
}
.cert-sample__desc { margin: 4px 0 0; font-size: 12px; line-height: 1.55; color: #64748b; }

@media (max-width: 480px) {
  .cert-sample { align-items: flex-start; }
  .cert-sample__photo { width: 108px; height: 81px; }
}
</style>

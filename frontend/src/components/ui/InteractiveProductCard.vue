<template>
  <article
    ref="cardRef"
    class="interactive-product-card"
    :class="{ 'interactive-product-card--clickable': clickable }"
    :style="tiltStyle"
    :role="clickable ? 'link' : undefined"
    :tabindex="clickable ? 0 : undefined"
    :aria-label="clickable ? `查看商品：${title}` : undefined"
    @pointermove="handlePointerMove"
    @pointerleave="resetTilt"
    @pointercancel="resetTilt"
    @click="emit('click', $event)"
    @keydown.enter="activate"
    @keydown.space="activate"
  >
    <img
      v-if="imageUrl"
      class="interactive-product-card__image"
      :src="imageUrl"
      :alt="title"
      loading="lazy"
      decoding="async"
      draggable="false"
    />
    <div v-else class="interactive-product-card__placeholder" aria-hidden="true">
      <span>{{ title.slice(0, 2) }}</span>
    </div>

    <div class="interactive-product-card__scrim" aria-hidden="true" />

    <div class="interactive-product-card__content">
      <header class="interactive-product-card__glass">
        <div class="interactive-product-card__copy">
          <h3 class="interactive-product-card__title" :title="title">{{ title }}</h3>
          <p class="interactive-product-card__description">{{ description || 'LANLINK SELECTION' }}</p>
        </div>
        <img v-if="logoUrl" class="interactive-product-card__logo" :src="logoUrl" alt="" loading="lazy" />
        <span v-else class="interactive-product-card__logo-mark" aria-label="LANLinkshopping">LL</span>
      </header>

      <div class="interactive-product-card__price">{{ price }}</div>

      <div class="interactive-product-card__bottom">
        <div v-if="$slots.footer" class="interactive-product-card__footer">
          <slot name="footer" />
        </div>
        <div v-if="showPagination" class="interactive-product-card__dots" aria-hidden="true">
          <span class="is-active" />
          <span />
          <span />
          <span />
        </div>
      </div>
    </div>
  </article>
</template>

<script setup>
import { ref } from 'vue'

defineProps({
  imageUrl: { type: String, default: '' },
  logoUrl: { type: String, default: '' },
  title: { type: String, required: true },
  description: { type: String, default: '' },
  price: { type: [String, Number], default: '' },
  clickable: { type: Boolean, default: true },
  showPagination: { type: Boolean, default: true }
})

const emit = defineEmits(['click'])
const cardRef = ref(null)
const tiltStyle = ref({})

function handlePointerMove(event) {
  if (event.pointerType === 'touch' || window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return
  const card = cardRef.value
  if (!card) return

  const { left, top, width, height } = card.getBoundingClientRect()
  if (!width || !height) return
  const x = event.clientX - left
  const y = event.clientY - top
  const rotateX = ((y / height) - 0.5) * -16
  const rotateY = ((x / width) - 0.5) * 16

  tiltStyle.value = {
    transform: `perspective(1000px) rotateX(${rotateX}deg) rotateY(${rotateY}deg) scale3d(1.035, 1.035, 1.035)`,
    transition: 'transform 100ms ease-out'
  }
}

function resetTilt() {
  tiltStyle.value = {
    transform: 'perspective(1000px) rotateX(0deg) rotateY(0deg) scale3d(1, 1, 1)',
    transition: 'transform 400ms ease-in-out'
  }
}

function activate(event) {
  // Keyboard activation is only for the card itself, never nested CTA buttons.
  if (event.target !== cardRef.value || event.currentTarget !== cardRef.value) return
  event.preventDefault()
  emit('click', event)
}
</script>

<style scoped>
.interactive-product-card {
  position: relative;
  display: block;
  width: 100%;
  min-width: 0;
  min-height: 320px;
  aspect-ratio: 3 / 4;
  overflow: hidden;
  isolation: isolate;
  border: 3px solid var(--ll-brand-red, #9B2D20);
  border-radius: 24px;
  background: var(--ll-brand-red, #9B2D20);
  box-shadow: 0 16px 34px rgba(24, 33, 47, .16);
  transform: perspective(1000px) rotateX(0deg) rotateY(0deg) scale3d(1, 1, 1);
  transform-style: preserve-3d;
  transition: transform 400ms ease-in-out, box-shadow 250ms ease;
  will-change: transform;
}
.interactive-product-card--clickable { cursor: pointer; }
.interactive-product-card--clickable:focus-visible {
  outline: 3px solid var(--ll-brand-gold, #C8A45C);
  outline-offset: 4px;
}
.interactive-product-card__image,
.interactive-product-card__placeholder {
  position: absolute;
  inset: 3px;
  width: calc(100% - 6px);
  height: calc(100% - 6px);
  border-radius: 19px;
}
.interactive-product-card__image {
  object-fit: cover;
  object-position: center;
  transform: translateZ(-20px) scale(1.1);
  transition: transform 350ms ease;
  user-select: none;
}
.interactive-product-card:hover .interactive-product-card__image {
  transform: translateZ(-20px) scale(1.14);
}
.interactive-product-card__placeholder {
  display: grid;
  place-items: center;
  color: rgba(255, 255, 255, .82);
  background:
    radial-gradient(circle at 72% 24%, rgba(200, 164, 92, .5), transparent 35%),
    linear-gradient(135deg, #1F3864, #9B2D20);
  font-size: 56px;
  font-weight: 800;
  letter-spacing: -.06em;
}
.interactive-product-card__scrim {
  position: absolute;
  inset: 3px;
  border-radius: 19px;
  background: linear-gradient(180deg, rgba(11, 17, 27, .12) 0%, rgba(11, 17, 27, .03) 28%, rgba(11, 17, 27, .52) 100%);
  pointer-events: none;
}
.interactive-product-card__content {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  padding: 18px 17px 14px;
  transform-style: preserve-3d;
}
.interactive-product-card__glass {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  min-width: 0;
  padding: 14px 13px;
  border: 1px solid rgba(255, 255, 255, .2);
  border-radius: 16px;
  background: rgba(255, 255, 255, .12);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, .12);
  backdrop-filter: blur(16px) saturate(140%);
  -webkit-backdrop-filter: blur(16px) saturate(140%);
  transform: translateZ(40px);
}
.interactive-product-card__copy { min-width: 0; }
.interactive-product-card__title {
  margin: 0;
  color: #fff;
  font-size: clamp(15px, 1.25vw, 21px);
  font-weight: 800;
  line-height: 1.25;
  letter-spacing: -.035em;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  overflow-wrap: anywhere;
}
.interactive-product-card__description {
  margin: 5px 0 0;
  color: rgba(255, 255, 255, .78);
  font-size: 12px;
  line-height: 1.45;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
}
.interactive-product-card__logo {
  flex: 0 0 auto;
  max-width: 54px;
  max-height: 28px;
  object-fit: contain;
}
.interactive-product-card__logo-mark {
  display: grid;
  flex: 0 0 31px;
  width: 31px;
  height: 31px;
  place-items: center;
  border-radius: 9px;
  color: var(--ll-brand-base, #13233A);
  background: rgba(255, 255, 255, .9);
  font-size: 11px;
  font-weight: 900;
  letter-spacing: -.06em;
}
.interactive-product-card__price {
  align-self: flex-start;
  margin-top: 14px;
  padding: 8px 16px;
  border: 1px solid rgba(255, 255, 255, .16);
  border-radius: 999px;
  color: #fff;
  background: rgba(86, 15, 24, .72);
  box-shadow: 0 6px 16px rgba(11, 17, 27, .16);
  font-size: 16px;
  font-weight: 850;
  line-height: 1.2;
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  transform: translateZ(44px);
}
.interactive-product-card__bottom {
  margin-top: auto;
  transform: translateZ(35px);
}
.interactive-product-card__footer { display: flex; flex-direction: column; gap: 7px; }
.interactive-product-card__dots {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
}
.interactive-product-card__dots span {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: rgba(255, 255, 255, .35);
}
.interactive-product-card__dots span.is-active { background: #fff; }
@media (max-width: 1100px) {
  .interactive-product-card { min-height: 300px; border-radius: 20px; }
  .interactive-product-card__content { padding: 14px 12px 12px; }
  .interactive-product-card__glass { padding: 11px 10px; }
}
@media (max-width: 600px) {
  .interactive-product-card { min-height: 330px; aspect-ratio: auto; }
  .interactive-product-card__title { font-size: 19px; }
}
@media (hover: none) {
  .interactive-product-card { transform: none !important; transition: box-shadow 250ms ease; }
  .interactive-product-card__image { transform: scale(1.04); }
}
@media (prefers-reduced-motion: reduce) {
  .interactive-product-card, .interactive-product-card__image { transition: none !important; }
  .interactive-product-card:hover .interactive-product-card__image { transform: translateZ(-20px) scale(1.1); }
}
</style>

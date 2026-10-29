<template>
  <div class="apple-cards-carousel-root">
    <!-- 顶部标题与控制栏 -->
    <div v-if="showHeader" class="carousel-header">
      <div class="header-titles">
        <span v-if="subtitle" class="carousel-subtitle">{{ subtitle }}</span>
        <h2 class="carousel-main-title">{{ title }}</h2>
      </div>
      <div class="carousel-nav-buttons">
        <button
          type="button"
          class="nav-btn"
          :disabled="!canScrollLeft"
          aria-label="向左滚动"
          @click="scroll('left')"
        >
          <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="2.2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M15 19l-7-7 7-7" />
          </svg>
        </button>
        <button
          type="button"
          class="nav-btn"
          :disabled="!canScrollRight"
          aria-label="向右滚动"
          @click="scroll('right')"
        >
          <svg viewBox="0 0 24 24" class="size-5" fill="none" stroke="currentColor" stroke-width="2.2">
            <path stroke-linecap="round" stroke-linejoin="round" d="M9 5l7 7-7 7" />
          </svg>
        </button>
      </div>
    </div>

    <!-- 卡片横向滚动轨道 (移动端支持原生弹性滑动手势) -->
    <div
      ref="scrollContainerRef"
      class="carousel-scroll-container hide-scrollbar"
      @scroll="checkScroll"
    >
      <div class="carousel-track">
        <div
          v-for="(card, index) in items"
          :key="card.id || index"
          class="carousel-card-item"
          @click="openModal(card, index)"
        >
          <!-- 卡片外层质感容器 -->
          <div class="apple-card">
            <!-- 封面背景图 (具备优雅加载与默认底图) -->
            <img
              :src="card.src || card.image || defaultPlaceholder"
              :alt="card.title"
              class="card-bg-img"
              loading="lazy"
              @error="onImgError"
            />
            
            <!-- 沉浸式暗影渐变遮罩 -->
            <div class="card-overlay"></div>

            <!-- 卡片顶部信息 -->
            <div class="card-top-content">
              <span v-if="card.category" class="card-category">{{ card.category }}</span>
              <span v-if="card.badge" class="card-badge">{{ card.badge }}</span>
            </div>

            <!-- 卡片底部标题与副标 -->
            <div class="card-bottom-content">
              <h3 class="card-title">{{ card.title }}</h3>
              <p v-if="card.tagline || card.description" class="card-tagline">
                {{ card.tagline || card.description }}
              </p>
              
              <div v-if="card.price !== undefined" class="card-price-row">
                <span class="card-price-symbol">¥</span>
                <span class="card-price-val">{{ card.price }}</span>
                <span v-if="card.originalPrice" class="card-original-price">¥{{ card.originalPrice }}</span>
              </div>
            </div>

            <!-- 展开提示胶囊 (移动端常驻展示，桌面端悬停展开) -->
            <div class="card-expand-pill" aria-hidden="true">
              <span>探索详情</span>
              <svg viewBox="0 0 24 24" class="size-3.5" fill="none" stroke="currentColor" stroke-width="2.5">
                <path stroke-linecap="round" stroke-linejoin="round" d="M9 5l7 7-7 7" />
              </svg>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Apple 风格沉浸式故事 Modal (移动端自适应为触控抽屉 Bottom-Sheet) -->
    <Teleport to="body">
      <Transition name="apple-modal-fade">
        <div
          v-if="activeCard"
          class="apple-modal-backdrop"
          @click.self="closeModal"
        >
          <div
            class="apple-modal-dialog"
            role="dialog"
            aria-modal="true"
            @click.stop
          >
            <!-- 移动端顶部下拉把手 (Sheet Drag Handle) -->
            <div class="modal-drag-handle" aria-hidden="true"></div>

            <!-- 圆形关闭按钮 (移动端大触控区) -->
            <button
              class="modal-close-btn"
              type="button"
              aria-label="关闭详情"
              @click="closeModal"
            >
              <svg viewBox="0 0 24 24" class="size-4" fill="none" stroke="currentColor" stroke-width="2.5">
                <path stroke-linecap="round" stroke-linejoin="round" d="M6 18L18 6M6 6l12 12" />
              </svg>
            </button>

            <!-- 顶部大幅视觉区域 -->
            <div class="modal-hero">
              <img
                :src="activeCard.src || activeCard.image || defaultPlaceholder"
                :alt="activeCard.title"
                class="modal-hero-img"
              />
              <div class="modal-hero-overlay"></div>
              <div class="modal-hero-content">
                <span v-if="activeCard.category" class="modal-category">{{ activeCard.category }}</span>
                <h2 class="modal-hero-title">{{ activeCard.title }}</h2>
                <p v-if="activeCard.tagline" class="modal-hero-tagline">{{ activeCard.tagline }}</p>
              </div>
            </div>

            <!-- 详情主体内容 -->
            <div class="modal-body">
              <slot name="card-content" :card="activeCard">
                <div class="modal-section">
                  <h4 class="modal-section-title">核心业务详情</h4>
                  <p class="modal-desc">
                    {{ activeCard.content || activeCard.description || '官方签约认证供应链正品，支持大批量企业采购、增值税专用发票开具及企业信用账期月结服务。' }}
                  </p>
                </div>

                <!-- 特性列表 -->
                <div v-if="activeCard.features && activeCard.features.length" class="modal-features-grid">
                  <div
                    v-for="(feat, fIdx) in activeCard.features"
                    :key="fIdx"
                    class="feature-pill"
                  >
                    <svg viewBox="0 0 24 24" class="size-4 text-sky-500 shrink-0" fill="none" stroke="currentColor" stroke-width="2">
                      <path stroke-linecap="round" stroke-linejoin="round" d="M5 13l4 4L19 7" />
                    </svg>
                    <span>{{ feat }}</span>
                  </div>
                </div>

                <!-- 底部价格与真实业务操作栏 (移动端固定底栏吸底) -->
                <div v-if="activeCard.price !== undefined" class="modal-pricing-box">
                  <div class="price-info">
                    <span class="price-label">官方采购价</span>
                    <div class="price-figures">
                      <span class="currency">¥</span>
                      <span class="amount">{{ activeCard.price }}</span>
                      <span v-if="activeCard.originalPrice" class="orig-amount">¥{{ activeCard.originalPrice }}</span>
                    </div>
                  </div>
                  <div class="action-wrap">
                    <button
                      v-if="activeCard.showAddToCart !== false"
                      type="button"
                      class="modal-secondary-btn"
                      @click="handleAddToCart(activeCard)"
                    >
                      <svg viewBox="0 0 24 24" class="btn-svg-icon" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <circle cx="8" cy="21" r="1" />
                        <circle cx="19" cy="21" r="1" />
                        <path d="M2.05 2.05h2l2.66 12.42a2 2 0 0 0 2 1.58h9.78a2 2 0 0 0 1.95-1.57l1.65-7.43H5.12" />
                      </svg>
                      <span>加入购物车</span>
                    </button>
                    <button
                      type="button"
                      class="modal-buy-btn"
                      @click="handleCardAction(activeCard)"
                    >
                      <svg viewBox="0 0 24 24" class="btn-svg-icon" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <rect width="20" height="14" x="2" y="5" rx="2" />
                        <line x1="2" x2="22" y1="10" y2="10" />
                      </svg>
                      <span>{{ activeCard.buttonText || '立即采购下单' }}</span>
                    </button>
                  </div>
                </div>
              </slot>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup>
/**
 * AppleCardsCarousel.vue — 苹果风格大促画廊轮播卡片 (支持全端适配)
 * 灵感源: 21st.dev Aceternity UI (apple-cards-carousel.tsx)
 *
 * 特性:
 * - 纯 Vue 3 原生实现，零 framer-motion / 第三方依赖
 * - 移动端自适应: 支持触控手势滑动 (scroll-snap-type)，小屏自适应为 iOS Bottom Sheet 底部抽屉
 * - 真实业务闭环: 在 Modal 内内置【加入购物车】与【立即采购下单】真实业务动作
 * - 纯正 Apple Human Interface Guidelines 字体、间距与毛玻璃液态折射
 */
import { ref, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'

const props = defineProps({
  /** 卡片数据列表 */
  items: {
    type: Array,
    required: true
  },
  /** 主标题 */
  title: {
    type: String,
    default: 'Apple 风格精品甄选'
  },
  /** 副标题 */
  subtitle: {
    type: String,
    default: 'LANLINK COMMERCE'
  },
  /** 是否展示顶部标题栏 */
  showHeader: {
    type: Boolean,
    default: true
  },
  /** 每次滚动的像素步进 */
  scrollStep: {
    type: Number,
    default: 340
  }
})

const emit = defineEmits(['select', 'card-click', 'card-action', 'add-cart'])

const scrollContainerRef = ref(null)
const canScrollLeft = ref(false)
const canScrollRight = ref(true)
const activeCard = ref(null)
const activeIndex = ref(-1)

const defaultPlaceholder = 'https://images.unsplash.com/photo-1581092160607-ee22621dd758?q=80&w=800&auto=format&fit=crop'

function onImgError(e) {
  e.target.src = defaultPlaceholder
}

function checkScroll() {
  if (!scrollContainerRef.value) return
  const { scrollLeft, scrollWidth, clientWidth } = scrollContainerRef.value
  canScrollLeft.value = scrollLeft > 10
  canScrollRight.value = scrollLeft < scrollWidth - clientWidth - 10
}

function scroll(direction) {
  if (!scrollContainerRef.value) return
  const offset = direction === 'left' ? -props.scrollStep : props.scrollStep
  scrollContainerRef.value.scrollBy({ left: offset, behavior: 'smooth' })
}

// 卡片数据异步到达 / 容器尺寸变化后重新计算翻页可用态，
// 否则挂载时列表为空会把「向右」永久置灰，导致翻页按钮失效。
watch(() => props.items, () => nextTick(checkScroll), { deep: false })

let resizeObserver = null

function onResize() {
  checkScroll()
}

function openModal(card, index) {
  activeCard.value = card
  activeIndex.value = index
  emit('select', { card, index })
  emit('card-click', { card, index })
  document.body.style.overflow = 'hidden'
  window.addEventListener('keydown', onKeyDown)
}

function closeModal() {
  activeCard.value = null
  activeIndex.value = -1
  document.body.style.overflow = ''
  window.removeEventListener('keydown', onKeyDown)
}

function onKeyDown(e) {
  if (e.key === 'Escape') {
    closeModal()
  }
}

function handleCardAction(card) {
  emit('card-action', card)
  closeModal()
}

function handleAddToCart(card) {
  emit('add-cart', card)
  closeModal()
}

onMounted(() => {
  nextTick(() => {
    checkScroll()
    if (scrollContainerRef.value && typeof ResizeObserver !== 'undefined') {
      resizeObserver = new ResizeObserver(onResize)
      resizeObserver.observe(scrollContainerRef.value)
      if (scrollContainerRef.value.firstElementChild) {
        resizeObserver.observe(scrollContainerRef.value.firstElementChild)
      }
    }
  })
})

onBeforeUnmount(() => {
  if (resizeObserver) {
    resizeObserver.disconnect()
    resizeObserver = null
  }
  document.body.style.overflow = ''
  window.removeEventListener('keydown', onKeyDown)
})
</script>

<style scoped>
.apple-cards-carousel-root {
  width: 100%;
  position: relative;
  margin: 1.25rem 0;
}

/* 顶部标题与导航 */
.carousel-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  margin-bottom: 1rem;
  padding: 0 0.5rem;
}

.carousel-subtitle {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: #0284c7;
  display: block;
  margin-bottom: 4px;
}
:global(.dark) .carousel-subtitle {
  color: #38bdf8;
}

.carousel-main-title {
  font-size: 1.65rem;
  font-weight: 700;
  letter-spacing: -0.025em;
  color: #0f172a;
  margin: 0;
  line-height: 1.2;
}
:global(.dark) .carousel-main-title {
  color: #f8fafc;
}

.carousel-nav-buttons {
  display: flex;
  align-items: center;
  gap: 8px;
}

.nav-btn {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.06);
  color: #334155;
  border: none;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
:global(.dark) .nav-btn {
  background: rgba(255, 255, 255, 0.1);
  color: #f1f5f9;
}

.nav-btn:hover:not(:disabled) {
  background: rgba(0, 0, 0, 0.12);
  transform: scale(1.05);
}
:global(.dark) .nav-btn:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.2);
}

.nav-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
  transform: none;
}

/* 滚动容器 */
.carousel-scroll-container {
  overflow-x: auto;
  scroll-behavior: smooth;
  scroll-snap-type: x mandatory;
  padding: 6px 4px 18px;
  -webkit-overflow-scrolling: touch;
}

.hide-scrollbar::-webkit-scrollbar {
  display: none;
}
.hide-scrollbar {
  -ms-overflow-style: none;
  scrollbar-width: none;
}

.carousel-track {
  display: flex;
  gap: 18px;
  width: max-content;
}

/* 单个卡片包装 */
.carousel-card-item {
  scroll-snap-align: center;
  flex-shrink: 0;
}

/* Apple 经典卡片设计 (手机端自适应缩放) */
.apple-card {
  position: relative;
  width: 320px;
  height: 440px;
  border-radius: 26px;
  overflow: hidden;
  cursor: pointer;
  background-color: #0f172a;
  
  box-shadow:
    0 12px 30px -4px rgba(0, 0, 0, 0.15),
    0 4px 12px 0 rgba(0, 0, 0, 0.08);

  transition:
    transform 0.4s cubic-bezier(0.16, 1, 0.3, 1),
    box-shadow 0.4s cubic-bezier(0.16, 1, 0.3, 1);
}

@media (min-width: 768px) {
  .apple-card {
    width: 340px;
    height: 460px;
  }
}

.apple-card:hover {
  transform: scale(1.025) translateY(-4px);
  box-shadow:
    0 24px 48px -8px rgba(0, 0, 0, 0.28),
    0 8px 20px 0 rgba(0, 0, 0, 0.12);
}

.card-bg-img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: transform 0.6s cubic-bezier(0.16, 1, 0.3, 1);
}

.apple-card:hover .card-bg-img {
  transform: scale(1.06);
}

/* 顶部与底部双层暗调渐变 */
.card-overlay {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    180deg,
    rgba(0, 0, 0, 0.5) 0%,
    rgba(0, 0, 0, 0.05) 40%,
    rgba(0, 0, 0, 0.2) 60%,
    rgba(0, 0, 0, 0.88) 100%
  );
  pointer-events: none;
}

.card-top-content {
  position: absolute;
  top: 18px;
  left: 18px;
  right: 18px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  z-index: 2;
}

.card-category {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.9);
  background: rgba(0, 0, 0, 0.35);
  backdrop-filter: blur(10px);
  padding: 4px 10px;
  border-radius: 9999px;
  border: 1px solid rgba(255, 255, 255, 0.18);
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.card-badge {
  font-size: 11px;
  font-weight: 600;
  color: #38bdf8;
  background: rgba(56, 189, 248, 0.2);
  backdrop-filter: blur(10px);
  padding: 4px 10px;
  border-radius: 9999px;
  border: 1px solid rgba(56, 189, 248, 0.3);
}

.card-bottom-content {
  position: absolute;
  bottom: 22px;
  left: 20px;
  right: 20px;
  z-index: 2;
  color: #ffffff;
}

.card-title {
  font-size: 1.3rem;
  font-weight: 700;
  line-height: 1.25;
  color: #ffffff;
  margin: 0 0 6px;
  text-shadow: 0 2px 8px rgba(0, 0, 0, 0.45);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.card-tagline {
  font-size: 12.5px;
  color: rgba(255, 255, 255, 0.8);
  margin: 0 0 10px;
  line-height: 1.35;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.card-price-row {
  display: flex;
  align-items: baseline;
  gap: 3px;
}

.card-price-symbol {
  font-size: 13px;
  font-weight: 600;
  color: #38bdf8;
}

.card-price-val {
  font-size: 1.35rem;
  font-weight: 800;
  color: #ffffff;
}

.card-original-price {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.5);
  text-decoration: line-through;
  margin-left: 6px;
}

/* 展开提示胶囊 */
.card-expand-pill {
  position: absolute;
  bottom: 20px;
  right: 20px;
  display: flex;
  align-items: center;
  gap: 4px;
  background: rgba(255, 255, 255, 0.22);
  backdrop-filter: blur(12px);
  color: #ffffff;
  font-size: 11px;
  font-weight: 600;
  padding: 5px 11px;
  border-radius: 9999px;
  border: 1px solid rgba(255, 255, 255, 0.3);
  opacity: 0;
  transform: translateY(6px);
  transition: all 0.3s cubic-bezier(0.16, 1, 0.3, 1);
  z-index: 3;
}

.apple-card:hover .card-expand-pill {
  opacity: 1;
  transform: translateY(0);
}

/* ========== Apple 沉浸式 Modal ========== */
.apple-modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 9999;
  background: rgba(0, 0, 0, 0.65);
  backdrop-filter: blur(24px) saturate(180%);
  -webkit-backdrop-filter: blur(24px) saturate(180%);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1.5rem;
}

.apple-modal-dialog {
  position: relative;
  width: 100%;
  max-width: 680px;
  max-height: 85vh;
  background: #ffffff;
  border-radius: 30px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  box-shadow: 0 25px 60px -15px rgba(0, 0, 0, 0.5);
}
:global(.dark) .apple-modal-dialog {
  background: #111827;
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.modal-drag-handle {
  display: none;
}

.modal-close-btn {
  position: absolute;
  top: 18px;
  right: 18px;
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.55);
  backdrop-filter: blur(10px);
  color: #ffffff;
  border: 1px solid rgba(255, 255, 255, 0.2);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  z-index: 10;
  transition: all 0.2s;
}
.modal-close-btn:hover {
  background: rgba(0, 0, 0, 0.85);
  transform: scale(1.08);
}

.modal-hero {
  position: relative;
  width: 100%;
  height: 260px;
  overflow: hidden;
  flex-shrink: 0;
}

.modal-hero-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.modal-hero-overlay {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, rgba(0, 0, 0, 0.3) 0%, rgba(0, 0, 0, 0.85) 100%);
}

.modal-hero-content {
  position: absolute;
  bottom: 22px;
  left: 24px;
  right: 24px;
  color: #ffffff;
}

.modal-category {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: #38bdf8;
  display: block;
  margin-bottom: 4px;
}

.modal-hero-title {
  font-size: 1.7rem;
  font-weight: 800;
  margin: 0;
  line-height: 1.25;
}

.modal-hero-tagline {
  font-size: 13.5px;
  color: rgba(255, 255, 255, 0.85);
  margin: 6px 0 0;
}

.modal-body {
  padding: 22px 24px 26px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.modal-section-title {
  font-size: 14.5px;
  font-weight: 700;
  color: #0f172a;
  margin: 0 0 8px;
}
:global(.dark) .modal-section-title {
  color: #f1f5f9;
}

.modal-desc {
  font-size: 13.5px;
  line-height: 1.6;
  color: #475569;
  margin: 0;
}
:global(.dark) .modal-desc {
  color: #94a3b8;
}

.modal-features-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 10px;
}

.feature-pill {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: #334155;
  background: rgba(0, 0, 0, 0.03);
  padding: 9px 12px;
  border-radius: 12px;
  border: 1px solid rgba(0, 0, 0, 0.06);
}
:global(.dark) .feature-pill {
  color: #e2e8f0;
  background: rgba(255, 255, 255, 0.04);
  border-color: rgba(255, 255, 255, 0.08);
}

.modal-pricing-box {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #f8fafc;
  padding: 14px 18px;
  border-radius: 18px;
  border: 1px solid #e2e8f0;
  margin-top: 4px;
}
:global(.dark) .modal-pricing-box {
  background: rgba(255, 255, 255, 0.03);
  border-color: rgba(255, 255, 255, 0.08);
}

.price-label {
  font-size: 11px;
  color: #64748b;
  display: block;
}

.price-figures {
  display: flex;
  align-items: baseline;
  gap: 2px;
}

.price-figures .currency {
  font-size: 14px;
  font-weight: 700;
  color: #0284c7;
}

.price-figures .amount {
  font-size: 1.55rem;
  font-weight: 800;
  color: #0f172a;
}
:global(.dark) .price-figures .amount {
  color: #ffffff;
}

.orig-amount {
  font-size: 12px;
  color: #94a3b8;
  text-decoration: line-through;
  margin-left: 6px;
}

.action-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
}

.modal-secondary-btn {
  background: rgba(2, 132, 199, 0.08);
  color: #0284c7;
  font-size: 13.5px;
  font-weight: 600;
  padding: 9px 18px;
  border-radius: 9999px;
  border: 1px solid rgba(2, 132, 199, 0.25);
  cursor: pointer;
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
.modal-secondary-btn:hover {
  background: rgba(2, 132, 199, 0.16);
  transform: scale(1.02);
}
:global(.dark) .modal-secondary-btn {
  background: rgba(56, 189, 248, 0.12);
  color: #38bdf8;
  border-color: rgba(56, 189, 248, 0.3);
}

.btn-svg-icon {
  width: 15px;
  height: 15px;
  margin-right: 6px;
  display: inline-block;
  vertical-align: -2px;
  flex-shrink: 0;
}

.modal-buy-btn {
  background: #0284c7;
  color: #ffffff;
  font-size: 13.5px;
  font-weight: 600;
  padding: 9px 22px;
  border-radius: 9999px;
  border: none;
  cursor: pointer;
  box-shadow: 0 4px 12px rgba(2, 132, 199, 0.35);
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
.modal-buy-btn:hover {
  background: #0369a1;
  transform: scale(1.03);
}

/* Modal 动画淡入 */
.apple-modal-fade-enter-active,
.apple-modal-fade-leave-active {
  transition: opacity 0.35s cubic-bezier(0.16, 1, 0.3, 1);
}

.apple-modal-fade-enter-active .apple-modal-dialog,
.apple-modal-fade-leave-active .apple-modal-dialog {
  transition: transform 0.35s cubic-bezier(0.16, 1, 0.3, 1);
}

.apple-modal-fade-enter-from,
.apple-modal-fade-leave-to {
  opacity: 0;
}

.apple-modal-fade-enter-from .apple-modal-dialog {
  transform: scale(0.92) translateY(24px);
}
.apple-modal-fade-leave-to .apple-modal-dialog {
  transform: scale(0.96) translateY(12px);
}

/* ================= 移动端适配 (≤ 640px) ================= */
@media (max-width: 640px) {
  .carousel-header {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }
  .carousel-nav-buttons {
    display: none; /* 移动端靠自然触控拖拽，隐藏桌面翻页箭头节约空间 */
  }
  .carousel-main-title {
    font-size: 1.35rem;
  }

  /* 移动端卡片缩放 */
  .apple-card {
    width: min(290px, 82vw);
    height: 400px;
    border-radius: 22px;
  }
  .card-expand-pill {
    opacity: 0.92; /* 移动端常驻展示，提示用户可点击探索 */
    transform: none;
    bottom: 16px;
    right: 16px;
    padding: 4px 10px;
    font-size: 10.5px;
  }
  .card-bottom-content {
    bottom: 18px;
    left: 16px;
    right: 16px;
  }
  .card-title {
    font-size: 1.15rem;
  }

  /* 移动端 Modal 转换为 iOS 拟真底部抽屉 (Bottom Sheet) */
  .apple-modal-backdrop {
    padding: 0;
    align-items: flex-end;
  }
  .apple-modal-dialog {
    max-width: 100%;
    max-height: 90vh;
    border-radius: 24px 24px 0 0;
    margin: 0;
    padding-bottom: max(16px, env(safe-area-inset-bottom));
  }
  .modal-drag-handle {
    display: block;
    width: 36px;
    height: 4px;
    background: rgba(0, 0, 0, 0.2);
    border-radius: 9999px;
    margin: 10px auto 4px;
  }
  :global(.dark) .modal-drag-handle {
    background: rgba(255, 255, 255, 0.25);
  }
  .modal-hero {
    height: 190px;
  }
  .modal-hero-title {
    font-size: 1.35rem;
  }
  .modal-close-btn {
    top: 14px;
    right: 14px;
    width: 34px;
    height: 34px;
  }
  .modal-body {
    padding: 16px 18px 20px;
    gap: 14px;
  }
  .modal-features-grid {
    grid-template-columns: 1fr;
  }
  .modal-pricing-box {
    flex-direction: column;
    align-items: stretch;
    gap: 12px;
    position: sticky;
    bottom: 0;
    z-index: 20;
    background: rgba(255, 255, 255, 0.95);
    backdrop-filter: blur(16px);
    -webkit-backdrop-filter: blur(16px);
  }
  :global(.dark) .modal-pricing-box {
    background: rgba(17, 24, 39, 0.95);
  }
  .action-wrap {
    width: 100%;
    display: grid;
    grid-template-columns: 1fr 1.2fr;
    gap: 8px;
  }
  .modal-secondary-btn, .modal-buy-btn {
    text-align: center;
    padding: 10px 0;
    width: 100%;
  }

  .apple-modal-fade-enter-from .apple-modal-dialog,
  .apple-modal-fade-leave-to .apple-modal-dialog {
    transform: translateY(100%);
  }
}
</style>

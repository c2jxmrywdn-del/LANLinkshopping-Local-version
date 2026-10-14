<template>
  <div class="banner-wrap">
    <Swiper
      :modules="modules"
      :slides-per-view="1"
      :loop="true"
      :speed="700"
      :autoplay="{ delay: 3500, disableOnInteraction: false, pauseOnMouseEnter: true }"
      :pagination="{ clickable: true, dynamicBullets: true }"
      :navigation="true"
      :keyboard="{ enabled: true }"
      :grab-cursor="true"
      class="banner-swiper"
    >
      <SwiperSlide v-for="s in slides" :key="s.key">
        <div class="slide" :class="`slide-${s.key}`" @click="go(s)">
          <img :src="s.img" :alt="s.title" loading="lazy" decoding="async" />
          <div class="mask"></div>
          <div class="content">
            <h2>{{ s.title }}</h2>
            <p>{{ s.sub }}</p>
            <span class="cta">{{ s.cta }} →</span>
          </div>
        </div>
      </SwiperSlide>
    </Swiper>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { Swiper, SwiperSlide } from 'swiper/vue'
import { Autoplay, Pagination, Navigation, Keyboard } from 'swiper/modules'
import 'swiper/css'
import 'swiper/css/pagination'
import 'swiper/css/navigation'

const router = useRouter()
const modules = [Autoplay, Pagination, Navigation, Keyboard]

const slides = [
  {
    key: 'c',
    img: 'https://images.unsplash.com/photo-1780362507569-7c6e50287566?auto=format&fit=crop&w=1800&q=86',
    tag: '建筑行业',
    title: '建筑材料源头直供',
    sub: '钢材 · 管材 · 工程材料，规格与供应能力一站核验',
    cta: '进入建筑专区',
    indId: 1
  },
  {
    key: 't',
    img: 'https://images.unsplash.com/photo-1610891015188-5369212db097?auto=format&fit=crop&w=1800&q=86',
    tag: '纺织行业',
    title: '纺织制造与面料采购',
    sub: '面料 · 纱线 · 辅料，连接制造端与企业采购端',
    cta: '进入纺织专区',
    indId: 2
  },
  {
    key: 'p',
    img: 'https://images.unsplash.com/photo-1784914179675-0e6d7260dfd0?auto=format&fit=crop&w=1800&q=86',
    tag: '石化行业',
    title: '化工原料与产业供应',
    sub: '石化装置、工业管线与原料供应场景，突出合规采购',
    cta: '进入石化专区',
    indId: 3
  },
  {
    key: 'e',
    img: 'https://images.unsplash.com/photo-1780034766228-3fd70d9463c3?auto=format&fit=crop&w=1800&q=86',
    tag: '电子行业',
    title: '电子元器件与智能制造',
    sub: '工业控制 · 电子模块 · 核心器件，面向企业批量采购',
    cta: '进入电子专区',
    indId: 4
  },
  {
    key: 'g',
    img: 'https://images.unsplash.com/photo-1769355104335-acef3aa4c9b6?auto=format&fit=crop&w=1800&q=86',
    tag: '企业定制',
    title: '包装定制 · 批量交付',
    sub: '从包装物料到批量生产，适合企业礼赠与品牌定制采购',
    cta: '了解企业定制'
  },
  {
    key: 'q',
    img: 'https://images.unsplash.com/photo-1554224155-8d04cb21cd6c?auto=format&fit=crop&w=1800&q=86',
    tag: '企业账期',
    title: '企业账期管理 · 采购先享后付',
    sub: '授信额度 · 账期申请 · 到期提醒，让采购与回款节奏更清晰',
    cta: '查看账期服务',
    route: 'credit-term'
  },
  {
    key: 'f',
    img: 'https://images.unsplash.com/photo-1769144256227-5185141c3aca?auto=format&fit=crop&w=1800&q=86',
    tag: '全球供应链',
    title: '港口物流 · 采购协同',
    sub: '连接工厂、仓储、港口与采购需求，强化跨区域履约能力',
    cta: '了解供应链服务'
  }
]

function go(s) {
  if (s.route) router.push({ name: s.route })
  else if (s.indId) router.push({ name: 'mall', query: { indId: s.indId } })
  else router.push({ name: 'mall' })
}
</script>

<style scoped>
.banner-wrap { margin-bottom: 24px; border-radius: 14px; overflow: hidden; box-shadow: 0 6px 24px rgba(15,23,42,.12); }
.banner-swiper { width: 100%; }
.slide { position: relative; height: 320px; cursor: pointer; }
.slide img { width: 100%; height: 100%; object-fit: cover; object-position: center center; display: block; }
.slide-p img { object-position: center 100%; }
.mask { position: absolute; inset: 0; background: linear-gradient(90deg, rgba(15,23,42,.78) 0%, rgba(15,23,42,.35) 45%, rgba(15,23,42,0) 75%); }
.content { position: absolute; left: 0; bottom: 0; top: 0; display: flex; flex-direction: column; justify-content: center; padding: 0 48px; color: #fff; max-width: 62%; }
.content h2 { font-size: 30px; margin: 0 0 8px; font-weight: 800; text-shadow: 0 2px 10px rgba(0,0,0,.35); }
.content p { font-size: 15px; margin: 0 0 14px; opacity: .92; }
.cta { font-size: 14px; font-weight: 600; color: #ffb020; }
</style>

<style>
/* Swiper 全局控件主题化（品牌色） */
/* 圆点：默认隐藏，悬停轮播时才显示 */
.banner-swiper .swiper-pagination { opacity: 0; transition: opacity .25s ease; }
.banner-swiper:hover .swiper-pagination { opacity: 1; }
.banner-swiper .swiper-pagination-bullet { background: #cbd5e1; opacity: .8; }
.banner-swiper .swiper-pagination-bullet-active { background: #0ea5e9; opacity: 1; }

/* 箭头：无底衬，默认隐藏，悬停浮现，半透明，两侧垂直居中 */
.banner-swiper .swiper-button-next,
.banner-swiper .swiper-button-prev {
  width: auto; height: auto; margin-top: 0; border-radius: 0;
  top: 50%;
  background: none; box-shadow: none; backdrop-filter: none;
  color: rgba(255, 255, 255, .65);
  opacity: 0; pointer-events: none;
  transition: opacity .25s ease, transform .25s ease, color .2s ease;
}
.banner-swiper .swiper-button-prev { left: 16px; transform: translateY(-50%) translateX(-12px); }
.banner-swiper .swiper-button-next { right: 16px; transform: translateY(-50%) translateX(12px); }
.banner-swiper:hover .swiper-button-next,
.banner-swiper:hover .swiper-button-prev { opacity: 1; pointer-events: auto; }
.banner-swiper:hover .swiper-button-prev { transform: translateY(-50%) translateX(0); }
.banner-swiper:hover .swiper-button-next { transform: translateY(-50%) translateX(0); }
.banner-swiper .swiper-button-next:hover,
.banner-swiper .swiper-button-prev:hover { color: rgba(255, 255, 255, .95); }
.banner-swiper .swiper-button-next:after,
.banner-swiper .swiper-button-prev:after { font-size: 44px; font-weight: 700; }
</style>

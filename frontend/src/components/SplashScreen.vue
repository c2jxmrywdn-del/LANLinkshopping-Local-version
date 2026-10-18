<template>
  <transition name="ss-leave">
    <div class="ss-overlay notranslate" translate="no" v-show="visible">
      <div class="ss-bg" aria-hidden="true">
        <span class="ss-gradient-orb ss-orb-brick"></span>
        <span class="ss-gradient-orb ss-orb-gold"></span>
        <span class="ss-gradient-orb ss-orb-cyan"></span>
        <span class="ss-gradient-noise"></span>
        <!-- 液态金属玻璃光泽：沿 45°（左下 → 右上）缓慢扫过 -->
        <span class="ss-metal-sheen"></span>
      </div>
      <!-- 内容区压暗层：光泽扫过时保证文字对比度仍然达标 -->
      <span class="ss-content-scrim" aria-hidden="true"></span>

      <div class="ss-content">
        <BrandDecodeReveal class="ss-wordmark" size="clamp(38px, 5vw, 58px)" mode="soft" delay="1.55s" />
        <div class="ss-tagline">聚合 · 连接 · 一体多元解决方案</div>
        <div class="ss-progress ll-glass" aria-hidden="true">
          <span class="ll-glass__sheen"></span>
          <span class="ss-progress-track"><span class="ss-progress-bar"></span></span>
        </div>
      </div>

      <button class="ss-skip ll-glass ll-glass--interactive" @click="finish">
        <span class="ll-glass__sheen"></span>
        跳过 ›
      </button>
    </div>
  </transition>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import BrandDecodeReveal from './BrandDecodeReveal.vue'

const emit = defineEmits(['done'])
const visible = ref(true)
let timer = null

function finish() {
  if (!visible.value) return
  visible.value = false
  setTimeout(() => emit('done'), 650)
}

onMounted(() => {
  timer = setTimeout(finish, 2800)
})

onBeforeUnmount(() => clearTimeout(timer))
</script>

<style scoped>
.ss-overlay {
  position: fixed; inset: 0; z-index: 9999;
  display: flex; align-items: center; justify-content: center;
  overflow: hidden; isolation: isolate;
}
.ss-leave-leave-active { transition: opacity .65s ease, transform .65s cubic-bezier(.2,.8,.2,1); }
.ss-leave-leave-to { opacity: 0; transform: scale(1.035); }

.ss-bg {
  position: absolute; inset: 0; overflow: hidden;
  background:
    radial-gradient(90% 80% at 50% 45%, rgba(30,110,184,.12), transparent 64%),
    linear-gradient(135deg, var(--ll-brand-base) 0%, #101B2D 52%, var(--ll-brand-base-deep) 100%);
  animation: ssBgIn .8s ease both;
}
.ss-bg::after {
  content: ""; position: absolute; inset: -22%;
  background: conic-gradient(from 120deg at 50% 50%,
    rgba(155,45,32,.09),
    rgba(200,164,92,.12),
    rgba(30,110,184,.12),
    rgba(155,45,32,.09));
  filter: blur(56px);
  animation: ssColorShift var(--ll-brand-ambient-duration) ease-in-out infinite alternate;
}
.ss-gradient-orb {
  position: absolute; width: 560px; height: 560px; border-radius: 50%;
  filter: blur(48px); opacity: .18; mix-blend-mode: screen;
  will-change: transform, opacity;
}
.ss-orb-brick {
  left: -170px; top: 2%;
  background: radial-gradient(circle, rgba(155,45,32,.98) 0%, rgba(155,45,32,.42) 42%, transparent 72%);
  animation: ssOrbBrick 8.2s ease-in-out infinite alternate;
}
.ss-orb-gold {
  right: -140px; top: 15%;
  background: radial-gradient(circle, rgba(200,164,92,.94) 0%, rgba(200,164,92,.38) 45%, transparent 72%);
  animation: ssOrbGold 9.2s ease-in-out -2s infinite alternate;
}
.ss-orb-cyan {
  left: 30%; bottom: -280px;
  background: radial-gradient(circle, rgba(30,110,184,.96) 0%, rgba(30,110,184,.4) 45%, transparent 72%);
  animation: ssOrbCyan 8.7s ease-in-out -1s infinite alternate;
}
/* =====================================================
   柔光液态金属（低饱和 / 低反光）：亮带基准倾斜 45°（“/”向：左下角 → 右上角），
   整体以「从左往右」缓慢扫过，并在扫过中带一点极轻微的形态呼吸（scaleX），
   保留液态金属的流动感与形态过渡特性。

   四个关键点：
   ① 亮带倾斜必须用 135deg：linear-gradient(45deg) 的等相位线垂直于 45° 轴，
      亮带实际走向是 “\”（左上→右下）；要得到 “/” 向亮带必须用 135deg（等价 -45deg）。
   ② 位移只做水平方向（±115vw）：亮带整体沿“/”向刚性平移 → 视觉上就是从
      左往右缓慢扫过；若改回 45° 对角位移，光带会沿自身轴向滑动，看起来是静止的。
   ③ 必须叠一层沿 45° 轴（“/”向）渐隐的遮罩，把光带收成有限长度的斜向光带：
      既让光带明确"从左边进来、右边出去"，又让两端自然衰减（无生硬断层）。
   ④ 元素外扩到 340%/160%（inset: -30% -120%），大于位移幅度 ±115vw，
      保证动画全程元素自身边界都不进入视口 —— 否则会切出矩形硬边。

   柔光配方：色阶全部收敛为低饱和的暖白（不再是银白 + 金 + 冷蓝的高反差金属色），
   峰值反光强度从 .68 降到 .34，中段用 4 档渐变缓慢过渡、边缘衰减展开到 ±34vmax，
   整体呈现"柔光"而非"镜面高光"——避免刺眼反射，质感更温润细腻。
   ===================================================== */
.ss-metal-sheen {
  position: absolute; inset: -30% -120%;
  pointer-events: none;
  background-image: linear-gradient(135deg,
    transparent calc(50% - 34vmax),
    rgba(236, 233, 228, .07) calc(50% - 26vmax),
    rgba(245, 242, 237, .13) calc(50% - 17vmax),
    rgba(251, 249, 245, .21) calc(50% - 9vmax),
    rgba(255, 253, 250, .34) 50%,
    rgba(251, 249, 245, .21) calc(50% + 9vmax),
    rgba(245, 242, 237, .13) calc(50% + 17vmax),
    rgba(236, 233, 228, .07) calc(50% + 26vmax),
    transparent calc(50% + 34vmax));
  /* 遮罩：沿 45° 轴（“/”向）把光带两端渐隐，使光带成为有限长度的斜向光带 */
  -webkit-mask-image: linear-gradient(45deg,
    transparent calc(50% - 72vmax),
    rgba(0, 0, 0, .5) calc(50% - 52vmax),
    #000 calc(50% - 42vmax),
    #000 calc(50% + 42vmax),
    rgba(0, 0, 0, .5) calc(50% + 52vmax),
    transparent calc(50% + 72vmax));
  mask-image: linear-gradient(45deg,
    transparent calc(50% - 72vmax),
    rgba(0, 0, 0, .5) calc(50% - 52vmax),
    #000 calc(50% - 42vmax),
    #000 calc(50% + 42vmax),
    rgba(0, 0, 0, .5) calc(50% + 52vmax),
    transparent calc(50% + 72vmax));
  -webkit-mask-repeat: no-repeat;
  mask-repeat: no-repeat;
  mix-blend-mode: screen;
  will-change: transform;
  /* 线性缓动（不再用 ease-in-out）：保证光带自左至右全程匀速，
     经过 LOGO 区域及其周边时既不减速也不停留，一路匀速扫过去。
     起点/终点光带均在视口之外（见下方 keyframes 幅度取值），
     因此循环衔接点不可见，不存在可见的"停顿"节点。 */
  animation: ssMetalSweep 6s linear infinite;
}

/* 内容区压暗层：光泽峰值经过文字时的最不利情形下仍保证对比度 */
.ss-content-scrim {
  position: absolute; inset: 0; z-index: 1; pointer-events: none;
  background: radial-gradient(58% 44% at 50% 46%,
    rgba(6, 8, 30, .72) 0%,
    rgba(6, 8, 30, .46) 58%,
    rgba(6, 8, 30, 0) 100%);
}
.ss-gradient-noise {
  position: absolute; inset: 0; opacity: .04;
  background-image: radial-gradient(rgba(251,249,241,.55) .6px, transparent .6px);
  background-size: 4px 4px;
  animation: ssNoiseDrift 6s linear infinite;
}

.ss-content { position: relative; z-index: 2; text-align: center; }
.ss-wordmark {
  margin-top: 18px;
  opacity: 0;
  animation: ssFadeUp .45s .95s var(--ll-logo-ease) forwards;
}
/* =====================================================
   LOGO 液态金属光泽：background-clip:text 让金属渐变穿过字形，
   高光相位自左向右缓慢扫过（与背景扫光同向、同节奏：6s / 同缓动）。
   柔光化：把原先"纯白 ↔ 深银/深金"的高反差金属色阶换成低对比、低饱和的
   哑光银与低饱和香槟金（色阶极差从 #A9BCD4→#FFFFFF 收到 #C6CFDA→#F3F6F9），
   降低反光强度、去除刺眼高光，同时保留金属的明暗层次与温润质感。
   仅在 @supports 内启用：不支持时保留 BrandWordmark 原色，
   不会出现 color:transparent 导致的"文字消失"。
   ===================================================== */
@supports (background-clip: text) or (-webkit-background-clip: text) {
  .ss-wordmark :deep(.ll-lan),
  .ss-wordmark :deep(.ll-shopping) {
    color: transparent;
    background-image: linear-gradient(135deg,
      #D6DCE4 0%, #F1F4F8 18%, #C6CFDA 34%, #EDF1F6 50%,
      #D9E1E9 66%, #F3F6F9 82%, #C9D2DC 100%);
    background-size: 250% 250%;
    -webkit-background-clip: text;
    background-clip: text;
    animation: ssLogoSheen 6s linear infinite;
  }
  .ss-wordmark :deep(.ll-link) {
    color: transparent;
    background-image: linear-gradient(135deg,
      #C3AE82 0%, #E3D5B3 18%, #B9A476 34%, #DFD1AC 50%,
      #C8B88C 66%, #E6DABD 82%, #BDA97B 100%);
    background-size: 250% 250%;
    -webkit-background-clip: text;
    background-clip: text;
    animation: ssLogoSheen 6s linear infinite;
  }
}
.ss-tagline {
  margin-top: 8px; font-size: 14px; letter-spacing: 4px; color: #AEB7C4;
  opacity: 0; animation: ssFadeUp .7s 1.72s ease forwards;
}
/* 进度胶囊：改用液态光感玻璃材质（背板模糊 + 厚度内阴影 + 边环折射 + 流动高光） */
.ss-progress {
  --ll-glass-blur: 14px;
  position: relative; margin: 30px auto 0; width: 264px; height: 12px;
  opacity: 0; animation: ssFadeIn .4s 1s forwards;
}
.ss-progress-track { position: absolute; inset: 3px; border-radius: 999px; overflow: hidden; }
.ss-progress-bar {
  display: block; height: 100%; min-width: 2px; width: 0; border-radius: inherit;
  background: rgba(255,255,255,.92); box-shadow: 0 0 10px rgba(255,255,255,.24), inset 0 1px 0 rgba(255,255,255,.9);
  animation: ssSweep 4.55s .55s cubic-bezier(.22,.8,.32,1) forwards;
}
.ss-skip {
  --ll-glass-blur: 12px;
  position: absolute; top: 22px; right: 26px; z-index: 3;
  padding: 7px 15px; border: 0; font-family: inherit; line-height: 1;
  color: #E8E3D9; font-size: 13px;
  /* 深色玻璃填充：扫光从按钮下方经过时仍保证浅色文字对比度
     （浅色填充会被 screen 混合的扫光冲淡到不可读） */
  background: linear-gradient(-75deg, rgba(8, 12, 28, .46) 0%, rgba(8, 12, 28, .62) 50%, rgba(8, 12, 28, .46) 100%);
  opacity: 0; animation: ssFadeIn .5s .8s forwards;
}

@keyframes ssBgIn { from { opacity: 0; } to { opacity: 1; } }
@keyframes ssColorShift { from { transform: translate3d(-4%, -2%, 0) rotate(0deg) scale(1); } to { transform: translate3d(4%, 3%, 0) rotate(18deg) scale(1.08); } }
@keyframes ssOrbBrick { from { transform: translate3d(0,0,0) scale(.88); opacity:.10; } to { transform: translate3d(210px,130px,0) scale(1.18); opacity:.19; } }
@keyframes ssOrbGold { from { transform: translate3d(0,0,0) scale(1.04); opacity:.10; } to { transform: translate3d(-165px,105px,0) scale(.86); opacity:.19; } }
@keyframes ssOrbCyan { from { transform: translate3d(-40px,0,0) scale(.86); opacity:.08; } to { transform: translate3d(120px,-165px,0) scale(1.16); opacity:.17; } }
/* 45° 倾斜光带的扫过位移：只做水平方向（从左往右）。
   不能改成 45° 对角位移 —— 对角位移与亮带倾斜同轴，光带会沿自身轴向滑动，
   视觉上几乎静止（这正是方向"看起来反了/没在扫"的原因）。
   幅度 ±115vw：元素横向余量 = 120%W > 115vw，纵向不做位移，
   因此动画全程元素边界都在视口之外，不会切出矩形硬边。
   叠一个 ±5%/3% 的 scaleX 呼吸（仅 transform，走合成器不触发重绘），
   让光带宽度在扫过时极轻微地涨落，保留液态金属的形态过渡感，
   且不会产生任何硬边或断层。 */
@keyframes ssMetalSweep {
  0%   { transform: translate3d(-115vw, 0, 0) scaleX(1.05); }
  50%  { transform: translate3d(0, 0, 0) scaleX(.97); }
  100% { transform: translate3d(115vw, 0, 0) scaleX(1.05); }
}
/* LOGO 专属光泽：与背景扫光严格同节奏（6s / linear 匀速），方向同为「从左至右」。
   关键：把"高光真正压在字形上的那一段"精确安排在周期正中（34% ~ 66%），
   因此光带经过画面中央（即 LOGO 所在位置）的时刻与 LOGO 自身高光穿过字形的
   时刻重合 —— 两段光泽在视觉上连成一次连续的扫过，中间不出现停顿或空档。

   background-position 的百分号是"图像边缘对齐"语义：x 由 100% → 0% 即图案右移。
   background-size 250% 时高光中心 = 1.25W − 1.5W×p：
   p=83.3% 高光刚好在字首（左边缘），p=16.7% 高光刚好离开字尾（右边缘），
   所以 34% → 66% 这一段正好完成"自左至右穿过字形"。
   y 固定 50% 保证只在水平方向移动，不做任何纵向位移。 */
@keyframes ssLogoSheen {
  0%   { background-position: 100% 50%; }
  34%  { background-position: 83.3% 50%; }
  66%  { background-position: 16.7% 50%; }
  100% { background-position: 0% 50%; }
}
@keyframes ssNoiseDrift { from { transform: translate3d(0,0,0); } to { transform: translate3d(12px,8px,0); } }
@keyframes ssFadeUp { from { transform: translateY(16px); opacity: 0; } to { transform: translateY(0); opacity: 1; } }
@keyframes ssFadeIn { from { opacity: 0; } to { opacity: 1; } }
@keyframes ssSweep { from { width: 0; } to { width: 100%; } }

@media (prefers-reduced-motion: reduce) {
  .ss-leave-leave-active { transition: opacity .15s linear; }
  .ss-leave-leave-to { transform: none; }
  .ss-bg::after, .ss-gradient-orb, .ss-gradient-noise, .ss-metal-sheen,
  .ss-wordmark, .ss-tagline, .ss-progress, .ss-skip { animation: none !important; }
  /* 无动效时把扫光停在画面中部：保留静态金属质感，且文字直接可见 */
  .ss-metal-sheen { transform: translate3d(0, 0, 0); }
  .ss-wordmark :deep(.ll-lan), .ss-wordmark :deep(.ll-shopping), .ss-wordmark :deep(.ll-link) {
    animation: none !important; background-position: 50% 50%;
  }
  .ss-wordmark { opacity: 1; }
  .ss-wordmark, .ss-tagline, .ss-progress, .ss-skip { opacity: 1; }
  .ss-progress-bar { width: 100%; }
}
</style>

<template>
  <div class="home-page">
    <section class="brand-hero">
      <div class="brand-ambient ambient-a"></div><div class="brand-ambient ambient-b"></div><div class="brand-grid" aria-hidden="true"></div>
      <div class="brand-hero-copy">
        <span class="brand-eyebrow">LANLINK SHOPPING · CONNECTED COMMERCE</span>
        <BrandWordmark class="home-wordmark" size="clamp(38px, 5vw, 58px)" />
        <h1>让每一次连接，都产生价值。</h1>
        <p>连接商品、采购、商户与增长，让交易从发现开始，在同一个网络里持续发生。</p>
        <div class="brand-actions"><a-button type="primary" class="brand-primary" @click="$router.push('/mall')">进入商城 <span>→</span></a-button><a-button class="brand-ghost" @click="$router.push('/about')">了解平台</a-button></div>
      </div>
      <BrandSymbol class="brand-orbit" size="410px" />
    </section>
    <div class="brand-promise"><span>01</span><b>DISCOVER</b><em>→</em><span>02</span><b>TRANSACT</b><em>→</em><span>03</span><b>GROW</b></div>
    <BannerCarousel />

    <h2 class="sec"><span>01</span> 行业解决方案</h2>
    <a-row :gutter="16">
      <a-col :span="6" v-for="ind in industries" :key="ind.indId">
        <a-card hoverable class="ind-card" @click="$router.push({ name: 'mall', query: { indId: ind.indId } })">
          <div class="ind-name" :title="ind.name">{{ ind.name }}</div>
          <div class="ind-desc" :title="'查看'+ind.name+'专区商品'">查看{{ ind.name }}专区商品 →</div>
        </a-card>
      </a-col>
    </a-row>

    <div class="section-head"><h2 class="sec"><span>02</span> 热销推荐</h2><button class="section-link" type="button" @click="$router.push('/mall')">查看全部商品 →</button></div>
    <a-row :gutter="16">
      <a-col :span="6" v-for="p in hot" :key="p.prodId">
        <InteractiveProductCard
          :image-url="p.coverUrl || ''"
          :title="p.title"
          :description="p.brand || 'LANLINK SELECTION'"
          :price="'¥' + p.price"
          @click="$router.push('/product/' + p.prodId)"
        >
          <template #footer>
            <div class="product-card-meta">销量 {{ p.sales }} · 库存 {{ p.stock }}</div>
          </template>
        </InteractiveProductCard>
      </a-col>
    </a-row>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { homeApi } from '../api'
import BannerCarousel from '../components/BannerCarousel.vue'
import BrandWordmark from '../components/BrandWordmark.vue'
import BrandSymbol from '../components/BrandSymbol.vue'
import InteractiveProductCard from '../components/ui/InteractiveProductCard.vue'
const industries = ref([])
const hot = ref([])
onMounted(async () => {
  industries.value = await homeApi.industries()
  hot.value = await homeApi.hot(8)
})
</script>

<style scoped>
.sec { margin: 24px 0 12px; }
.ind-card { text-align: center; height: 116px; margin-bottom: 16px; }
.ind-card :deep(.ant-card-body) { height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 12px 16px; }
.ind-name { font-size: 18px; font-weight: 700; color: var(--ll-navy); max-width: 100%; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ind-desc { color: var(--ll-muted); font-size: 13px; margin-top: 6px; max-width: 100%; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.home-page :deep(.interactive-product-card) { margin-bottom: 16px; }
.thumb { height: 120px; background: var(--ll-thumb-bg); border-radius: 8px; display: flex; align-items: center; justify-content: center; font-size: 40px; color: var(--ll-thumb-fg); margin-bottom: 8px; overflow: hidden; }
.thumb img { width: 100%; height: 100%; object-fit: cover; }
.ptitle { font-size: 14px; height: 40px; overflow: hidden; }
.pprice { color: var(--ll-price); font-weight: 700; font-size: 18px; margin-top: 4px; }
.pmeta { color: var(--ll-gray); font-size: 12px; }
.home-page{color:var(--ll-ink)}
.brand-hero{min-height:430px;position:relative;overflow:hidden;border-radius:28px;padding:60px 68px;display:flex;align-items:center;isolation:isolate;background: var(--ll-brand-hero-gradient);box-shadow:0 24px 70px rgba(19,35,58,.18)}
.brand-grid{position:absolute;inset:0;opacity:.12;background-image:linear-gradient(rgba(255,255,255,.12) 1px,transparent 1px),linear-gradient(90deg,rgba(255,255,255,.12) 1px,transparent 1px);background-size:44px 44px;mask-image:linear-gradient(90deg,black,transparent 75%)}
.brand-ambient{position:absolute;border-radius:999px;filter:blur(42px);pointer-events:none}.ambient-a{width:340px;height:340px;right:24%;top:-140px;background:rgba(155,45,32,.46);animation:ambientFloat 7s ease-in-out infinite}.ambient-b{width:400px;height:400px;right:-110px;bottom:-190px;background:rgba(200,164,92,.32);animation:ambientFloat 9s 1s ease-in-out infinite reverse}
.brand-hero-copy{position:relative;z-index:2;max-width:650px}.brand-eyebrow{color:var(--ll-brand-gold-soft);font-size:10px;font-weight:800;letter-spacing:.17em}.home-wordmark{margin-top:18px}
.brand-hero h1{margin:22px 0 12px;color:#fff;font-size:clamp(30px,4vw,48px);line-height:1.12;letter-spacing:-.045em;font-weight:750}.brand-hero p{margin:0;max-width:610px;color:rgba(255,255,255,.7);font-size:15px;line-height:1.9}.brand-actions{display:flex;gap:12px;margin-top:26px}.brand-primary{border:0;background:linear-gradient(100deg,var(--ll-brand-gold),#F59E0B);color:var(--ll-brand-base);font-weight:750}.brand-primary:hover,.brand-primary:focus{color:var(--ll-brand-base);filter:brightness(1.06)}.brand-ghost{color:#fff;background:rgba(255,255,255,.055);border-color:rgba(255,255,255,.25);backdrop-filter:blur(12px)}.brand-ghost:hover,.brand-ghost:focus{color:#fff;background:rgba(255,255,255,.12);border-color:rgba(255,255,255,.55)}
.brand-orbit{position:absolute;right:45px;top:10px}
.brand-promise{display:flex;align-items:center;justify-content:center;gap:10px;margin:14px 0 34px;padding:13px 16px;border:1px solid #D9D3C7;border-radius:14px;background:rgba(251,249,241,.82);color:#667085;font-size:10px;letter-spacing:.13em;backdrop-filter:blur(14px)}.brand-promise span{color:var(--ll-brand-red);font-weight:800}.brand-promise b{color:var(--ll-brand-base)}.brand-promise em{font-style:normal;color:var(--ll-brand-gold);margin:0 8px}.sec span{font-size:10px;color:var(--ll-brand-gold);letter-spacing:.12em;margin-right:8px;vertical-align:middle}.section-head{display:flex;align-items:center;justify-content:space-between;gap:16px}.section-link{border:0;background:transparent;color:var(--ll-brand-blue);font:inherit;font-size:12px;cursor:pointer}.home-page :deep(.product-card-meta){color:rgba(255,255,255,.84);font-size:12px;line-height:1.5;text-shadow:0 1px 8px rgba(0,0,0,.2)}
@keyframes ambientFloat{0%,100%{transform:translate3d(0,0,0) scale(1)}50%{transform:translate3d(-18px,18px,0) scale(1.06)}}
@media(max-width:1000px){.brand-hero{padding:48px 42px}.brand-orbit{right:-100px;opacity:.38}}@media(max-width:640px){.brand-hero{min-height:500px;border-radius:20px;padding:34px 24px;align-items:flex-start}.brand-hero h1{font-size:34px}.brand-orbit{right:-80px;top:auto;bottom:-70px;transform:scale(.683);transform-origin:right bottom}.brand-promise{justify-content:flex-start;overflow-x:auto;white-space:nowrap}.brand-actions{flex-wrap:wrap}.section-head{align-items:flex-end}}
@media(prefers-reduced-motion:reduce){.ambient-a,.ambient-b,.orbit-dot{animation:none}}
</style>

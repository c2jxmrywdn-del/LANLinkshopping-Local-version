<template>
  <div class="legal-page">
    <div class="legal-hero">
      <div class="eyebrow">LEGAL CENTER · AGREEMENTS &amp; POLICIES</div>
      <h1>{{ doc ? doc.title[langKey] : '协议与条款' }}</h1>
      <p class="hero-sub">{{ heroSub }}</p>
      <nav class="doc-switch" aria-label="法律文档切换">
        <button
          v-for="d in docs" :key="d.key" type="button"
          class="doc-chip" :class="{ active: d.key === docKey }"
          @click="switchDoc(d.key)"
        >{{ d.title[langKey] }}</button>
      </nav>
    </div>

    <div v-if="doc" class="legal-body">
      <aside class="section-nav" aria-label="文档目录">
        <div class="nav-title">{{ doc.title[langKey] }}</div>
        <a
          v-for="(s, i) in doc.sections" :key="i"
          :href="'#legal-sec-' + i"
          class="nav-link"
        >{{ s.h[langKey] }}</a>
        <button v-if="doc.key === 'cookies'" type="button" class="nav-cta" @click="goCookiePrefs">
          Cookie 偏好设置
        </button>
        <button v-else-if="doc.key === 'privacy'" type="button" class="nav-cta" @click="goCookiePrefs">
          隐私与 Cookie 偏好
        </button>
      </aside>

      <article class="legal-article">
        <p class="doc-updated">最后更新：{{ doc.updated }}</p>
        <section
          v-for="(s, i) in doc.sections" :key="i"
          :id="'legal-sec-' + i"
          class="legal-section"
        >
          <h2>{{ s.h[langKey] }}</h2>
          <p v-for="(p, j) in s.paras" :key="'p' + j">{{ p[langKey] }}</p>
          <ul v-if="s.bullets && s.bullets.length">
            <li v-for="(b, k) in s.bullets" :key="'b' + k">{{ b[langKey] }}</li>
          </ul>
        </section>
        <div class="legal-footnote">
          <span>本页面内容与《用户服务协议》《隐私政策》等其他文档相互补充；如单一文档表述不一致，以更符合法律法规要求者为准。</span>
          <button type="button" class="foot-link" @click="$router.push({ name: 'about' })">了解平台与联系我们</button>
        </div>
      </article>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { locale } from '../i18n'
import { LEGAL_DOCS, legalDocByKey } from '../data/legalDocs'

const route = useRoute()
const router = useRouter()
const docs = LEGAL_DOCS

const docKey = computed(() => {
  const k = String(route.params.docKey || 'terms')
  return legalDocByKey(k) ? k : 'terms'
})
const doc = computed(() => legalDocByKey(docKey.value))
const langKey = computed(() => (locale.value === 'en-US' ? 'en' : 'zh'))

const heroSub = computed(() => {
  const map = {
    terms: { zh: '您与平台之间的服务约定，注册与使用前请仔细阅读。', en: 'The agreement between you and the Platform. Please read carefully before registration and use.' },
    privacy: { zh: '我们收集哪些信息、如何使用与保护，以及您对自己的数据拥有的权利。', en: 'What we collect, how we use and protect it, and your rights over your data.' },
    cookies: { zh: '平台 Cookie 的分类、用途与管理方式。', en: 'Categories, purposes and management of cookies on the Platform.' },
    platform: { zh: '平台定位、身份协同、演示环境与 AI 能力说明。', en: 'Positioning, role collaboration, demo environment and AI capabilities.' },
    disclaimer: { zh: '行情数据、交易主体、AI 内容与外部链接的责任边界。', en: 'Responsibility boundaries for market data, transactions, AI content and external links.' }
  }
  const m = map[docKey.value] || map.terms
  return locale.value === 'en-US' ? m.en : m.zh
})

function switchDoc(key) {
  router.push({ name: 'legal-doc', params: { docKey: key } })
}
function goCookiePrefs() {
  router.push({ name: 'privacy-cookies' })
}
</script>

<style scoped>
.legal-page { max-width: 1120px; margin: 0 auto; padding-bottom: 30px; }
.legal-hero {
  margin: 8px 0 18px; padding: 30px 28px 24px;
  border-radius: 20px; color: #fff; overflow: hidden;
  background: linear-gradient(135deg, rgba(19,35,58,.97), rgba(31,56,100,.92));
  box-shadow: 0 14px 36px rgba(15,23,42,.08);
}
.eyebrow { color: #F6D38A; font-size: 10px; letter-spacing: .16em; font-weight: 800; }
.legal-hero h1 { margin: 9px 0 7px; font-size: 27px; line-height: 1.25; }
.hero-sub { margin: 0; max-width: 720px; color: rgba(255,255,255,.78); font-size: 13px; line-height: 1.75; }
.doc-switch { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 18px; }
.doc-chip {
  border: 1px solid rgba(255,255,255,.28); background: rgba(255,255,255,.08);
  color: rgba(255,255,255,.86); font: inherit; font-size: 12px; font-weight: 600;
  padding: 7px 14px; border-radius: 999px; cursor: pointer; transition: .2s ease;
}
.doc-chip:hover { background: rgba(255,255,255,.16); color: #fff; }
.doc-chip.active { background: #fff; border-color: #fff; color: #13233A; }

.legal-body { display: grid; grid-template-columns: 250px 1fr; gap: 22px; align-items: start; }
.section-nav {
  position: sticky; top: 84px;
  border: 1px solid #e6e9ef; border-radius: 16px; background: rgba(255,255,255,.9);
  padding: 16px 14px; display: flex; flex-direction: column; gap: 2px;
}
.nav-title { color: #13233A; font-weight: 800; font-size: 13px; padding: 0 8px 8px; }
.nav-link {
  color: #667085; text-decoration: none; font-size: 12px; line-height: 1.5;
  padding: 7px 8px; border-radius: 8px; transition: .15s ease;
}
.nav-link:hover { color: #13233A; background: rgba(30,110,184,.08); }
.nav-cta {
  margin-top: 8px; border: 0; background: rgba(30,110,184,.10); color: #1E6EB8;
  font: inherit; font-size: 12px; font-weight: 700; padding: 9px 10px;
  border-radius: 10px; cursor: pointer; transition: .2s ease;
}
.nav-cta:hover { background: rgba(30,110,184,.18); }

.legal-article {
  border: 1px solid #e6e9ef; border-radius: 18px; background: rgba(255,255,255,.92);
  box-shadow: 0 14px 36px rgba(15,23,42,.06); padding: 28px 30px 24px;
}
.doc-updated { margin: 0 0 6px; color: #98A2B3; font-size: 12px; }
.legal-section { padding: 14px 0 6px; border-bottom: 1px solid #f1f3f6; }
.legal-section:last-of-type { border-bottom: 0; }
.legal-section h2 { color: #13233A; font-size: 16.5px; margin: 6px 0 10px; }
.legal-section p { color: #475467; font-size: 13px; line-height: 1.85; margin: 8px 0; text-align: justify; }
.legal-section ul { margin: 8px 0; padding-left: 20px; }
.legal-section li { color: #475467; font-size: 13px; line-height: 1.85; margin: 5px 0; }
.legal-footnote {
  margin-top: 14px; padding-top: 14px; border-top: 1px solid #f1f3f6;
  color: #98A2B3; font-size: 11.5px; line-height: 1.7;
  display: flex; flex-direction: column; gap: 8px;
}
.foot-link {
  align-self: flex-start; border: 0; background: transparent; padding: 0;
  color: #1E6EB8; font: inherit; font-size: 11.5px; font-weight: 700; cursor: pointer;
}
.foot-link:hover { text-decoration: underline; }

@media (max-width: 900px) {
  .legal-body { grid-template-columns: 1fr; }
  .section-nav { position: static; flex-direction: row; flex-wrap: wrap; gap: 4px; }
  .nav-title { width: 100%; padding-bottom: 4px; }
}
@media (max-width: 575px) {
  .legal-hero { padding: 22px 18px 18px; }
  .legal-hero h1 { font-size: 22px; }
  .doc-switch { gap: 6px; }
  .doc-chip { padding: 6px 11px; font-size: 11.5px; }
  .legal-article { padding: 20px 16px 18px; }
}
</style>

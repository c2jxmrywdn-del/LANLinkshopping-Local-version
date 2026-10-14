<template>
  <div class="about-page">
    <!-- 头部：品牌叙事 + 快速入口 -->
    <section class="about-hero">
      <div class="hero-glow hero-glow-a"></div>
      <div class="hero-glow hero-glow-b"></div>
      <div class="hero-copy">
        <span class="eyebrow">ABOUT LANLINK SHOPPING</span>
        <h1>让采购、商品与增长<br /><em>在同一个网络里连接。</em></h1>
        <p>
          LANLinkshopping 面向 B2B 场景，将商品展示、在线交易、会员营销、商户经营与平台运营
          汇聚到同一套数字化交易链路中。
        </p>
        <div class="hero-actions">
          <a-button type="primary" class="hero-primary" @click="goMall">进入商城 <span>→</span></a-button>
          <a-button class="hero-secondary" @click="scrollToSection('contact')">联系平台</a-button>
        </div>
      </div>
      <div class="hero-orbit" aria-hidden="true">
        <div class="orbit-ring ring-one"></div>
        <div class="orbit-ring ring-two"></div>
        <div class="orbit-core"><span>LL</span></div>
        <span class="orbit-node node-a"></span>
        <span class="orbit-node node-b"></span>
        <span class="orbit-node node-c"></span>
      </div>
    </section>

    <!-- 页内导航：不再依赖 a-anchor 的事件签名，避免锚点点击在不同 antd 版本下失效 -->
    <nav class="section-nav" aria-label="关于我们页面导航">
      <button
        v-for="item in sections"
        :key="item.key"
        type="button"
        :class="{ active: activeSection === item.key }"
        @click="scrollToSection(item.key)"
      >
        <span>{{ item.index }}</span>{{ item.title }}
      </button>
    </nav>

    <main>
      <!-- 我们解决什么 -->
      <section id="company" class="about-section intro-section">
        <div class="section-heading">
          <span class="section-kicker">01 · PLATFORM</span>
          <h2>不是单一商城，而是一条完整的交易链路</h2>
          <p>从“找到商品”到“完成交易”，再到“持续经营”，每一个角色都有对应的工作台与能力。</p>
        </div>

        <div class="capability-grid">
          <article v-for="item in capabilities" :key="item.title" class="capability-card">
            <div class="capability-icon">{{ item.icon }}</div>
            <div>
              <span class="card-index">{{ item.index }}</span>
              <h3>{{ item.title }}</h3>
              <p>{{ item.desc }}</p>
            </div>
            <span class="card-arrow">↗</span>
          </article>
        </div>
      </section>

      <!-- 平台架构 -->
      <section class="about-section architecture-section">
        <div class="section-heading compact">
          <span class="section-kicker">02 · ECOSYSTEM</span>
          <h2>五种身份，一套协同逻辑</h2>
          <p>身份决定可见内容与操作边界；权限由前端路由与后端接口共同校验。</p>
        </div>

        <div class="identity-layout">
          <div class="identity-visual">
            <div class="identity-center">
              <strong>LAN</strong><span>Link</span>
              <small>交易网络</small>
            </div>
            <div
              v-for="(item, index) in identities"
              :key="item.code"
              class="identity-node"
              :class="'identity-' + index"
            >
              <b>{{ item.label }}</b>
              <span>{{ item.code }}</span>
            </div>
          </div>

          <div class="identity-list">
            <div v-for="item in identities" :key="item.code" class="identity-row">
              <div class="identity-tag">{{ item.short }}</div>
              <div class="identity-copy">
                <strong>{{ item.label }}</strong>
                <span>{{ item.desc }}</span>
              </div>
              <span class="identity-status">{{ item.scope }}</span>
            </div>
          </div>
        </div>
      </section>

      <!-- 能力矩阵 -->
      <section class="about-section matrix-section">
        <div class="matrix-card">
          <div class="matrix-head">
            <div>
              <span class="section-kicker">03 · CAPABILITY MAP</span>
              <h2>从获客到复购，能力彼此衔接</h2>
            </div>
            <span class="matrix-badge">DEMO PLATFORM</span>
          </div>
          <div class="matrix-grid">
            <div v-for="item in modules" :key="item.title" class="module-card">
              <span class="module-number">{{ item.number }}</span>
              <div class="module-icon">{{ item.icon }}</div>
              <h3>{{ item.title }}</h3>
              <p>{{ item.desc }}</p>
              <div class="module-tags">
                <span v-for="tag in item.tags" :key="tag">{{ tag }}</span>
              </div>
            </div>
          </div>
        </div>
      </section>

      <!-- 项目作者与联系矩阵 -->
      <section id="contact" class="about-section contact-section">
        <div class="contact-intro">
          <span class="section-kicker">04 · CONTACT</span>
          <h2>项目作者：Jason Ouyang</h2>
          <p>LANLinkshopping 为个人毕业设计项目。这里集中提供作者邮箱、社交平台与交流社区入口，便于项目展示、沟通与学术交流。</p>
          <a-alert type="info" show-icon message="本页面公开的联系方式由项目作者本人提供，仅用于项目展示、交流与联系。" />
        </div>

        <AuthorCard variant="detailed" :show-socials="true" />

        <div class="contact-grid">
          <article class="contact-card">
            <span class="contact-icon"><img class="contact-icon-image" src="https://cdn.simpleicons.org/qq/13233A" alt="QQ邮箱图标" /></span>
            <span class="contact-label">QQ邮箱</span>
            <a href="mailto:3514485358@qq.com" class="contact-link">3514485358@qq.com</a>
            <small>项目联系 / 日常沟通</small>
            <a-button block @click="copyText('3514485358@qq.com', '主要邮箱')">复制邮箱</a-button>
          </article>
          <article class="contact-card">
            <span class="contact-icon"><img class="contact-icon-image" src="https://cdn.simpleicons.org/gmail/13233A" alt="Google Gmail 图标" /></span>
            <span class="contact-label">Google Gmail</span>
            <a href="mailto:ouyangjason001@gmail.com" class="contact-link">ouyangjason001@gmail.com</a>
            <small>备用联系邮箱</small>
            <a-button block @click="copyText('ouyangjason001@gmail.com', '备用邮箱')">复制邮箱</a-button>
          </article>
          <article class="contact-card">
            <span class="contact-icon"><img class="contact-icon-image" src="https://cdn.simpleicons.org/whatsapp/13233A" alt="WhatsApp 图标" /></span>
            <span class="contact-label">WhatsApp</span>
            <a class="contact-link" href="https://wa.me/OuyangJason?s=t" target="_blank" rel="noopener noreferrer">WhatsApp · OuyangJason</a>
            <small>点击“显示二维码”查看扫码方式，也可直接打开 WhatsApp。</small>
            <div v-if="whatsappQrVisible" class="whatsapp-qr-panel">
              <img class="contact-qr" src="/assets/contact/whatsapp-qr.png" alt="WhatsApp OuyangJason 联系二维码" />
              <span>扫码添加 / 联系 OuyangJason</span>
            </div>
            <div class="whatsapp-actions">
              <a-button size="small" @click="whatsappQrVisible = !whatsappQrVisible">{{ whatsappQrVisible ? '收起二维码' : '显示二维码' }}</a-button>
              <a-button size="small" type="primary" href="https://wa.me/OuyangJason?s=t" target="_blank" rel="noopener noreferrer">直接打开 WhatsApp</a-button>
            </div>
          </article>
        </div>
      </section>

      <!-- 知识产权声明：毕业设计作品及原创成果保护提示 -->
      <section class="about-section ip-notice" aria-label="知识产权声明">
        <div class="ip-notice-copy">
          <span class="section-kicker">INTELLECTUAL PROPERTY · GRADUATION PROJECT</span>
          <h2>毕业设计原创性及知识产权声明</h2>
          <p>
            本项目为个人毕业设计作品。项目名称、产品定位、整体创意与构思、平台架构、功能组合、交互流程、
            页面视觉方案、代码实现、文档资料及其他原创成果均属于本毕业设计成果的一部分。未经本人书面授权，
            任何个人、组织或机构不得擅自盗用、复制、抄袭、改编、转载、传播、发布、署名冒用，或将本项目及其原创成果
            冒用、仿冒、包装、转化为他人项目或商业项目；如需用于商业宣传、商业产品、商业服务或其他营利性用途，
            应事先取得本人书面授权。本人将依法采取必要措施维护自身合法权益，请尊重知识产权。
          </p>
        </div>
        <div class="ip-notice-badge">
          <strong>请尊重知识产权</strong>
          <span>未经许可请勿使用、冒用或商业化。</span>
        </div>
      </section>

      <!-- 法务信息：折叠而非长文本堆叠 -->
      <section id="terms" class="about-section legal-section">
        <div class="legal-tabs" role="tablist">
          <button :class="{ active: legalTab === 'terms' }" type="button" @click="legalTab = 'terms'">
            服务条款 <span>01</span>
          </button>
          <button :class="{ active: legalTab === 'privacy' }" type="button" @click="legalTab = 'privacy'">
            隐私政策 <span>02</span>
          </button>
          <button :class="{ active: legalTab === 'ip' }" type="button" @click="legalTab = 'ip'">
            知识产权声明 <span>03</span>
          </button>
        </div>

        <article class="legal-panel">
          <template v-if="legalTab === 'terms'">
            <div class="legal-title">
              <span>TERMS OF SERVICE</span>
              <h2>服务条款</h2>
              <p>用于演示平台交易、账号与商户经营的基本规则。</p>            </div>
            <ol class="legal-list">
              <li><b>账号与实名</b><span>用户应使用真实企业信息注册并完成身份认证，妥善保管账号、密码及两步验证凭证。</span></li>
              <li><b>交易规范</b><span>商品由入驻商户发布并经平台审核；下单前请核对规格、价格与库存。</span></li>
              <li><b>支付与退款</b><span>演示环境支持钱包、模拟、微信与支付宝渠道；真实渠道需完成对应商户配置。</span></li>
              <li><b>商户义务</b><span>商户应保证商品质量与证照信息真实有效，并按约定时效履约。</span></li>
              <li><b>服务中断</b><span>因系统维护、不可抗力等导致服务中断时，平台将尽快恢复服务。</span></li>
            </ol>
          </template>

          <template v-else-if="legalTab === 'privacy'">
            <div class="legal-title">
              <span>PRIVACY POLICY</span>
              <h2>隐私政策</h2>
              <p>说明演示平台如何处理账号、订单与必要的安全审计数据。</p>
            </div>
            <ol class="legal-list">
              <li><b>信息收集</b><span>为提供交易与履约服务，平台可能处理账号、企业资料、收货地址与订单数据。</span></li>
              <li><b>信息使用</b><span>数据用于身份识别、订单履约、会员权益计算与安全审计，不用于无关用途。</span></li>
              <li><b>信息保护</b><span>平台采用 HTTPS、密码散列、支付流水脱敏与权限拦截等安全措施。</span></li>
              <li><b>用户权利</b><span>用户可在个人中心查看、更正资料并管理收货地址与第三方授权。</span></li>
              <li><b>Cookie</b><span>仅使用维持登录态所需的会话机制，不用于跨站广告追踪。</span></li>
            </ol>
          </template>

          <template v-else>
            <div class="legal-title">
              <span>INTELLECTUAL PROPERTY</span>
              <h2>知识产权声明</h2>
              <p>本页面用于明确本毕业设计作品的原创成果归属及未经许可使用的禁止范围。</p>
            </div>
            <ol class="legal-list">
              <li><b>作品属性</b><span>本平台属于毕业设计演示作品。项目中的软件程序及相关文档、界面设计、页面文案、图片编排、交互流程等具体表达，按适用法律享有相应的知识产权保护。</span></li>
              <li><b>原创成果</b><span>项目名称、整体创意与构思、产品定位、功能组合、信息架构、业务流程、系统组件设计以及相关具体创作成果，均属于本人毕业设计过程中的研究、设计与开发成果；涉及第三方素材的，以其相应权利人的权利声明为准。</span></li>
              <li><b>禁止盗用</b><span>未经书面许可，不得盗用、复制、抄袭、改编、转载、镜像部署、删除或篡改权利标识，不得冒用本人或本项目名义对外发布，不得将本项目或其具体原创成果包装、转化、仿冒或冒用为他人的项目或商业方案。</span></li>
              <li><b>商业使用</b><span>任何用于商业宣传、商业产品、商业服务、融资材料、投标材料或其他营利性场景的使用，均应事先取得本人书面授权；未经授权的使用，本人保留依法追究相关责任的权利。</span></li>
              <li><b>维权方式</b><span>如发现未经许可的盗用、抄袭、冒名使用或其他涉嫌侵权行为，本人将依法采取包括但不限于固定证据、发送侵权通知、投诉举报、申请平台处置以及提起民事诉讼等维权措施。</span></li>
            </ol>
          </template>
        </article>
      </section>
    </main>
  </div>
</template>

<script setup>
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import AuthorCard from '../components/AuthorCard.vue'
import { useRouter, useRoute } from 'vue-router'

const router = useRouter()
const route = useRoute()
const activeSection = ref('company')
const whatsappQrVisible = ref(false)
const legalTab = ref(route.query.legal === 'ip' ? 'ip' : 'terms')

const sections = [
  { key: 'company', index: '01', title: '平台定位' },
  { key: 'contact', index: '04', title: '联系我们' },
  { key: 'terms', index: '05', title: '服务条款' },
  { key: 'ip', index: '06', title: '知识产权' }
]

const capabilities = [
  { index: '01', icon: '⌁', title: '商品与采购', desc: '商品浏览、搜索、详情、购物车与订单形成一条连续路径。' },
  { index: '02', icon: '◎', title: '商户经营', desc: '商户入驻、商品发布与流量分析形成经营闭环。' },
  { index: '03', icon: '◇', title: '营销中台', desc: '活动、促销与会员能力共同作用于交易转化与复购。' },
  { index: '04', icon: '↗', title: '平台运营', desc: '管理员通过商户、商品、支付与审计模块维护平台秩序。' }
]

const identities = [
  { code: 'GUEST', short: 'G', label: '访客', desc: '未登录用户，可浏览与搜索商品', scope: '浏览' },
  { code: 'BUYER', short: 'B', label: '普通用户', desc: '已登录采购方，进入交易流程', scope: '采购' },
  { code: 'VIP', short: 'V', label: 'VIP 用户', desc: '满足累计支付门槛的采购方', scope: '权益' },
  { code: 'MERCHANT', short: 'M', label: '商户', desc: '审核通过后管理商品与经营数据', scope: '经营' },
  { code: 'ADMIN', short: 'A', label: '管理员', desc: '平台运营身份，负责审核与治理', scope: '运营' }
]

const modules = [
  { number: '01', icon: '◌', title: '交易', desc: '商品 → 购物车 → 结算 → 支付 → 订单', tags: ['订单', '钱包', '支付'] },
  { number: '02', icon: '✦', title: '营销', desc: '活动、促销与会员权益协同工作', tags: ['活动', '促销', '会员'] },
  { number: '03', icon: '⌁', title: '增长', desc: '商户流量、来源、转化与诊断形成经营反馈', tags: ['流量', '转化', '诊断'] }
]

let observer
watch(() => route.query.legal, value => {
  if (value === 'ip') {
    legalTab.value = 'ip'
    requestAnimationFrame(() => document.getElementById('terms')?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
  }
})

onMounted(() => {
  const targets = ['company', 'contact', 'terms'].map(id => document.getElementById(id)).filter(Boolean)
  observer = new IntersectionObserver(entries => {
    const visible = entries
      .filter(entry => entry.isIntersecting)
      .sort((a, b) => b.intersectionRatio - a.intersectionRatio)
    if (visible[0]?.target?.id) activeSection.value = visible[0].target.id
  }, { rootMargin: '-96px 0px -55% 0px', threshold: [0.15, 0.35, 0.6] })
  targets.forEach(el => observer.observe(el))
})
onBeforeUnmount(() => observer?.disconnect())

function scrollToSection(id) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  activeSection.value = id
}
function goMall() {
  router.push('/mall')
}
async function copyText(text, label) {
  try {
    await navigator.clipboard.writeText(text)
    message.success(label + '已复制')
  } catch {
    message.warning('当前浏览器不允许自动复制，请手动复制：' + text)
  }
}
</script>

<style scoped>
.about-page { --ab-navy: var(--ll-brand-base); --ab-ink: #0F172A; --ab-muted: #667085; --ab-line: #D9D3C7; --ab-paper: #FBF9F1; --ab-paper2: #F5F0E8; --ab-gold: var(--ll-brand-gold); --ab-red: var(--ll-brand-red); --ab-blue: var(--ll-brand-blue); color: var(--ab-ink); padding-bottom: 24px; }
.about-hero { min-height: 430px; position: relative; overflow: hidden; border-radius: 28px; padding: 64px 68px; display: flex; align-items: center; background: var(--ll-brand-hero-gradient); box-shadow: var(--ll-glass-shadow); }
.hero-copy { position: relative; z-index: 2; max-width: 650px; }
.eyebrow, .section-kicker { display: inline-flex; align-items: center; gap: 8px; font-size: 11px; font-weight: 800; letter-spacing: .16em; color: var(--ab-gold); }
.eyebrow::before, .section-kicker::before { content: ''; width: 24px; height: 1px; background: currentColor; }
.hero-copy h1 { margin: 16px 0 16px; color: #fff; font-size: clamp(34px, 4vw, 54px); line-height: 1.12; letter-spacing: -.045em; font-weight: 750; }
.hero-copy h1 em { color: var(--ll-brand-gold-soft); font-style: normal; }
.hero-copy p { max-width: 620px; margin: 0; color: rgba(255,255,255,.72); font-size: 15px; line-height: 1.9; }
.hero-actions { display: flex; gap: 12px; margin-top: 28px; }
.hero-primary { border: 0; background: linear-gradient(100deg, var(--ll-brand-gold), #F59E0B); color: var(--ll-brand-base); font-weight: 750; box-shadow: 0 10px 28px rgba(200,164,92,.22); }
.hero-primary:hover { filter: brightness(1.06); color: var(--ll-brand-base); }
.hero-secondary { color: #fff; background: rgba(255,255,255,.06); border-color: rgba(255,255,255,.25); backdrop-filter: blur(10px); }
.hero-secondary:hover { color: #fff; border-color: rgba(255,255,255,.55); background: rgba(255,255,255,.12); }
.hero-glow { position: absolute; border-radius: 999px; filter: blur(30px); opacity: .28; }
.hero-glow-a { width: 300px; height: 300px; right: 18%; top: -80px; background: var(--ll-brand-red); }
.hero-glow-b { width: 360px; height: 360px; right: -70px; bottom: -160px; background: var(--ll-brand-gold); }
.hero-orbit { position: absolute; width: 400px; height: 400px; right: 70px; top: 18px; opacity: .92; }
.orbit-ring { position: absolute; inset: 42px; border: 1px solid rgba(255,255,255,.15); border-radius: 50%; transform: rotate(-18deg) skewX(-8deg); }
.ring-two { inset: 82px 12px; transform: rotate(28deg) skewY(-7deg); border-color: rgba(200,164,92,.3); }
.orbit-core { position: absolute; left: 50%; top: 50%; transform: translate(-50%,-50%); width: 108px; height: 108px; border-radius: 32px; display: grid; place-items: center; background: linear-gradient(145deg, rgba(255,255,255,.17), rgba(255,255,255,.04)); border: 1px solid rgba(255,255,255,.24); box-shadow: inset 0 1px rgba(255,255,255,.35), 0 24px 50px rgba(0,0,0,.18); backdrop-filter: blur(var(--ll-glass-blur)) saturate(var(--ll-glass-saturate)); }
.orbit-core strong { font-size: 26px; color: #fff; letter-spacing: -.08em; }
.orbit-core span { font-size: 28px; font-weight: 800; color: #fff; letter-spacing: -.08em; }
.orbit-core::after { content: 'SHOPPING'; position: absolute; bottom: 18px; font-size: 7px; letter-spacing: .18em; color: rgba(255,255,255,.55); }
.orbit-node { position: absolute; width: 10px; height: 10px; border-radius: 50%; background: var(--ll-brand-gold-soft); box-shadow: 0 0 20px rgba(245,158,11,.75); }
.node-a { left: 64px; top: 104px; } .node-b { right: 34px; top: 205px; } .node-c { left: 104px; bottom: 64px; }

.section-nav { position: sticky; top: 64px; z-index: 20; display: flex; gap: 6px; margin: 18px 0 34px; padding: 7px; border: 1px solid rgba(217,211,199,.8); border-radius: 14px; background: rgba(251,249,241,.86); box-shadow: 0 8px 24px rgba(19,35,58,.06); backdrop-filter: blur(16px); }
.section-nav button { border: 0; background: transparent; color: #667085; border-radius: 9px; padding: 9px 14px; cursor: pointer; font: inherit; font-size: 13px; transition: .2s ease; }
.section-nav button span { margin-right: 7px; font-size: 10px; opacity: .55; }
.section-nav button:hover { color: var(--ab-navy); background: rgba(200,164,92,.1); }
.section-nav button.active { color: var(--ab-navy); background: #fff; box-shadow: 0 3px 12px rgba(19,35,58,.08); font-weight: 700; }

.about-section { scroll-margin-top: 130px; margin-bottom: 64px; }
.section-heading { max-width: 760px; margin-bottom: 24px; }
.section-heading.compact { max-width: 720px; }
.section-heading h2, .contact-intro h2, .legal-title h2, .matrix-head h2 { margin: 10px 0 8px; font-size: clamp(25px, 3vw, 36px); line-height: 1.2; letter-spacing: -.035em; color: var(--ab-navy); }
.section-heading p, .contact-intro p { margin: 0; color: var(--ab-muted); line-height: 1.8; font-size: 14px; }

.capability-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; }
.capability-card { min-height: 220px; position: relative; padding: 22px; border: 1px solid var(--ab-line); border-radius: 18px; background: linear-gradient(145deg, #fff, var(--ab-paper)); transition: transform .25s ease, box-shadow .25s ease, border-color .25s ease; }
.capability-card:hover { transform: translateY(-4px); border-color: rgba(200,164,92,.7); box-shadow: 0 18px 38px rgba(19,35,58,.09); }
.capability-icon { width: 42px; height: 42px; display: grid; place-items: center; border-radius: 13px; background: var(--ab-navy); color: var(--ll-brand-gold-soft); font-size: 21px; margin-bottom: 34px; }
.card-index { font-size: 10px; color: var(--ab-red); font-weight: 800; letter-spacing: .14em; }
.capability-card h3 { margin: 6px 0; font-size: 18px; color: var(--ab-navy); }
.capability-card p { margin: 0; color: var(--ab-muted); line-height: 1.7; font-size: 13px; }
.card-arrow { position: absolute; right: 20px; top: 20px; color: #98A2B3; font-size: 18px; }

.identity-layout { display: grid; grid-template-columns: 1.05fr .95fr; gap: 26px; align-items: stretch; }
.identity-visual { min-height: 390px; position: relative; border-radius: 22px; overflow: hidden; background: var(--ab-navy); }
.identity-visual::before { content: ''; position: absolute; inset: 0; background: radial-gradient(circle at 50% 50%, rgba(200,164,92,.18), transparent 32%), radial-gradient(circle at 20% 15%, rgba(155,45,32,.24), transparent 30%); }
.identity-center { position: absolute; left: 50%; top: 50%; transform: translate(-50%,-50%); width: 132px; height: 132px; border-radius: 38px; display: grid; place-content: center; text-align: center; background: var(--ll-glass-bg-strong); border: 1px solid var(--ll-glass-border); box-shadow: inset 0 1px rgba(255,255,255,.25), 0 22px 50px rgba(0,0,0,.2); backdrop-filter: blur(18px); }
.identity-center strong { color: #fff; font-size: 24px; letter-spacing: -.06em; }
.identity-center span { color: var(--ll-brand-gold-soft); font-size: 24px; font-weight: 800; letter-spacing: -.06em; }
.identity-center small { margin-top: 7px; color: rgba(255,255,255,.58); letter-spacing: .12em; font-size: 8px; }
.identity-node { position: absolute; min-width: 92px; padding: 9px 12px; text-align: center; border-radius: 12px; color: #fff; background: rgba(255,255,255,.07); border: 1px solid rgba(255,255,255,.14); backdrop-filter: blur(8px); }
.identity-node b { display: block; font-size: 12px; } .identity-node span { font-size: 9px; color: rgba(255,255,255,.48); letter-spacing: .08em; }
.identity-0 { left: 26px; top: 40px; } .identity-1 { right: 26px; top: 40px; } .identity-2 { right: 22px; bottom: 44px; } .identity-3 { left: 50%; bottom: 24px; transform: translateX(-50%); } .identity-4 { left: 22px; bottom: 44px; }
.identity-node::after { content: ''; position: absolute; width: 54px; height: 1px; background: rgba(246,211,138,.38); top: 50%; }
.identity-0::after, .identity-4::after { left: 100%; transform: rotate(18deg); transform-origin: left; }
.identity-1::after, .identity-2::after { right: 100%; transform: rotate(-18deg); transform-origin: right; }
.identity-3::after { display: none; }

.identity-list { display: grid; gap: 8px; }
.identity-row { min-height: 70px; display: grid; grid-template-columns: 42px 1fr auto; gap: 12px; align-items: center; padding: 10px 14px; border: 1px solid var(--ab-line); border-radius: 14px; background: #fff; }
.identity-tag { width: 36px; height: 36px; border-radius: 11px; display: grid; place-items: center; background: var(--ab-paper2); color: var(--ab-navy); font-weight: 800; }
.identity-copy { min-width: 0; display: grid; gap: 3px; } .identity-copy strong { color: var(--ab-navy); font-size: 14px; } .identity-copy span { color: var(--ab-muted); font-size: 12px; }
.identity-status { color: var(--ab-red); font-size: 11px; font-weight: 700; white-space: nowrap; }

.matrix-card { padding: 28px; border-radius: 22px; background: var(--ab-paper2); border: 1px solid var(--ab-line); }
.matrix-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 18px; margin-bottom: 20px; }
.matrix-head h2 { margin-bottom: 0; }
.matrix-badge { padding: 6px 9px; border: 1px solid rgba(155,45,32,.25); color: var(--ab-red); border-radius: 999px; font-size: 9px; font-weight: 800; letter-spacing: .12em; white-space: nowrap; }
.matrix-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.module-card { position: relative; padding: 22px; min-height: 190px; border-radius: 17px; background: #fff; border: 1px solid rgba(217,211,199,.9); overflow: hidden; }
.module-number { position: absolute; top: 14px; right: 16px; color: rgba(19,35,58,.15); font-size: 28px; font-weight: 800; }
.module-icon { width: 36px; height: 36px; display: grid; place-items: center; border-radius: 11px; background: var(--ab-navy); color: var(--ll-brand-gold-soft); }
.module-card h3 { margin: 16px 0 5px; color: var(--ab-navy); font-size: 17px; }
.module-card p { margin: 0; color: var(--ab-muted); font-size: 12px; line-height: 1.65; }.module-tags { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 16px; }
.module-tags span { padding: 4px 7px; border-radius: 6px; background: var(--ab-paper); color: #667085; font-size: 10px; }

.contact-section { display: grid; grid-template-columns: .78fr 1.22fr; gap: 26px; align-items: start; }
.contact-intro { padding-top: 8px; }
.contact-intro .ant-alert { margin-top: 18px; }
.contact-link { display: block; max-width: 100%; margin: 5px 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--ab-navy); font-weight: 750; text-decoration: none; }
.contact-link:hover { text-decoration: underline; }

.contact-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.contact-card { min-height: 220px; display: flex; flex-direction: column; padding: 20px; border: 1px solid var(--ab-line); border-radius: 17px; background: #fff; }
.contact-icon { width: 36px; height: 36px; display: grid; place-items: center; border-radius: 10px; background: var(--ab-navy); color: var(--ll-brand-gold-soft); }
.contact-icon-image { width: 20px; height: 20px; display: block; filter: brightness(0) invert(1); }
.whatsapp-qr-panel { display: flex; align-items: center; gap: 10px; margin: 8px 0 10px; padding: 8px; border: 1px solid var(--ab-line); border-radius: 10px; background: var(--ab-paper); }
.contact-qr { width: 76px; height: 76px; object-fit: contain; border: 1px solid var(--ab-line); border-radius: 8px; background: #fff; padding: 3px; }
.whatsapp-qr-panel span { color: var(--ab-muted); font-size: 10px; line-height: 1.5; }
.whatsapp-actions { display: grid; grid-template-columns: 1fr 1.25fr; gap: 8px; margin-top: auto; }
.whatsapp-actions .ant-btn { margin-top: 0; }
.contact-label { margin-top: 18px; color: var(--ab-muted); font-size: 11px; }
.contact-card strong { margin: 5px 0; color: var(--ab-navy); font-size: 13px; line-height: 1.5; overflow-wrap: anywhere; }
.contact-card small { color: #98A2B3; line-height: 1.5; min-height: 36px; }
.contact-card .ant-btn { margin-top: auto; }

.ip-notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 24px 26px;
  border: 1px solid rgba(155,45,32,.22);
  border-radius: 20px;
  background: linear-gradient(135deg, rgba(155,45,32,.055), rgba(200,164,92,.10));
}
.ip-notice-copy { min-width: 0; }
.ip-notice-copy h2 { margin: 6px 0 7px; color: var(--ab-navy); font-size: 22px; }
.ip-notice-copy p { margin: 0; color: var(--ab-muted); font-size: 13px; line-height: 1.8; max-width: 820px; }
.ip-notice-badge {
  flex: 0 0 230px;
  padding: 15px 17px;
  border-radius: 14px;
  background: var(--ab-navy);
  color: #fff;
  box-shadow: 0 10px 24px rgba(19,35,58,.12);
}
.ip-notice-badge strong { display:block; color: var(--ll-brand-gold-soft); font-size: 14px; }
.ip-notice-badge span { display:block; margin-top: 5px; color: rgba(255,255,255,.68); font-size: 11px; line-height:1.6; }

.legal-section { padding-bottom: 10px; }
.legal-tabs { display: flex; gap: 4px; padding: 5px; width: fit-content; border: 1px solid var(--ab-line); border-radius: 12px; background: var(--ab-paper2); margin-bottom: 14px; }
.legal-tabs button { border: 0; background: transparent; border-radius: 8px; padding: 9px 13px; color: var(--ab-muted); cursor: pointer; font: inherit; font-size: 12px; }
.legal-tabs button span { margin-left: 8px; opacity: .45; font-size: 9px; }
.legal-tabs button.active { background: #fff; color: var(--ab-navy); font-weight: 700; box-shadow: 0 2px 8px rgba(19,35,58,.07); }
.legal-panel { border: 1px solid var(--ab-line); border-radius: 20px; background: #fff; padding: 28px; }
.legal-title span { color: var(--ab-gold); font-size: 10px; font-weight: 800; letter-spacing: .14em; }
.legal-title h2 { margin-top: 6px; margin-bottom: 5px; }
.legal-title p { color: var(--ab-muted); font-size: 13px; margin: 0; }
.legal-list { margin: 22px 0 0; padding: 0; list-style: none; display: grid; gap: 10px; }
.legal-list li { display: grid; grid-template-columns: 110px 1fr; gap: 18px; padding: 13px 0; border-top: 1px solid #ECE8DF; }
.legal-list b { color: var(--ab-navy); font-size: 13px; } .legal-list span { color: var(--ab-muted); font-size: 13px; line-height: 1.75; }

@media (max-width: 1000px) {
  .about-hero { padding: 52px 42px; }
  .hero-orbit { right: -50px; opacity: .42; }
  .capability-grid { grid-template-columns: repeat(2, 1fr); }
  .identity-layout, .contact-section { grid-template-columns: 1fr; }
  .contact-grid { grid-template-columns: repeat(3, 1fr); }
}
@media (max-width: 640px) {
  .about-page { padding-bottom: 8px; }
  .about-hero { min-height: 500px; border-radius: 20px; padding: 34px 24px; align-items: flex-start; }
  .hero-copy h1 { font-size: 34px; }
  .hero-copy p { font-size: 14px; }
  .hero-actions { flex-wrap: wrap; }
  .hero-orbit { width: 280px; height: 280px; right: -70px; bottom: -60px; top: auto; opacity: .5; }
  .section-nav { top: 62px; margin: 12px 0 28px; overflow-x: auto; scrollbar-width: none; white-space: nowrap; }
  .section-nav::-webkit-scrollbar { display: none; }
  .section-nav button { flex: 0 0 auto; }
  .about-section { margin-bottom: 46px; }
  .capability-grid, .matrix-grid, .contact-grid { grid-template-columns: 1fr; }
  .capability-card { min-height: 180px; }
  .identity-visual { min-height: 350px; }
  .identity-node { min-width: 78px; padding-inline: 8px; }
  .identity-0 { left: 10px; top: 28px; } .identity-1 { right: 10px; top: 28px; } .identity-2 { right: 8px; bottom: 34px; } .identity-4 { left: 8px; bottom: 34px; }
  .identity-row { grid-template-columns: 38px 1fr; }
  .identity-status { grid-column: 2; justify-self: start; margin-top: -5px; }
  .matrix-card, .legal-panel { padding: 20px; }
  .ip-notice { flex-direction: column; align-items: stretch; }
  .ip-notice-badge { flex-basis: auto; }
  .matrix-head { display: block; } .matrix-badge { display: inline-flex; margin-top: 12px; }
    .social-panel-head { align-items: flex-start; flex-direction: column; }
  .social-grid { grid-template-columns: 1fr; }
  .legal-list li { grid-template-columns: 1fr; gap: 4px; }
}
</style>
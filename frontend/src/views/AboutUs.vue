<template>
  <div class="about-page">
    <!-- 全页斜向平铺水印：SVG pattern 瓦片，纯装饰层，不参与布局、不拦截交互 -->
    <svg class="about-watermark" aria-hidden="true" focusable="false">
      <defs>
        <pattern id="ab-wm-tile" width="360" height="170" patternUnits="userSpaceOnUse">
          <text x="180" y="95" text-anchor="middle" transform="rotate(-24 180 95)">Dev of Jason ouyang · Since 2026.09.12</text>
        </pattern>
      </defs>
      <rect width="100%" height="100%" fill="url(#ab-wm-tile)" />
    </svg>

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
        <div class="hero-stats">
          <div class="hero-stat"><b>08</b><span>BUSINESS MODULES</span></div>
          <div class="hero-stat"><b>05</b><span>IDENTITY ROLES</span></div>
          <div class="hero-stat"><b>01</b><span>CONNECTED NETWORK</span></div>
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

      <!-- 加入我们：Apple 风大标题 + 双通道玻璃卡 + 申请弹窗（求职 / 渠道合作） -->
      <section id="join" class="about-section join-section">
        <div class="join-head">
          <span class="section-kicker">04 · JOIN US</span>
          <h2>下一段旅程，<br /><em>和我们一起写。</em></h2>
          <p>无论你想成为团队的一员，还是以渠道伙伴身份共同增长——选择一条通道，提交申请，剩下的交给我们。</p>
        </div>

        <div class="join-grid">
          <article class="join-card" :class="{ active: joinType === 'job' }" @click="openJoin('job')">
            <div class="join-card-glow"></div>
            <span class="join-card-tag">CAREERS</span>
            <h3>加入团队</h3>
            <p>产品、工程、设计与运营岗位，远程协作，长期主义。</p>
            <span class="join-card-link">查看方向并申请 <span>→</span></span>
          </article>
          <article class="join-card" :class="{ active: joinType === 'partner' }" @click="openJoin('partner')">
            <div class="join-card-glow"></div>
            <span class="join-card-tag">PARTNERSHIP</span>
            <h3>渠道合作</h3>
            <p>区域代理、行业渠道与供应链伙伴，共享平台增长红利。</p>
            <span class="join-card-link">申请成为伙伴 <span>→</span></span>
          </article>
        </div>

        <div class="join-actions">
          <button class="join-cta" type="button" @click="openJoin(joinType)">
            <span>填写加入申请</span>
            <span class="join-cta-arrow">→</span>
          </button>
          <button v-if="logged" class="join-my" type="button" @click="goMyApplications">查看我的申请</button>
        </div>
      </section>

      <!-- 申请弹窗：Apple 风 bottom sheet 式玻璃卡片 -->
      <a-modal v-model:open="joinOpen" :footer="null" :width="640" :mask-closable="!joinSubmitting"
               class="join-modal" :destroy-on-close="true" @cancel="closeJoin">
        <div class="join-sheet">
          <div class="join-sheet-head">
            <span class="join-sheet-kicker">JOIN LANLINK</span>
            <h3>{{ joinType === 'job' ? '加入团队' : '渠道合作' }}</h3>
            <p>{{ joinType === 'job'
              ? '告诉我们你想做的事，我们会认真读完每一份申请。'
              : '告诉我们你的渠道与区域，一起把供给做得更深。' }}</p>
            <div class="join-switch" role="tablist">
              <button :class="{ active: joinType === 'job' }" type="button" @click="switchJoinType('job')">求职加入</button>
              <button :class="{ active: joinType === 'partner' }" type="button" @click="switchJoinType('partner')">渠道合作</button>
              <span class="join-switch-thumb" :class="{ right: joinType === 'partner' }"></span>
            </div>
          </div>

          <a-form layout="vertical" class="join-form" :model="joinForm">
            <div class="join-form-grid">
              <template v-if="joinType === 'job'">
                <a-form-item label="应聘职位" class="span-2">
                  <a-select v-model:value="joinForm.position" placeholder="选择一个方向">
                    <a-select-option v-for="p in jobPositions" :key="p" :value="p">{{ p }}</a-select-option>
                  </a-select>
                </a-form-item>
                <a-form-item label="工作年限">
                  <a-select v-model:value="joinForm.experience" placeholder="选择">
                    <a-select-option v-for="e in experiences" :key="e" :value="e">{{ e }}</a-select-option>
                  </a-select>
                </a-form-item>
              </template>
              <template v-else>
                <a-form-item label="公司 / 机构名称" class="span-2">
                  <a-input v-model:value="joinForm.companyName" placeholder="请输入公司全称" allow-clear />
                </a-form-item>
                <a-form-item label="意向区域">
                  <a-input v-model:value="joinForm.city" placeholder="例如：华东 / 上海" allow-clear />
                </a-form-item>
              </template>

              <a-form-item label="联系人">
                <a-input v-model:value="joinForm.contactName" placeholder="您的姓名" allow-clear />
              </a-form-item>
              <a-form-item label="联系电话">
                <a-input v-model:value="joinForm.phone" placeholder="11 位手机号" allow-clear />
              </a-form-item>
              <a-form-item label="邮箱（选填）" class="span-2">
                <a-input v-model:value="joinForm.email" placeholder="name@company.com" allow-clear />
              </a-form-item>
              <a-form-item :label="joinType === 'job' ? '为什么想加入（选填）' : '合作说明（选填）'" class="span-2">
                <a-textarea v-model:value="joinForm.message" :rows="4" :maxlength="1000" show-count
                            :placeholder="joinType === 'job' ? '介绍一下你的经历、作品或想法' : '介绍一下渠道资源、覆盖区域与合作诉求'" />
              </a-form-item>
            </div>

            <div class="join-submit-row">
              <span class="join-privacy">提交即表示同意平台就本次申请与您联系。</span>
              <button class="join-submit" type="button" :disabled="joinSubmitting" @click="submitJoin">
                <span v-if="!joinSubmitting">提交申请</span>
                <span v-else class="join-spinner"></span>
              </button>
            </div>
          </a-form>
        </div>
      </a-modal>

      <!-- 项目作者与联系矩阵 -->
      <section id="contact" class="about-section contact-section">
        <div class="contact-intro">
          <span class="section-kicker">05 · CONTACT</span>
          <h2>项目作者：Jason Ouyang</h2>
          <p>LANLinkshopping 为个人毕业设计项目。这里集中提供作者邮箱、社交平台与交流社区入口，便于项目展示、沟通与学术交流。</p>
          <a-alert type="info" show-icon message="本页面公开的联系方式由项目作者本人提供，仅用于项目展示、交流与联系。" />
        </div>

        <AuthorCard variant="detailed" :show-socials="true" />

        <div class="contact-grid">
          <article class="contact-card">
            <span class="contact-icon"><img class="contact-icon-image" src="https://cdn.simpleicons.org/qq/13233A" alt="QQ邮箱图标" loading="lazy" decoding="async" /></span>
            <span class="contact-label">QQ邮箱</span>
            <a href="mailto:3514485358@qq.com" class="contact-link">3514485358@qq.com</a>
            <small>项目联系 / 日常沟通</small>
            <a-button block @click="copyText('3514485358@qq.com', '主要邮箱')">复制邮箱</a-button>
          </article>
          <article class="contact-card">
            <span class="contact-icon"><img class="contact-icon-image" src="https://cdn.simpleicons.org/gmail/13233A" alt="Google Gmail 图标" loading="lazy" decoding="async" /></span>
            <span class="contact-label">Google Gmail</span>
            <a href="mailto:ouyangjason001@gmail.com" class="contact-link">ouyangjason001@gmail.com</a>
            <small>备用联系邮箱</small>
            <a-button block @click="copyText('ouyangjason001@gmail.com', '备用邮箱')">复制邮箱</a-button>
          </article>
          <article class="contact-card">
            <span class="contact-icon"><img class="contact-icon-image" src="https://cdn.simpleicons.org/whatsapp/13233A" alt="WhatsApp 图标" loading="lazy" decoding="async" /></span>
            <span class="contact-label">WhatsApp</span>
            <a class="contact-link" href="https://wa.me/OuyangJason?s=t" target="_blank" rel="noopener noreferrer">WhatsApp · OuyangJason</a>
            <small>点击“显示二维码”查看扫码方式，也可直接打开 WhatsApp。</small>
            <div v-if="whatsappQrVisible" class="whatsapp-qr-panel">
              <img class="contact-qr" src="/assets/contact/whatsapp-qr.svg" alt="WhatsApp OuyangJason 联系二维码" />
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
          <p class="ip-notice-sign">Dev of Jason ouyang · Since 2026.09.12</p>
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

    <!-- 底部署名：开发者、项目启动时间与版权信息 -->
    <footer class="about-footer" aria-label="开发者署名与版权">
      <span class="about-footer-brand">LANLinkShopping · 关于我们</span>
      <span class="about-footer-sign">
        <span>Dev of Jason ouyang · Since 2026.09.12</span>
        <i aria-hidden="true">·</i>
        <span>© 2026 Jason Ouyang</span>
      </span>
    </footer>
  </div>
</template>

<script setup>
import { computed, onMounted, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import AuthorCard from '../components/AuthorCard.vue'
import { useRouter, useRoute } from 'vue-router'
import { joinApi } from '../api'
import { useUserStore } from '../store/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const logged = computed(() => userStore.logged)
const activeSection = ref('company')
const whatsappQrVisible = ref(false)
const legalTab = ref(route.query.legal === 'ip' ? 'ip' : 'terms')

// ===== 加入我们（求职 / 渠道合作）=====
const joinOpen = ref(false)
const joinType = ref('job')
const joinSubmitting = ref(false)
const jobPositions = ['前端 / 全栈工程师', '后端工程师（Java）', '产品经理', 'UI / UX 设计师', '运营与增长', '数据分析']
const experiences = ['应届 / 1 年内', '1-3 年', '3-5 年', '5 年以上']
const emptyJoinForm = () => ({
  position: undefined, companyName: '', contactName: '', phone: '',
  email: '', city: '', experience: undefined, message: ''
})
const joinForm = reactive(emptyJoinForm())

const sections = [
  { key: 'company', index: '01', title: '平台定位' },
  { key: 'join', index: '04', title: '加入我们' },
  { key: 'contact', index: '05', title: '联系我们' },
  { key: 'terms', index: '06', title: '服务条款' },
  { key: 'ip', index: '07', title: '知识产权' }
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
  const targets = ['company', 'join', 'contact', 'terms'].map(id => document.getElementById(id)).filter(Boolean)
  observer = new IntersectionObserver(entries => {
    const visible = entries
      .filter(entry => entry.isIntersecting)
      .sort((a, b) => b.intersectionRatio - a.intersectionRatio)
    if (visible[0]?.target?.id) activeSection.value = visible[0].target.id
  }, { rootMargin: '-96px 0px -55% 0px', threshold: [0.15, 0.35, 0.6] })
  targets.forEach(el => observer.observe(el))
})
onBeforeUnmount(() => observer?.disconnect())

function resetJoinForm() {
  Object.assign(joinForm, emptyJoinForm())
}
function openJoin(type) {
  if (!logged.value) {
    message.warning('请先登录后再提交申请')
    router.push({ path: '/login', query: { redirect: '/about' } })
    return
  }
  joinType.value = type
  joinOpen.value = true
}
function closeJoin() {
  if (!joinSubmitting.value) joinOpen.value = false
}
function switchJoinType(type) {
  joinType.value = type
}
function validateJoin() {
  const name = (joinForm.contactName || '').trim()
  const phone = (joinForm.phone || '').trim()
  const email = (joinForm.email || '').trim()
  if (name.length < 2) return '请填写联系人姓名（至少 2 个字符）'
  if (!/^1[3-9]\d{9}$/.test(phone)) return '请输入正确的 11 位手机号'
  if (email && !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) return '邮箱格式不正确'
  if (joinType.value === 'job' && !joinForm.position) return '请选择应聘职位'
  if (joinType.value === 'partner' && !(joinForm.companyName || '').trim()) return '请填写公司 / 机构名称'
  return ''
}
async function submitJoin() {
  const err = validateJoin()
  if (err) { message.warning(err); return }
  joinSubmitting.value = true
  try {
    await joinApi.apply({
      applyType: joinType.value,
      position: joinType.value === 'job' ? joinForm.position : null,
      companyName: joinType.value === 'partner' ? joinForm.companyName.trim() : null,
      contactName: joinForm.contactName.trim(),
      phone: joinForm.phone.trim(),
      email: (joinForm.email || '').trim(),
      city: (joinForm.city || '').trim(),
      experience: joinType.value === 'job' ? (joinForm.experience || '') : '',
      message: (joinForm.message || '').trim()
    })
    message.success('申请已提交，审核结果将通过站内消息通知你')
    joinOpen.value = false
    resetJoinForm()
    setTimeout(() => goMyApplications(), 700)
  } catch (e) {
    // 请求拦截器已统一提示
  } finally {
    joinSubmitting.value = false
  }
}
function goMyApplications() {
  router.push({ path: '/me', query: { tab: 'join' } })
}

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
/* 全页斜向平铺水印：SVG pattern 瓦片（浅色淡墨 / 深色淡光），无运行时逻辑 */
.about-page { position: relative; }
.about-watermark {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  z-index: 1;
  pointer-events: none;
}
.about-watermark :deep(text) {
  font-family: -apple-system, "PingFang SC", "Microsoft YaHei", "Segoe UI", sans-serif;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 2px;
  fill: rgba(15, 23, 42, .055);
}
[data-theme='dark'] .about-watermark :deep(text) { fill: rgba(232, 237, 245, .07); }
.about-page { --ab-navy: var(--ll-brand-base); --ab-ink: #0F172A; --ab-muted: #667085; --ab-line: #D9D3C7; --ab-paper: #FBF9F1; --ab-paper2: #F5F0E8; --ab-gold: var(--ll-brand-gold); --ab-gold-strong: var(--ll-brand-gold-strong); --ab-red: var(--ll-brand-red); --ab-blue: var(--ll-brand-blue); color: var(--ab-ink); padding-bottom: 24px; }
.about-hero { min-height: 430px; position: relative; overflow: hidden; border-radius: 28px; padding: 64px 68px; display: flex; align-items: center; background: var(--ll-brand-hero-gradient); box-shadow: var(--ll-glass-shadow); }
.hero-copy { position: relative; z-index: 2; max-width: 650px; }
.eyebrow, .section-kicker { display: inline-flex; align-items: center; gap: 8px; font-size: 11px; font-weight: 800; letter-spacing: .16em; }
/* .eyebrow 落在深色 Hero 上 → 保持亮金（4.58:1）；.section-kicker 落在浅色纸底 → 用加深金（≥5.1:1） */
.eyebrow { color: var(--ll-brand-gold); }
.section-kicker { color: var(--ab-gold-strong); }
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
.identity-node b { display: block; font-size: 12px; } .identity-node span { font-size: 9px; color: rgba(255,255,255,.70); letter-spacing: .08em; }
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
.legal-title span { color: var(--ab-gold-strong); font-size: 10px; font-weight: 800; letter-spacing: .14em; }
.legal-title h2 { margin-top: 6px; margin-bottom: 5px; }
.legal-title p { color: var(--ab-muted); font-size: 13px; margin: 0; }
.legal-list { margin: 22px 0 0; padding: 0; list-style: none; display: grid; gap: 10px; }
.legal-list li { display: grid; grid-template-columns: 110px 1fr; gap: 18px; padding: 13px 0; border-top: 1px solid #ECE8DF; }
.legal-list b { color: var(--ab-navy); font-size: 13px; } .legal-list span { color: var(--ab-muted); font-size: 13px; line-height: 1.75; }

/* 知识产权区块内的开发者署名：与正文保持明显的层级差 */
.ip-notice-sign { margin: 12px 0 0; color: var(--ab-gold-strong); font-size: 11px; font-weight: 700; letter-spacing: .08em; }

/* 底部署名栏：内容流末端，弱化于正文 */
.about-footer { position: relative; z-index: 2; display: flex; align-items: center; justify-content: space-between; gap: 14px; margin-top: 8px; padding: 18px 4px 6px; border-top: 1px solid var(--ab-line); }
.about-footer-brand { color: var(--ab-muted); font-size: 12px; font-weight: 600; letter-spacing: .02em; }
.about-footer-sign { display: inline-flex; align-items: center; gap: 8px; color: var(--ab-muted); font-size: 11px; letter-spacing: .04em; }
.about-footer-sign i { color: #C8C2B6; font-style: normal; }
.about-footer-sign span:last-child { color: #98A2B3; }
[data-theme='dark'] .about-footer { border-top-color: rgba(255,255,255,.12); }
[data-theme='dark'] .about-footer-brand,
[data-theme='dark'] .about-footer-sign { color: #A7B0BD; }
[data-theme='dark'] .about-footer-sign span:last-child { color: #8A94A3; }

/* 打印时隐藏装饰水印 */
@media print { .about-watermark { display: none; } }

@media (max-width: 1000px) {
  .about-hero { padding: 52px 42px; }
  .hero-orbit { right: -50px; opacity: .42; }
  .capability-grid { grid-template-columns: repeat(2, 1fr); }
  .identity-layout, .contact-section { grid-template-columns: 1fr; }
  .contact-grid { grid-template-columns: repeat(3, 1fr); }
}
@media (max-width: 640px) {
  .about-page { padding-bottom: 8px; }
  .about-watermark :deep(text) { font-size: 11px; letter-spacing: 1.4px; }
  .about-footer { flex-direction: column; align-items: flex-start; gap: 6px; padding: 16px 2px 4px; }
  .about-footer-sign { flex-wrap: wrap; gap: 6px; }
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

/* ===================== 加入我们（Apple 风） ===================== */
.join-section {
  position: relative;
  padding: 8px 0 4px;
}
.join-head { max-width: 720px; }
.join-head h2 {
  margin: 14px 0 14px;
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display", "PingFang SC", "Segoe UI", sans-serif;
  font-size: clamp(30px, 4vw, 48px);
  line-height: 1.1;
  letter-spacing: -.04em;
  font-weight: 750;
}
.join-head h2 em {
  font-style: normal;
  background: linear-gradient(100deg, var(--ab-gold-strong), var(--ll-brand-blue, #2563EB));
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.join-head p { margin: 0; color: var(--ab-muted); font-size: 15px; line-height: 1.85; max-width: 640px; }

.join-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; margin-top: 30px; }
.join-card {
  position: relative;
  overflow: hidden;
  cursor: pointer;
  padding: 30px 30px 26px;
  border-radius: 26px;
  border: 1px solid rgba(255, 255, 255, .75);
  background: linear-gradient(155deg, rgba(255,255,255,.92), rgba(255,255,255,.58));
  backdrop-filter: blur(20px) saturate(160%);
  -webkit-backdrop-filter: blur(20px) saturate(160%);
  box-shadow: 0 18px 44px rgba(15, 23, 42, .07), inset 0 1px 0 rgba(255,255,255,.9);
  transition: transform .38s cubic-bezier(.22,.61,.36,1), box-shadow .38s ease, border-color .3s ease;
}
.join-card:hover { transform: translateY(-6px) scale(1.012); box-shadow: 0 30px 70px rgba(15,23,42,.13), inset 0 1px 0 rgba(255,255,255,.95); }
.join-card.active { border-color: rgba(200,164,92,.65); }
.join-card-glow {
  position: absolute; inset: -40% -30% auto auto;
  width: 320px; height: 320px; border-radius: 50%;
  background: radial-gradient(circle, rgba(200,164,92,.35), transparent 62%);
  opacity: 0; transition: opacity .38s ease; pointer-events: none;
}
.join-card:hover .join-card-glow { opacity: 1; }
.join-card-tag { position: relative; display: inline-block; font-size: 10px; font-weight: 800; letter-spacing: .18em; color: var(--ab-gold-strong); }
.join-card h3 { position: relative; margin: 10px 0 8px; font-size: 24px; font-weight: 700; letter-spacing: -.02em; }
.join-card p { position: relative; margin: 0 0 20px; color: var(--ab-muted); font-size: 14px; line-height: 1.8; }
.join-card-link { position: relative; display: inline-flex; align-items: center; gap: 6px; font-size: 14px; font-weight: 650; color: var(--ab-ink); }
.join-card-link span { transition: transform .3s ease; }
.join-card:hover .join-card-link span { transform: translateX(4px); }

.join-actions { display: flex; align-items: center; gap: 18px; margin-top: 28px; flex-wrap: wrap; }
.join-cta {
  display: inline-flex; align-items: center; gap: 10px;
  padding: 14px 28px; border-radius: 999px; cursor: pointer;
  color: #fff; font-size: 15px; font-weight: 650; letter-spacing: -.01em;
  border: 1px solid rgba(255,255,255,.18);
  background: linear-gradient(180deg, #23272f, #0b0f16);
  box-shadow: inset 0 1px 0 rgba(255,255,255,.28), 0 14px 34px rgba(15,23,42,.22);
  transition: transform .28s cubic-bezier(.22,.61,.36,1), box-shadow .28s ease, filter .28s ease;
}
.join-cta:hover { transform: translateY(-2px) scale(1.02); filter: brightness(1.08); box-shadow: inset 0 1px 0 rgba(255,255,255,.34), 0 20px 44px rgba(15,23,42,.28); }
.join-cta-arrow { transition: transform .3s ease; }
.join-cta:hover .join-cta-arrow { transform: translateX(4px); }
.join-my {
  background: none; border: 0; padding: 6px 2px; cursor: pointer;
  font-size: 14px; font-weight: 600; color: var(--ab-muted);
  border-bottom: 1px solid transparent; transition: color .2s ease, border-color .2s ease;
}
.join-my:hover { color: var(--ab-ink); border-color: currentColor; }

[data-theme='dark'] .join-card {
  border-color: rgba(255,255,255,.12);
  background: linear-gradient(155deg, rgba(30,41,59,.78), rgba(15,23,42,.55));
  box-shadow: 0 18px 44px rgba(0,0,0,.28), inset 0 1px 0 rgba(255,255,255,.08);
}
[data-theme='dark'] .join-card-link { color: #E2E8F0; }
[data-theme='dark'] .join-my { color: #94A3B8; }

@media (max-width: 860px) {
  .join-grid { grid-template-columns: 1fr; }
  .join-card { padding: 24px; }
}

/* 内容层级：压在全页装饰水印之上，避免水印文字盖住卡片 */
.about-hero, .section-nav, main, .about-footer { position: relative; z-index: 2; }

/* 大标题平衡换行，避免孤字成行 */
.hero-copy h1, .section-heading h2, .contact-intro h2, .legal-title h2, .matrix-head h2, .join-head h2, .ip-notice-copy h2 {
  text-wrap: balance;
}

/* ============================================================
   Apple 风整页重构（2026.10）
   参考 21st.dev：Animated hero / Finance Bento / Spotlight Card /
   Liquid Glass Button / Tabs / 极简 Footer
   设计语言：系统字体、-0.03~-0.05em 紧字距、20–32px 圆角、
   1px 发丝边、16–20px 玻璃模糊、160–220ms ease-out 动效。
   ============================================================ */
.about-page {
  --ab-ink: #1D1D1F;
  --ab-muted: #6E6E73;
  --ab-paper: #F5F5F7;
  --ab-paper2: #FAFAFC;
  --ab-line: rgba(0, 0, 0, .08);
  --ab-gold-strong: #8C6A1F;
  --ab-navy: #131A2A;
  padding-bottom: 8px;
}
[data-theme='dark'] .about-page {
  --ab-ink: #F5F5F7;
  --ab-muted: #A1A1A6;
  --ab-paper: #16181D;
  --ab-paper2: #1C1F26;
  --ab-line: rgba(255, 255, 255, .1);
  --ab-navy: #E8EDF5;
}

/* ---------- Hero：暗色产品舞台 + 数据胶囊 ---------- */
.about-hero {
  min-height: 560px;
  padding: 72px 76px;
  border-radius: 36px;
  background: radial-gradient(120% 120% at 12% 0%, #22304A 0%, #101725 55%, #0A0E17 100%);
  box-shadow: 0 40px 100px rgba(10, 15, 26, .28);
  align-items: center;
}
.about-hero::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, .045) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, .045) 1px, transparent 1px);
  background-size: 56px 56px;
  -webkit-mask-image: radial-gradient(80% 70% at 30% 40%, #000 20%, transparent 100%);
  mask-image: radial-gradient(80% 70% at 30% 40%, #000 20%, transparent 100%);
  pointer-events: none;
}
.hero-copy { max-width: 760px; }
.hero-copy h1 {
  margin: 20px 0 22px;
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display", "PingFang SC", "Segoe UI", sans-serif;
  font-size: clamp(44px, 5.6vw, 76px);
  line-height: .98;
  letter-spacing: -.045em;
  font-weight: 700;
}
.hero-copy h1 em {
  background: linear-gradient(100deg, #F6D38A, #C8A45C 55%, #8FB3FF);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.hero-copy p { max-width: 620px; font-size: 17px; line-height: 1.85; color: rgba(255, 255, 255, .66); }
.eyebrow { font-size: 11px; letter-spacing: .22em; color: rgba(246, 211, 138, .92); }
.eyebrow::before { width: 32px; background: rgba(246, 211, 138, .5); }
.hero-actions { margin-top: 32px; gap: 14px; }
.hero-primary, .hero-secondary { height: 46px; padding: 0 24px; border-radius: 999px; font-size: 14.5px; }
.hero-primary {
  background: #F5F5F7;
  color: #0B0F16;
  font-weight: 650;
  box-shadow: 0 10px 30px rgba(255, 255, 255, .14);
  transition: transform .18s ease-out, background .18s ease-out;
}
.hero-primary:hover { background: #fff; color: #0B0F16; transform: translateY(-1px); }
.hero-secondary { color: #fff; border: 1px solid rgba(255, 255, 255, .22); background: rgba(255, 255, 255, .08); backdrop-filter: blur(16px) saturate(140%); }
.hero-stats { display: flex; gap: 10px; margin-top: 40px; flex-wrap: wrap; }
.hero-stat {
  display: flex; flex-direction: column; gap: 2px;
  padding: 12px 18px; border-radius: 16px;
  border: 1px solid rgba(255, 255, 255, .14);
  background: rgba(255, 255, 255, .07);
  backdrop-filter: blur(16px) saturate(140%);
}
.hero-stat b { font-size: 20px; letter-spacing: -.02em; color: #fff; }
.hero-stat span { font-size: 9px; letter-spacing: .16em; color: rgba(255, 255, 255, .55); }
.hero-orbit { right: 40px; top: 50%; transform: translateY(-50%) scale(.92); opacity: .9; }
.hero-glow { filter: blur(70px); opacity: .22; }

/* ---------- 页内导航：Apple 分段胶囊 ---------- */
.section-nav {
  position: sticky; top: 76px; z-index: 20;
  display: flex; gap: 4px; width: fit-content; max-width: 100%;
  margin: 20px auto 56px; padding: 4px;
  border: 1px solid var(--ab-line); border-radius: 999px;
  background: rgba(255, 255, 255, .72);
  backdrop-filter: blur(20px) saturate(180%);
  box-shadow: 0 12px 34px rgba(0, 0, 0, .08);
  overflow-x: auto; scrollbar-width: none;
}
.section-nav::-webkit-scrollbar { display: none; }
.section-nav button { border-radius: 999px; padding: 9px 16px; font-size: 13px; letter-spacing: -.01em; white-space: nowrap; }
.section-nav button span { margin-right: 6px; font-size: 10px; opacity: .45; }
.section-nav button.active { background: var(--ab-ink); color: #fff; box-shadow: 0 6px 18px rgba(0, 0, 0, .18); font-weight: 600; }
[data-theme='dark'] .section-nav { background: rgba(22, 24, 29, .72); }
[data-theme='dark'] .section-nav button.active { background: #F5F5F7; color: #111; }

/* ---------- 章节节奏与标题 ---------- */
.about-section { margin-bottom: 104px; scroll-margin-top: 150px; }
.section-heading, .section-heading.compact { max-width: 820px; margin-bottom: 40px; }
.section-kicker { font-size: 10.5px; letter-spacing: .2em; color: var(--ab-gold-strong); }
.section-heading h2, .contact-intro h2, .legal-title h2, .matrix-head h2 {
  margin: 14px 0;
  font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display", "PingFang SC", "Segoe UI", sans-serif;
  font-size: clamp(32px, 4vw, 52px);
  line-height: 1.06;
  letter-spacing: -.035em;
  font-weight: 700;
  color: var(--ab-ink);
}
.section-heading p, .contact-intro p { max-width: 680px; font-size: 16px; line-height: 1.75; color: var(--ab-muted); }

/* ---------- 01 Bento：首卡深色主推 + 宽卡收尾 ---------- */
.capability-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; }
.capability-card {
  min-height: 250px; padding: 32px;
  border-radius: 28px; border: 1px solid var(--ab-line);
  background: #fff;
  box-shadow: 0 12px 40px rgba(0, 0, 0, .05);
  transition: transform .2s ease-out, box-shadow .2s ease-out;
}
.capability-card:hover { transform: translateY(-2px); box-shadow: 0 24px 60px rgba(0, 0, 0, .1); }
.capability-card:first-child {
  grid-column: span 2; min-height: 230px;
  background: linear-gradient(135deg, #1B2334, #0D1220);
  border-color: rgba(255, 255, 255, .08);
}
.capability-card:first-child h3 { color: #fff; font-size: 30px; letter-spacing: -.03em; }
.capability-card:first-child p { max-width: 620px; color: rgba(255, 255, 255, .62); font-size: 14.5px; }
.capability-card:first-child .card-index { color: #F6D38A; }
.capability-card:first-child .capability-icon { background: rgba(255, 255, 255, .1); color: #F6D38A; }
.capability-card:first-child .card-arrow { color: rgba(255, 255, 255, .5); }
.capability-card:nth-child(4) { grid-column: span 2; }
.capability-icon { width: 48px; height: 48px; border-radius: 14px; background: var(--ab-paper); color: var(--ab-ink); font-size: 22px; margin-bottom: 28px; }
.capability-card h3 { margin: 8px 0; font-size: 22px; letter-spacing: -.02em; color: var(--ab-ink); }
.capability-card p { font-size: 14px; line-height: 1.7; color: var(--ab-muted); }
.card-index { color: var(--ab-gold-strong); letter-spacing: .16em; }
.card-arrow { right: 26px; top: 26px; }
[data-theme='dark'] .capability-card { background: #1C1F26; }
[data-theme='dark'] .capability-card:first-child { background: linear-gradient(135deg, #202A3E, #0D1220); }

/* ---------- 02 身份：深色舞台 + Apple 列表 ---------- */
.identity-layout { grid-template-columns: 1fr 1fr; gap: 24px; }
.identity-visual {
  min-height: 440px; border-radius: 32px;
  background: radial-gradient(120% 100% at 30% 20%, #1E2A44, #0C111C 70%);
  box-shadow: 0 24px 60px rgba(10, 15, 26, .24);
}
.identity-list {
  display: grid; gap: 0;
  border: 1px solid var(--ab-line); border-radius: 24px;
  background: #fff; overflow: hidden;
}
.identity-row {
  min-height: 76px; padding: 12px 20px;
  border: 0; border-bottom: 1px solid var(--ab-line); border-radius: 0;
  background: transparent;
  transition: background .12s ease-out;
}
.identity-row:last-child { border-bottom: 0; }
.identity-row:hover { background: rgba(0, 0, 0, .035); }
.identity-tag { width: 38px; height: 38px; border-radius: 50%; background: var(--ab-paper); color: var(--ab-ink); font-size: 13px; }
.identity-copy strong { font-size: 15px; color: var(--ab-ink); }
.identity-copy span { font-size: 13px; color: var(--ab-muted); }
.identity-status { font-size: 11px; letter-spacing: .06em; color: var(--ab-gold-strong); }
[data-theme='dark'] .identity-list { background: #1C1F26; }
[data-theme='dark'] .identity-row:hover { background: rgba(255, 255, 255, .04); }
[data-theme='dark'] .identity-tag { background: #242830; }

/* ---------- 03 能力矩阵：浅灰大片 + Spotlight 卡片 ---------- */
.matrix-card { padding: 40px; border-radius: 32px; background: var(--ab-paper); border: 1px solid var(--ab-line); }
.matrix-grid { gap: 16px; }
.module-card {
  min-height: 240px; padding: 28px;
  border-radius: 24px; background: #fff; border: 1px solid var(--ab-line);
  box-shadow: 0 10px 30px rgba(0, 0, 0, .04);
  transition: transform .18s ease-out, box-shadow .18s ease-out;
}
.module-card:hover { transform: translateY(-2px); box-shadow: 0 20px 50px rgba(0, 0, 0, .09); }
.module-number { font-size: 40px; letter-spacing: -.04em; color: rgba(0, 0, 0, .08); }
.module-icon { width: 44px; height: 44px; border-radius: 13px; background: var(--ab-paper); color: var(--ab-ink); }
.module-card h3 { margin: 20px 0 6px; font-size: 21px; letter-spacing: -.02em; color: var(--ab-ink); }
.module-card p { font-size: 13.5px; color: var(--ab-muted); }
.module-tags span { padding: 5px 10px; border-radius: 999px; background: var(--ab-paper); color: var(--ab-muted); font-size: 10.5px; }
.matrix-badge { border-radius: 999px; border-color: var(--ab-line); color: var(--ab-muted); font-size: 9.5px; }
[data-theme='dark'] .module-card { background: #1C1F26; }
[data-theme='dark'] .module-number { color: rgba(255, 255, 255, .12); }

/* ---------- 05 联系：整宽叙事 + 三张图标卡 ---------- */
.contact-section { grid-template-columns: 1fr; gap: 28px; }
.contact-intro { max-width: 820px; padding-top: 0; }
.contact-intro .ant-alert { margin-top: 20px; border-radius: 16px; }
.contact-grid { grid-template-columns: repeat(3, 1fr); gap: 16px; }
.contact-card {
  min-height: 250px; padding: 28px;
  border-radius: 26px; border: 1px solid var(--ab-line);
  background: #fff; box-shadow: 0 12px 40px rgba(0, 0, 0, .05);
  transition: transform .2s ease-out, box-shadow .2s ease-out;
}
.contact-card:hover { transform: translateY(-2px); box-shadow: 0 22px 54px rgba(0, 0, 0, .1); }
.contact-icon { width: 48px; height: 48px; border-radius: 14px; background: var(--ab-paper); }
.contact-icon-image { width: 22px; height: 22px; filter: none; }
.contact-label { margin-top: 20px; font-size: 11px; letter-spacing: .12em; color: var(--ab-muted); }
.contact-card strong { font-size: 15px; }
.contact-link { margin: 6px 0; font-size: 15px; font-weight: 600; color: var(--ab-ink); }
.contact-card small { min-height: 44px; font-size: 12.5px; color: var(--ab-muted); }
.contact-card .ant-btn { border-radius: 999px; height: 38px; }
.whatsapp-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 12px; }
.whatsapp-actions .ant-btn { flex: 1 1 auto; min-width: 0; }
[data-theme='dark'] .contact-card { background: #1C1F26; }

/* AuthorCard 对齐 Apple 卡片体系（含暗色） */
.contact-section :deep(.author-card--detailed) {
  border-radius: 28px;
  padding: 26px 28px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, .05);
}
[data-theme='dark'] .contact-section :deep(.author-card) {
  border-color: rgba(255, 255, 255, .1);
  background: linear-gradient(120deg, rgba(28, 31, 38, .96), rgba(23, 26, 32, .92));
  color: #F5F5F7;
  box-shadow: 0 12px 40px rgba(0, 0, 0, .3);
}
[data-theme='dark'] .contact-section :deep(.author-card__copy strong),
[data-theme='dark'] .contact-section :deep(.author-card__group-heading strong) { color: #F5F5F7; }
[data-theme='dark'] .contact-section :deep(.author-card__copy small),
[data-theme='dark'] .contact-section :deep(.author-card__meta),
[data-theme='dark'] .contact-section :deep(.author-card__group-description) { color: #A1A1A6; }
[data-theme='dark'] .contact-section :deep(.author-card__meta) { border-top-color: rgba(255, 255, 255, .1); }
[data-theme='dark'] .contact-section :deep(.author-card__social) { background: rgba(255, 255, 255, .04); border-color: rgba(255, 255, 255, .08); }
[data-theme='dark'] .contact-section :deep(.author-card__social-icon) { background: #242830; }
[data-theme='dark'] .contact-section :deep(.author-card__social-icon img) { filter: brightness(0) invert(1); }
[data-theme='dark'] .contact-section :deep(.author-card__developer) { background: rgba(255, 255, 255, .03); border-color: rgba(255, 255, 255, .08); }

/* ---------- 知识产权提示：暖色磨砂卡 ---------- */
.ip-notice {
  padding: 44px 48px; gap: 40px;
  border-radius: 32px;
  border: 1px solid rgba(200, 164, 92, .22);
  background: linear-gradient(180deg, #FFF9EC, #FFFDF8);
}
.ip-notice-copy h2 { margin: 8px 0 10px; font-size: clamp(26px, 3vw, 38px); letter-spacing: -.03em; color: var(--ab-ink); }
.ip-notice-copy p { max-width: 780px; font-size: 14.5px; line-height: 1.9; color: var(--ab-muted); }
.ip-notice-badge { flex: 0 0 240px; padding: 22px 26px; border-radius: 22px; background: #fff; border: 1px solid rgba(200, 164, 92, .25); box-shadow: 0 12px 30px rgba(140, 106, 31, .08); }
.ip-notice-badge strong { color: var(--ab-gold-strong); font-size: 15px; }
.ip-notice-badge span { color: var(--ab-muted); }
[data-theme='dark'] .ip-notice { background: linear-gradient(180deg, #1E1B14, #17150F); }
[data-theme='dark'] .ip-notice-badge { background: #232019; }

/* ---------- 法务：分段控件 + 白色长卡 ---------- */
.legal-tabs {
  display: inline-flex; gap: 4px; width: fit-content;
  padding: 4px; margin-bottom: 22px;
  border-radius: 999px; border: 1px solid var(--ab-line);
  background: var(--ab-paper);
}
.legal-tabs button { border: 0; background: transparent; border-radius: 999px; padding: 9px 16px; font-size: 13px; color: var(--ab-muted); cursor: pointer; transition: .18s ease-out; }
.legal-tabs button span { margin-left: 6px; font-size: 10px; opacity: .5; }
.legal-tabs button.active { background: #fff; color: var(--ab-ink); font-weight: 600; box-shadow: 0 4px 14px rgba(0, 0, 0, .08); }
.legal-panel { padding: 40px 44px; border-radius: 32px; border: 1px solid var(--ab-line); background: #fff; box-shadow: 0 12px 40px rgba(0, 0, 0, .04); }
.legal-title h2 { margin: 8px 0; }
.legal-title p { color: var(--ab-muted); font-size: 14px; }
.legal-list { margin-top: 24px; gap: 0; }
.legal-list li { display: grid; grid-template-columns: 160px 1fr; gap: 24px; padding: 18px 0; border-top: 1px solid var(--ab-line); }
.legal-list b { font-size: 14px; color: var(--ab-ink); }
.legal-list span { font-size: 13.5px; line-height: 1.8; color: var(--ab-muted); }
[data-theme='dark'] .legal-tabs button.active { background: #242830; }
[data-theme='dark'] .legal-panel { background: #1C1F26; }

/* ---------- 页脚：极简发丝线 ---------- */
.about-footer {
  margin-top: 40px; padding: 40px 0 12px;
  border-top: 1px solid var(--ab-line);
  display: flex; justify-content: space-between; align-items: center; gap: 16px;
  font-size: 12px; letter-spacing: .02em;
}
.about-footer-brand { color: var(--ab-muted); font-size: 12px; font-weight: 600; }
.about-footer-sign { font-size: 11.5px; color: var(--ab-muted); }

/* ---------- 响应式 ---------- */
@media (max-width: 1024px) {
  .about-hero { padding: 56px 44px; }
  .capability-grid { grid-template-columns: 1fr 1fr; }
  .identity-layout { grid-template-columns: 1fr; }
  .contact-grid { grid-template-columns: 1fr; }
  .matrix-card { padding: 30px; }
}
@media (max-width: 760px) {
  .about-hero { min-height: auto; padding: 48px 26px; border-radius: 28px; }
  .hero-copy h1 { font-size: clamp(34px, 9vw, 48px); }
  .hero-orbit { display: none; }
  .hero-stats { margin-top: 28px; }
  .hero-stat { padding: 10px 14px; }
  .capability-grid { grid-template-columns: 1fr; }
  .capability-card:first-child, .capability-card:nth-child(4) { grid-column: span 1; }
  .capability-card { min-height: 200px; padding: 24px; }
  .matrix-grid { grid-template-columns: 1fr; }
  .matrix-card { padding: 24px; border-radius: 26px; }
  .legal-panel { padding: 26px 20px; border-radius: 26px; }
  .legal-list li { grid-template-columns: 1fr; gap: 6px; }
  .ip-notice { flex-direction: column; align-items: stretch; padding: 28px 24px; }
  .ip-notice-badge { flex-basis: auto; }
  .contact-card { min-height: 220px; padding: 24px; }
  .about-footer { flex-direction: column; align-items: flex-start; gap: 6px; }
  .section-nav { margin-bottom: 40px; }
}
</style>

<!-- 弹窗被 teleport 到 body，用非 scoped 样式覆盖 AntD 结构 -->
<style>
.join-modal .ant-modal-content {
  padding: 0;
  overflow: hidden;
  border-radius: 28px;
  border: 1px solid rgba(255, 255, 255, .7);
  background: rgba(255, 255, 255, .88);
  backdrop-filter: blur(30px) saturate(180%);
  -webkit-backdrop-filter: blur(30px) saturate(180%);
  box-shadow: 0 40px 120px rgba(15, 23, 42, .28);
}
.join-modal .ant-modal-close { top: 18px; right: 18px; border-radius: 999px; }
.join-modal .ant-modal-close:hover { background: rgba(15, 23, 42, .06); }

.join-sheet { padding: 30px 32px 26px; }
.join-sheet-head { padding-bottom: 6px; }
.join-sheet-kicker { font-size: 10px; font-weight: 800; letter-spacing: .2em; color: #B08D3F; }
.join-sheet-head h3 { margin: 8px 0 6px; font-family: -apple-system, BlinkMacSystemFont, "SF Pro Display", "PingFang SC", "Segoe UI", sans-serif; font-size: 26px; font-weight: 750; letter-spacing: -.03em; color: #0F172A; }
.join-sheet-head p { margin: 0 0 18px; color: #667085; font-size: 13.5px; line-height: 1.7; }

.join-switch {
  position: relative;
  display: grid; grid-template-columns: 1fr 1fr;
  padding: 4px; margin-bottom: 6px;
  border-radius: 999px; background: rgba(15, 23, 42, .06);
}
.join-switch button {
  position: relative; z-index: 1;
  padding: 9px 0; border: 0; background: none; cursor: pointer;
  font-size: 13.5px; font-weight: 650; color: #667085;
  transition: color .25s ease;
}
.join-switch button.active { color: #0F172A; }
.join-switch-thumb {
  position: absolute; top: 4px; left: 4px;
  width: calc(50% - 4px); height: calc(100% - 8px);
  border-radius: 999px; background: #fff;
  box-shadow: 0 2px 10px rgba(15, 23, 42, .12), inset 0 1px 0 rgba(255,255,255,.9);
  transition: transform .32s cubic-bezier(.22, .61, .36, 1);
}
.join-switch-thumb.right { transform: translateX(100%); }

.join-form { margin-top: 12px; }
.join-form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; }
.join-form-grid .span-2 { grid-column: 1 / -1; }
.join-form .ant-form-item { margin-bottom: 14px; }
.join-form .ant-form-item-label > label { font-size: 12.5px; font-weight: 600; color: #475569; }
.join-form .ant-input, .join-form .ant-select-selector, .join-form textarea.ant-input {
  border-radius: 12px !important;
}
.join-submit-row { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-top: 6px; flex-wrap: wrap; }
.join-privacy { font-size: 11.5px; color: #94A3B8; }
.join-submit {
  display: inline-flex; align-items: center; justify-content: center;
  min-width: 132px; height: 44px; padding: 0 24px;
  border-radius: 999px; border: 1px solid rgba(255,255,255,.18); cursor: pointer;
  color: #fff; font-size: 14.5px; font-weight: 650;
  background: linear-gradient(180deg, #23272f, #0b0f16);
  box-shadow: inset 0 1px 0 rgba(255,255,255,.28), 0 12px 28px rgba(15,23,42,.22);
  transition: transform .25s ease, filter .25s ease;
}
.join-submit:hover:not(:disabled) { transform: translateY(-1px) scale(1.02); filter: brightness(1.08); }
.join-submit:disabled { opacity: .6; cursor: not-allowed; }
.join-spinner {
  width: 16px; height: 16px;
  border: 2px solid rgba(255,255,255,.28);
  border-top-color: #fff; border-radius: 50%;
  animation: join-spin .8s linear infinite;
}
@keyframes join-spin { to { transform: rotate(360deg); } }

[data-theme='dark'] .join-modal .ant-modal-content {
  border-color: rgba(255,255,255,.12);
  background: rgba(17, 24, 39, .9);
  box-shadow: 0 40px 120px rgba(0,0,0,.5);
}
[data-theme='dark'] .join-sheet-head h3 { color: #F1F5F9; }
[data-theme='dark'] .join-sheet-head p { color: #94A3B8; }
[data-theme='dark'] .join-switch { background: rgba(255,255,255,.08); }
[data-theme='dark'] .join-switch button { color: #94A3B8; }
[data-theme='dark'] .join-switch button.active { color: #F8FAFC; }
[data-theme='dark'] .join-switch-thumb { background: rgba(255,255,255,.14); box-shadow: none; }
[data-theme='dark'] .join-form .ant-form-item-label > label { color: #CBD5E1; }

@media (max-width: 640px) {
  .join-form-grid { grid-template-columns: 1fr; }
  .join-sheet { padding: 24px 20px 20px; }
}
</style>

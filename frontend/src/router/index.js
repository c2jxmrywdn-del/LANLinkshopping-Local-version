import { createRouter, createWebHistory } from 'vue-router'
import MainLayout from '../layouts/MainLayout.vue'
import { useUserStore } from '../store/user'
import { trackEvent } from '../utils/analytics'
import { applyRouteSeo } from '../utils/seo'
/**
 * 登录态以 Pinia store 为准（localStorage 只是缓存快照，可被本地篡改、也可能已过期）。
 * store 尚未水合时（例如硬刷新后首次导航）回落到快照，仅用于避免首屏闪回登录页；
 * 真正的权限判定在后端，前端守卫只负责减少无效页面。
 */
function readUser(){
  try{
    const store=useUserStore()
    if(store&&store.user)return store.user
  }catch(e){/* pinia 未激活（极早期导航）时回落快照 */}
  try{return JSON.parse(localStorage.getItem('ll_user')||'null')}catch(e){return null}
}
/** 首次导航用 /auth/me 校准一次，避免快照过期导致"看似已登录、接口全 401" */
let sessionHydrated=false
async function hydrateSession(){
  if(sessionHydrated)return
  sessionHydrated=true
  try{
    const store=useUserStore()
    if(!store.user&&!localStorage.getItem('ll_user'))return
    await store.fetchMe()
  }catch(e){/* 网络失败不阻断导航 */}
}
function hasPerms(user,perms){if(!perms||!perms.length)return true;const owned=(user&&user.identity&&user.identity.permissions)||[];if(owned.includes('admin:all'))return true;return perms.every(p=>owned.includes(p))}
const routes=[{path:'/',component:MainLayout,children:[
{path:'',name:'home',component:()=>import('../views/Home.vue'),meta:{seo:{title:'LANLinkshopping · 聚合型B2B电商平台',description:'LANLinkshopping 聚合型一体多元化解决方案 B2B 电商平台：面向工业大宗集采与供应链协同，覆盖商品商城、商户入驻、询价下单支付、企业钱包与账期、会员活动与物流履约的一站式采购体验。',keywords:'B2B电商,工业品采购,大宗集采,供应链协同,商户入驻,企业账期,询价采购,批发平台,LANLinkshopping',og:{type:'website'}}}},
{path:'mall',name:'mall',component:()=>import('../views/Mall.vue'),meta:{seo:{title:'商品商城 · 工业品选型与批量采购 | LANLinkshopping',description:'B2B 商品商城：集中展示工业品与批量采购商品，支持输入工况、国标代号或参数需求由 AI 智能选型结构化匹配现货，促销商品同步在线。',keywords:'商品商城,工业品采购,AI智能选型,批量采购,大宗集采,B2B批发',og:{type:'website'}}}},
{path:'promotions',redirect:{name:'activity'}},{path:'promotions/:id',redirect:to=>({name:'activity-detail',params:{id:to.params.id}})},
{path:'product/:id',name:'product',component:()=>import('../views/ProductDetail.vue'),meta:{seo:{title:'商品详情 {id} · LANLinkshopping',description:'商品详情：查看商品规格、起订量与阶梯价说明，真实商品信息由商品数据实时渲染。',keywords:'商品详情,工业品采购,规格选型,起订量,阶梯价,B2B电商',og:{type:'product'}}}},
{path:'cart',name:'cart',component:()=>import('../views/Cart.vue'),meta:{auth:true,perms:['cart:manage']}},{path:'checkout',name:'checkout',component:()=>import('../views/Checkout.vue'),meta:{auth:true,perms:['order:create']}},{path:'orders',name:'orders',component:()=>import('../views/MyOrders.vue'),meta:{auth:true,perms:['order:view']}},{path:'orders/:orderNo',name:'order-detail',component:()=>import('../views/OrderDetail.vue'),meta:{auth:true,perms:['order:view']}},
{path:'rfq',name:'rfq',component:()=>import('../views/RfqCenter.vue'),meta:{auth:true}},
{path:'wallet',name:'wallet',component:()=>import('../views/Wallet.vue'),meta:{auth:true}},{path:'credit-term',name:'credit-term',component:()=>import('../views/CreditTermCenter.vue'),meta:{auth:true,perms:['credit:view'],seo:{title:'企业账期 · 授信额度与账单管理 | LANLinkshopping',description:'账期管理：授信额度、账单到期、还款与对账统一管理，可查看已生效额度与调额申请入口，授信申请需企业资质审核。',keywords:'企业账期,账期管理,授信额度,账期账单,调额申请,对账',og:{type:'website'}}}},
{path:'credit-term/apply',name:'credit-term-apply',component:()=>import('../views/CreditTermApply.vue'),meta:{auth:true,perms:['credit:apply'],seo:{title:'账期申请 · 企业授信资质提交 | LANLinkshopping',description:'账期授信申请：提交企业与授信资质信息，由平台审核后开通账期额度，审核结果以平台通知为准。',keywords:'账期申请,授信申请,企业资质,账期审核,B2B金融'}}},
{path:'credit-term/adjust',name:'credit-term-adjust',component:()=>import('../views/CreditTermAdjust.vue'),meta:{auth:true,perms:['credit:apply'],seo:{title:'调额申请 · 调整企业账期额度 | LANLinkshopping',description:'账期调额申请：提交账期额度调整诉求，审核通过后按新额度执行，额度信息以账户页展示为准。',keywords:'调额申请,额度调整,账期额度,授信,企业账期'}}},
{path:'credit-term/bills',name:'credit-term-bills',component:()=>import('../views/CreditTermBills.vue'),meta:{auth:true,perms:['credit:view'],seo:{title:'账单中心 · 账期账单与还款 | LANLinkshopping',description:'账期账单中心：查看账期账单明细、到期时间与还款安排，账单按期核对并支持对账。',keywords:'账期账单,账单中心,账期还款,到期提醒,对账'}}},
{path:'credit-term/bills/:id',name:'credit-term-bill-detail',component:()=>import('../views/CreditTermBillDetail.vue'),meta:{auth:true,perms:['credit:view'],seo:{title:'账期账单详情 · 单笔账单明细 | LANLinkshopping',description:'账期账单详情：查看单笔账期账单的金额、账期周期与明细构成，明细以账单记录为准。',keywords:'账期账单详情,账单明细,账期周期,企业账期'}}},{path:'wallet/transactions',name:'wallet-transactions',component:()=>import('../views/WalletTransactions.vue'),meta:{auth:true}},{path:'wallet/transactions/:id',name:'wallet-transaction-detail',component:()=>import('../views/WalletTransactionDetail.vue'),meta:{auth:true}},{path:'wallet/recharge',name:'wallet-recharge',component:()=>import('../views/WalletRecharge.vue'),meta:{auth:true}},{path:'wallet/recharge/result/:id',name:'wallet-recharge-result',component:()=>import('../views/WalletRechargeResult.vue'),meta:{auth:true}},
{path:'merchant',alias:'/merchant-apply',name:'merchant',component:()=>import('../views/MerchantApply.vue'),meta:{auth:true}},
// 商户中心：二级导航（工作台/商品/订单/物流/流量/资金/店铺），布局内含入驻状态门控
{path:'merchant/center',component:()=>import('../layouts/MerchantCenterLayout.vue'),meta:{auth:true,perms:['merchant:manage']},children:[
{path:'',name:'merchant-center',component:()=>import('../views/merchant/CenterDashboard.vue')},
{path:'products',name:'merchant-center-products',component:()=>import('../views/merchant/CenterProducts.vue')},
{path:'marketing',name:'merchant-center-marketing',component:()=>import('../views/merchant/CenterMarketing.vue')},{path:'marketing-image',name:'merchant-center-marketing-image',component:()=>import('../views/merchant/MarketingImage.vue')},{path:'live',name:'merchant-center-live',component:()=>import('../views/merchant/CenterLive.vue')},
{path:'pricing',name:'merchant-center-pricing',component:()=>import('../views/merchant/CenterPricing.vue')},
{path:'products/import',name:'merchant-center-product-import',component:()=>import('../views/ProductBatchImport.vue')},
{path:'products/:id/edit',name:'merchant-center-product-edit',component:()=>import('../views/merchant/CenterProductEdit.vue')},
{path:'orders',name:'merchant-center-orders',component:()=>import('../views/merchant/CenterOrders.vue')},
{path:'rfq',name:'merchant-center-rfq',component:()=>import('../views/merchant/CenterRfq.vue')},
{path:'orders/:orderNo',name:'merchant-center-order-detail',component:()=>import('../views/merchant/CenterOrderDetail.vue')},
{path:'logistics',name:'merchant-center-logistics',component:()=>import('../views/merchant/CenterLogistics.vue')},
{path:'promotion',name:'merchant-center-promotion',component:()=>import('../views/merchant/CenterPromotion.vue')},
{path:'traffic',name:'merchant-center-traffic',component:()=>import('../views/merchant/CenterTraffic.vue')},
{path:'finance',name:'merchant-center-finance',component:()=>import('../views/merchant/CenterFinance.vue')},
{path:'exit',name:'merchant-center-exit',component:()=>import('../views/merchant/CenterExit.vue')},
{path:'chat',name:'merchant-center-chat',component:()=>import('../views/merchant/CenterChat.vue')},
{path:'store',name:'merchant-center-store',component:()=>import('../views/merchant/CenterStore.vue')},
// 商户中心段 404 兜底：保留侧栏布局，错链/过期深链不再整页死白
{path:':pathMatch(.*)',name:'merchant-center-not-found',component:()=>import('../views/NotFound.vue')}
]},
// 买家↔商家 联系商家对话页（携带 merId+prodId 进入时 find-or-create 后 replace 到会话页）
{path:'chat/:sessionId',name:'chat-session',component:()=>import('../views/Chat.vue'),meta:{auth:true}},
// 旧商户页路由兼容跳转
{path:'merchant/products/import',redirect:{name:'merchant-center-product-import'}},
{path:'merchant/products',redirect:{name:'merchant-center-products'}},
{path:'merchant/traffic',redirect:{name:'merchant-center-traffic'}},
// 活动中心（统一活动系统）：广场/详情公开浏览，领券/参与需登录（未登录时后端 401 → 拦截器引导登录）
{path:'activity',name:'activity',component:()=>import('../views/ActivityCenter.vue'),meta:{seo:{title:'活动中心 · 官方直发活动与限时促销 | LANLinkshopping',description:'平台活动中心：官方直发的限时活动与大促玩法陆续上线，活动详情页可查看参与方式与规则，参与动作需登录账户完成。',keywords:'活动中心,官方直发,限时活动,营销活动,大促,活动规则',og:{type:'website'}}}},
{path:'activity/:id',name:'activity-detail',component:()=>import('../views/ActivityDetail.vue'),meta:{seo:{title:'活动详情 {id} · LANLinkshopping',description:'活动详情：查看当前活动的参与方式、优惠力度与规则说明，具体内容以活动页实际展示为准。',keywords:'活动详情,限时活动,活动规则,优惠活动,B2B营销'}}},
{path:'activity/:id/participation',name:'activity-participation',component:()=>import('../views/ActivityParticipationResult.vue'),meta:{auth:true}},{path:'activity/my',name:'activity-my',component:()=>import('../views/ActivityMy.vue'),meta:{auth:true}},
{path:'activity/rules',name:'activity-rules',component:()=>import('../views/ActivityRules.vue'),meta:{seo:{title:'活动规则 · 平台活动参与规则说明 | LANLinkshopping',description:'活动规则：平台活动的参与条件、优惠叠加与售后处理规则说明，参与前请先阅读本页规则。',keywords:'活动规则,活动参与规则,优惠叠加,平台活动'}}},
// 领券中心（券池聚合+批量领取，浏览公开、领取需登录）/ 任务中心（个人任务与奖励，登录态）/ 大促会场（纯前端聚合公开页）
{path:'coupons',name:'coupon-center',component:()=>import('../views/CouponCenter.vue'),meta:{seo:{title:'领券中心 · 优惠券批量领取 | LANLinkshopping',description:'领券中心：券池一站聚合平台与商户优惠券，支持一键批量领取入账并在结算时自动抵扣货款，领取动作需登录账户。',keywords:'领券中心,优惠券,批量领取,满减券,折扣券,券池',og:{type:'website'}}}},
{path:'tasks',name:'task-center',component:()=>import('../views/TaskCenter.vue'),meta:{auth:true,seo:{title:'任务中心 · 任务奖励与积分成长值 | LANLinkshopping',description:'任务中心：完成平台任务赢取积分与成长值，奖励经会员积分闭环自动到账，任务进度需登录后查看。',keywords:'任务中心,平台任务,任务奖励,积分奖励,成长值,B2B福利'}}},
{path:'venue',name:'campaign-venue',component:()=>import('../views/CampaignVenue.vue'),meta:{seo:{title:'大促会场 · 行业大促与热销现货 | LANLinkshopping',description:'大促会场：行业大促一站逛齐，促销让利与热销现货同场直采，可按会场分区浏览进行中的活动与专属优惠。',keywords:'大促会场,行业大促,促销让利,热销现货,主题会场,B2B电商',og:{type:'website'}}}},
{path:'live',name:'live-square',component:()=>import('../views/live/LiveSquare.vue'),meta:{seo:{title:'直播广场 · 工业品直播带货与商家直播 | LANLinkshopping',description:'直播广场：汇聚平台商家直播与工业品讲解直播，边看边买支持即时咨询下单，直播安排以各直播间实际开播为准。',keywords:'直播广场,工业品直播,商家直播,直播带货,边看边买',og:{type:'website'}}}},{path:'live/:roomId',name:'live-room',component:()=>import('../views/live/LiveRoomView.vue'),meta:{seo:{title:'直播间 {roomId} · LANLinkshopping 直播',description:'直播间：观看商家直播讲解与商品展示，直播间内容与在售商品以直播实际展示为准。',keywords:'直播间,直播观看,工业品直播,直播购物'}}},{path:'membership',name:'membership',component:()=>import('../views/MembershipCenter.vue'),meta:{auth:true,seo:{title:'会员中心 · 等级成长与积分权益 | LANLinkshopping',description:'会员中心：围绕等级、成长值、积分与权益建立完整会员闭环，可查看等级体系、积分明细与专属权益，需登录后使用。',keywords:'会员中心,会员等级,成长值,积分权益,会员订阅,黑钻会员',og:{type:'website'}}}},{path:'membership/orders',name:'membership-orders',component:()=>import('../views/SubscriptionOrders.vue'),meta:{auth:true,seo:{title:'会员订阅订单 · 订阅记录查询 | LANLinkshopping',description:'会员订阅订单：查询会员订阅的订单记录与有效期，订阅状态以订单记录为准。',keywords:'订阅订单,会员订阅,订阅记录,会员有效期'}}},{path:'membership/levels',name:'membership-levels',component:()=>import('../views/MembershipLevels.vue'),meta:{auth:true,seo:{title:'会员等级 · 成长值门槛与等级权益 | LANLinkshopping',description:'会员等级体系：成长值门槛、等级折扣与各级别权益说明，从青铜到黑钻的成长路径一览。',keywords:'会员等级,成长值,等级折扣,青铜会员,黑钻会员,等级权益'}}},{path:'membership/points',name:'membership-points',component:()=>import('../views/MembershipPoints.vue'),meta:{auth:true,seo:{title:'积分中心 · 积分明细与获取玩法 | LANLinkshopping',description:'积分中心：查看积分明细与来源，完成任务与参与活动可获取积分，积分抵现等玩法以平台规则为准。',keywords:'积分中心,积分明细,积分抵现,积分获取,会员积分'}}},{path:'membership/points/:id',name:'membership-point-detail',component:()=>import('../views/MembershipPointDetail.vue'),meta:{auth:true,seo:{title:'积分明细详情 {id} · LANLinkshopping',description:'积分明细详情：查看单笔积分的获取来源、时间与数值，明细以积分流水记录为准。',keywords:'积分明细,积分流水,积分来源,会员积分'}}},{path:'membership/benefits',name:'membership-benefits',component:()=>import('../views/MembershipBenefits.vue'),meta:{auth:true,seo:{title:'我的权益 · 会员专属权益一览 | LANLinkshopping',description:'会员权益：查看当前等级可享的专属权益与使用方式，权益内容以会员协议与平台规则为准。',keywords:'会员权益,专属权益,权益兑换,会员福利'}}},
{path:'design-studio',name:'design-studio',component:()=>import('../views/DesignStudio.vue')},
{path:'price-monitor',name:'price-monitor',component:()=>import('../views/PriceIntelligence.vue'),meta:{auth:true,seo:{title:'价格监控 · 价格走势与行情对标 | LANLinkshopping',description:'价格情报：看懂价格背后的风向，跟踪商品价格走势并与行业行情对标，辅助采购比价与下单决策，需登录后使用。',keywords:'价格监控,价格走势,行情对标,采购比价,价格情报',og:{type:'website'}}}},
{path:'about',name:'about',component:()=>import('../views/AboutUs.vue'),meta:{seo:{title:'关于我们 · 连接采购、商品与增长 | LANLinkshopping',description:'关于 LANLinkshopping：让采购、商品与增长在同一个网络里连接，从找货、比价到下单支付一站式完成企业采购全流程，询价、账期与发票能力开箱即用。',keywords:'关于我们,平台介绍,B2B电商平台,企业采购,供应链协同,LANLinkshopping',og:{type:'website'}}}},{path:'privacy/cookies',name:'privacy-cookies',component:()=>import('../views/PrivacyCenter.vue')},
// 法律文档中心：用户服务协议 / 隐私政策 / Cookie 政策 / 平台说明 / 免责声明（/legal 重定向到 terms）
{path:'legal',redirect:{name:'legal-doc',params:{docKey:'terms'}}},{path:'legal/:docKey',name:'legal-doc',component:()=>import('../views/LegalCenter.vue')},{path:'me',name:'me',component:()=>import('../views/Me.vue'),meta:{auth:true,perms:['profile:manage']}},{path:'me/messages',name:'account-messages',component:()=>import('../views/AccountMessages.vue'),meta:{auth:true,perms:['profile:manage']}},{path:'me/address',name:'account-addresses',component:()=>import('../views/AccountAddresses.vue'),meta:{auth:true,perms:['profile:manage']}},{path:'me/login-log',name:'account-login-log',component:()=>import('../views/AccountLoginLog.vue'),meta:{auth:true,perms:['profile:manage']}},{path:'me/settings',name:'account-settings',component:()=>import('../views/AccountSettings.vue'),meta:{auth:true,perms:['profile:manage']}},
// 技术服务：公开浏览（专家/需求/成果/政策/社区）；申请与工作台需登录
{path:'tech',name:'tech',component:()=>import('../views/techservice/TechService.vue'),meta:{seo:{title:'技术服务 · 技术交易与专利运营 | LANLinkshopping',description:'技术服务中枢：连接企业技术需求、高校成果、专家、技术经纪人与服务机构，覆盖诊断、匹配、评估、托管到成交落地的全流程协同。',keywords:'技术服务,技术交易,专利运营,技术经纪人,成果转化,技术需求',og:{type:'website'}}}},
{path:'tech/exchange',name:'tech-exchange',component:()=>import('../views/techservice/TechExchange.vue'),meta:{seo:{title:'技术交流 · 把技术语言翻译成生意语言 | LANLinkshopping',description:'技术交流：把技术语言翻译成生意语言，汇聚技术需求、成果展示与行业讨论，供需双方在对话中找到合作切口。',keywords:'技术交流,技术社区,技术对接,行业讨论,供需对接'}}},
{path:'tech/experts',name:'tech-experts',component:()=>import('../views/techservice/TechExperts.vue'),meta:{seo:{title:'技术专家 · 定向邀请专家与技术经纪人 | LANLinkshopping',description:'专家库：定向邀请的专家与技术经纪人，经平台审核后上架展示，可按领域查找并对接技术咨询与合作。',keywords:'技术专家,技术经纪人,专家库,技术咨询,专家对接'}}},
{path:'tech/demands',name:'tech-demands',component:()=>import('../views/techservice/TechDemands.vue'),meta:{seo:{title:'技术需求 · 发布难题与对接解题方 | LANLinkshopping',description:'技术需求对接：把问题说清楚，比先选技术更重要；发布技术需求后由平台匹配专家、经纪人与服务机构。',keywords:'技术需求,需求发布,技术难题,需求对接,解题方'}}},
{path:'tech/achievements',name:'tech-achievements',component:()=>import('../views/techservice/TechAchievements.vue'),meta:{seo:{title:'技术成果 · 专利与成果快筛 | LANLinkshopping',description:'成果库：不是每一件专利都是资产，先快筛再投入；浏览高校与机构提交的可转化技术成果与专利。',keywords:'技术成果,专利转化,成果快筛,高校成果,专利资产'}}},
{path:'tech/appraisal',name:'tech-appraisal',component:()=>import('../views/techservice/TechAppraisal.vue'),meta:{seo:{title:'技术评估 · 证据链与机构结论 | LANLinkshopping',description:'技术评估：平台准备证据链，评估机构出具结论；评估流程与材料要求公开可查，结论以机构出具为准。',keywords:'技术评估,成果鉴定,证据链,评估机构,技术评价'}}},
{path:'tech/hosting',name:'tech-hosting',component:()=>import('../views/techservice/TechHosting.vue'),meta:{auth:true,seo:{title:'技术托管 · 从托管入口到成交结果 | LANLinkshopping',description:'技术托管：托管是入口，成交才是结果；托管项目进展与资金安排以托管协议约定为准，需登录后使用。',keywords:'技术托管,项目托管,技术交易,成果落地,托管协议'}}},
{path:'tech/workspace',name:'tech-workspace',component:()=>import('../views/techservice/TechWorkspace.vue'),meta:{auth:true}},
{path:'tech/contracts',name:'tech-contracts',component:()=>import('../views/techservice/TechContracts.vue'),meta:{auth:true}},
{path:'tech/org-tasks',name:'tech-org-tasks',component:()=>import('../views/techservice/TechOrgTasks.vue'),meta:{auth:true}},
{path:'tech/pilot',name:'tech-pilot',component:()=>import('../views/techservice/TechPilot.vue'),meta:{seo:{title:'中试验证 · 先验证再放大 | LANLinkshopping',description:'中试与验证服务：先验证，再放大；资源由合作机构提交并经运营核验后上架，设备清单、能力范围与计价方式公开可查。',keywords:'中试,中试平台,验证服务,工艺验证,设备清单'}}},
{path:'tech/open-license',name:'tech-open-license',component:()=>import('../views/techservice/TechOpenLicense.vue'),meta:{seo:{title:'开放许可 · 让专利先用起来 | LANLinkshopping',description:'开放许可专区：让专利先被用起来，再谈收益；许可方式与费用以权利人声明为准，平台不代为定价。',keywords:'开放许可,专利许可,专利运营,许可备案,专利收益'}}},
{path:'tech/policies',name:'tech-policies',component:()=>import('../views/techservice/TechPolicies.vue'),meta:{seo:{title:'政策服务 · 政策入口与落地服务 | LANLinkshopping',description:'政策服务：政策提供入口，落地创造结果；汇聚可申报政策与服务对接入口，申报结果以主管部门受理为准。',keywords:'政策服务,政策申报,项目申报,政策落地,政策对接'}}}
]},{path:'/login',name:'login',component:()=>import('../views/Login.vue')},{path:'/register',name:'register',component:()=>import('../views/Register.vue')},{path:'/admin',component:()=>import('../layouts/AdminLayout.vue'),meta:{admin:true},children:[{path:'',name:'admin-home',redirect:{name:'admin-dash'}},{path:'dashboard',name:'admin-dash',component:()=>import('../views/admin/AdminDashboard.vue')},{path:'metrics',name:'admin-metrics',component:()=>import('../views/admin/AdminMetrics.vue')},{path:'merchants',name:'admin-merchants',component:()=>import('../views/admin/MerchantReview.vue')},{path:'merchants/:merId',name:'admin-merchant-detail',component:()=>import('../views/admin/MerchantDetail.vue')},{path:'products',name:'admin-products',component:()=>import('../views/admin/AdminProducts.vue')},{path:'campaigns',name:'admin-campaigns',component:()=>import('../views/admin/AdminCampaigns.vue')},{path:'price-monitor',name:'admin-price-monitor',component:()=>import('../views/admin/AdminPriceMonitor.vue')},{path:'knowledge/multimodal',name:'admin-multimodal-rag',component:()=>import('../views/admin/AdminMultimodalRag.vue')},{path:'live',name:'admin-live',component:()=>import('../views/admin/AdminLive.vue')},{path:'payments',name:'admin-payments',component:()=>import('../views/admin/AdminPayments.vue')},{path:'credit-terms',name:'admin-credit-terms',component:()=>import('../views/admin/AdminCreditTerms.vue')},{path:'payments/:orderNo',name:'admin-payment-detail',component:()=>import('../views/admin/PaymentDetail.vue')},{path:'ai-models',name:'admin-ai-models',component:()=>import('../views/admin/AiModelGateway.vue')},{path:'tech-service',name:'admin-tech-service',component:()=>import('../views/admin/AdminTechService.vue')},{path:'join-us',name:'admin-join-us',component:()=>import('../views/admin/AdminJoinApplications.vue')},{path:'audit',name:'admin-audit',component:()=>import('../views/admin/AdminAuditLog.vue')},{path:'risk',name:'admin-risk',component:()=>import('../views/admin/AdminRiskUsers.vue')},{path:'logistics',name:'admin-logistics',component:()=>import('../views/admin/LogisticsCenter.vue')},{path:'logistics/tracking-exceptions',name:'admin-tracking-exceptions',component:()=>import('../views/admin/AdminTrackingExceptions.vue')},{path:'communications',name:'admin-communications',component:()=>import('../views/admin/CustomerCommunicationCenter.vue')},
{path:'communications/inbox',name:'admin-communications-inbox',component:()=>import('../views/admin/CustomerCommunicationInbox.vue')},
{path:'communications/conversations/:conversationId',name:'admin-communication-conversation',component:()=>import('../views/admin/CustomerCommunicationConversation.vue')},
{path:'communications/conversations/:conversationId/messages/:messageId',name:'admin-communication-message-detail',component:()=>import('../views/admin/CustomerCommunicationMessageDetail.vue')},
{path:'communications/learning',name:'admin-communications-learning',component:()=>import('../views/admin/CustomerCommunicationLearning.vue')},
{path:'communications/learning/:candidateId',name:'admin-communications-learning-detail',component:()=>import('../views/admin/CustomerCommunicationLearningDetail.vue')},
{path:'communications/learning/:candidateId/review',name:'admin-communications-learning-review',component:()=>import('../views/admin/CustomerCommunicationLearningReview.vue')},
{path:'communications/ai-config',name:'admin-communications-ai-config',component:()=>import('../views/admin/CustomerCommunicationAiConfig.vue')},
{path:'support-learning',name:'admin-support-learning',component:()=>import('../views/admin/SupportLearningSandbox.vue')},
{path:'support-tickets',name:'admin-support-tickets',component:()=>import('../views/admin/AdminSupportTickets.vue')},
{path:'knowledge',name:'admin-knowledge',component:()=>import('../views/admin/AdminKnowledgeBase.vue')},
// 商户结算运营台（批次生成/审核/打款登记/佣金率/手动对账；二清红线：仅记账不对私划转）
{path:'settlement',name:'admin-settlement',component:()=>import('../views/admin/AdminSettlement.vue')},
// 商户提现审核（待审列表/通过登记打款凭证/驳回解冻）
{path:'withdrawals',name:'admin-withdrawals',component:()=>import('../views/admin/AdminWithdrawals.vue')},
// 商户退店审核（申请/公示/清算/关闭；保证金只记账不打款）
{path:'merchant-exit',name:'admin-merchant-exit',component:()=>import('../views/admin/AdminMerchantExit.vue')},
// 商家入驻邀约（仅运营）：生成 merchant_apply 码，商家凭码走入驻申请，审核通过后才成商户
{path:'merchant-invite',name:'admin-merchant-invite',component:()=>import('../views/admin/AdminMerchantInvite.vue')}]},
// 全站 404 兜底：未知 URL 渲染引导页而非整页死白
{path:'/:pathMatch(.*)*',name:'not-found',component:()=>import('../views/NotFound.vue')}]
// 路由滚动策略：前进/新页面一律回到顶部；浏览器后退恢复原位置；带 hash 时定位到锚点
function scrollBehavior(to,from,savedPosition){
  if(savedPosition)return savedPosition
  if(to.hash){
    try{if(document.querySelector(to.hash))return {el:to.hash,top:96,behavior:'auto'}}catch(e){/* 非法选择器忽略 */}
  }
  return {left:0,top:0}
}
const router=createRouter({history:createWebHistory(),routes,scrollBehavior})
router.beforeEach(async to=>{
  await hydrateSession()
  const user=readUser()
  if(to.meta.admin){if(!user||user.roleCode!=='admin')return{name:'home'};return true}
  if(to.meta.auth&&!user)return{name:'login',query:{redirect:to.fullPath}}
  if(to.meta.perms&&user&&!hasPerms(user,to.meta.perms))return{name:'home'}
  return true
})
// 路由切换后刷新站内消息未读数角标：useUserStore() 必须在回调内部调用（回调执行时 pinia 已激活），
// 未登录或前往登录页时跳过；refreshUnread 内部已静默处理失败，不影响导航
router.afterEach(to=>{
  // 按路由注入 TDK/og（CSR 方案，拍板⑦-12）：meta.seo 缺失（私域/工具页）时回落全局兜底
  try{ applyRouteSeo(to) }catch(e){/* SEO 注入绝不阻断导航 */}
  try{
    const user=useUserStore()
    if(user.logged&&to.path!=='/login') user.refreshUnread()
  }catch(e){/* pinia 未就绪等极端场景静默跳过 */}
  // Amplitude 漏斗：路由级 PV（门控在 analytics 内部，未初始化时 no-op）
  trackEvent('page_view', { route_name: to.name ? String(to.name) : null })
})
export default router

// 轻量 i18n：locale ref + t('a.b') 取值，取不到回退中文再回退 key
import { ref } from 'vue'
import { LOCALE_STORAGE_KEY, detectLocale } from '../utils/localeDetect'
import { UI_ZH_EN_EXTRA, ZH_EN_PATTERNS } from './zh-en-extra'

export const locale = ref('zh-CN')

const zh = {
  qrsl: {
    title: '扫码登录',
    subtitle: '使用手机扫描二维码，在「{app}」确认后即可登录',
    badge: '安全登录',
    qrAria: '扫码登录二维码',
    expired: '二维码已失效',
    refreshNow: '点击刷新',
    scanned: '已扫码，请在手机上确认',
    confirming: '确认成功，正在进入…',
    refresh: '刷新二维码',
    usePassword: '使用密码登录',
    demo: '演示',
    simScan: '模拟已扫码',
    simConfirm: '模拟确认登录',
    pause: '暂停',
    play: '播放',
    replay: '重播',
    noteExpired: '二维码已失效，点击「刷新二维码」重新获取',
    noteCountdown: '{s}s 后自动刷新（有效期 {total}s）',
    stWaiting: '等待扫码…',
    stScanned: '{device} 已扫码，等待确认',
    stConfirmed: '{msg}，正在跳转…',
    stExpired: '二维码已失效，请刷新后重试'
  },
  acct: {
    title: '账号中心',
    nav: { profile: '个人信息', settings: '系统设置' },
    card: {
      role: '角色', phone: '手机', email: '邮箱', unbound: '未绑定',
      quick: '快捷入口', orders: '我的订单', cart: '购物车', merchant: '商户入驻',
      clearCache: '清空本地缓存', cacheCleared: '缓存已清空',
      accountInfo: '账号信息', userId: '用户ID', member: '会员等级'
    },
    loadFail: '数据加载失败',
    loadFailDesc: '请检查网络后重试',
    retry: '重试',
    discardTitle: '放弃未保存的修改？',
    discardContent: '当前编辑内容尚未保存，切换后修改将丢失。',
    leaveConfirm: '当前页面有未保存的修改，确定离开吗？',
    ok: '确定', cancelText: '取消',
    profile: {
      title: '个人信息',
      edit: '编辑', save: '保存', cancel: '取消',
      changeAvatar: '更换头像',
      realName: '姓名', nickname: '昵称', gender: '性别', birthday: '出生日期',
      bio: '个人简介', phone: '手机号', email: '邮箱',
      male: '男', female: '女', secret: '保密',
      notSet: '未设置',
      bioPlaceholder: '介绍一下自己吧（最多 500 字）',
      bioHelp: '剩余可输入 {n} 字',
      saveSuccess: '个人信息已保存',
      draftTitle: '恢复草稿',
      draftContent: '检测到上次未保存的修改，是否恢复？',
      restore: '恢复', discardDraft: '放弃',
      ruleRealNameRequired: '请输入姓名',
      ruleNicknameRequired: '请输入昵称',
      ruleTooLong: '长度不能超过 32 字',
      change: '更换',
      changePhone: '更换手机号',
      changeEmail: '更换邮箱',
      newPhone: '新手机号',
      newEmail: '新邮箱',
      sendCode: '获取验证码',
      resendIn: '{s}s 后重发',
      code: '验证码',
      codePlaceholder: '6 位验证码',
      codeSent: '验证码已发送至邮箱，5 分钟内有效',
      devCode: '演示验证码：{code}',
      submit: '提交',
      bindSuccess: '绑定信息已更新',
      invalidPhone: '手机号格式不正确',
      invalidEmail: '邮箱格式不正确',
      codeRequired: '请输入 6 位验证码'
    },
    avatar: {
      title: '编辑头像',
      dropHint: '点击或拖拽图片到此处上传',
      typeHint: '支持 JPG/PNG/WebP，≤5MB',
      invalidType: '仅支持 JPG/PNG/WebP 图片',
      invalidSize: '图片大小不能超过 5MB',
      zoom: '缩放',
      rotate: '旋转90°',
      reselect: '重新选择',
      upload: '上传',
      uploadFail: '上传失败，请重试',
      retry: '重试',
      done: '头像已更新'
    },
    settings: {
      security: '账户安全',
      changePwd: '修改密码',
      oldPwd: '原密码', newPwd: '新密码', confirmPwd: '确认新密码',
      ruleOldRequired: '请输入原密码',
      ruleNewRequired: '请输入新密码',
      ruleNewPattern: '8-20 位，需包含大小写字母、数字和特殊字符',
      ruleNewSameAsOld: '新密码不能与原密码相同',
      ruleConfirmRequired: '请再次输入新密码',
      ruleConfirmMismatch: '两次输入的密码不一致',
      strength: '密码强度',
      pwdConfirmTitle: '确认修改密码？',
      pwdConfirmContent: '修改密码后，下次登录需使用新密码。',
      pwdSuccess: '密码修改成功',
      submit: '提交',
      twofa: '两步验证（TOTP）',
      twofaOn: '已启用',
      twofaOff: '未启用',
      twofaDesc: '启用后登录需额外输入动态验证码，提升账号安全性。',
      firstTime: '首次验证时间',
      enable2fa: '启用两步验证',
      disable2fa: '关闭两步验证',
      twofaSetupTitle: '设置两步验证',
      twofaStart: '开始设置',
      twofaScan: '使用验证器 App（如 Google Authenticator）扫描二维码',
      twofaSecret: '无法扫码？手动输入密钥：',
      copy: '复制', copied: '已复制',
      twofaCodeLabel: '动态验证码',
      twofaVerifyEnable: '验证并启用',
      twofaEnabled: '两步验证已启用',
      twofaDisableTitle: '关闭两步验证',
      twofaDisableTip: '关闭后账号安全性将降低，请输入当前动态验证码确认。',
      twofaConfirmDisable: '确认关闭',
      twofaDisabled: '两步验证已关闭',
      codeRequired: '请输入 6 位验证码',
      notify: '通知偏好',
      notifyEmail: '邮件通知',
      notifyPush: '站内推送',
      notifySms: '短信提醒',
      notifyGroup: '分类通知',
      notifyOrder: '订单动态',
      notifyPromotion: '优惠活动',
      notifySystem: '系统公告',
      notifyAll: '全部开启 / 全部关闭',
      saved: '设置已保存',
      saveFail: '保存失败，已恢复原设置',
      appearance: '界面个性化',
      theme: '主题模式',
      themeLight: '亮色', themeDark: '暗色', themeSystem: '跟随系统',
      language: '语言',
      fontSize: '字体大小',
      fontStandard: '标准', fontLarge: '放大 120%', fontSmall: '缩小 80%',
      privacy: '隐私权限',
      recommend: '个性化推荐',
      recommendTip: '开启后将根据你的浏览与购买偏好推荐商品（对应隐私政策第 3 条）。',
      ads: '广告推送',
      adsTip: '开启后允许平台向你推送营销广告信息（对应隐私政策第 5 条）。',
      thirdAuth: '第三方授权管理',
      thirdEmpty: '暂无第三方应用授权',
      revoke: '撤回授权',
      revokeTitle: '撤回授权？',
      revokeContent: '撤回后，该应用将无法再访问你的账号信息。',
      revoked: '授权已撤回',
      scopes: '授权范围',
      authTime: '授权时间',
      reset: '恢复默认设置',
      resetTitle: '恢复默认设置？',
      resetContent: '仅重置系统设置（通知、外观、隐私），不影响个人信息。',
      resetDone: '已恢复默认设置'
    }
  }
}

const en = {
  qrsl: {
    title: 'Scan to sign in',
    subtitle: 'Scan this QR code with your phone and confirm in {app}',
    badge: 'Secure',
    qrAria: 'Sign-in QR code',
    expired: 'QR code expired',
    refreshNow: 'Refresh now',
    scanned: 'Scanned — confirm on your phone',
    confirming: 'Confirmed — signing you in…',
    refresh: 'Refresh QR code',
    usePassword: 'Sign in with password',
    demo: 'Demo',
    simScan: 'Simulate scan',
    simConfirm: 'Simulate confirm',
    pause: 'Pause',
    play: 'Play',
    replay: 'Replay',
    noteExpired: 'The QR code expired — tap "Refresh QR code" to get a new one',
    noteCountdown: 'Auto-refresh in {s}s (valid {total}s)',
    stWaiting: 'Waiting for scan…',
    stScanned: '{device} scanned — awaiting confirmation',
    stConfirmed: '{msg} — redirecting…',
    stExpired: 'QR code expired, please refresh and try again'
  },
  acct: {
    title: 'Account Center',
    nav: { profile: 'Profile', settings: 'Settings' },
    card: {
      role: 'Role', phone: 'Phone', email: 'Email', unbound: 'Not bound',
      quick: 'Quick Links', orders: 'My Orders', cart: 'Cart', merchant: 'Become a Merchant',
      clearCache: 'Clear Local Cache', cacheCleared: 'Cache cleared',
      accountInfo: 'Account Info', userId: 'User ID', member: 'Member Level'
    },
    loadFail: 'Failed to load',
    loadFailDesc: 'Check your network and retry',
    retry: 'Retry',
    discardTitle: 'Discard unsaved changes?',
    discardContent: 'Your edits have not been saved and will be lost after switching.',
    leaveConfirm: 'You have unsaved changes. Leave anyway?',
    ok: 'OK', cancelText: 'Cancel',
    profile: {
      title: 'Profile',
      edit: 'Edit', save: 'Save', cancel: 'Cancel',
      changeAvatar: 'Change Avatar',
      realName: 'Name', nickname: 'Nickname', gender: 'Gender', birthday: 'Birthday',
      bio: 'Bio', phone: 'Phone', email: 'Email',
      male: 'Male', female: 'Female', secret: 'Secret',
      notSet: 'Not set',
      bioPlaceholder: 'Introduce yourself (up to 500 chars)',
      bioHelp: '{n} characters remaining',
      saveSuccess: 'Profile saved',
      draftTitle: 'Restore Draft',
      draftContent: 'Unsaved changes from last time detected. Restore them?',
      restore: 'Restore', discardDraft: 'Discard',
      ruleRealNameRequired: 'Please enter your name',
      ruleNicknameRequired: 'Please enter a nickname',
      ruleTooLong: 'Must be within 32 characters',
      change: 'Change',
      changePhone: 'Change Phone',
      changeEmail: 'Change Email',
      newPhone: 'New Phone',
      newEmail: 'New Email',
      sendCode: 'Send Code',
      resendIn: 'Resend in {s}s',
      code: 'Code',
      codePlaceholder: '6-digit code',
      codeSent: 'Verification code sent to your email (valid for 5 minutes)',
      devCode: 'Demo code: {code}',
      submit: 'Submit',
      bindSuccess: 'Binding updated',
      invalidPhone: 'Invalid phone number',
      invalidEmail: 'Invalid email address',
      codeRequired: 'Please enter the 6-digit code'
    },
    avatar: {
      title: 'Edit Avatar',
      dropHint: 'Click or drag an image here',
      typeHint: 'JPG/PNG/WebP supported, ≤5MB',
      invalidType: 'Only JPG/PNG/WebP images are allowed',
      invalidSize: 'Image must be within 5MB',
      zoom: 'Zoom',
      rotate: 'Rotate 90°',
      reselect: 'Reselect',
      upload: 'Upload',
      uploadFail: 'Upload failed, please retry',
      retry: 'Retry',
      done: 'Avatar updated'
    },
    settings: {
      security: 'Account Security',
      changePwd: 'Change Password',
      oldPwd: 'Current Password', newPwd: 'New Password', confirmPwd: 'Confirm Password',
      ruleOldRequired: 'Please enter current password',
      ruleNewRequired: 'Please enter new password',
      ruleNewPattern: '8-20 chars incl. upper/lowercase letters, digits and symbols',
      ruleNewSameAsOld: 'New password must differ from the current one',
      ruleConfirmRequired: 'Please confirm new password',
      ruleConfirmMismatch: 'Passwords do not match',
      strength: 'Strength',
      pwdConfirmTitle: 'Change password?',
      pwdConfirmContent: 'You will need the new password at next login.',
      pwdSuccess: 'Password changed',
      submit: 'Submit',
      twofa: 'Two-Factor Auth (TOTP)',
      twofaOn: 'Enabled',
      twofaOff: 'Disabled',
      twofaDesc: 'Requires an extra dynamic code at login for better security.',
      firstTime: 'First verified at',
      enable2fa: 'Enable 2FA',
      disable2fa: 'Disable 2FA',
      twofaSetupTitle: 'Set up 2FA',
      twofaStart: 'Start Setup',
      twofaScan: 'Scan the QR code with an authenticator app',
      twofaSecret: 'Cannot scan? Enter the key manually:',
      copy: 'Copy', copied: 'Copied',
      twofaCodeLabel: 'Dynamic Code',
      twofaVerifyEnable: 'Verify & Enable',
      twofaEnabled: '2FA enabled',
      twofaDisableTitle: 'Disable 2FA',
      twofaDisableTip: 'Security will be reduced. Enter the current dynamic code to confirm.',
      twofaConfirmDisable: 'Confirm Disable',
      twofaDisabled: '2FA disabled',
      codeRequired: 'Please enter the 6-digit code',
      notify: 'Notifications',
      notifyEmail: 'Email',
      notifyPush: 'In-app Push',
      notifySms: 'SMS',
      notifyGroup: 'Categories',
      notifyOrder: 'Order Updates',
      notifyPromotion: 'Promotions',
      notifySystem: 'System Announcements',
      notifyAll: 'All On / All Off',
      saved: 'Settings saved',
      saveFail: 'Save failed, reverted',
      appearance: 'Appearance',
      theme: 'Theme',
      themeLight: 'Light', themeDark: 'Dark', themeSystem: 'System',
      language: 'Language',
      fontSize: 'Font Size',
      fontStandard: 'Standard', fontLarge: 'Large 120%', fontSmall: 'Small 80%',
      privacy: 'Privacy',
      recommend: 'Personalized Recommendations',
      recommendTip: 'Recommend products based on browsing and purchase history (Privacy Policy §3).',
      ads: 'Ad Push',
      adsTip: 'Allow marketing messages from the platform (Privacy Policy §5).',
      thirdAuth: 'Third-party Authorizations',
      thirdEmpty: 'No third-party authorizations',
      revoke: 'Revoke',
      revokeTitle: 'Revoke authorization?',
      revokeContent: 'The app will no longer access your account info.',
      revoked: 'Authorization revoked',
      scopes: 'Scopes',
      authTime: 'Authorized at',
      reset: 'Reset to Defaults',
      resetTitle: 'Reset settings?',
      resetContent: 'Only system settings (notifications, appearance, privacy) are reset; your profile is not affected.',
      resetDone: 'Settings reset to defaults'
    }
  }
}

const dicts = { 'zh-CN': zh, 'en-US': en }

function pick(dict, path) {
  let cur = dict
  for (const k of path.split('.')) {
    if (cur == null || typeof cur !== 'object') return undefined
    cur = cur[k]
  }
  return cur
}

/**
 * 取文案，支持 {a.b} 路径与 {name} 插值
 * t('acct.profile.bioHelp', { n: 12 })
 */
export function t(key, params) {
  let s = pick(dicts[locale.value], key)
  if (s === undefined) s = pick(zh, key) // 回退中文
  if (s === undefined) return key // 再回退 key 本身
  if (params) {
    for (const [k, v] of Object.entries(params)) {
      s = s.replaceAll(`{${k}}`, String(v))
    }
  }
  return s
}

export function setLocale(l) {
  locale.value = dicts[l] ? l : 'zh-CN'
  try { localStorage.setItem(LOCALE_STORAGE_KEY, locale.value) } catch (e) {}
  applyDomLocale()
}


// ===== 全站双语层（无需第三方运行时依赖） =====
const UI_ZH_EN = {
  '首页':'Home','商城':'Mall','优惠':'Promotions','账期':'Credit Terms','我的订单':'My Orders','商户管理':'Merchant Management','商户入驻':'Become a Merchant','流量管理':'Traffic Management','我的商品':'My Products','关于我们':'About Us',
  '消息':'Messages','购物车':'Cart','登录 / 注册':'Login / Register','退出登录':'Log out','退出':'Log out','返回前台':'Back to Storefront',
  '当前身份':'Current Identity','我的钱包':'My Wallet','企业账期':'Business Credit','活动中心':'Activity Center','会员中心':'Membership Center','个人资料':'Profile','消息中心':'Messages','收货地址':'Shipping Addresses','登录安全':'Login Security','账户设置':'Account Settings',
  '让每一次连接，都产生价值。':'Make every connection create value.','隐私与 Cookie':'Privacy & Cookies','知识产权声明':'Intellectual Property',
  '关于我们页面导航':'About page navigation','联系平台':'Contact Platform','进入商城':'Enter Mall','跳过':'Skip',
  '项目作者':'Project Author','版权所有':'All Rights Reserved','未经授权请勿擅自复制、冒用或商业化':'Unauthorized copying, impersonation or commercialization is prohibited.',
  '社交平台':'Social Platforms','开发者交流区':'Developer Community','项目讨论区':'Project Discussions','社区交流':'Community','项目联系 / 日常沟通':'Project contact / daily communication','备用联系邮箱':'Secondary contact email',
  '联系我们':'Contact Us','主要邮箱':'Primary Email','备用邮箱':'Secondary Email','复制邮箱':'Copy Email','已复制':'Copied','复制成功':'Copied successfully',
  'WhatsApp':'WhatsApp','打开 WhatsApp':'Open WhatsApp','直接打开 WhatsApp':'Open WhatsApp directly','显示二维码':'Show QR Code','收起二维码':'Hide QR Code','扫码添加 / 联系 OuyangJason':'Scan to connect with OuyangJason','点击“显示二维码”查看扫码方式，也可直接打开 WhatsApp。':'Tap “Show QR Code” to scan, or open WhatsApp directly.',
  '平台定位':'Platform Overview','能力矩阵':'Capability Map','平台架构':'Platform Architecture','服务条款':'Terms of Service','知识产权':'Intellectual Property',
  '不是单一商城，而是一条完整的交易链路':'More than a storefront — a complete commerce journey','五种身份，一套协同逻辑':'Five identities, one collaboration model','从获客到复购，能力彼此衔接':'From acquisition to repurchase, every capability connects.',
  '我们解决什么':'What We Solve','平台生态':'Platform Ecosystem','演示平台':'Demo Platform',
  '商品与采购':'Products & Procurement','商户经营':'Merchant Operations','营销中台':'Marketing Hub','平台运营':'Platform Operations',
  '交易':'Transactions','营销':'Marketing','增长':'Growth','订单':'Orders','钱包':'Wallet','支付':'Payments','活动':'Campaigns','促销':'Promotions','会员':'Membership','流量':'Traffic','转化':'Conversion','诊断':'Diagnostics',
  '访客':'Guest','普通用户':'Buyer','VIP 用户':'VIP User','商户':'Merchant','管理员':'Administrator','浏览':'Browse','采购':'Procure','权益':'Benefits','经营':'Operate','运营':'Administer',
  '让采购、商品与增长':'Connect procurement, products and growth','在同一个网络里连接。':'within one network.','欢迎来到 LANLinkshopping':'Welcome to LANLinkshopping',
  '搜索':'Search','筛选':'Filter','重置':'Reset','确认':'Confirm','取消':'Cancel','保存':'Save','编辑':'Edit','删除':'Delete','新增':'Add','提交':'Submit','下一步':'Next','上一步':'Previous','返回':'Back','查看':'View','详情':'Details','关闭':'Close','完成':'Done','加载中':'Loading','暂无数据':'No data','暂无':'None','重试':'Retry','刷新':'Refresh',
  '商品':'Product','商品详情':'Product Details','商品分类':'Product Categories','行业分类':'Industry Categories','全部商品':'All Products','热销商品':'Best Sellers','热门商品':'Popular Products','推荐商品':'Recommended Products','库存':'Inventory','销量':'Sales','价格':'Price','起价':'From','数量':'Quantity','规格':'Specifications','单位':'Unit','品牌':'Brand','产地':'Origin','描述':'Description','参数':'Specifications','包装':'Packaging','商品编号':'SKU','上架':'Published','下架':'Unpublished',
  '加入购物车':'Add to Cart','立即购买':'Buy Now','收藏':'Add to Favorites','已收藏':'Favorited','分享':'Share','加入成功':'Added successfully','购买数量':'Purchase Quantity','小计':'Subtotal','合计':'Total','应付金额':'Amount Due','去结算':'Checkout','继续购物':'Continue Shopping','购物车为空':'Your cart is empty',
  '结算':'Checkout','收货信息':'Shipping Information','支付方式':'Payment Method','订单备注':'Order Note','订单确认':'Order Confirmation','提交订单':'Place Order','支付订单':'Pay Order','订单已创建':'Order created','订单已支付':'Order paid','订单编号':'Order No.','订单状态':'Order Status','创建时间':'Created At','更新时间':'Updated At',
  '待支付':'Pending Payment','待发货':'Pending Shipment','配送中':'In Transit','已完成':'Completed','已取消':'Cancelled','退款中':'Refunding','已退款':'Refunded',
  '全部订单':'All Orders','待付款':'Pending Payment','待收货':'Pending Receipt','已完成订单':'Completed Orders','取消订单':'Cancel Order','确认收货':'Confirm Receipt','申请退款':'Request Refund',
  '钱包余额':'Wallet Balance','充值':'Top Up','充值金额':'Top-up Amount','充值记录':'Top-up History','交易记录':'Transactions','交易详情':'Transaction Details','可用余额':'Available Balance','收入':'Income','支出':'Expense',
  '会员等级':'Membership Level','会员权益':'Membership Benefits','积分':'Points','积分明细':'Points History','积分详情':'Point Details','成长值':'Growth Value','当前等级':'Current Level','升级':'Upgrade',
  '活动详情':'Campaign Details','活动规则':'Campaign Rules','我的活动':'My Campaigns','立即参加':'Join Now','参与结果':'Participation Result','活动时间':'Campaign Time','活动状态':'Campaign Status',
  '商户中心':'Merchant Center','商户申请':'Merchant Application','商品管理':'Product Management','发布商品':'Publish Product','流量分析':'Traffic Analytics','访客数':'Visitors','转化率':'Conversion Rate','来源':'Source','经营数据':'Business Data',
  '数据概览':'Dashboard','交易管理':'Transaction Management','账期审核':'Credit Review','审计日志':'Audit Log','商户审核':'Merchant Review','系统管理':'System Management',
  '个人信息':'Profile','个人简介':'Bio','手机号码':'Phone Number','手机号':'Phone Number','邮箱':'Email','姓名':'Name','昵称':'Nickname','性别':'Gender','出生日期':'Birthday','男':'Male','女':'Female','保密':'Private','未设置':'Not set','更换头像':'Change Avatar',
  '系统设置':'Settings','界面个性化':'Appearance','主题模式':'Theme','亮色':'Light','暗色':'Dark','跟随系统':'System','语言':'Language','字体大小':'Font Size','标准':'Standard','放大 120%':'Large 120%','缩小 80%':'Small 80%',
  '账户安全':'Account Security','修改密码':'Change Password','原密码':'Current Password','新密码':'New Password','确认新密码':'Confirm Password','密码强度':'Password Strength','两步验证（TOTP）':'Two-Factor Authentication (TOTP)','已启用':'Enabled','未启用':'Disabled','启用两步验证':'Enable 2FA','关闭两步验证':'Disable 2FA',
  '邮件通知':'Email Notifications','站内推送':'In-app Notifications','短信提醒':'SMS Alerts','订单动态':'Order Updates','优惠活动':'Promotions','系统公告':'System Announcements','全部开启 / 全部关闭':'Toggle All',
  '隐私权限':'Privacy','个性化推荐':'Personalized Recommendations','广告推送':'Ad Push','第三方授权管理':'Third-party Authorizations','撤回授权':'Revoke Authorization','授权范围':'Scopes','授权时间':'Authorized At',
  'Cookie 与隐私偏好':'Cookie & Privacy Preferences','管理 Cookie':'Manage Cookies','必要 Cookie':'Necessary Cookies','偏好 Cookie':'Preference Cookies','分析 Cookie':'Analytics Cookies','营销 Cookie':'Marketing Cookies','始终启用':'Always On','仅必要 Cookie':'Necessary Cookies Only','接受全部':'Accept All','接受全部 Cookie':'Accept All Cookies','保存选择':'Save Choices','Cookie 偏好已保存':'Cookie preferences saved','Cookie 偏好设置':'Cookie Preferences','查看 Cookie 详情与偏好':'View Cookie details & preferences',
  '隐私中心':'Privacy Center','隐私政策':'Privacy Policy','数据保护':'Data Protection',
  '请选择':'Please select','请输入':'Please enter','请输入验证码':'Please enter the verification code','验证码':'Verification Code','获取验证码':'Get Code','重新获取':'Resend','秒后重发':'s before resend',
  '登录成功':'Signed in successfully','注册成功':'Registration successful','登录失败':'Sign-in failed','注册':'Register','登录':'Sign in','忘记密码':'Forgot password','没有账号？':'No account?','已有账号？':'Already have an account?','退出成功':'Signed out',
  // 登录/注册互切提示语：按整段短语建词条，避免逐词替换残留中文造成"请 Register"式中英混杂
  '首次使用？请':'First time here? Please','创建账号。':'to create an account.','已有账号？请':'Already have an account? Please',
  // 高频业务复合词条（整词建条，配合防混杂护栏让核心界面完整翻译，避免逐词拼接）
  '订单号':'Order No.','支付状态':'Payment Status','支付渠道':'Payment Channel','支付时间':'Payment Time','订单金额':'Order Amount','订单摘要':'Order Summary',
  '已支付':'Paid','未支付':'Unpaid','支付成功':'Payment Successful','支付已确认':'Payment Confirmed','立即支付':'Pay Now','企业钱包':'Corporate Wallet','支付宝':'alipay','微信支付':'WeChat Pay',
  // 社交登录渠道名：英文写法按需求方指定（Wechat / alipay），不套用官方大小写，改动前请先确认
  '微信':'Wechat',
  '商品名称':'Product Name','商品介绍':'Product Intro','累计销量':'Total Sales','已下架':'Delisted','业务分类':'Category','接口类型':'API Type','类型':'Type','关联订单':'Related Order',
  '退款申请原因':'Refund Reason','退款申请被驳回':'Refund Rejected','退款申请已提交，请等待平台处理':'Refund request submitted, awaiting review','请填写退款原因':'Please fill in the refund reason','请填写驳回原因':'Please fill in the rejection reason','退款原因（必填，将提交平台审核）':'Refund reason (required, will be submitted for review)','审核驳回':'Review Rejected','平台驳回':'Rejected by Platform',
  '支付与退款':'Payment & Refund','积分中心':'Points Center','积分抵现':'Points Deduction','积分奖励':'Points Reward','账期管理':'Credit Term','钱包流水':'Wallet Transactions','平台管理员':'Platform Admin','网站访客':'Site Visitor','注册有礼':'Signup Gift',
  '请输入 6 位验证码':'Please enter the 6-digit code','请输入 6 位数字验证码':'Please enter the 6-digit code','6 位验证码':'6-digit code','验证码错误':'Incorrect code','请先获取验证码':'Please get a code first','请先填写正确的手机号':'Please enter a valid phone number first',
  '收货人':'Receiver','联系电话':'Contact Phone','详细地址':'Detailed Address','省市区':'Province/City/District','还没有收货地址':'No shipping addresses yet','设为默认':'Set as Default','默认':'Default',
  '保存成功':'Saved','已加入购物车':'Added to cart','已删除':'Deleted','请先登录':'Please sign in first','登录后购买':'Sign in to purchase','刷新数据':'Refresh','清空筛选条件':'Clear filters','没有找到相关商品':'No matching products',
  '价格降序':'Price: High to Low','价格升序':'Price: Low to High','销量优先':'Best Selling','搜索商品名称':'Search product name','暂无账单':'No bills','暂无账期账单':'No credit bills','申请账期':'Apply for Credit','订单、支付与退款核对':'Orders, payments & refunds',
  '用户账号 / 邮箱':'Account / Email','请输入用户账号或邮箱':'Please enter account or email','请输入正确的手机号或邮箱格式':'Please enter a valid phone or email','请输入注册手机号或邮箱':'Please enter registered phone or email','请输入注册时设置的密码':'Please enter your password','请输入已注册的手机号':'Please enter registered phone','密码重置链接已发送至您的邮箱，请查收':'Password reset link sent to your email',
  '密码':'Password','确认密码':'Confirm Password','手机号格式不正确':'Invalid phone number','邮箱格式不正确':'Invalid email address',
  '策略版本':'Policy version','最近更新':'Last updated','当前状态':'Current status','尚未选择':'Not selected','策略版本 v1.0':'Policy v1.0',
  '项目作者：Jason Ouyang':'Project Author: Jason Ouyang','本页面公开的联系方式由项目作者本人提供，仅用于项目展示、交流与联系。':'The contact details on this page are provided by the project author for project presentation, communication and academic exchange.',
  '毕业设计原创性及知识产权声明':'Originality & Intellectual Property Statement','请尊重知识产权':'Please Respect Intellectual Property','未经许可请勿使用、冒用或商业化。':'Do not use, impersonate or commercialize without permission.',
  '开发者社区':'Developer Community',
  '聚合 · 连接 · 一体多元解决方案':'Aggregate · Connect · Integrated Multi-Solution','关于 LANLinkshopping':'About LANLinkshopping','查看详情':'View Details',
  '行业':'Industry','行业列表':'Industry List','全部行业':'All Industries','行业数据暂时无法更新':'Industry data is temporarily unavailable','自动同步':'Auto Sync','同步中…':'Syncing…','该行业暂无细分分类':'No subcategories in this industry yet','行业已自动接入，等待商品分类配置。':'This industry is connected automatically and is waiting for category configuration.','综合商品中心':'All-Industry Product Center','覆盖平台当前全部行业采购供给。':'Covers procurement supply across all industries on the platform.','采购入口':'Procurement','热销采购':'Best-Selling Procurement','查看全平台供给':'View platform-wide supply','按销量快速筛选':'Quickly filter by sales','采购优惠':'Procurement Offers','满减与行业折扣':'Threshold discounts and industry offers','类':'categories','进入':'Enter',
  '待审核':'Pending Review','已通过':'Approved','已驳回':'Rejected','审核通过':'Approve','审核完成':'Review Completed','资质核验':'Qualification Check','提交申请':'Application Submitted','材料':'Materials','已入驻':'Onboarded','原因':'Reason','工商信息':'Business Registration','主体类型':'Entity Type','注册资本':'Registered Capital','入驻方式':'Onboarding Method','企业ID':'Enterprise ID','商户ID':'Merchant ID','纳税申报':'Tax Filing','证照材料':'Licensing Documents','申请时间':'Application Time','营业执照':'Business License','查看原件':'View Original','近期纳税记录':'Recent Tax Records','税务登记号':'Tax Registration No.','纳税记录材料':'Tax Record Documents','记录':'Record','已具备资质':'Existing Qualifications','驳回原因':'Rejection Reason','操作':'Actions','快速查看':'Quick View','档案':'Profile','通过':'Approve','驳回':'Reject','商户档案详情':'Merchant Profile Details','驳回入驻申请':'Reject Onboarding Application','驳回原因（必填，将展示给商户用于整改）':'Rejection reason (required; shown to the merchant for remediation)','请输入驳回原因':'Please enter a rejection reason','确认通过':'Confirm Approval','审核通过该商户入驻申请':'Approve this merchant onboarding application',
  // 入驻证照：示例照片与图片回退（CertSampleHint / CertImage）
  '示例':'Sample',
  '营业执照示例照片':'Business license sample','完税凭证示例照片':'Tax payment certificate sample','危险化学品经营许可证示例照片':'Hazardous Chemicals Business License sample',
  '红色边框、国徽、统一社会信用代码与发照机关公章需完整入镜，四角不裁切。':'Include the red border, national emblem, unified social credit code and issuing-authority seal; do not crop the corners.',
  '电子税务局完税证明或纳税记录，需含纳税人识别号、税款所属期与税务机关印章。':'E-tax bureau payment certificate or tax record showing the taxpayer ID, tax period and tax-authority seal.',
  '真实《危险化学品经营许可证》（副本）：需含许可证编号、经营方式、许可范围、有效期与发证机关印章；示例中编号、统一社会信用代码、企业名称、住所、法定代表人及二维码已打码。':'A real Hazardous Chemicals Business License (duplicate): include the license number, business mode, permitted scope, validity period and issuing-authority seal; in this sample the number, unified social credit code, company name, address, legal representative and QR code are masked.',
  '原件未上传':'Original not uploaded','原件加载失败':'Original failed to load','上传中':'Uploading','加载失败':'Load failed',
  '营业执照图片加载失败，请重新上传':'Failed to load the business license image; please upload again',
  '许可证图片加载失败，请重新上传':'Failed to load the permit image; please upload again',
  '原件图片加载失败，请点击右上角「新窗口打开原图」核验':'Failed to load the original; use “Open original in new window” at the top right to review it',
  '商户暂未上传营业执照原件':'The merchant has not uploaded the business license','查看原图 ↗':'View original ↗',
  // 完税记录季度/月度口径
  '已上传':'Uploaded','待上传':'Pending upload','上报':'Submit','补报':'Submit late','重报':'Resubmit','企业所得税':'Corporate Income Tax',
  '企业所得税完税凭证示例照片':'Corporate income tax certificate sample',
  '税务登记号及本季度完税记录':'Tax Registration No. & Quarterly Tax Records',
  '本季度应报月份：':'Months due this quarter:','按季度提交：':'Submit quarterly:',
  '各 1 份《企业所得税》完税凭证（PDF/JPG/PNG，共 3 份）':'one corporate income tax certificate per month (PDF/JPG/PNG; 3 files in total)',
  '季度完税材料：':'Quarterly tax records:','完税记录月度上报':'Monthly Tax Record Reporting',
  '入驻首季按季度提交近 3 个月（共 3 份）；之后每月上报上一自然月记录，次月 15 日前完成，最多延期 15 天。':'Submit 3 months of records (3 files) in the first quarter; afterwards report the previous calendar month by the 15th of the following month, with a grace period of up to 15 days.',
  '本期所属：':'Current period:','（企业所得税）':'(Corporate Income Tax)',
  '完税记录上报情况 · 逾期未报将影响商铺经营评级':'Tax record reporting · overdue submissions affect your merchant rating',
  '真实《税收完税证明》：需含企业所得税税种、税款所属期、入（退）库日期与税务机关印章；票证号、纳税人识别号、金额已打码。':'A real Tax Payment Certificate showing the corporate income tax type, tax period, treasury entry date and tax authority seal; voucher no., taxpayer ID and amounts are masked.',
  '平台运营 13800000000':'Platform Ops 13800000000','采购方':'Buyer','商户/供应商':'Merchant / Supplier','注册身份':'Registration Role','邮箱（选填）':'Email (optional)','11 位手机号':'11-digit phone number','至少 8 位，含字母与数字':'At least 8 characters, including letters and numbers','两次输入的密码不一致':'The passwords do not match','密码强度不足，至少 8 位且含字母与数字':'Password is too weak; use at least 8 characters with letters and numbers','演示账号（密码均 123456）':'Demo accounts (all passwords are 123456)','演示: 13900000001':'Demo: 13900000001','演示密码: 123456':'Demo password: 123456','动态验证码':'One-Time Code','验证并登录':'Verify & Sign In','返回重新登录':'Back to Sign In','去登录':'Go to Sign In','注册账号':'Create Account','请填写 6 位动态验证码':'Enter the 6-digit one-time code','验证码错误或未获取':'Invalid or missing verification code',
  '必要 Cookie 用于登录、会话和安全功能；可选 Cookie 用于记住偏好、分析平台使用情况及改善营销体验。':'Necessary cookies support sign-in, sessions and security; optional cookies remember preferences, analyze usage and improve marketing experiences.','你可以接受全部，也可以仅启用必要 Cookie。':'You can accept all cookies or enable only necessary cookies.','必要 Cookie 用于身份认证、会话保持、CSRF 防护和核心交易流程，无法关闭。':'Necessary cookies support authentication, sessions, CSRF protection and core transaction flows and cannot be disabled.','当前状态：尚未选择':'Current status: Not selected','当前：':'Current:','更新时间：':'Updated:','管理必要、偏好、分析和营销 Cookie。必要 Cookie 始终启用。':'Manage necessary, preference, analytics and marketing cookies. Necessary cookies are always enabled.','当前平台默认不启用第三方广告追踪':'Third-party ad tracking is disabled by default on this platform',
  '扫码登录':'Scan to Sign In','扫码登录二维码':'Sign-in QR code','扫码支付二维码':'Payment QR code',
  '本地未接入扫码换票后端，演示确认后以平台演示账号登录。':'No QR ticket-exchange backend locally; demo confirmation signs in with the demo account.',
  '微信扫码支付':'WeChat QR Pay',
  '请使用微信「扫一扫」完成支付，支付成功后订单状态自动更新':'Scan with WeChat to pay; the order status updates automatically once paid',
  '支付渠道未配置，以下为演示二维码（可真实扫描查看订单信息）':'Payment channel not configured — a demo QR is shown and is still scannable',
  '刷新二维码':'Refresh QR code','模拟支付成功':'Simulate payment success','返回其它支付方式':'Back to other payment methods',
  '或使用账号登录':'or sign in with your account',
  '面向 B2B 采购与供给的一体化平台，覆盖商品、订单、钱包、账期、会员与物流全链路。':'An integrated B2B procurement & supply platform covering products, orders, wallet, credit terms, membership and logistics.',
  '演示平台 · 数据仅用于功能验证':'Demo platform · data is used for feature validation only',
  '全行业采购供给':'All-industry procurement','企业钱包与账期':'Corporate wallet & credit terms','会员权益与活动':'Member benefits & campaigns',
  '正在探测可用渠道…':'Detecting available providers…',
  '社交登录均已接入但尚未配置凭证，填写 application-social.yml 后自动启用':'Social sign-in is wired up but has no credentials yet; fill in application-social.yml to enable it',
  '登录未配置，请在 application-social.yml 中填写凭证后启用':'sign-in is not configured; fill in application-social.yml to enable it',
  '未配置':'Not configured',
  '社交登录已取消或授权失败':'Social sign-in was cancelled or failed',
  '无法确定授权渠道，请重新发起社交登录':'Could not determine the provider; please start social sign-in again',
  ...UI_ZH_EN_EXTRA
}

const _zhOrder = Object.keys(UI_ZH_EN).sort((a,b)=>b.length-a.length)
// 单遍正则替换：400+ 词条原本逐条 includes+split/join（O(词条数×文本长)），
// 对中文文本节点是主线程热点；合并为一个 alternation 正则后单次 replace（O(文本长)），
// 结果与逐条替换等价（_zhOrder 已按长度降序，长词优先命中）。
const _zhPattern = new RegExp(_zhOrder.map(k => k.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|'), 'g')
const _nodeSources = new WeakMap()
const _attrSources = new WeakMap()
let _observer = null
let _domApplying = false

function _translateString(value, toEn) {
  if (!value || !toEn || !/[\u4e00-\u9fff]/.test(value)) return value

  // 1) 整句词条（保留原有首尾空白，避免布局文本粘连）
  const lead = value.match(/^\s*/)[0]
  const tail = value.match(/\s*$/)[0]
  const core = value.trim()
  const exact = UI_ZH_EN[core]
  if (exact !== undefined) return lead + exact + tail

  // 2) 动态句式：数字/状态插值的整句翻译
  for (const [re, repl] of ZH_EN_PATTERNS) {
    const m = re.exec(core)
    if (m) {
      const out = typeof repl === 'function' ? repl(m) : core.replace(re, repl)
      if (out !== core) return lead + out + tail
    }
  }

  const MARK = '\u0001'
  let out = value.replace(_zhPattern, (m) => (UI_ZH_EN[m] ?? m) + MARK)
  // 中文原文无空格，多个词条连译会粘连成 "Please enterReceiverName"；
  // 在词条边界（标记处）补空格还原单词分隔——仅当下一个字符是词字符时补，
  // 句尾标记与标点前标记直接丢弃，避免尾随空格与 "code 。" 式错位。
  out = out.replace(/\u0001(?=[A-Za-z0-9])/g, ' ').replace(/\u0001/g, '')
  // 防混杂护栏：翻译后仍含中文说明词条只覆盖了部分片段，
  // 输出"请 Register 创建账号。"式中英拼接会很难看——此时整体保留原文，
  // 待词条补齐后再完整翻译。整句要么全译、要么保持原文，绝不拼接。
  return /[\u4e00-\u9fff]/.test(out) ? value : out
}

/**
 * 翻译业务数据中的可译文案（如昵称、头像 alt），供组件在渲染前直接使用，
 * 避免中文缩写或 "张三 的头像" 之类拼接串残留。
 */
export function translateDataText(value) {
  if (locale.value !== 'en-US') return value
  return _translateString(value, true)
}

function _isIgnoredNode(node, forAttr = false) {
  if (!node) return true
  const el = node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement
  if (!el) return true
  const tag = el.tagName
  if (tag === 'FONT' || tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT') return true
  // textarea 的正文不翻译（用户输入），但它的 placeholder 属于界面文案，需要翻译
  if (!forAttr && tag === 'TEXTAREA') return true
  return Boolean(el.closest?.('.skiptranslate, [data-no-translate]'))
}

function _walkAndTranslate(root=document.body) {
  if (!root || locale.value !== 'en-US') return
  if (_isIgnoredNode(root)) return
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode(node) {
      if (_isIgnoredNode(node)) return NodeFilter.FILTER_REJECT
      return node.nodeValue?.trim() ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_SKIP
    }
  })
  const nodes=[]
  while(walker.nextNode()) nodes.push(walker.currentNode)
  for(const node of nodes){
    if(!_nodeSources.has(node)) _nodeSources.set(node,node.nodeValue)
    const source=_nodeSources.get(node)
    const next=_translateString(source,true)
    if(next!==node.nodeValue) node.nodeValue=next
  }
  const attrs=['placeholder','title','aria-label','alt']
  // 新增子树时 root 自身的属性也必须处理：Vue 常把 title/aria-label 直接写在
  // 新插入的根节点上（如社交登录按钮、商品卡片），只遍历子孙会漏译。
  const attrRoots=[]
  if (root.nodeType === Node.ELEMENT_NODE) attrRoots.push(root)
  if (root.querySelectorAll) root.querySelectorAll('*').forEach(el=>attrRoots.push(el))
  for(const el of attrRoots){
    if (_isIgnoredNode(el, true)) continue
    for(const attr of attrs){
      if(!el.hasAttribute(attr)) continue
      let cache=_attrSources.get(el)
      if(!cache){cache={};_attrSources.set(el,cache)}
      if(cache[attr]===undefined) cache[attr]=el.getAttribute(attr)
      const next=_translateString(cache[attr],true)
      if(next!==el.getAttribute(attr)) el.setAttribute(attr,next)
    }
  }
}

function _restoreChinese(root=document.body) {
  if(!root) return
  const walker=document.createTreeWalker(root,NodeFilter.SHOW_TEXT)
  const nodes=[]
  while(walker.nextNode()) nodes.push(walker.currentNode)
  for(const node of nodes){
    const source=_nodeSources.get(node)
    if(source!==undefined && node.nodeValue!==source) node.nodeValue=source
  }
  const attrs=['placeholder','title','aria-label','alt']
  root.querySelectorAll?.('*').forEach(el=>{
    const cache=_attrSources.get(el)
    if(!cache) return
    for(const attr of attrs) if(cache[attr]!==undefined && el.getAttribute(attr)!==cache[attr]) el.setAttribute(attr,cache[attr])
  })
}

function applyDomLocale(){
  if(typeof document==='undefined') return
  document.documentElement.lang=locale.value
  document.documentElement.setAttribute('data-locale',locale.value)
  // 保持 notranslate 与 translate="no"，防止浏览器内置翻译扩展（Edge/Chrome "正在翻译"）注入 <font> 破坏 DOM
  document.documentElement.setAttribute('translate', 'no')
  document.documentElement.classList.add('notranslate')
  if(_observer) _observer.disconnect()
  _domApplying=true
  if(locale.value==='en-US') _walkAndTranslate(document.body)
  else _restoreChinese(document.body)
  document.title=locale.value==='en-US' ? 'LANLinkshopping · Connected Commerce' : 'LANLinkshopping · 聚合型B2B电商平台'
  _domApplying=false
  _observeDom()
}

let _mutationCount = 0
let _mutationWindowStart = 0
const MAX_MUTATIONS_PER_SEC = 60

// 大批量渲染（表格/后台仪表盘）会在一秒内产生上百条 DOM 变更。
// 此时不做逐条处理，合并为一次全量扫描，既避免卡顿，也不会漏译后续渲染的内容。
let _pendingFullScan = null
function _scheduleFullScan(){
  if (_pendingFullScan) return
  _pendingFullScan = setTimeout(() => {
    _pendingFullScan = null
    if (locale.value !== 'en-US' || !document.body) return
    _domApplying = true
    try { _walkAndTranslate(document.body) } finally { _domApplying = false }
  }, 150)
}

function _observeDom(){
  if(typeof MutationObserver==='undefined' || !document.body) return
  if(_observer) _observer.disconnect()
  _observer=new MutationObserver(mutations=>{
    if(_domApplying || locale.value!=='en-US') return

    // 熔断保护：1秒内超过 MAX_MUTATIONS_PER_SEC 次变更，判断存在外部扩展（如浏览器翻译）死循环，立即熔断断开
    const now = Date.now()
    if (now - _mutationWindowStart > 1000) {
      _mutationWindowStart = now
      _mutationCount = 0
    }
    _mutationCount += mutations.length
    if (_mutationCount > MAX_MUTATIONS_PER_SEC) {
      _mutationCount = 0
      _scheduleFullScan()
      return
    }

    // 暂停监听以避免修改 DOM 时产生新的 MutationObserver 递归触发
    _observer.disconnect()
    _domApplying=true

    try {
      for(const m of mutations){
        if(m.type==='characterData' && m.target){
          const node=m.target
          if(_isIgnoredNode(node)) continue
          const cur=node.nodeValue||''
          if(/[一-鿿]/.test(cur)){
            _nodeSources.set(node,cur)
            const next=_translateString(cur,true)
            if(next!==cur) node.nodeValue=next
          }
        } else if(m.type==='childList'){
          m.addedNodes.forEach(n=>{
            if(_isIgnoredNode(n)) return
            if(n.nodeType===Node.ELEMENT_NODE) _walkAndTranslate(n);
            else if(n.nodeType===Node.TEXT_NODE && n.nodeValue?.trim()){
              const val = n.nodeValue
              if(/[一-鿿]/.test(val)){
                _nodeSources.set(n,val)
                const next=_translateString(val,true)
                if(next!==val) n.nodeValue=next
              }
            }
          })
        } else if(m.type==='attributes'){
          const el=m.target
          if(_isIgnoredNode(el, true)) continue
          const attr=m.attributeName
          if(['placeholder','title','aria-label','alt'].includes(attr)){
            let cache=_attrSources.get(el); if(!cache){cache={};_attrSources.set(el,cache)}
            if(cache[attr]===undefined) cache[attr]=el.getAttribute(attr)
            const next=_translateString(cache[attr],true)
            if(el.getAttribute(attr)!==next) el.setAttribute(attr,next)
          }
        }
      }
    } finally {
      _domApplying=false
      if(locale.value==='en-US' && document.body){
        _observer.observe(document.body,{subtree:true,childList:true,characterData:true,attributes:true,attributeFilter:['placeholder','title','aria-label','alt']})
      }
    }
  })
  _observer.observe(document.body,{subtree:true,childList:true,characterData:true,attributes:true,attributeFilter:['placeholder','title','aria-label','alt']})
}

export function initI18nDom(){
  if(typeof window==='undefined') return
  // localStorage 在隐私模式/禁用 Cookie/配额满时会抛异常，必须容错，否则应用启动即崩
  let saved=null
  try{ saved=localStorage.getItem(LOCALE_STORAGE_KEY) }catch(e){ saved=null }
  if(saved && dicts[saved]) locale.value=saved
  applyDomLocale()
}

/**
 * 系统语言自动识别并落地（多维特征：时区 / 浏览器语言 / Intl 区域指纹 / IP 归属地）。
 *
 * 约定：
 *  - **用户显式选择优先**：只要 localStorage 里有 ll_locale 就直接采用，不做任何自动覆盖，
 *    因此登录后手动切换的语言不会被识别逻辑改回去；
 *  - 识别过程不写入 localStorage（不污染"是否已手动选择"这一事实），仅设置当前 locale；
 *  - 离线信号高置信时跳过 IP 请求，兼顾启动速度与隐私外发最小化；
 *  - 失败静默降级为站点默认语言，绝不阻塞启动。
 * @returns {Promise<{locale:string, confidence:number, source:string}|null>}
 */
export async function applyAutoLocale() {
  if (typeof window === 'undefined') return null
  let saved = null
  try { saved = localStorage.getItem(LOCALE_STORAGE_KEY) } catch (e) { saved = null }
  if (saved && dicts[saved]) {
    // 已存在有效手动选择 → 直接生效，不参与自动识别
    locale.value = saved
    applyDomLocale()
    return { locale: saved, confidence: 1, source: 'stored' }
  }
  const result = await detectLocale({ saved: null })
  if (result && dicts[result.locale]) locale.value = result.locale
  applyDomLocale()
  return result
}

export const switchLocale = setLocale

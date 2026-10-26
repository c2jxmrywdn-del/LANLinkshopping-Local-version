# 21st.dev Apple UI 图标规范与矢量化映射表 (Icon System)

根据 21st.dev 设计平台（Aceternity UI、Magic UI、Cult UI）及 Apple Human Interface Guidelines (Apple HIG)，系统全量弃用 Emoji 作为图标，统一升级为 **21st.dev 推荐的现代化 SVG 矢量图标体系（基于 Lucide / Tabler 规范 + Apple macOS Squircle 质感渐变）**。

---

## 1. 为什么不能在 Apple UI 中使用 Emoji？

| 维度 | Emoji 缺陷 | 21st.dev 矢量图标 (Lucide / Apple HIG) |
| :--- | :--- | :--- |
| **视觉一致性** | Windows、Android、iOS 和 Chrome 对同一个 Emoji 渲染风格迥异，Windows 上颜色刺眼发灰，iOS 上为高反光拟物，破坏整体统一质感。 | 统一的几何轮廓线条（`viewBox="0 0 24 24"`, `stroke-width="2"`, `stroke-linecap="round"`），在任何平台与屏幕下表现绝对一致。 |
| **色彩与主题适配** | Emoji 色彩固定无法随 CSS 变量、深色模式（Dark Mode）或 Apple 柔和低饱和度主题自适应。 | 支持 `currentColor` 与 CSS 渐变注入，自动响应浅色/深色模式及 Apple 空间玻璃透光。 |
| **高分屏渲染 (Retina)** | 在 Retina 视网膜屏与高 DPI 设备上，Emoji 作为字体字符易发虚或基线对齐偏离。 | 100% 矢量矢量路径，在任何缩放级别下极致锐利，支持精准的像素级垂直对齐。 |
| **动效与交互控制** | 无法对 Emoji 单独做微描边动画、旋转、高斯模糊光晕或悬浮微放大。 | 可配合 CSS Transforms、Spring Physics 弹簧插值做物理弹跳与光斑映射。 |

---

## 2. 21st.dev 上游设计标准与 Apple macOS Dock 图标映射表

参考 21st.dev 上游 `Floating Dock` (Aceternity UI) 与 `Dock` (Magic UI) 的实现标准，每个图标封装为**拟真 Apple App Icon 连续曲率圆角矩形（Squircle）**，内嵌专属的 Apple iOS 18 / macOS Sequoia 官方标准渐变色盘与纯白微阴影矢量核心：

| 业务入口 | 业务语义 | 21st.dev / Lucide 图标 | Apple 标准渐变配色方案 (macOS / iOS) | 核心矢量代码 |
| :--- | :--- | :--- | :--- | :--- |
| **全部活动** | 发现、大促、活动总览 | `Sparkles` / `Compass` | `linear-gradient(135deg, #FF5E62 0%, #FF9966 100%)` (Apple Arcade 暖红渐变) | `<Sparkles :size="20" />` |
| **领券专区** | 优惠券、减免、折扣票据 | `TicketPercent` / `Ticket` | `linear-gradient(135deg, #F59E0B 0%, #D97706 100%)` (Apple Wallet 金橙渐变) | `<TicketPercent :size="20" />` |
| **限时秒杀** | 闪电秒杀、急促倒计时 | `Zap` / `Flame` | `linear-gradient(135deg, #F43F5E 0%, #BE123C 100%)` (Apple Fitness 极速品红) | `<Zap :size="20" />` |
| **爆款画廊** | Aceternity 苹果卡片画廊 | `Layers` / `Spotlight` | `linear-gradient(135deg, #8B5CF6 0%, #6D28D9 100%)` (Apple Podcasts 空间紫) | `<Layers :size="20" />` |
| **B2B 商城** | 官方自营、供应链商城 | `Store` / `Building2` | `linear-gradient(135deg, #3B82F6 0%, #1D4ED8 100%)` (Apple App Store 商务蓝) | `<Store :size="20" />` |
| **购物车** | 待采购清单、实时角标 | `ShoppingCart` | `linear-gradient(135deg, #06B6D4 0%, #0891B2 100%)` (Apple Books 湖蓝渐变) | `<ShoppingCart :size="20" />` |
| **我的订单** | 采购履约、物流轨迹 | `Package` / `Truck` | `linear-gradient(135deg, #10B981 0%, #059669 100%)` (Apple Messages 翡翠绿) | `<Package :size="20" />` |
| **企业账期** | 授信免息、B2B金融 | `CreditCard` / `ShieldCheck` | `linear-gradient(135deg, #6366F1 0%, #4338CA 100%)` (Apple Card 靛青质感) | `<CreditCard :size="20" />` |
| **回到顶部** | 页面快速回滚 | `ArrowUp` | `linear-gradient(135deg, #64748B 0%, #475569 100%)` (Apple System 灰金属) | `<ArrowUp :size="20" />` |

---

## 3. 组件内部图标升级一览

1. **`AppleCardsCarousel.vue` (卡片画廊)**：
   - 底部操作栏彻底移除 `🛒 加入购物车` Emoji，替换为 `<ShoppingCart :size="16" />` + 文本；
   - “立即采购下单” 替换为 `<CreditCard :size="16" />` + 文本。
2. **`AppleDynamicIsland.vue` (灵动岛)**：
   - 增加 `:icon` 属性插槽，直接支持接收 Lucide 图标组件（如 `Package`、`CreditCard`、`ShieldAlert`、`Sparkles`）；
   - 彻底清除通知态与紧凑态中内嵌的 Emoji 字符。
3. **`ActivityCenter.vue` (活动中心主页)**：
   - 秒杀标签 `⚡ 限时秒杀` 替换为 `<Zap :size="12" />`；
   - 积分激励 `🎁 积分有礼` 替换为 `<Gift :size="22" />`；
   - 空状态 `🎟️` 替换为 `<Ticket :size="44" />`。
4. **`MainLayout.vue` (全局导航栏)**：
   - 顶部消息替换为 `<Bell :size="16" />`；
   - 顶部购物车替换为 `<ShoppingCart :size="16" />`；
   - 下拉菜单中的钱包与活动中心全部采用标准化矢量图标。

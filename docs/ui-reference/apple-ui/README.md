# Apple UI 风格动效与交互组件库 (21st.dev 抓取与 Vue 3 原生实现)

本目录归档了从 **21st.dev** 及主流 Apple 风格设计系统（Aceternity UI、Cult UI、Magic UI、DesignAli）抓取的 4 大代表性 Apple UI/UX 动态效果与交互组件源码，并在项目中提供了 **Vue 3 + `<script setup>` 原生零外部重型依赖** 的完整实现。

---

## 目录结构与组件映射表

| 组件名称 | 灵感源与上游 (21st.dev / 原作) | 上游参考源码 (TSX) | 项目原生 Vue 3 组件 | 适用业务场景 |
| :--- | :--- | :--- | :--- | :--- |
| **Apple Cards Carousel** | Aceternity UI (`apple-cards-carousel`) | [`apple-cards-carousel.tsx`](./apple-cards-carousel.tsx) | [`frontend/src/components/ui/AppleCardsCarousel.vue`](file:///d:/桌面/LANLinkShopping/Shopping/frontend/src/components/ui/AppleCardsCarousel.vue) | 商城首页大促、爆款新品发布、会员权益推荐、商品故事沉浸式展示 |
| **Apple Dynamic Island** | Cult UI / SmoothUI (`dynamic-island`) | [`dynamic-island.tsx`](./dynamic-island.tsx) | [`frontend/src/components/ui/AppleDynamicIsland.vue`](file:///d:/桌面/LANLinkShopping/Shopping/frontend/src/components/ui/AppleDynamicIsland.vue) | 悬浮灵动胶囊：订单状态、支付倒计时、审核待办提醒、合规拦截预警 |
| **Apple macOS Dock** | Magic UI (`dock`) | [`macos-dock.tsx`](./macos-dock.tsx) | [`frontend/src/components/ui/AppleDock.vue`](file:///d:/桌面/LANLinkShopping/Shopping/frontend/src/components/ui/AppleDock.vue) | 底部/侧边浮动导航坞、快速操作栏、工作台快捷入口、经典鱼眼抛物线放大 |
| **Apple Vision Liquid Glass** | DesignAli (`liquid-glass-card`) | [`liquid-glass-card.tsx`](./liquid-glass-card.tsx) | [`frontend/src/components/ui/AppleGlassCard.vue`](file:///d:/桌面/LANLinkShopping/Shopping/frontend/src/components/ui/AppleGlassCard.vue) | VisionOS 空间玻璃卡片、3D 鼠标视差微倾斜、镜面光斑追踪、液态折射微光 |

---

## Apple UI 设计语言与美学规范 (Apple HIG)

1. **液态毛玻璃质感 (Liquid Glass / Frosted Glass)**
   - 采用标准 Apple 规范的 `backdrop-filter: blur(20px) saturate(180%)`。
   - 内部边缘叠加微妙高光反射：`box-shadow: inset 0 1px 1px 0 rgba(255, 255, 255, 0.25)`。
   - 在明亮与深色模式下均保持极高的文本可读性，严格避免高饱和度的扎眼纯色。

2. **流体弹簧物理动效 (Fluid Spring Physics)**
   - 放弃僵硬的线性过渡，全量采用 iOS 规范贝塞尔与阻尼弹簧曲线：
     - `cubic-bezier(0.16, 1, 0.3, 1)`（Apple 经典的弹簧减速曲线）
     - `cubic-bezier(0.34, 1.56, 0.64, 1)`（轻微弹性回弹 Overshoot）

3. **微交互视差 (Subtle Spatial Parallax & Spotlight)**
   - 鼠标滑动时实时计算距离视口中心/卡片中心的偏移，动态施加 3D 旋转（`rotateX` / `rotateY`）与光源高光（Radial Gradient Spotlight）。

---

## Vue 3 移植实现要点

- **零冗余依赖**：上游 React 代码依赖于 `framer-motion` 与 `@tabler/icons-react`。本项目使用 Vue 3 原生响应式（`ref`, `computed`, `watch`）结合原生 CSS 3D Transforms、rAF 阻尼计算与 Transition/Teleport，无需额外下载几十兆沉重的外部依赖。
- **开箱即用**：支持 TypeScript 类型提示、Props 默认值、Slot 插槽扩展与全局响应式。

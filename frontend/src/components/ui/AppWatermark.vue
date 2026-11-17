<template>
  <!--
    全站平铺水印：斜向重复铺满整个视口，覆盖所有路由（含登录页、后台、商户中心）。
    不参与布局计算，也不拦截鼠标事件（pointer-events: none）。

    为什么放 App.vue 而不是各 Layout：
    主站/后台/商户中心是三套 Layout，另有登录、法务文档等独立页；
    逐页插入会漏，且每次新增布局都要记得补一行。挂在根组件只写一次。

    为什么用 SVG 平铺背景而不是循环渲染几百个 span：
    平铺是一次背景绘制 + 一次合成，不产生 DOM 节点、不参与重排；
    几百个 span 在低端设备滚动时会有明显掉帧。代价是文字不可选中，
    但水印本就不该被选中或复制。
  -->
  <div class="app-watermark" aria-hidden="true" data-no-translate></div>
</template>

<script setup>
/**
 * 全屏平铺签名水印。
 *
 * 文案是**固定签名**，不参与 i18n —— 署名与起始日期属于作者标识，
 * 切换中英文时不应被翻译。文案写在 SVG data URI 里，全局 DOM 翻译扫描
 * 不到它；外层再加 `data-no-translate` 兜底。
 */
</script>

<style scoped>
/*
 * 平铺参数：
 *   单元 320×190，文字旋转 -28°，覆盖同一水平/垂直方向的两个相邻单元；
 *   密度约每 6.1 万 px² 一处 —— 1440×900 视口约 21 处，够"铺满"但不糊住正文。
 */
.app-watermark {
  position: fixed;
  inset: 0;
  z-index: 40;
  pointer-events: none;
  user-select: none;
  background-repeat: repeat;
  background-size: 320px 190px;
  background-image: url("data:image/svg+xml,%3Csvg%20xmlns%3D'http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg'%20width%3D'320'%20height%3D'190'%3E%3Ctext%20x%3D'160'%20y%3D'95'%20transform%3D'rotate(-28%20160%2095)'%20text-anchor%3D'middle'%20font-family%3D'-apple-system%2CSegoe%20UI%2CRoboto%2Csans-serif'%20font-size%3D'12.5'%20letter-spacing%3D'0.6'%20fill%3D'%2364748B'%20fill-opacity%3D'0.55'%3EDev%20of%20Jason%20ouyang%20%C2%B7%20Since%202026.09.12%3C%2Ftext%3E%3C%2Fsvg%3E");
}

/* 深色主题：换成更亮的描边色，避免灰字消失在深底里 */
[data-theme='dark'] .app-watermark {
  background-image: url("data:image/svg+xml,%3Csvg%20xmlns%3D'http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg'%20width%3D'320'%20height%3D'190'%3E%3Ctext%20x%3D'160'%20y%3D'95'%20transform%3D'rotate(-28%20160%2095)'%20text-anchor%3D'middle'%20font-family%3D'-apple-system%2CSegoe%20UI%2CRoboto%2Csans-serif'%20font-size%3D'12.5'%20letter-spacing%3D'0.6'%20fill%3D'%23A8B8CC'%20fill-opacity%3D'0.5'%3EDev%20of%20Jason%20ouyang%20%C2%B7%20Since%202026.09.12%3C%2Ftext%3E%3C%2Fsvg%3E");
}

/* 窄屏：单元缩小，保持"细小"观感，同时不至于一行只有两个水印 */
@media (max-width: 640px) {
  .app-watermark { background-size: 210px 130px; }
}

/* 打印与导出不需要水印（截图/PDF 由浏览器自行决定） */
@media print {
  .app-watermark { display: none; }
}
</style>

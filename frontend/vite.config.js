import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  build: {
    rollupOptions: {
      output: {
        // 第三方框架分包：利于浏览器缓存与并行加载，降低首屏 JS 阻塞
        manualChunks: {
          'antd': ['ant-design-vue'],
          'vue-vendor': ['vue', 'vue-router', 'pinia', 'axios'],
          'swiper': ['swiper']
        }
      }
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  // 生产构建预览（Lighthouse 评测等）同样代理 /api 到后端
  preview: {
    port: 4173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})

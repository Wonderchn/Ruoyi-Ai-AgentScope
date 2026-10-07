import path from 'node:path';
import vue from '@vitejs/plugin-vue';
import { defineConfig } from 'vite';

// https://vite.dev/config/
export default defineConfig({
  base: process.env.WEB_ADMIN_BASE_PATH || '/',
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  // 浏览器缓存问题（与 workbench 的 dev server 同一约定）
  server: {
    headers: {
      'Cache-Control': 'no-store',
    },
  },
});

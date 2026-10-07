import path from 'node:path';
import process from 'node:process';
import { defineConfig, loadEnv } from 'vite';
import plugins from './.build/plugins';

// https://vite.dev/config/
export default defineConfig((cnf) => {
  const { mode } = cnf;
  const env = loadEnv(mode, process.cwd());
  const { VITE_APP_ENV } = env;

  /**
   * 开发期反代目标（**只在显式设置时才启用**，不把任何端口写进产品配置）。
   *
   * 为什么需要：应用里的请求路径是**绝对路径**（AI 资源面 `/api/ai/v1/**`、平台面
   * `/system/**`），而 `VITE_API_URL` 按 `.env.example` 必须留空或只填 origin
   * （填 `/api` 会拼出 `/api/api/...`，见 `tests/session-paths.test.ts`）。
   * 于是开发时若不反代，这些请求会打到 dev server 自己身上 —— 浏览器验收就变成了
   * "请求 200 但返回的是 index.html"，看起来通过、实际没到后端。
   *
   * 生产用 nginx 做同一件事；这里只是让开发/验收与生产**同源语义**一致。
   * 用法：`DEV_PROXY_TARGET=http://127.0.0.1:6040 pnpm dev`
   *
   * ⚠️ **刻意不叫 `VITE_DEV_PROXY_TARGET`**：`VITE_` 前缀会被 `vite-plugin-env-typed`
   * （`.build/plugins/index.ts:16-21`，`envPrefix: 'VITE_'`）写进**生成文件**
   * `types/import_meta.d.ts` —— 那是**应用**的 `import.meta.env` 类型声明面，
   * 而这是**开发服务器**的配置，不是应用环境变量。实测：用 `VITE_` 命名会让生成器把
   * `VITE_API_URL`（9 处代码在用）换成 `VITE_DEV_PROXY_TARGET` ⇒ 生成物与仓库意图不一致。
   */
  const proxyTarget = process.env.DEV_PROXY_TARGET || '';
  const proxy = proxyTarget
    ? {
        '/api': { target: proxyTarget, changeOrigin: true },
        '/system': { target: proxyTarget, changeOrigin: true },
        '/auth': { target: proxyTarget, changeOrigin: true },
      }
    : undefined;

  return {
    base: VITE_APP_ENV === 'production' ? '/' : '/',
    plugins: plugins(cnf),
    resolve: {
      alias: {
        '@': path.resolve(__dirname, './src'),
      },
    },
    css: {
      // css全局变量使用，@/styles/variable.scss文件
      preprocessorOptions: {
        scss: {
          additionalData: '@use "@/styles/var.scss" as *;',
        },
      },
    },
    // 浏览器缓存问题
    server: {
      headers: {
        'Cache-Control': 'no-store',
      },
      proxy,
    },
  };
});

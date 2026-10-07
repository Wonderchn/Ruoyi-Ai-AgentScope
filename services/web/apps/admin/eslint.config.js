import antfu from '@antfu/eslint-config';

// 与 apps/workbench 同一套规则（antfu 配置 + Vue 块顺序 script → template → style）。
export default antfu({
  vue: {
    'vue/block-order': [
      'error',
      {
        order: ['script', 'template', 'style'],
      },
    ],
  },
  typescript: true,
  stylistic: {
    indent: 2,
    semi: true,
    quotes: 'single',
  },
  rules: {
    'new-cap': ['off', { newIsCap: true, capIsNew: false }],
    'no-console': 'off',
  },
  ignores: [
    '**/dist/**',
    '**/node_modules/**',
    '**/types/**',
    '**/public/**',
    '**/vite.config.ts',
    '**/vite.config.mts',
    '**/eslint.config.js',
    './*.cjs',
    './*.js',
    './package.json',
  ],
}, {
  files: ['tests/**/*.test.ts'],
  rules: {
    // 与 workbench 相同：测试用 Node 自带的 node:test，不引入测试框架依赖。
    'test/no-import-node-test': 'off',
  },
}, {
  files: ['tests/ts-loader.mjs'],
  rules: {
    // 加载器必须在注册之前 await 已安装的 TypeScript 编译器。
    'antfu/no-top-level-await': 'off',
  },
});

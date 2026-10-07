import type { ConfigEnv, PluginOption } from 'vite';
import path from 'node:path';
import vue from '@vitejs/plugin-vue';
import UnoCSS from 'unocss/vite';
import AutoImport from 'unplugin-auto-import/vite';
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers';
import Components from 'unplugin-vue-components/vite';
import createSvgIcon from './svg-icon';

const root = path.resolve(__dirname, '../../');

function plugins({ command }: ConfigEnv): PluginOption[] {
  return [
    UnoCSS(),
    vue(),
    AutoImport({
      imports: ['vue'],
      eslintrc: {
        enabled: true,
      },
      resolvers: [ElementPlusResolver()],
      dts: path.join(root, 'types', 'auto-imports.d.ts'),
    }),
    Components({
      resolvers: [ElementPlusResolver()],
      dts: path.join(root, 'types', 'components.d.ts'),
    }),
    createSvgIcon(command === 'build'),
  ];
}

export default plugins;

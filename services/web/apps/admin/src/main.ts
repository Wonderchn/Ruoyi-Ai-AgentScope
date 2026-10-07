import * as ElementPlusIconsVue from '@element-plus/icons-vue';
import ElementPlus from 'element-plus';
import { createApp } from 'vue';
import App from './App.vue';
import router from './routers';
import store from './stores';
import 'element-plus/dist/index.css';
import './styles/index.css';

const app = createApp(App);

app.use(store);
app.use(router);
app.use(ElementPlus);
// 注册 ElementPlus 全部图标，供菜单用 <component :is="iconName" /> 引用。
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component);
}

app.mount('#app');

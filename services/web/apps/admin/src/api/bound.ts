/**
 * 把领域 API 工厂绑到**本应用唯一的**共享客户端实例上。
 *
 * 这里刻意是唯一允许 import `@/utils/request` 的地方：
 * - 工厂（`./system`、`./monitor`）只接受注入的 `client`，因此**可以被单元测试直接调用**
 *   （`apps/admin/tests/ts-loader.mjs` 不认识 `@/` 别名，也不会渲染 `.vue`）；
 * - 绑定只做一次，**不新建第二个客户端**（C7 禁止第二套客户端）。
 */
import platformClient from '@/utils/request';
import { createMonitorApi } from './monitor';
import { createSystemApi } from './system';

export const systemApi = createSystemApi(platformClient);
export const monitorApi = createMonitorApi(platformClient);

/**
 * 平台监控 API（WP-046 面）+ **RAG Trace 页面的四条真实端点**（G-10）。
 *
 * ## G-10 的证据链（写在这里，免得后人再去踩一次）
 *
 * T0 台账里曾登记「`monitor:trace:*` 端点实测 404（模块已打包但端点不可达）」。**那条记录是错的**，
 * 错在**路径名**：探针用的是 `/monitor/trace/list`（少一层 `run`）。T7 实测（同一实例、同一 token）：
 *
 * | 探针 | body.code | 含义 |
 * |---|---|---|
 * | `GET /monitor/trace/run/list` | **403** | **命中了 handler**，权限切面拒绝 |
 * | `GET /monitor/trace/list` | **404** `请求地址不存在` | **路由表未命中** |
 * | `GET /monitor/trace/NOPE/NOPE` | **404** `请求地址不存在` | 同上（对照） |
 *
 * ⇒ **"403 与 404 在鉴权层不可区分"这个担心在本应用里不成立**：本应用恒定 HTTP 200、
 * 语义码在 body，而且路由未命中会给出**另一句话**（`请求地址不存在`）。所以 403 = 端点存在。
 * 这与 `CONTRACTS-v1.md` **C13.4** 已登记的教训同形：`/agent/v1/stream` 的 404 是
 * **"路由名未登记"**，不是**"未放行"**。
 *
 * **但要写清是哪一层可达**：403 只证明**到达了权限切面**，**不证明 handler 能返回 200**。
 * 「有权限 → 200」的正例由 `t7_synth_admin` 在实例恢复后补跑（见报告）。
 *
 * ## 四条端点的形状差异（不是同一个模板）
 *
 * - `run/list` 是 `TableDataInfo`（**分页**，见 `TraceController:37-40`）；
 * - `run/{traceId}`、`node/list/{traceId}`、`detail/{traceId}` 都是 `R<T>`（**不分页、单资源**）。
 *
 * 所以这里分成 `trace.runs()`（走 `getRows`）与 `trace.run/nodes/detail()`（走 `get`）——
 * 把四条都写成 `getRows` 会在单资源端点上静默拿到 `{rows: [], total: 0}`（**假空态**）。
 */
import type { PlatformClient } from '@ruoyi/platform-client/http';
import type { PageParams } from '../../utils/list';

/** `TraceRunVo` 里管理端展示用到的字段（`TraceRunBo` 是它的查询对象）。 */
export interface TraceRunVo {
  id?: string;
  traceId?: string;
  traceName?: string;
  businessType?: string;
  businessId?: string;
  userId?: string;
  tenantId?: string;
  status?: string;
  startTime?: string;
  endTime?: string;
  durationMs?: number;
  errorMessage?: string;
}

export interface TraceNodeVo {
  id?: string;
  traceId?: string;
  nodeName?: string;
  nodeType?: string;
  status?: string;
  startTime?: string;
  endTime?: string;
  durationMs?: number;
  errorMessage?: string;
}

/** `GET /monitor/trace/detail/{traceId}` 的返回（运行 + 节点）。字段按后端 VO 声明。 */
export interface TraceDetailVo {
  run?: TraceRunVo;
  nodes?: TraceNodeVo[];
}

/** 与 `TraceRunBo`（`ruoyi-common-trace`）逐字段对应的筛选条件。 */
export interface TraceRunQuery extends PageParams {
  traceId?: string;
  traceName?: string;
  businessType?: string;
  businessId?: string;
  userId?: string;
  tenantId?: string;
  status?: string;
  startTime?: string;
  endTime?: string;
}

export interface SysOperlogVo {
  operId?: string;
  title?: string;
  businessType?: number;
  method?: string;
  requestMethod?: string;
  operName?: string;
  operUrl?: string;
  operIp?: string;
  status?: number;
  operTime?: string;
  costTime?: number;
}

export interface SysOperlogQuery extends PageParams {
  title?: string;
  operName?: string;
  businessType?: number;
  status?: number;
  operTime?: string;
}

export interface SysLogininforVo {
  infoId?: string;
  userName?: string;
  ipaddr?: string;
  loginLocation?: string;
  browser?: string;
  os?: string;
  status?: string;
  msg?: string;
  loginTime?: string;
}

export interface SysLogininforQuery extends PageParams {
  userName?: string;
  ipaddr?: string;
  status?: string;
}

export interface SysUserOnlineVo {
  tokenId?: string;
  userName?: string;
  deptName?: string;
  ipaddr?: string;
  loginLocation?: string;
  browser?: string;
  os?: string;
  loginTime?: number;
}

/**
 * S2-F01/op14：服务监控快照（`GET /monitor/server`，`R<ServerInfoVo>`，单资源不分页）。
 * 口径：负载类不可用为 -1；内存/JVM/磁盘为字节，usage 为已换算百分数（-1=不可算）。
 */
export interface ServerInfoVo {
  cpu: { cores: number; systemLoadAverage: number; systemCpuLoad: number; processCpuLoad: number };
  mem: { total: number; free: number; used: number; usage: number };
  jvm: {
    total: number;
    max: number;
    free: number;
    used: number;
    usage: number;
    version: string;
    home: string;
    startTimeMillis: number;
    uptimeSeconds: number;
  };
  sys: { hostName: string; osName: string; osArch: string; userDir: string };
  disk: { path: string; total: number; free: number; usable: number; used: number; usage: number };
}

export function createMonitorApi(client: PlatformClient) {
  return {
    trace: {
      /** 分页列表（`TableDataInfo`）。筛选字段与 `TraceRunBo` 逐项对应。 */
      runs: (query: TraceRunQuery) => client.getRows<TraceRunVo>('/monitor/trace/run/list', { query: { ...query } }),
      /** 单条运行详情（`R<TraceRunVo>`，**不分页**）。 */
      run: (traceId: string) => client.get<TraceRunVo>(`/monitor/trace/run/${encodeURIComponent(traceId)}`),
      /** 节点列表（`R<List<TraceNodeVo>>`，**不分页**）。 */
      nodes: (traceId: string) => client.get<TraceNodeVo[]>(`/monitor/trace/node/list/${encodeURIComponent(traceId)}`),
      /** 完整详情（`R<TraceDetailVo>`，**不分页**）。 */
      detail: (traceId: string) => client.get<TraceDetailVo>(`/monitor/trace/detail/${encodeURIComponent(traceId)}`),
    },

    /** S2-F01/op14：服务监控快照（`SysServerController`，权限=菜单 117 既有 `monitor:admin:list`）。 */
    server: {
      info: () => client.get<ServerInfoVo>('/monitor/server'),
    },

    operlogs: {
      list: (query: SysOperlogQuery) => client.getRows<SysOperlogVo>('/monitor/operlog/list', { query: { ...query } }),
      remove: (operIds: readonly string[]) =>
        client.del<unknown>(`/monitor/operlog/${operIds.map(encodeURIComponent).join(',')}`),
      /** 清空：**DELETE** 且无参数（与按 id 删除共用权限 `monitor:operlog:remove`）。 */
      clean: () => client.del<unknown>('/monitor/operlog/clean'),
      exportUrl: () => '/monitor/operlog/export',
    },

    logininfors: {
      list: (query: SysLogininforQuery) =>
        client.getRows<SysLogininforVo>('/monitor/logininfor/list', { query: { ...query } }),
      remove: (infoIds: readonly string[]) =>
        client.del<unknown>(`/monitor/logininfor/${infoIds.map(encodeURIComponent).join(',')}`),
      clean: () => client.del<unknown>('/monitor/logininfor/clean'),
      /** 账户解锁是 **GET**（`SysLogininforController`），不是 PUT/POST —— 照模板写会 405。 */
      unlock: (userName: string) =>
        client.get<unknown>(`/monitor/logininfor/unlock/${encodeURIComponent(userName)}`),
      exportUrl: () => '/monitor/logininfor/export',
    },

    onlines: {
      list: (query: PageParams & { userName?: string; ipaddr?: string }) =>
        client.getRows<SysUserOnlineVo>('/monitor/online/list', { query: { ...query } }),
      /** 强退指定会话：**DELETE** + tokenId。 */
      forceLogout: (tokenId: string) => client.del<unknown>(`/monitor/online/${encodeURIComponent(tokenId)}`),
      /** 退出自己：该端点**没有** `@SaCheckPermission`（任何已登录用户可调用自己的会话）。 */
      logoutMyself: (tokenId: string) =>
        client.del<unknown>(`/monitor/online/myself/${encodeURIComponent(tokenId)}`),
    },

    cache: {
      info: () => client.get<unknown>('/monitor/cache'),
    },
  };
}

export type MonitorApi = ReturnType<typeof createMonitorApi>;

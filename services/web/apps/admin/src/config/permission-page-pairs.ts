/**
 * 权限行 ⇔ 页面 成对核对表（W3-5 硬要求，G-10/G-12/G-15/G-28 全族）。
 *
 * ## 口径（先读，避免把"分母"当"证据"）
 *
 * - **权限行分母**：`sys_menu` 全链迁移（V2 种子 + V4/V5/V6/V12/V14/V16 + V19 套餐扩展）。
 *   采集方式：对 `services/platform/docs/script/sql/postgres/V*.sql` grep `'perms'` 串；
 *   V19 只扩套餐不写 sys_role_menu（G-28 的处置是"扩套餐不取消求交"）。
 * - **页面分母**：本文件 `pages` 数组 + `apps/admin/src/routers/index.ts` 路由表。
 *   断言在 `tests/permission-page-pairs.test.ts`：
 *   有权限行（本文件 claim 的）没有页面路由 ⇒ FAIL；
 *   有页面路由（permission 非空）没有权限行 ⇒ FAIL。
 * - **不在本表管辖**：`ai:conversation:*`/`ai:run:*`/`ai:memory:read` 等动作行
 *   （消费方是 workbench / API 面，不是 admin 页面）；monitor 系统页为前波已交付面，
 *   本文件只收 AI 管理域（W3-5 admin 半边）+ trace（G-10 对照锚点）。
 *
 * ## 双向断言的两类 FAIL（都是真实缺陷形态）
 *
 * 1. **有权限无页面**（G-28 家族）：`ai:kb:*` 等 25 行 V4 起就播种，
 *    但前端没有入口 ⇒ 用户"有权限却看不见任何东西"。
 * 2. **有页面无权限**（G-10 反向）：路由挂出来、后端却会拒绝 ⇒ "菜单可见进去就 403"。
 *
 * ## 本期不做（K2/F08 与 F19/F21 裁决，裁决优先于分母）
 *
 * - F08 知识图谱页面：不做（K2）。入口只保留"未配置"提示（/ai/knowledge 页内
 *   data-testid="kg-unconfigured"）。`/admin/kg/graph`、`/admin/kg/labels` 两个
 *   API 分母不建页、不造页。该行以 `status: 'deferred'` 记录，双向断言跳过。
 * - F19 媒体 / F21 短剧页面：不做（本期范围外），同 deferred。
 */

/** 成对表的一行。 */
export interface PermissionPagePair {
  /** 权限行（sys_menu.perms 逐字）；ragent 管理面无行时为 ''。 */
  permission: string;
  /** 权限行来源（迁移文件与菜单 id，便于审计复核）。 */
  rowSource: string;
  /** 页面路由（admin 路由表路径）；无页面时为 ''。 */
  route: string;
  /** 页面名（04-page-map 的 page 字段或本地命名）。 */
  page: string;
  /** 本期状态。 */
  status: 'live' | 'pair-gap' | 'deferred';
  /**
   * pair-gap 的具体形态：
   * - `NO-ROW`：页面存在但 sys_menu 没有对应权限行（补行走 T0 迁移）；
   * - `NO-PAGE`：权限行存在但本期不建页（需说明去向）。
   */
  gapKind?: 'NO-ROW' | 'NO-PAGE';
  /** 端点可达性（页面能否真实联调）。 */
  endpoint: 'live' | 'BLOCKED-BY-G-22' | 'BLOCKED-BY-EMBEDDED-REGISTRY';
  note?: string;
}

/**
 * AI 管理域成对核对表。
 *
 * `permission` 非空的行参与双向断言（测试会 grep 迁移 SQL 校验行存在、
 * 读路由表校验页面存在）；`''` 权限行只参与"页面有但行没有"的单向登记
 * （PAIR-GAP，交付说明里点名，等待 T0 分配迁移号补行）。
 */
export const AI_ADMIN_PAIRS: readonly PermissionPagePair[] = [
  // ---- 知识库族（有行有页，端点活——本波唯一可真实联调族）----
  {
    permission: 'ai:kb:list',
    rowSource: 'V4__ai_policy_revision.sql 菜单 7101',
    route: '/ai/knowledge',
    page: '知识库列表',
    status: 'live',
    endpoint: 'live',
    note: 'GET /api/ai/v1/knowledge-bases（白名单 kb.list）；M19/M20 实测族健康',
  },
  {
    permission: 'ai:document:read',
    rowSource: 'V4__ai_policy_revision.sql 菜单 7107',
    route: '/ai/knowledge/:kbId',
    page: '知识文档',
    status: 'live',
    endpoint: 'live',
    note: 'GET /api/ai/v1/knowledge-bases/{kbId}/documents（白名单 document.list）；计划任务=文档级 schedule 字段（无独立 SysJob 端点）',
  },
  {
    permission: 'ai:document:read',
    rowSource: 'V4__ai_policy_revision.sql 菜单 7107',
    route: '/ai/knowledge/:kbId/docs/:docId',
    page: '文档分块',
    status: 'live',
    endpoint: 'BLOCKED-BY-EMBEDDED-REGISTRY',
    note: 'chunks 6 条端点未进内嵌装配；页面契约先行，联调 NOT_RUN',
  },
  {
    permission: 'ai:kb:write',
    rowSource: 'V4__ai_policy_revision.sql 菜单 7103',
    route: '/ai/knowledge',
    page: '知识库列表（新建对话框）',
    status: 'live',
    endpoint: 'live',
    note: 'POST /api/ai/v1/knowledge-bases（白名单 kb.write）',
  },
  {
    permission: 'ai:kb:delete',
    rowSource: 'V4__ai_policy_revision.sql 菜单 7104',
    route: '/ai/knowledge',
    page: '知识库列表（删除）',
    status: 'live',
    endpoint: 'live',
    note: 'DELETE /api/ai/v1/knowledge-bases/{kbId}（白名单 kb.delete）',
  },

  // ---- 运行配置权威（F02）：**有行有页且有可达端点**（RW-07 2026-10-07 接通）----
  // V28 播了 3 条权限行（7150-7152，默认不分配角色/套餐）；端点经网关白名单
  // /api/ai/v1/runtime-config/** 真实可达（RW-06 RuntimeCatalogController + 既有四条路由）。
  // /ai/models 与 /ai/settings 两页都用 config.read；写操作按 publish/revoke 独立权限显示。
  {
    permission: 'ai:config:read',
    rowSource: 'V28__runtime_config_permissions.sql 菜单 7150',
    route: '/ai/models',
    page: '模型与提供方（运行配置权威：目录/版本序列/单版本）',
    status: 'live',
    endpoint: 'live',
    note: 'GET /api/ai/v1/runtime-config/{catalog,settings,revisions}（config.read）；无已发布版本时 /catalog 503 CONFIG_AUTHORITY_UNAVAILABLE（页面按"尚未发布权威"空态）',
  },
  {
    permission: 'ai:config:read',
    rowSource: 'V28__runtime_config_permissions.sql 菜单 7150',
    route: '/ai/settings',
    page: '系统设置（运行配置权威分布：可写事实 vs 仅展示）',
    status: 'live',
    endpoint: 'live',
    note: 'GET /api/ai/v1/runtime-config/settings（config.read）；无版本仍 200（revisionAvailable=false）',
  },
  {
    permission: 'ai:config:publish',
    rowSource: 'V28__runtime_config_permissions.sql 菜单 7151',
    route: '/ai/models',
    page: '模型与提供方（发布新版本/回滚/档位附加）',
    status: 'live',
    endpoint: 'live',
    note: 'POST /runtime-config/revisions、…/rollback、…/{id}/catalog（config.publish）；发布=追加不可变版本',
  },
  {
    permission: 'ai:config:revoke',
    rowSource: 'V28__runtime_config_permissions.sql 菜单 7152',
    route: '/ai/models',
    page: '模型与提供方（撤销版本）',
    status: 'live',
    endpoint: 'live',
    note: 'POST /runtime-config/revisions/{revisionId}/revoke（config.revoke，不可逆）',
  },

  // ---- 已退场旧权限（NO-PAGE）：ruoyi-chat 模型/提供方管理面（G-22 未打包）----
  // RW-07（2026-10-07）：admin 的 /ai/models 页改由 runtime-config 权威面服务，
  // 旧 `/system/model|provider` 客户端方法与页面入口一并退役（RW-06 §2.7 的旧→新映射）。
  // V2 的两条 perms 行仍在 sys_menu 里（不改冻结/既有迁移），此处如实登记为"无页面"。
  {
    permission: 'system:model:list',
    rowSource: 'V2__seed_system.sql 菜单 2000210913846157314',
    route: '',
    page: '（已退役：旧 ruoyi-chat 模型管理页，G-22 未打包；现由 /ai/models 的 runtime-config 面承接）',
    status: 'pair-gap',
    gapKind: 'NO-PAGE',
    endpoint: 'BLOCKED-BY-G-22',
    note: 'RW-07 退役：不再有页面使用 system:model:list',
  },
  {
    permission: 'system:provider:list',
    rowSource: 'V2__seed_system.sql 菜单 2000210913451892738',
    route: '',
    page: '（已退役：旧 ruoyi-chat 提供方管理页，同上）',
    status: 'pair-gap',
    gapKind: 'NO-PAGE',
    endpoint: 'BLOCKED-BY-G-22',
    note: 'RW-07 退役：不再有页面使用 system:provider:list',
  },
  {
    permission: 'mcp:tool:list',
    rowSource: 'V2__seed_system.sql 菜单 2001',
    route: '/ai/mcp',
    page: 'MCP 工具目录（工具 Tab）',
    status: 'live',
    endpoint: 'BLOCKED-BY-G-22',
    note: '/mcp/tool/** 在 ruoyi-chat',
  },
  {
    permission: 'mcp:market:list',
    rowSource: 'V2__seed_system.sql 菜单 2010',
    route: '/ai/mcp',
    page: 'MCP 工具目录（市场 Tab）',
    status: 'live',
    endpoint: 'BLOCKED-BY-G-22',
    note: '/mcp/market/** 同上',
  },

  // ---- Agent 目录（F09）：**有行有页且有可达端点**（RW-03 2026-10-07 接通）----
  // V27 播了 5 条权限行（7132-7136）+ 1 条页面 C 行（7141）；端点经网关白名单
  // /api/ai/v1/agent-catalog/agents/** 真实可达（AiEmbeddedAgentCatalogConfiguration）。
  // 页面按 5 个动作分别显示入口，故 5 行都登记（route 相同）。
  {
    permission: 'ai:agent:list',
    rowSource: 'V27__agent_catalog_permissions.sql 菜单 7132（页面 C 行 7141）',
    route: '/ai/agents',
    page: 'Agent 定义 + Prompt 槽位',
    status: 'live',
    endpoint: 'live',
    note: 'GET /api/ai/v1/agent-catalog/agents（agent.list）；RW-03 已接，浏览器 E2E 记 NOT_RUN（待 T8 实例窗口）',
  },
  {
    permission: 'ai:agent:read',
    rowSource: 'V27__agent_catalog_permissions.sql 菜单 7133',
    route: '/ai/agents',
    page: 'Agent 定义 + Prompt 槽位（槽位抽屉）',
    status: 'live',
    endpoint: 'live',
    note: 'GET /agents/{id}/prompts 与 GET /agents/prompt-slots/{slotKey}/default（agent.read）',
  },
  {
    permission: 'ai:agent:write',
    rowSource: 'V27__agent_catalog_permissions.sql 菜单 7134',
    route: '/ai/agents',
    page: 'Agent 定义 + Prompt 槽位（新建/编辑/存槽位）',
    status: 'live',
    endpoint: 'live',
    note: 'POST /agents、PUT /agents/{id}、PUT /agents/{id}/prompts/{slotKey}（agent.write，体字段 content）',
  },
  {
    permission: 'ai:agent:delete',
    rowSource: 'V27__agent_catalog_permissions.sql 菜单 7135',
    route: '/ai/agents',
    page: 'Agent 定义（删除）',
    status: 'live',
    endpoint: 'live',
    note: 'DELETE /agents/{id}（agent.delete；内置/激活中由服务端拒绝）',
  },
  {
    permission: 'ai:agent:activate',
    rowSource: 'V27__agent_catalog_permissions.sql 菜单 7136',
    route: '/ai/agents',
    page: 'Agent 定义（激活）',
    status: 'live',
    endpoint: 'live',
    note: 'POST /agents/{id}/activate（agent.activate，唯一激活语义）',
  },

  // ---- 摄取管线（S2-F06-A1，2026-10-10）：**有页有行且有可达端点**（复用既有行，零新增）----
  // 管线 CRUD 5 条由 AiEmbeddedIngestionConfiguration 装配 + AiGatewayController.ROUTES
  // 逐条放行（/api/ai/v1/ingestion/pipelines**）。动作复用既有 config.read/config.publish
  // ⇒ 权限行复用 V28-7150（ai:config:read），**不新增权限行/菜单号/迁移**（D1 默认裁定）。
  // 写操作（create/update/delete → config.publish）由 V28-7151 行覆盖；
  // 页面当前只接只读列表（写按钮不在本切片），故只登记读行。
  {
    permission: 'ai:config:read',
    rowSource: 'V28__runtime_config_permissions.sql 菜单 7150（复用既有行）',
    route: '/ai/ingestion',
    page: '摄取流水线（管线 CRUD 列表）',
    status: 'live',
    endpoint: 'live',
    note: 'GET /api/ai/v1/ingestion/pipelines（config.read，IPage records/total）；写 4 条走 config.publish（V28-7151 行），真机 CRUD 全链由 T0 环境相位实测',
  },

  // ---- ragent 管理面（有页面、**无权限行** ⇒ PAIR-GAP-NO-ROW）----
  // 【T0 裁决 2026-10-06，team-message-d1d39727 ②】C 行**现在不播种**：
  // 判则 =「扩套餐/播权限的唯一依据是端点真实可达」（G-36b trace 教训同形）。
  // 该族端点 BLOCKED-BY-EMBEDDED-REGISTRY（装配未登记），现在播 C 行 =
  // 制造"有权限无端点"的假公开面。处置：页面保留 + blocked 标注，
  // **C 行播种与端点落地绑定同一个批次**（进维护者决策批次 + 下一迭代，
  // 迁移号届时由 T0 分配）。测试把"1 gap 未播种"如实钉住。
  // （/ai/agents 已于 2026-10-07 由 RW-03 移出本清单：端点落地 ⇒ 上表 live 行；
  //   /ai/settings 已于 2026-10-07 由 RW-07 移出：改由 runtime-config/settings 服务；
  //   /ai/ingestion 已于 2026-10-10 由 S2-F06-A1 移出：管线 CRUD 装配落地 ⇒ 上表 live 行
  //   ——任务面（/ingestion/tasks**）仍未装配，其阻断标注保留在页面任务卡片内。）
  {
    permission: '',
    rowSource: '（无；同上裁决）',
    route: '/ai/skills',
    page: 'Skills',
    status: 'pair-gap',
    gapKind: 'NO-ROW',
    endpoint: 'BLOCKED-BY-EMBEDDED-REGISTRY',
    note: '同上',
  },

  // ---- 裁决行（双向断言跳过）----
  {
    permission: '（K2 裁决：F08 本期不做）',
    rowSource: '（无——图谱端点 /admin/kg/** 属 ai_reference_only 且不建页）',
    route: '',
    page: '知识图谱（F08）',
    status: 'deferred',
    endpoint: 'BLOCKED-BY-EMBEDDED-REGISTRY',
    note: 'K2：图谱入口保留"未配置"提示（/ai/knowledge 页 kg-unconfigured），不建页不造页',
  },
  {
    permission: '（本期范围外：F19/F21）',
    rowSource: '（无）',
    route: '',
    page: '媒体与短剧管理页',
    status: 'deferred',
    endpoint: 'BLOCKED-BY-G-22',
    note: '本期任务书明令不做',
  },
];

/** G-10 对照锚点：前波已交付的 trace 页（它的成对关系已有实测证据链）。 */
export const TRACE_ANCHOR_PAIR: PermissionPagePair = {
  permission: 'monitor:trace:list',
  rowSource: 'V14__system_permission_gaps.sql 菜单 7126',
  route: '/traces',
  page: '链路追踪',
  status: 'live',
  endpoint: 'live',
  note: 'G-10 对照锚点：403/404 对照实测见 api/monitor/index.ts 注释',
};

/** 参与双向断言的行（deferred 跳过；空权限行只做页面侧登记）。 */
export function assertablePairs(): readonly PermissionPagePair[] {
  return AI_ADMIN_PAIRS.filter(pair => pair.status === 'live' && pair.permission !== '');
}

/** PAIR-GAP（页面有但权限行无）清单——交付时点名，等 T0 补行。 */
export function pairGapNoRows(): readonly PermissionPagePair[] {
  return AI_ADMIN_PAIRS.filter(pair => pair.status === 'pair-gap' && pair.gapKind === 'NO-ROW');
}

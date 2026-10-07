/**
 * 权限行 ⇔ 页面 双向断言（W3-5 硬要求）。
 *
 * ## 断言什么（双向，都缺一不可）
 *
 * 1. **有权限行，必须有页面**：成对表里 claim 的每个权限串，必须能
 *    (a) 在 sys_menu 迁移 SQL 里找到该行（grep 迁移文件），
 *    (b) 在 admin 路由表里找到使用该权限的页面（读 routers/index.ts 源码）。
 *    缺 (b) = G-28 家族缺陷（有权限无页面）⇒ FAIL。
 * 2. **有页面，必须有权限行**：路由表里每个 permission 非空的 AI 管理域路由，
 *    其权限串必须出现在成对表里，且该串能在迁移 SQL 里找到权限行。
 *    缺 = G-10 反向（有页面无权限行，菜单可见进去就 403/404）⇒ FAIL。
 *
 * ## 锚点（防"空集合恒真"，判据纪律 §6.1-5）
 *
 * - 成对表必须包含 `ai:kb:list`（锚点：分母非空且内容正确）；
 * - 迁移 SQL 采集必须真读到文件（锚点：文件数 > 0 且含 V4 文件）；
 * - 路由采集必须真读到 `permission: '...'`（锚点：至少命中已知 8 条）。
 * 锚点失败同样是 FAIL —— 表结构一变（比如有人把成对表清空），测试立刻红，
 * 而不是静默"通过"。
 *
 * ## 变异负例的说明
 *
 * "断言能触发"由两层保证：锚点本身会在采集失效时失败（等价于把采集能力
 * 关掉的变异）；成对表里的 deferred 行（F08/F19/F21）被刻意排除在断言外，
 * 若有人把它们改成 live 而没有页面，断言会立即抓到。
 *
 * 采集方法：`node:fs` 直接读迁移文件与路由源码文本（readFileSync + UTF-8），
 * **不经过 PowerShell/Get-Content**（GBK 伪影见 BRIEF §4）。SQL 文件是 UTF-8。
 */
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';
import { AI_ADMIN_PAIRS, assertablePairs, pairGapNoRows, TRACE_ANCHOR_PAIR } from '../src/config/permission-page-pairs.ts';

const here = dirname(fileURLToPath(import.meta.url));
/** admin 应用根（tests/ 的上一级）。 */
const appRoot = join(here, '..');
/** 工作树根（apps/admin → apps → web → services → 根，共 4 级向上）。 */
const repoRoot = join(appRoot, '..', '..', '..', '..');
const migrationDir = join(repoRoot, 'services', 'platform', 'docs', 'script', 'sql', 'postgres');
const routersPath = join(appRoot, 'src', 'routers', 'index.ts');

function readMigrationSqls(): { file: string; text: string }[] {
  const files = readdirSync(migrationDir).filter(f => f.endsWith('.sql')).sort();
  return files.map(file => ({
    file,
    text: readFileSync(join(migrationDir, file), 'utf8'),
  }));
}

function readRoutersSource(): string {
  return readFileSync(routersPath, 'utf8');
}

/**
 * 从路由源码抽 AI 管理域路由（path + permission）。
 *
 * **逐行**解析而不是按 `{` 切块：`meta: { ... }` 自己的花括号会把
 * `path` 与 `permission` 切进不同的段（第一版实测踩到，成对断言因此
 * 把有权限的页面误判成"无权限行"）——逐行状态机没有这个问题。
 *
 * 路由表里的 path 是**相对布局的子路径**（`ai/knowledge`，无前导斜杠）；
 * 成对表里的 route 是**浏览器路径**（`/ai/knowledge`）。比较时统一加前导 `/`。
 */
function extractAiRoutes(source: string): { path: string; permission: string }[] {
  const routes: { path: string; permission: string }[] = [];
  let currentPath: string | null = null;
  for (const line of source.split('\n')) {
    const pathMatch = line.match(/path:\s*'([^']+)'/);
    if (pathMatch && pathMatch[1].startsWith('ai/')) {
      currentPath = pathMatch[1];
      continue;
    }
    if (currentPath) {
      const permMatch = line.match(/permission:\s*'([^']*)'/);
      if (permMatch) {
        routes.push({ path: currentPath, permission: permMatch[1] });
        currentPath = null;
      }
    }
  }
  return routes;
}

/** 成对表 route（/ai/...）与路由表 path（ai/...）的统一比较形态。 */
function browserPath(routePath: string): string {
  return `/${routePath}`;
}

describe('成对表锚点（采集方式自证非恒真）', () => {
  it('成对表非空且包含已知锚点行 ai:kb:list（V4-7101）', () => {
    assert.ok(AI_ADMIN_PAIRS.length >= 10, `成对表行数应 >= 10，实际 ${AI_ADMIN_PAIRS.length}`);
    const kbList = assertablePairs().find(pair => pair.permission === 'ai:kb:list');
    assert.ok(kbList, '成对表必须包含 ai:kb:list（锚点：清空/改名即失败）');
    assert.equal(kbList.route, '/ai/knowledge');
  });

  it('迁移 SQL 目录真实可读且包含 V4（锚点：采集通道有效性）', () => {
    const sqls = readMigrationSqls();
    assert.ok(sqls.length >= 20, `迁移 SQL 文件数应 >= 20，实际 ${sqls.length}`);
    assert.ok(sqls.some(s => s.file.startsWith('V4__')), '必须存在 V4__ai_policy_revision.sql');
  });

  it('路由源码真实可读且含 AI 管理域路由（锚点：页面采集通道有效性）', () => {
    const source = readRoutersSource();
    assert.ok(source.includes(`path: 'ai/knowledge'`), '路由表必须登记 ai/knowledge');
    assert.ok(source.includes('ai/models'), '路由表必须登记 ai/models');
  });
});

describe('双向断言一：有权限行 ⇒ 必须有页面', () => {
  const sqls = readMigrationSqls();

  it('成对表里每个 (permission, route) 对：权限行存在 + 页面路由存在', () => {
    const routers = readRoutersSource();
    for (const pair of assertablePairs()) {
      // (a) 权限行存在于 sys_menu 迁移（逐字 perms 串）
      const rowHit = sqls.find(s => s.text.includes(`'${pair.permission}'`));
      assert.ok(
        rowHit,
        `有权限无页面族检查的反向前置失败：权限行 '${pair.permission}' 在任何迁移 SQL 中都不存在（成对表 rowSource=${pair.rowSource} 是错的）`,
      );
      // (b) 页面路由存在（routers/index.ts 登记了该路径；路由表是子路径形态）
      const routeKey = pair.route.split('?')[0].replace(/^\//, '');
      const routeHit = routers.includes(`path: '${routeKey}'`) || routers.includes(`path: "${routeKey}"`);
      assert.ok(
        routeHit,
        `G-28 缺陷：权限行 '${pair.permission}'（${pair.rowSource}）有页面声明 '${pair.route}'，但路由表里找不到 —— 有权限无页面 ⇒ FAIL`,
      );
    }
  });
});

describe('双向断言二：有页面 ⇒ 必须有权限行（或显式登记为 NO-ROW）', () => {
  it('路由表中每个 AI 管理域 permission 非空的路由：权限串在成对表中有行且 SQL 有该行', () => {
    const routers = readRoutersSource();
    const sqls = readMigrationSqls();
    const claimed = new Map(assertablePairs().map(pair => [pair.permission, pair]));

    const aiRoutes = extractAiRoutes(routers);
    assert.ok(aiRoutes.length >= 9, `AI 管理域路由应 >= 9，实际 ${aiRoutes.length}（锚点）`);
    for (const route of aiRoutes) {
      if (route.permission === '')
        continue; // 无权限行的路由（ragent 管理面）在下方单独断言登记
      assert.ok(
        claimed.has(route.permission),
        `G-10 反向缺陷：路由 '${route.path}' 用权限 '${route.permission}'，但成对表没有这一行（有页面无权限登记）⇒ FAIL`,
      );
      const rowHit = sqls.find(s => s.text.includes(`'${route.permission}'`));
      assert.ok(
        rowHit,
        `G-10 反向缺陷：路由 '${route.path}' 的权限 '${route.permission}' 在迁移 SQL 中没有权限行（菜单可见、后端必拒）⇒ FAIL`,
      );
    }
  });

  it('无权限行的 AI 页面（ragent 管理面）必须全部登记在 PAIR-GAP 清单里，数量为 3', () => {
    const routers = readRoutersSource();
    const gaps = pairGapNoRows();
    const gapRoutes = new Set(gaps.map(pair => pair.route));
    const aiRoutes = extractAiRoutes(routers);
    for (const route of aiRoutes) {
      if (route.permission === '') {
        const full = browserPath(route.path);
        assert.ok(
          gapRoutes.has(full),
          `页面 '${full}' 没有权限行，但未登记进 PAIR-GAP 清单（交付说明会漏掉它）⇒ FAIL`,
        );
      }
    }
    // 2026-10-07 RW-03：/ai/agents 已随端点落地接到 ai:agent:list（V27-7141），
    // 从 PAIR-GAP 清单移出 ⇒ 余下 skills/ingestion/settings 三条。
    assert.equal(gaps.length, 3, `PAIR-GAP-NO-ROW 应为 3（skills/ingestion/settings），实际 ${gaps.length}`);
  });
});

describe('裁决行不被实现侧悄悄推翻（K2/F19/F21）', () => {
  it('deferred 行不得声称有页面路由', () => {
    for (const pair of AI_ADMIN_PAIRS) {
      if (pair.status === 'deferred')
        assert.equal(pair.route, '', `deferred 行 '${pair.page}' 不得有路由（K2/本期范围外裁决）`);
    }
  });

  it('知识图谱页面不存在（K2），图谱入口提示存在于知识库页', () => {
    const knowledgePage = readFileSync(join(appRoot, 'src', 'pages', 'ai', 'knowledge', 'index.vue'), 'utf8');
    assert.ok(knowledgePage.includes('kg-unconfigured'), '知识库页必须有图谱"未配置"提示标记');
    const routers = readRoutersSource();
    assert.ok(!routers.includes('knowledge-graph'), '路由表不得登记 knowledge-graph 页面（K2 裁决）');
  });
});

describe('G-10 对照锚点（trace 成对链路保持）', () => {
  it('trace 页与权限行仍然成对（前波成果不被本波破坏）', () => {
    const sqls = readMigrationSqls();
    const routers = readRoutersSource();
    assert.ok(sqls.some(s => s.text.includes(`'${TRACE_ANCHOR_PAIR.permission}'`)), 'monitor:trace:list 行仍存在');
    assert.ok(
      routers.includes(`path: '${TRACE_ANCHOR_PAIR.route.replace(/^\//, '')}'`),
      'trace 页路由仍存在',
    );
  });
});

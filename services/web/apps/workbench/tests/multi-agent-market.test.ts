/**
 * F14:2 多 Agent 市场前端契约（纯函数判据）。
 *
 * 钉住两件容易漂移的事：
 * 1) 市场 GET 打**新**公开面且参数逐字
 *    `GET /api/ai/v1/multi-agent/public/search`（`current/size/keyword`），
 *    详情 `GET /api/ai/v1/multi-agent/public/{uuid}`；
 * 2) “使用”提交的 Supervisor 受理体是**白名单**：只发
 *    `{schemaVersion, action:"agent.supervisor", agentVersion:"supervisor-v1", input, resourceRefs:[], budget}`，
 *    `input` 只含 `text/agents` 与可选的 `appId`；模型/工具/预算外字段一律不发。
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { describe, it } from 'node:test';

import {
  buildMultiAgentSearchUrl,
  buildSupervisorRunBody,
  MULTI_AGENT_SEARCH_PATH,
  multiAgentDetailPath,
  SUPERVISOR_ACTION,
  SUPERVISOR_MAX_AGENTS,
  SUPERVISOR_MAX_WALL_CLOCK_SECONDS,
  SUPERVISOR_MAX_WALL_CLOCK_SECONDS_CEILING,
  SUPERVISOR_MIN_AGENTS,
  SUPERVISOR_VERSION,
  SupervisorInputError,
} from '../src/api/chat/multi-agent-market.ts';

const INDEX_SOURCE = fs.readFileSync(new URL('../src/api/chat/index.ts', import.meta.url), 'utf8');
const MARKET_SOURCE = fs.readFileSync(new URL('../src/pages/app-market/index.vue', import.meta.url), 'utf8');
const DIALOG_SOURCE = fs.readFileSync(new URL('../src/pages/app-market/components/MultiAgentAppDialog.vue', import.meta.url), 'utf8');
const HOOK_SOURCE = fs.readFileSync(new URL('../src/pages/app-market/useApplicationMarket.ts', import.meta.url), 'utf8');

const APP_ID = '0123456789abcdef0123456789abcdef';
const AGENTS = [
  { name: 'analyst', instruction: '拆解问题' },
  { name: 'critic', instruction: '挑刺' },
];

describe('多 Agent 市场 URL', () => {
  it('列表路径逐字为 /api/ai/v1/multi-agent/public/search', () => {
    assert.equal(MULTI_AGENT_SEARCH_PATH, '/api/ai/v1/multi-agent/public/search');
  });

  it('参数是 current/size/keyword，空 keyword 不进入 query', () => {
    const url = buildMultiAgentSearchUrl({ current: 2, size: 12, keyword: '  季度 报告 ' });
    assert.ok(url.startsWith(`${MULTI_AGENT_SEARCH_PATH}?`), url);
    const query = new URL(url, 'http://gateway.test').searchParams;
    assert.equal(query.get('current'), '2');
    assert.equal(query.get('size'), '12');
    assert.equal(query.get('keyword'), '季度 报告');
    assert.equal(query.has('currentPage'), false);
    assert.equal(query.has('pageSize'), false);

    const blank = new URL(buildMultiAgentSearchUrl({ current: 1, size: 10, keyword: '   ' }), 'http://gateway.test');
    assert.equal(blank.searchParams.has('keyword'), false);
  });

  it('详情路径逐字为 /api/ai/v1/multi-agent/public/{uuid} 且做 URL 编码', () => {
    assert.equal(
      multiAgentDetailPath(APP_ID),
      `/api/ai/v1/multi-agent/public/${APP_ID}`,
    );
    assert.equal(
      multiAgentDetailPath('a/b c'),
      '/api/ai/v1/multi-agent/public/a%2Fb%20c',
    );
  });
});

describe('Supervisor 受理体（使用入口）', () => {
  it('动作/版本逐字且 body 是白名单', () => {
    const body = buildSupervisorRunBody({ text: '写一份风险清单', agents: AGENTS, appId: APP_ID });
    assert.equal(SUPERVISOR_ACTION, 'agent.supervisor');
    assert.equal(SUPERVISOR_VERSION, 'supervisor-v1');
    assert.deepEqual(Object.keys(body).sort(), ['action', 'agentVersion', 'budget', 'input', 'resourceRefs', 'schemaVersion']);
    assert.equal(body.schemaVersion, 1);
    assert.equal(body.action, 'agent.supervisor');
    assert.equal(body.agentVersion, 'supervisor-v1');
    assert.deepEqual(body.resourceRefs, []);
    assert.deepEqual(Object.keys(body.input).sort(), ['agents', 'appId', 'text']);
    assert.equal(body.input.appId, APP_ID);
    assert.deepEqual(body.input.agents, AGENTS);
    assert.equal(body.budget.maxTokens > 0, true);
    assert.equal(body.budget.maxWallClockSeconds > 0, true);
    // 不收模型/工具/系统提示词等客户端字段
    for (const forbidden of ['model', 'provider', 'tools', 'systemPrompt', 'mode', 'maxSteps'])
      assert.equal(forbidden in body, false, forbidden);
  });

  it('appId 可选：省略时 input 只有 text/agents（旧行为不变）', () => {
    const body = buildSupervisorRunBody({ text: 'q', agents: AGENTS });
    assert.deepEqual(Object.keys(body.input).sort(), ['agents', 'text']);
  });

  it('appId 形状：非 32 位 hex 拒绝（前端先失败，不发请求）', () => {
    for (const bad of ['', '   ', 'short', `${APP_ID}0`, '0123456789abcdef0123456789abcde/'])
      assert.throws(() => buildSupervisorRunBody({ text: 'q', agents: AGENTS, appId: bad }), SupervisorInputError, bad);
  });

  it('名册 2..3、alias 形状唯一、instruction/text 非空且有上限', () => {
    assert.equal(SUPERVISOR_MIN_AGENTS, 2);
    assert.equal(SUPERVISOR_MAX_AGENTS, 3);
    assert.throws(() => buildSupervisorRunBody({ text: 'q', agents: [AGENTS[0]] }), SupervisorInputError);
    assert.throws(() => buildSupervisorRunBody({
      text: 'q',
      agents: [...AGENTS, { name: 'writer', instruction: 'w' }, { name: 'coder', instruction: 'c' }],
    }), SupervisorInputError);
    assert.throws(() => buildSupervisorRunBody({
      text: 'q',
      agents: [AGENTS[0], { name: 'analyst', instruction: '重复' }],
    }), SupervisorInputError);
    for (const name of ['Analyst', 'a', '1analyst', 'analyst!']) {
      assert.throws(() => buildSupervisorRunBody({
        text: 'q',
        agents: [{ name, instruction: 'i' }, { name: 'critic', instruction: 'c' }],
      }), SupervisorInputError, name);
    }
    assert.throws(() => buildSupervisorRunBody({ text: '   ', agents: AGENTS }), SupervisorInputError);
    assert.throws(() => buildSupervisorRunBody({
      text: 'q',
      agents: [{ name: 'analyst', instruction: 'i'.repeat(1025) }, { name: 'critic', instruction: 'c' }],
    }), SupervisorInputError);
  });

  it('预算收敛到服务端边界（1..8192 / 1..600）', () => {
    const body = buildSupervisorRunBody({
      text: 'q',
      agents: AGENTS,
      budget: { maxTokens: 0, maxWallClockSeconds: 0 },
    });
    assert.equal(body.budget.maxTokens, 1);
    assert.equal(body.budget.maxWallClockSeconds, 1);
    assert.equal(SUPERVISOR_MAX_WALL_CLOCK_SECONDS, 120);
    assert.equal(SUPERVISOR_MAX_WALL_CLOCK_SECONDS_CEILING, 600);
    const capped = buildSupervisorRunBody({
      text: 'q',
      agents: AGENTS,
      budget: { maxTokens: 99999, maxWallClockSeconds: 99999 },
    });
    assert.equal(capped.budget.maxTokens, 8192);
    assert.equal(capped.budget.maxWallClockSeconds, 600);
  });
});

describe('市场页面接线（静态判据）', () => {
  it('api/chat/index.ts 接入两个构造器', () => {
    assert.match(INDEX_SOURCE, /buildMultiAgentSearchUrl/);
    assert.match(INDEX_SOURCE, /multiAgentDetailPath/);
  });

  it('市场页有 multi-agent 类别、卡片与详情/使用入口', () => {
    assert.match(MARKET_SOURCE, /multi-agent/);
    assert.match(MARKET_SOURCE, /app-market-card-\$\{app\.type\}/);
    assert.match(MARKET_SOURCE, /MultiAgentAppDialog/);
    assert.match(DIALOG_SOURCE, /app-market-multi-agent-dialog/);
    assert.match(DIALOG_SOURCE, /app-market-multi-agent-use/);
  });

  it('市场编排层拉取多 Agent 列表与详情', () => {
    assert.match(HOOK_SOURCE, /getMultiAgentAppList|buildMultiAgentSearchUrl/);
    assert.match(HOOK_SOURCE, /getMultiAgentAppDetail|multiAgentDetailPath/);
  });
});

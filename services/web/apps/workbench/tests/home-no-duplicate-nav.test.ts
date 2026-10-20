/**
 * 首页不得复述侧栏导航（实测缺陷 4）。
 *
 * 实测：`http://192.168.139.103:8080/` 的“工作区”面板列出 应用市场 / 知识检索 / 媒体工作台，
 * 与左侧 `Aside` 的导航项重复，同一屏出现两套入口。
 *
 * 处置口径：入口归侧栏（移动端侧栏会以抽屉形式出现，仍是唯一导航），首页只留“开始对话”
 * 这一件事。本文件用**静态判据**钉住：首页源码里不能再出现这些面板与其数据。
 *
 * 说明：本仓工作台测试没有 DOM 环境（见 `chat-page.test.ts` 头部说明），因此这里读真实
 * SFC 源码做断言，而不是渲染后比对节点。
 */
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { dirname, join } from 'node:path';
import { describe, it } from 'node:test';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const src = (relative: string) => fs.readFileSync(join(here, '..', 'src', relative), 'utf8');

describe('首页 chatDefaul：不再重复侧栏入口', () => {
  const home = src('pages/chat/layouts/chatDefaul/index.vue');

  it('“工作区”面板已被移除（标题与容器都不在）', () => {
    assert.ok(!home.includes('panel-workspace'), 'panel-workspace 容器应已删除');
    assert.ok(!home.includes('>工作区<'), '首页不应再有“工作区”标题');
    assert.ok(!home.includes('个入口'), '不应再有“N 个入口”的统计文案');
  });

  it('重复的入口清单已移除（capabilities / cap-* 样式）', () => {
    assert.ok(!home.includes('capabilities'), 'capabilities 数据应已删除');
    assert.ok(!home.includes('cap-row'), 'cap-row 样式应已删除');
    assert.ok(!home.includes('cap-list'), 'cap-list 样式应已删除');
  });

  it('只为该面板存在的样式不再残留', () => {
    // `.panel-note` 只服务面板里的“N 个入口”统计；面板删除后它成为死样式。
    assert.ok(!home.includes('.panel-note'), '.panel-note 应随面板一起删除');
  });

  it('不再把应用市场/知识检索/媒体工作台写成首页入口', () => {
    // 侧栏负责这些入口；首页出现即重复。
    for (const label of ['应用市场', '知识检索', '媒体工作台'])
      assert.ok(!home.includes(label), `首页不应再出现入口「${label}」`);
    assert.ok(!home.includes('/app-market'), '不应再链接到 /app-market');
    assert.ok(!home.includes('\'/media\''), '不应再链接到 /media');
  });

  it('首页仍保留真实的“开始对话”：输入器 + 知识库范围（没被误删）', () => {
    assert.ok(home.includes('ChatSender'), '输入器必须还在');
    assert.ok(home.includes('data-testid="home-kb-select"'), '知识库选择必须还在');
    assert.ok(home.includes('WelecomeText'), '欢迎区必须还在');
  });

  it('示例问题仍可用（usePrompt 填入输入框并聚焦，未被误删）', () => {
    assert.ok(home.includes('usePrompt'), '示例问题点击逻辑必须还在');
    assert.ok(home.includes('focusComposer'), 'focusComposer 仍被 usePrompt 使用');
    assert.ok(home.includes('senderValue.value = text'), '点击示例问题应把文案填进输入框');
  });

  it('网格重新平衡为三个 span 4 面板（没有留下空列）', () => {
    assert.ok(home.includes('.panel-prompts {') && home.includes('grid-column: span 4;'));
    const spans = home.match(/grid-column: span 4;/g) ?? [];
    assert.ok(spans.length >= 3, `三个面板各 span 4，实际匹配 ${spans.length} 处`);
    assert.ok(!home.includes('span 9'), '旧的 span 9 布局应已不再使用');
  });
});

describe('欢迎文案不再承诺“下面的工作区入口”', () => {
  it('副标题指向导航栏，而不是已被删除的下方面板', () => {
    const welcome = src('components/WelecomeText/index.vue');
    assert.ok(!welcome.includes('下面的工作区入口'), '下方面板已删除，文案不得再指向它');
    assert.ok(welcome.includes('导航栏'), '应改为指向导航栏');
  });
});

describe('侧栏仍是唯一的导航来源', () => {
  it('Aside 保留这些入口（首页删掉的正是它的副本）', () => {
    const aside = src('layouts/components/Aside/index.vue');
    for (const label of ['应用市场', '媒体工作台'])
      assert.ok(aside.includes(label), `侧栏应保留入口「${label}」`);
  });
});

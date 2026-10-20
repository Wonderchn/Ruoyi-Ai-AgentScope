/**
 * 侧栏“最近对话”同名消歧（实测缺陷 2）。
 *
 * 实测：侧栏出现**三条完全同名**的“一级类目 基准佣金率”，用户无法区分。
 * 根因：会话标题取首问（`createSessionList` 传 `title: text.slice(0, 10)`），
 * 重复提问即产生同名列，且前端没有任何消歧。
 *
 * 本文件钉住 `session-labels.ts` 的真实行为，重点是**不伪造数据**：
 * - 标题唯一时不加任何后缀；
 * - 同名时用服务端真实时间（`lastTime` → `createTime`）区分；
 * - 时间缺失/非法时明说“时间未知”，绝不拿"现在"顶替；
 * - 连时间也撞在同一分钟时再补组内序号，保证任意两行可区分。
 */
import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { buildSessionLabelRows, formatSessionStamp } from '../src/layouts/components/Aside/session-labels.ts';

const TITLE = '一级类目 基准佣金率';

function row(id: string, sessionTitle: string | undefined, createTime?: Date | string) {
  return { id, sessionTitle, createTime } as any;
}

describe('formatSessionStamp', () => {
  it('格式化为 MM-DD HH:mm（补零）', () => {
    assert.equal(formatSessionStamp(new Date(2026, 0, 5, 9, 7)), '01-05 09:07');
  });

  it('缺失/非法时间返回空串，不猜', () => {
    assert.equal(formatSessionStamp(undefined), '');
    assert.equal(formatSessionStamp(null), '');
    assert.equal(formatSessionStamp(new Date(Number.NaN)), '');
    assert.equal(formatSessionStamp('not-a-date'), '');
  });
});

describe('buildSessionLabelRows - 实测缺陷：三条同名会话', () => {
  it('三条同名会话各自带上可区分的真实时间', () => {
    const rows = buildSessionLabelRows([
      row('a', TITLE, new Date(2026, 9, 19, 10, 30)),
      row('b', TITLE, new Date(2026, 9, 19, 14, 5)),
      row('c', TITLE, new Date(2026, 9, 20, 9, 0)),
    ]);

    assert.equal(rows.length, 3);
    // 标题本身不被改写（改名默认值、搜索、路由都基于 sessionTitle）。
    assert.deepEqual(rows.map(r => r.displayTitle), [TITLE, TITLE, TITLE]);
    assert.deepEqual(rows.map(r => r.disambiguation), ['10-19 10:30', '10-19 14:05', '10-20 09:00']);
    // 三行的“标题+后缀”互不相同 —— 用户能区分。
    assert.equal(new Set(rows.map(r => `${r.displayTitle} ${r.disambiguation}`)).size, 3);
  });

  it('同一分钟内仍撞车时补组内序号，保证任意两行不同', () => {
    const same = new Date(2026, 9, 19, 10, 30);
    const rows = buildSessionLabelRows([
      row('a', TITLE, same),
      row('b', TITLE, new Date(same)),
      row('c', TITLE, new Date(same)),
    ]);

    assert.deepEqual(
      rows.map(r => r.disambiguation),
      ['10-19 10:30 #1', '10-19 10:30 #2', '10-19 10:30 #3'],
    );
    assert.equal(new Set(rows.map(r => `${r.displayTitle} ${r.disambiguation}`)).size, 3);
  });

  it('时间缺失时如实显示“时间未知”，不用当前时间顶替', () => {
    const before = Date.now();
    const rows = buildSessionLabelRows([row('a', TITLE), row('b', TITLE)]);
    const after = Date.now();

    assert.deepEqual(rows.map(r => r.disambiguation), ['时间未知 #1', '时间未知 #2']);
    // 关键：绝不能出现"现在"的时间戳（那就是伪造数据）。
    for (const r of rows) {
      assert.ok(!r.disambiguation.includes(formatSessionStamp(new Date(before))));
      assert.ok(!r.disambiguation.includes(formatSessionStamp(new Date(after))));
    }
  });
});

describe('buildSessionLabelRows - 不制造噪音', () => {
  it('标题唯一时保持原样，不追加后缀', () => {
    const rows = buildSessionLabelRows([
      row('a', '演示：默认模型', new Date(2026, 9, 19, 10, 30)),
      row('b', '默认模型是什么?', new Date(2026, 9, 19, 12, 0)),
    ]);

    assert.deepEqual(rows.map(r => r.displayTitle), ['演示：默认模型', '默认模型是什么?']);
    assert.deepEqual(rows.map(r => r.disambiguation), ['', '']);
  });

  it('未命名会话显示“未命名对话”，且不影响其它行的原名', () => {
    const rows = buildSessionLabelRows([row('a', undefined), row('b', '有标题')]);
    assert.equal(rows[0].displayTitle, '未命名对话');
    assert.equal(rows[1].displayTitle, '有标题');
  });

  it('空列表与 null 都安全返回空数组', () => {
    assert.deepEqual(buildSessionLabelRows([]), []);
    assert.deepEqual(buildSessionLabelRows(null), []);
    assert.deepEqual(buildSessionLabelRows(undefined), []);
  });

  it('只对发生冲突的组加后缀，不牵连其它行', () => {
    const rows = buildSessionLabelRows([
      row('a', TITLE, new Date(2026, 9, 19, 10, 30)),
      row('b', '另一个会话', new Date(2026, 9, 19, 11, 0)),
      row('c', TITLE, new Date(2026, 9, 19, 14, 5)),
    ]);

    assert.equal(rows[1].disambiguation, '');
    assert.equal(rows[0].disambiguation, '10-19 10:30');
    assert.equal(rows[2].disambiguation, '10-19 14:05');
  });

  it('保留原始字段（组件库按 labelKey/id 取用，不能被投影丢掉）', () => {
    const rows = buildSessionLabelRows([row('a', TITLE, new Date(2026, 9, 19, 10, 30))]);
    assert.equal(rows[0].id, 'a');
    assert.equal(rows[0].sessionTitle, TITLE);
    assert.ok(rows[0].createTime instanceof Date);
  });
});

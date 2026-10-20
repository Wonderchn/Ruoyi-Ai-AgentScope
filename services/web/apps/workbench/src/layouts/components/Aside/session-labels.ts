/**
 * 侧栏“最近对话”行的标题消歧。
 *
 * 背景（实测缺陷）：会话标题由**首问**生成（`createSessionList` 传 `title: text.slice(0, 10)`），
 * 用户重复问同一句话就会产生多条**完全同名**的会话，侧栏出现三行“一级类目 基准佣金率”，
 * 用户无法区分、也无法判断该点哪一个。
 *
 * 口径（不伪造数据）：
 * - 不改写 `sessionTitle` 本身——改名入口默认值、标题搜索、路由都以它为准；
 * - 区分后缀只由**服务端真实时间**派生（`GET /api/ai/v1/conversations` 的 `lastTime`，
 *   落到前端是 `createTime`），不猜、不排序造数；
 * - 时间缺失/非法 ⇒ 明说“时间未知”，不用当前时间顶替；
 * - 只有**同名冲突**的行才加后缀：标题本来就唯一时保持原样，避免制造噪音；
 * - 同名的多行若连时间也撞在同一分钟，再补组内序号 `#n`，保证任意两行可区分。
 */
import type { ChatSessionVo } from '@/api/session/types';

/** 一行会话在侧栏里的展示形态。`label`（原名）仍由组件库按 `labelKey` 取，这里只补展示文案。 */
export interface SessionLabelRow extends ChatSessionVo {
  /** 实际显示的标题（未命名会话显示“未命名对话”）。 */
  displayTitle: string;
  /** 同名消歧后缀；无冲突时为空串（不显示）。 */
  disambiguation: string;
}

/** `MM-DD HH:mm`；缺失或非法时间返回空串（由调用方决定如何如实说明）。 */
export function formatSessionStamp(value?: Date | string | null): string {
  if (value === undefined || value === null || value === '')
    return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime()))
    return '';
  const pad = (num: number) => String(num).padStart(2, '0');
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/**
 * 把会话列表投影成侧栏展示行。
 *
 * 纯函数：同样的输入永远得到同样的输出，不读时钟、不读 store —— 便于直接断言。
 */
export function buildSessionLabelRows(rows: readonly ChatSessionVo[] | null | undefined): SessionLabelRow[] {
  const list = rows ?? [];

  // 先按时长原名分组，收集每组的时间戳，用来判断“时间本身是否足以区分”。
  const stampsOf = new Map<string, string[]>();
  for (const row of list) {
    const raw = String(row.sessionTitle ?? '');
    const stamps = stampsOf.get(raw);
    if (stamps)
      stamps.push(formatSessionStamp(row.createTime));
    else
      stampsOf.set(raw, [formatSessionStamp(row.createTime)]);
  }

  const seen = new Map<string, number>();
  return list.map((row) => {
    const raw = String(row.sessionTitle ?? '');
    const title = raw || '未命名对话';
    const stamps = stampsOf.get(raw) ?? [];
    // 标题唯一 ⇒ 原样显示，不加任何后缀。
    if (stamps.length < 2)
      return { ...row, displayTitle: title, disambiguation: '' };

    const index = (seen.get(raw) ?? 0) + 1;
    seen.set(raw, index);
    const head = formatSessionStamp(row.createTime) || '时间未知';
    // 组内时间互不相同 ⇒ 时间已经能区分；否则追加组内序号。
    const timeAloneSuffices = new Set(stamps).size === stamps.length;
    return {
      ...row,
      displayTitle: title,
      disambiguation: timeAloneSuffices ? head : `${head} #${index}`,
    };
  });
}

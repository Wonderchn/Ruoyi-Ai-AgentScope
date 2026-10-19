/**
 * 知识图谱页面的纯逻辑（零依赖，可直接测）。
 *
 * 页面本身只做"发请求 + 绑定状态"；布局、截断、半径、可见边、溯源链接都在这里，
 * 这样无需 DOM 也能钉住"确定性布局 / 半径 13–30 / top-45 标签"这些判据。
 */
import type {
  GraphEdgeRow,
  GraphNodeRow,
  GraphSourceRow,
  GraphViewPayload,
} from '@/api/ai/knowledgeGraph';

export interface NormalizedGraph {
  nodes: GraphNodeRow[];
  edges: GraphEdgeRow[];
  truncated: boolean;
  droppedUnattributed: number;
  readSource: string;
}

export interface GraphPoint {
  id: string;
  x: number;
  y: number;
  r: number;
  degree: number;
}

export interface GraphLine {
  id: string;
  x1: number;
  y1: number;
  x2: number;
  y2: number;
  label: string;
  sourcesDerived: boolean;
  edge: GraphEdgeRow;
}

/** 归一化服务端 payload：缺字段/非法数值收敛成空图，绝不把 undefined 带进模板。 */
export function normalizeGraph(payload: GraphViewPayload | null | undefined): NormalizedGraph {
  const nodes = Array.isArray(payload?.nodes)
    ? payload.nodes.map(node => ({
        ...node,
        degree: Number.isFinite(node.degree) && (node.degree as number) > 0 ? Math.floor(node.degree as number) : 0,
        sources: Array.isArray(node.sources) ? node.sources : [],
        description: node.description ?? '',
      }))
    : [];
  const edges = Array.isArray(payload?.edges)
    ? payload.edges.map(edge => ({
        ...edge,
        sources: Array.isArray(edge.sources) ? edge.sources : [],
        description: edge.description ?? '',
        sourcesDerived: edge.sourcesDerived === true,
      }))
    : [];
  const dropped = Number.isFinite(payload?.droppedUnattributed)
    ? Math.max(0, Math.floor(payload?.droppedUnattributed as number))
    : 0;
  return {
    nodes,
    edges,
    truncated: payload?.truncated === true,
    droppedUnattributed: dropped,
    readSource: payload?.readSource || 'projection',
  };
}

/** 节点半径：13–30，按当前图的最大度数线性映射。 */
export function nodeRadius(degree: number, maxDegree: number): number {
  const max = Number.isFinite(maxDegree) && maxDegree > 0 ? maxDegree : 0;
  const safe = Number.isFinite(degree) && degree > 0 ? degree : 0;
  if (max <= 0) {
    return 13;
  }
  return Math.round(13 + 17 * Math.min(1, safe / max));
}

/**
 * 确定性同心/径向布局：度数高的在中心附近，其余按黄金角排布。
 *
 * 不存在随机数：同一输入必须得到同一坐标（刷新不跳动）。
 */
export function layoutNodes(nodes: GraphNodeRow[], width = 800, height = 600): GraphPoint[] {
  const ordered = [...nodes]
    .filter(node => Boolean(node.id))
    .sort((a, b) => (b.degree ?? 0) - (a.degree ?? 0) || String(a.id).localeCompare(String(b.id)));
  const maxDegree = ordered.reduce((max, node) => Math.max(max, node.degree ?? 0), 0);
  const cx = width / 2;
  const cy = height / 2;
  const maxRadius = Math.min(width, height) * 0.36;
  const goldenAngle = Math.PI * (3 - Math.sqrt(5));
  return ordered.map((node, index) => {
    const ring = index === 0 ? 0 : maxRadius * Math.sqrt(index / Math.max(1, ordered.length - 1));
    const angle = index * goldenAngle;
    return {
      id: String(node.id),
      x: Math.round((cx + Math.cos(angle) * ring) * 100) / 100,
      y: Math.round((cy + Math.sin(angle) * ring) * 100) / 100,
      r: nodeRadius(node.degree ?? 0, maxDegree),
      degree: node.degree ?? 0,
    };
  });
}

/** top-45 标签：按度数降序、id 升序，避免同度时顺序漂移。 */
export function topLabels(nodes: GraphNodeRow[], max = 45): GraphNodeRow[] {
  return [...nodes]
    .sort((a, b) => (b.degree ?? 0) - (a.degree ?? 0) || String(a.id).localeCompare(String(b.id)))
    .slice(0, Math.max(0, max));
}

/** 可见边：两端都有坐标才画，避免悬空线。 */
export function visibleEdges(edges: GraphEdgeRow[], points: GraphPoint[]): GraphLine[] {
  const byId = new Map(points.map(point => [point.id, point]));
  const lines: GraphLine[] = [];
  for (const edge of edges) {
    const source = byId.get(String(edge.source));
    const target = byId.get(String(edge.target));
    if (!source || !target) {
      continue;
    }
    lines.push({
      id: edge.id || `${edge.source}->${edge.target}`,
      x1: source.x,
      y1: source.y,
      x2: target.x,
      y2: target.y,
      label: edge.label ?? '',
      sourcesDerived: edge.sourcesDerived === true,
      edge,
    });
  }
  return lines;
}

/** 溯源链接：逐段编码；缺 kb/doc 时不生成链接（不伪造可点击）。 */
export function sourceLink(source: GraphSourceRow | null | undefined): string {
  const kbId = String(source?.kbId ?? '');
  const docId = String(source?.docId ?? '');
  if (!kbId || !docId) {
    return '';
  }
  return `/ai/knowledge/${encodeURIComponent(kbId)}/docs/${encodeURIComponent(docId)}`;
}

export function clampDepth(depth: number | null | undefined): number {
  if (!Number.isFinite(depth) || (depth as number) <= 0) {
    return 2;
  }
  return Math.min(3, Math.max(1, Math.floor(depth as number)));
}

export function clampLimit(limit: number | null | undefined): number {
  if (!Number.isFinite(limit) || (limit as number) <= 0) {
    return 200;
  }
  return Math.min(1000, Math.max(1, Math.floor(limit as number)));
}

export type StatusTone = 'success' | 'warning' | 'danger' | 'info';

/** 状态文案：DOWN/TIMEOUT 绝不显示成 success 色。 */
export function statusMeta(status: string | null | undefined): { tone: StatusTone; text: string } {
  switch (status) {
    case 'UP':
      return { tone: 'success', text: '图谱后端可用' };
    case 'DOWN':
      return { tone: 'danger', text: '图谱后端不可达；图与标签仍读取主库投影' };
    case 'TIMEOUT':
      return { tone: 'warning', text: '图谱后端探测超时；图与标签仍读取主库投影' };
    case 'NOT_CONFIGURED':
      return { tone: 'info', text: '未配置图谱后端（rag.graph.type=none）；只读主库投影' };
    default:
      return { tone: 'info', text: '图谱后端状态未知' };
  }
}

/** 重建结果提示（成功路径）。 */
export function rebuildSummary(payload: {
  documents?: number;
  entities?: number;
  relations?: number;
  skipped?: number;
} | null | undefined): string {
  return `已重建 ${payload?.documents ?? 0} 篇文档：实体 ${payload?.entities ?? 0}，关系 ${payload?.relations ?? 0}，丢弃 ${payload?.skipped ?? 0}`;
}

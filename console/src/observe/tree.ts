// 运行详情的调用树（observe-page 需求 4）：展开成一行行、折叠、只看出错、重复调用的提示、时间条的位置。纯函数。

import type { RunNode } from "../api/types";

export interface TreeRow {
  node: RunNode;
  depth: number;
  /** 有子节点（可以折叠）。 */
  parent: boolean;
  /** 现在是折叠的。 */
  collapsed: boolean;
}

/** 默认折叠的节点：只有一次尝试的请求（尝试和请求一模一样，没必要展开）。 */
export function defaultCollapsed(root: RunNode): Set<string> {
  const collapsed = new Set<string>();
  walk(root, (node) => {
    if (node.kind === "request" && (node.children?.length ?? 0) <= 1) collapsed.add(node.id);
  });
  return collapsed;
}

function walk(node: RunNode, visit: (node: RunNode) => void): void {
  visit(node);
  for (const child of node.children ?? []) walk(child, visit);
}

/** 它自己或它下面有出错的。 */
export function hasError(node: RunNode): boolean {
  return node.state === "error" || (node.children ?? []).some(hasError);
}

/** 看得见的行，按树的先后；根节点不算（运行的汇总在顶部单独显示）。 */
export function flatten(root: RunNode, collapsed: ReadonlySet<string>, errorsOnly: boolean): TreeRow[] {
  const rows: TreeRow[] = [];
  const visit = (node: RunNode, depth: number): void => {
    if (errorsOnly && !hasError(node)) return;
    const children = node.children ?? [];
    const closed = collapsed.has(node.id);
    rows.push({ node, depth, parent: children.length > 0, collapsed: closed });
    if (!closed) for (const child of children) visit(child, depth + 1);
  };
  for (const child of root.children ?? []) visit(child, 0);
  return rows;
}

/** 找一个节点和它的祖先（选中一个节点时把祖先都展开）。 */
export function pathTo(root: RunNode, id: string): RunNode[] {
  const trail: RunNode[] = [];
  const search = (node: RunNode): boolean => {
    trail.push(node);
    if (node.id === id) return true;
    for (const child of node.children ?? []) if (search(child)) return true;
    trail.pop();
    return false;
  };
  return search(root) ? trail : [];
}

export interface Repeat {
  name: string;
  count: number;
  /** 第一次和最后一次所在的步号。 */
  fromStep: number;
  toStep: number;
  /** 第一次调用的节点（点提示时选中它）。 */
  first: string;
}

export const REPEAT_THRESHOLD = 5;

function stepNumber(step: RunNode): number {
  const match = /(\d+)$/.exec(step.name ?? step.id);
  return match === null ? 0 : Number(match[1]);
}

/**
 * 同名工具连续调用至少 {@link REPEAT_THRESHOLD} 次（需求 4.5）：按步骤的先后把每一步的工具调用排成一串，
 * 连续同名的算一段。中间夹了别的工具就断开。
 */
export function repeats(root: RunNode, threshold = REPEAT_THRESHOLD): Repeat[] {
  const calls: { node: RunNode; step: number }[] = [];
  for (const step of root.children ?? []) {
    if (step.kind !== "step") continue;
    for (const child of step.children ?? []) if (child.kind === "tool") calls.push({ node: child, step: stepNumber(step) });
  }
  const found: Repeat[] = [];
  let start = 0;
  for (let i = 1; i <= calls.length; i++) {
    const current = calls[i];
    const first = calls[start];
    if (first === undefined) break;
    if (current !== undefined && current.node.name === first.node.name) continue;
    const count = i - start;
    const last = calls[i - 1] as { node: RunNode; step: number };
    if (count >= threshold) {
      found.push({ name: first.node.name ?? "", count, fromStep: first.step, toStep: last.step, first: first.node.id });
    }
    start = i;
  }
  return found;
}

export interface Bar {
  /** 占整条时间轴的比例，0–1。 */
  left: number;
  width: number;
  /** 模型请求：首 token 到达处占这根条的比例；没有时为空。 */
  split?: number;
}

const ms = (iso: string | undefined): number | undefined => {
  if (iso === undefined) return undefined;
  const value = Date.parse(iso);
  return Number.isFinite(value) ? value : undefined;
};

/** 整次运行的时间轴：运行头的开始到结束（还在进行时到 `now`）。 */
export function axis(root: RunNode, now: number): { start: number; end: number } | undefined {
  const start = ms(root.start);
  if (start === undefined) return undefined;
  const end = ms(root.end) ?? now;
  return { start, end: Math.max(end, start + 1) };
}

/** 一个节点在时间轴上的条；没有开始时刻时为空。没结束的画到 `now`。很短的条至少有 0.4% 宽，看得见。 */
export function bar(node: RunNode, span: { start: number; end: number }, now: number): Bar | undefined {
  const start = ms(node.start);
  if (start === undefined) return undefined;
  const end = Math.max(start, ms(node.end) ?? now);
  const total = span.end - span.start;
  const left = Math.min(1, Math.max(0, (start - span.start) / total));
  const width = Math.max(0.004, Math.min(1 - left, (end - start) / total));
  const firstToken = node.kind === "request" ? ms(lastAttempt(node)?.firstToken) : node.kind === "attempt" ? ms(node.firstToken) : undefined;
  const split = firstToken === undefined || end <= start ? undefined : Math.min(1, Math.max(0, (firstToken - start) / (end - start)));
  return split === undefined ? { left, width } : { left, width, split };
}

function lastAttempt(node: RunNode): RunNode | undefined {
  const attempts = (node.children ?? []).filter((child) => child.kind === "attempt");
  return attempts.at(-1);
}

/** 步骤节点的步号（显示“第几步”用）；不是步骤时为空。 */
export function stepLabel(node: RunNode): string | undefined {
  if (node.kind !== "step") return undefined;
  const match = /(\d+)$/.exec(node.name ?? "");
  return match === null ? undefined : match[1];
}

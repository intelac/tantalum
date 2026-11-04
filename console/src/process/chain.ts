import type { Item, TextItem, ThinkingItem, ToolStepItem } from "../api/types";

/** 过程区的一行：一段思考、一段中间文本，或一条工具链（连续的工具步骤，同一批并行调用成一组）。 */
export type ProcessRow =
  | { kind: "thinking"; item: ThinkingItem }
  | { kind: "text"; item: TextItem }
  | { kind: "tools"; groups: ToolStepItem[][] };

export function processRows(items: readonly (Item | undefined)[]): ProcessRow[] {
  const rows: ProcessRow[] = [];
  let tools: ToolStepItem[] = [];
  const flushTools = () => {
    if (tools.length > 0) rows.push({ kind: "tools", groups: chainOf(tools) });
    tools = [];
  };
  for (const item of items) {
    if (item?.kind === "tool") {
      tools.push(item);
      continue;
    }
    flushTools();
    if (item?.kind === "thinking") rows.push({ kind: "thinking", item });
    else if (item?.kind === "text") rows.push({ kind: "text", item });
  }
  flushTools();
  return rows;
}

/** 把连续的工具步骤按 group 分组：同一事件发出的并行调用一组，其他各自一组。 */
export function chainOf(steps: readonly ToolStepItem[]): ToolStepItem[][] {
  const groups: ToolStepItem[][] = [];
  for (const step of steps) {
    const last = groups[groups.length - 1];
    if (last !== undefined && step.group !== undefined && last[0]?.group === step.group) last.push(step);
    else groups.push([step]);
  }
  return groups;
}

/** 参数摘要：只有一个字符串参数就显示它，否则显示紧凑 JSON，最多 {@code max} 个字符。 */
const WRITES = new Set(["write_file", "edit_file"]);

export function summarizeArguments(step: ToolStepItem, max = 60): string {
  const args = step.call.arguments;
  let text: string;
  if (args !== undefined && args !== null && typeof args === "object" && !Array.isArray(args)) {
    const values = Object.values(args as Record<string, unknown>);
    const path = (args as Record<string, unknown>).path;
    // 写和改的其余参数是大段正文，摘要只看路径；正文在展开后的差异和预览里
    if (WRITES.has(step.call.name) && typeof path === "string") text = path;
    else text = values.length === 1 && typeof values[0] === "string" ? values[0] : JSON.stringify(args);
  } else if (args === undefined) {
    text = step.call.rawArguments;
  } else {
    text = JSON.stringify(args);
  }
  return text.length > max ? `${text.slice(0, max - 1)}…` : text;
}

/** 完整参数：能解析的美化输出，不能的显示原文。 */
export function formatArguments(step: ToolStepItem): string {
  return step.call.arguments === undefined ? step.call.rawArguments : JSON.stringify(step.call.arguments, null, 2);
}

/** 耗时的毫秒数，还没开始或没结束时用 {@code now} 补上；都没有时为 null。 */
export function elapsedMs(step: ToolStepItem, now: number): number | null {
  if (step.startedAt === undefined) return null;
  const start = new Date(step.startedAt).getTime();
  const end = step.endedAt === undefined ? now : new Date(step.endedAt).getTime();
  return Math.max(0, end - start);
}

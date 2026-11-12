// 待办清单（todo-tool 需求 3，照 DSH tool-todo）：清单不另外存，当前的就是这一轮最后一次成功的 todo_write 的参数。

import type { Item, ToolStepItem } from "../api/types";
import type { Text } from "../text/dictionary";

export const TODO_TOOL = "todo_write";

export type TodoStatus = "pending" | "in_progress" | "completed";

export interface TodoItem {
  content: string;
  status: TodoStatus;
}

const STATUSES: ReadonlySet<string> = new Set(["pending", "in_progress", "completed"]);

/** 调用参数像一张清单就读出来；不像（模型写错、被拒的那次）就是 null。 */
export function parseTodos(args: unknown): TodoItem[] | null {
  if (args === null || typeof args !== "object" || Array.isArray(args)) return null;
  const list = (args as Record<string, unknown>).todos;
  if (!Array.isArray(list)) return null;
  const out: TodoItem[] = [];
  for (const entry of list) {
    if (entry === null || typeof entry !== "object") return null;
    const { content, status } = entry as Record<string, unknown>;
    if (typeof content !== "string" || content.trim() === "" || typeof status !== "string" || !STATUSES.has(status)) return null;
    out.push({ content: content.trim(), status: status as TodoStatus });
  }
  return out;
}

/** 成功写下的一次：工具做完、结果不是错误。 */
function written(step: ToolStepItem): TodoItem[] | null {
  if (step.call.name !== TODO_TOOL || step.status !== "DONE" || step.result === undefined || step.result.error) return null;
  return parseTodos(step.call.arguments);
}

/**
 * 计划横条显示的清单：最近一轮里最后一次成功写下的。最近一轮没写过就是 null——下一轮开始时横条消失，直到模型再写
 * （DSH 2026-07-28：turn/end 时还留着，下一次 turn/start 清空）。
 */
export function currentTodos(items: readonly (Item | undefined)[], run: string | null): TodoItem[] | null {
  if (run === null) return null;
  for (let index = items.length - 1; index >= 0; index--) {
    const item = items[index];
    if (item?.kind !== "tool" || item.run !== run) continue;
    const list = written(item);
    if (list !== null) return list.length === 0 ? null : list;
  }
  return null;
}

export interface TodoProgress {
  done: number;
  total: number;
  /** 正在做的那一项；没有就是 null。 */
  current: string | null;
}

/** “2/5 已完成 · 正在：某某”；对话页的工具行、计划横条和轨迹页用同一句。 */
export function summaryOf(todos: readonly TodoItem[], text: Text["plan"]): string {
  const progress = progressOf(todos);
  const tail = progress.current !== null ? text.current(progress.current) : progress.done === progress.total ? text.allDone : null;
  return tail === null ? text.progress(progress.done, progress.total) : `${text.progress(progress.done, progress.total)} · ${tail}`;
}

export function progressOf(todos: readonly TodoItem[]): TodoProgress {
  return {
    done: todos.filter((todo) => todo.status === "completed").length,
    total: todos.length,
    current: todos.find((todo) => todo.status === "in_progress")?.content ?? null,
  };
}

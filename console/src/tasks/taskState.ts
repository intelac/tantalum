// 任务（subagent）在对话页上要用到的几个纯计算：还没回来的任务、自动继续停住时没处理的结果、任务会话里等着批准的问题。

import type { Item, TaskItem, TaskResultItem, ToolStepItem } from "../api/types";
import { pendingApproval, type PendingApproval } from "../composer/approval/pending";

/** 还没回来的任务，按放出的先后。 */
export function openTasks(items: readonly (Item | undefined)[]): TaskItem[] {
  return items.filter((item): item is TaskItem => item?.kind === "task" && item.outcome === undefined);
}

/**
 * 自动继续到了上限之后回来、还没人处理的结果：从最后往前数连续的 paused 结果，中间隔着卡片的更新不算断。
 * 人说了话（后面有了别的条目）就不再提示。
 */
export function pausedResults(items: readonly (Item | undefined)[]): TaskResultItem[] {
  const found: TaskResultItem[] = [];
  for (let i = items.length - 1; i >= 0; i--) {
    const item = items[i];
    if (item === undefined) continue;
    if (item.kind === "taskResult" && item.paused) found.unshift(item);
    else break;
  }
  return found;
}

/** 任务会话里最近的几步工具调用（卡片展开时显示）。 */
export function recentSteps(items: readonly Item[], limit: number): ToolStepItem[] {
  return items.filter((item): item is ToolStepItem => item.kind === "tool").slice(-limit);
}

/** 任务会话里有没有等你回答的问题（批准或 ask_user）。 */
export function waitingForUser(items: readonly Item[]): boolean {
  return items.some((item) => item.kind === "question" && item.status === "OPEN");
}

export interface TaskApproval {
  task: TaskItem;
  pending: PendingApproval;
}

/** 各个任务会话里等着批准的问题，按任务放出的先后取第一个：一次只问一个，答完再出下一个。 */
export function firstApproval(tasks: readonly TaskItem[], itemsOf: (session: string) => readonly Item[] | undefined): TaskApproval | null {
  for (const task of tasks) {
    const items = itemsOf(task.taskSession);
    if (items === undefined) continue;
    const pending = pendingApproval(items);
    if (pending !== null) return { task, pending };
  }
  return null;
}

// 现在等着批准的是哪个问题（input-area 需求 2）：规则插件在工具调用前问的、还没答的里面最新的一个，连同它问的那次调用。

import type { Item, QuestionItem, ToolStepItem } from "../../api/types";

export interface PendingApproval {
  question: QuestionItem;
  /** 被问的那次工具调用；对话里还没读到它时没有。 */
  step?: ToolStepItem;
}

/** items 按对话顺序。没有等着批准的返回 null；ask_user 的问题不带 toolCallId，不算。 */
export function pendingApproval(items: readonly (Item | undefined)[]): PendingApproval | null {
  for (let i = items.length - 1; i >= 0; i--) {
    const item = items[i];
    if (item?.kind !== "question" || item.status !== "OPEN" || item.toolCallId === undefined) continue;
    const step = items.find((each): each is ToolStepItem => each?.kind === "tool" && each.call.id === item.toolCallId);
    return step === undefined ? { question: item } : { question: item, step };
  }
  return null;
}

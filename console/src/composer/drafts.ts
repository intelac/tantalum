// 未发送的草稿按会话存在 sessionStorage：切换会话、登录过期跳到登录页再回来都还在；关掉标签页就没了。

import type { ModelChoice } from "../api/types";
import { readStored, removeStored, sessionArea, writeStored } from "../ui/storage";

const PREFIX = "tantalum.draft.";

type Area = Pick<Storage, "getItem" | "setItem" | "removeItem">;

export function readDraft(session: string, area: Area | null = sessionArea()): string {
  return readStored(PREFIX + session, area) ?? "";
}

export function saveDraft(session: string, text: string, area: Area | null = sessionArea()): void {
  if (text === "") removeStored(PREFIX + session, area);
  else writeStored(PREFIX + session, text, area);
}

export function clearDraft(session: string, area: Area | null = sessionArea()): void {
  saveDraft(session, "", area);
}

// 没发出去的模型选择和没发出去的文字一样按会话存：刷新页面不丢，发出去后清掉（model-picker M6）。
const CHOICE_PREFIX = "tantalum.model.";

export function readChoice(session: string, area: Area | null = sessionArea()): ModelChoice | null {
  const stored = readStored(CHOICE_PREFIX + session, area);
  if (stored === null) return null;
  try {
    const value = JSON.parse(stored) as Partial<ModelChoice>;
    if (typeof value.route !== "string" || typeof value.model !== "string") return null;
    return { route: value.route, model: value.model, effort: typeof value.effort === "string" ? value.effort : null };
  } catch {
    return null;
  }
}

export function saveChoice(session: string, choice: ModelChoice, area: Area | null = sessionArea()): void {
  writeStored(CHOICE_PREFIX + session, JSON.stringify(choice), area);
}

export function clearChoice(session: string, area: Area | null = sessionArea()): void {
  removeStored(CHOICE_PREFIX + session, area);
}

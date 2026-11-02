// 未发送的草稿按会话存在 sessionStorage：切换会话、登录过期跳到登录页再回来都还在；关掉标签页就没了。

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

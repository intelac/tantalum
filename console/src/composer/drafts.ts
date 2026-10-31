// 未发送的草稿按会话存在 sessionStorage：切换会话、登录过期跳到登录页再回来都还在；关掉标签页就没了。

const PREFIX = "tantalum.draft.";

type Store = Pick<Storage, "getItem" | "setItem" | "removeItem">;

function storage(): Store | null {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

export function readDraft(session: string, store: Store | null = storage()): string {
  try {
    return store?.getItem(PREFIX + session) ?? "";
  } catch {
    return "";
  }
}

export function saveDraft(session: string, text: string, store: Store | null = storage()): void {
  try {
    if (text === "") store?.removeItem(PREFIX + session);
    else store?.setItem(PREFIX + session, text);
  } catch {
    // 存不了只影响这次。
  }
}

export function clearDraft(session: string, store: Store | null = storage()): void {
  saveDraft(session, "", store);
}

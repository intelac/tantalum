// 管理员只读查看别人的会话（qagent-align 需求 6）：现在在看谁。只在内存里——刷新页面、开新标签页就回到自己，
// 不进地址、不进 localStorage。看别人时，读会话的那些请求在地址上带 `viewAs=用户名`，服务器把那一次请求换成被看的人；
// 别的请求（自己的资料、配置、模型列表）照常是自己的。写请求一律不发：服务器也会拒，但不该走到那一步。

import { useSyncExternalStore } from "react";

let viewed: string | null = null;
const listeners = new Set<() => void>();

/** 现在在看谁；没在看别人是 null。 */
export function viewedUser(): string | null {
  return viewed;
}

/** 开始看一个人，或者（null）回到自己。 */
export function setViewedUser(name: string | null): void {
  if (viewed === name) return;
  viewed = name;
  for (const listener of listeners) listener();
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useViewedUser(): string | null {
  return useSyncExternalStore(subscribe, viewedUser, viewedUser);
}

/** 读被看的人的会话数据的地址：在看别人时加上 `viewAs`，否则原样。只有服务器允许只读查看的那些接口用它。 */
export function asViewed(path: string): string {
  if (viewed === null) return path;
  return `${path}${path.includes("?") ? "&" : "?"}viewAs=${encodeURIComponent(viewed)}`;
}

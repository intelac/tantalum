// 浏览器禁用站点数据时，访问 localStorage 本身就会抛 SecurityError。所有读写都先经过这里，拿不到就当没有存储。

export type LocalStore = Pick<Storage, "getItem" | "setItem" | "removeItem">;

export function localStore(): LocalStore | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

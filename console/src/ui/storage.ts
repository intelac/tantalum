// 浏览器禁用站点数据时，访问 localStorage 本身就会抛 SecurityError；私密模式下写入也会抛。
// 所有读写都经过这里：拿不到存储就当没有，存不了只影响这次。

export function localArea(): Storage | null {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

export function sessionArea(): Storage | null {
  try {
    return window.sessionStorage;
  } catch {
    return null;
  }
}

export function readStored(key: string, area: Pick<Storage, "getItem"> | null): string | null {
  try {
    return area?.getItem(key) ?? null;
  } catch {
    return null;
  }
}

export function writeStored(key: string, value: string, area: Pick<Storage, "setItem"> | null): void {
  try {
    area?.setItem(key, value);
  } catch {
    // 存不了只影响这次。
  }
}

export function removeStored(key: string, area: Pick<Storage, "removeItem"> | null): void {
  try {
    area?.removeItem(key);
  } catch {
    // 同上。
  }
}

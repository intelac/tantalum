// 组件只订阅自己需要的键，其他条目变化不触发重绘。

import { useCallback, useMemo, useRef, useSyncExternalStore } from "react";
import type { Item, RunStatusItem } from "../api/types";
import { ConversationStore, type Live, type Paging, type SessionModel } from "./ConversationStore";

export function useItem(store: ConversationStore, id: string): Item | undefined {
  const subscribe = useCallback((listener: () => void) => store.subscribe(ConversationStore.itemKey(id), listener), [store, id]);
  const get = useCallback(() => store.getItem(id), [store, id]);
  return useSyncExternalStore(subscribe, get, get);
}

export function useOrder(store: ConversationStore, session: string): readonly string[] {
  const subscribe = useCallback(
    (listener: () => void) => store.subscribe(ConversationStore.orderKey(session), listener),
    [store, session],
  );
  const get = useCallback(() => store.getOrder(session), [store, session]);
  return useSyncExternalStore(subscribe, get, get);
}

export function useLive(store: ConversationStore, run: string): Live {
  const subscribe = useCallback((listener: () => void) => store.subscribe(ConversationStore.liveKey(run), listener), [store, run]);
  const get = useCallback(() => store.getLive(run), [store, run]);
  return useSyncExternalStore(subscribe, get, get);
}

export function usePaging(store: ConversationStore, session: string): Paging {
  const subscribe = useCallback(
    (listener: () => void) => store.subscribe(ConversationStore.pagingKey(session), listener),
    [store, session],
  );
  const get = useCallback(() => store.getPaging(session), [store, session]);
  return useSyncExternalStore(subscribe, get, get);
}

/** 订阅一组条目；只要有一个变了就重绘，快照数组只在内容变化时换新引用。 */
export function useItems(store: ConversationStore, ids: readonly string[]): (Item | undefined)[] {
  const cache = useRef<(Item | undefined)[]>([]);
  const subscribe = useCallback(
    (listener: () => void) => {
      const offs = ids.map((id) => store.subscribe(ConversationStore.itemKey(id), listener));
      return () => offs.forEach((off) => off());
    },
    [store, ids],
  );
  const get = useCallback(() => {
    const next = ids.map((id) => store.getItem(id));
    const previous = cache.current;
    if (previous.length === next.length && previous.every((item, index) => item === next[index])) return previous;
    cache.current = next;
    return next;
  }, [store, ids]);
  return useSyncExternalStore(subscribe, get, get);
}

/** 运行状态条目的 ID。只有这里知道后端用的 `run:` 前缀。 */
export function runStatusId(run: string): string {
  return `run:${run}`;
}

export function useRunStatus(store: ConversationStore, run: string | null): RunStatusItem | undefined {
  const item = useItem(store, run === null ? "" : runStatusId(run));
  return item?.kind === "run" ? item : undefined;
}

/** 运行状态每变一次就变的字符串，用作查询缓存键的最后一段：运行推进时重新读回放数据。 */
export function useRunVersion(store: ConversationStore, run: string | null): string {
  const status = useRunStatus(store, run);
  return status === undefined ? "" : `${status.phase}@${status.updatedAt}`;
}

/** 会话现在的模型选择和有没有工具变化（model-picker）。 */
export function useSessionModel(store: ConversationStore, session: string): SessionModel {
  const subscribe = useCallback(
    (listener: () => void) => store.subscribe(ConversationStore.modelKey(session), listener),
    [store, session],
  );
  const get = useCallback(() => store.getModel(session), [store, session]);
  return useSyncExternalStore(subscribe, get, get);
}

/** 会话里最近的运行，以及它是否还没结束。 */
export function useLatestRun(store: ConversationStore, session: string): { run: string | null; running: boolean } {
  const order = useOrder(store, session);
  // 用户条目在运行开始后也带运行 ID，所以只看运行产生的条目；它们的运行 ID 不会变。
  const run = useMemo(() => {
    for (let index = order.length - 1; index >= 0; index--) {
      const item = store.getItem(order[index] ?? "");
      if (item !== undefined && item.kind !== "user" && item.kind !== "compaction" && item.run !== undefined) return item.run;
    }
    return null;
  }, [order, store]);
  const status = useRunStatus(store, run);
  return { run, running: status !== undefined && status.endReason === undefined };
}

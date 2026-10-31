// 组件只订阅自己需要的键，其他条目变化不触发重绘。

import { useCallback, useRef, useSyncExternalStore } from "react";
import type { Item } from "../api/types";
import { ConversationStore, type Live } from "./ConversationStore";

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

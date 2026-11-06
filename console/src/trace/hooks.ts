// 轨迹页的订阅：记录和翻页位置来自 TraceStore，请求详情来自 TanStack Query（定下来的尝试不会再变）。

import { useQuery } from "@tanstack/react-query";
import { useCallback, useEffect, useState, useSyncExternalStore } from "react";
import { getTraceCall, getTracePage } from "../api/client";
import { useConnection } from "../api/connection";
import type { TraceCall, TraceRecord } from "../api/types";
import { TraceStore, type TracePaging } from "./TraceStore";

export function useTraceRecords(store: TraceStore, session: string): readonly TraceRecord[] {
  const subscribe = useCallback((listener: () => void) => store.subscribe(TraceStore.recordsKey(session), listener), [store, session]);
  const get = useCallback(() => store.getRecords(session), [store, session]);
  return useSyncExternalStore(subscribe, get, get);
}

export function useTracePaging(store: TraceStore, session: string): TracePaging {
  const subscribe = useCallback((listener: () => void) => store.subscribe(TraceStore.pagingKey(session), listener), [store, session]);
  const get = useCallback(() => store.getPaging(session), [store, session]);
  return useSyncExternalStore(subscribe, get, get);
}

/**
 * 打开轨迹页和每次重新连上都重新拉最新一页，和 journal 对齐；带 {@code run} 时一次取到包含这个运行的页。
 * 返回加载错误，没有错误时为 null；显示时才变成文字。
 */
export function useLatestTrace(store: TraceStore, session: string, run?: string): unknown {
  const { connects } = useConnection();
  const [error, setError] = useState<unknown>(null);
  useEffect(() => {
    let cancelled = false;
    setError(null);
    getTracePage(session, run === undefined ? {} : { run })
      .then((page) => {
        if (!cancelled) store.loadLatest(session, page);
      })
      .catch((failure: unknown) => {
        if (!cancelled) setError(failure);
      });
    return () => {
      cancelled = true;
    };
  }, [store, session, run, connects]);
  return error;
}

/** 一次尝试的完整输入，按命令 ID 缓存。{@code command} 为空时不取。 */
export function useTraceCall(session: string, command: string | undefined) {
  return useQuery<TraceCall>({
    queryKey: ["trace-call", session, command],
    queryFn: () => getTraceCall(session, command as string),
    enabled: command !== undefined,
    staleTime: Infinity,
  });
}

/** 取更早一页用的接口调用，交给 TraceStore。 */
export function fetchOlderTrace(session: string, before: number) {
  return getTracePage(session, { before });
}

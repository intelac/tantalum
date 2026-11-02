// 把 SSE 事件接到 store 和查询缓存上：item 覆盖条目，chunk 更新进行中的文本，PhaseChanged 结算分片并刷新侧栏。

import type { QueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { notifyUnauthorized } from "../api/client";
import { reportConnection, resetConnection } from "../api/connection";
import { connectEvents, type SseEvent } from "../api/sse";
import type { Chunk, Item, PhaseChanged } from "../api/types";
import { SESSIONS_KEY } from "../shell/useSessions";
import { seqOf, type ConversationStore } from "./ConversationStore";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function hasStrings(value: unknown, ...keys: string[]): boolean {
  return isRecord(value) && keys.every((key) => typeof value[key] === "string");
}

/** 形状不对的负载直接丢掉：服务端的 bug 或中间人改过的数据不该让整条流重连。 */
export function handleEvent(event: SseEvent, store: ConversationStore, queries: QueryClient): void {
  let data: unknown;
  try {
    data = JSON.parse(event.data);
  } catch {
    return;
  }
  switch (event.event) {
    case "item": {
      if (!hasStrings(data, "id", "session", "kind")) return;
      const item = data as Item;
      store.applyItem(item, seqOf(event.id));
      if (item.kind === "user" || item.kind === "run") void queries.invalidateQueries({ queryKey: SESSIONS_KEY });
      break;
    }
    case "chunk": {
      if (!hasStrings(data, "run", "kind", "text")) return;
      const chunk = data as Chunk;
      if (chunk.kind !== "text" && chunk.kind !== "reasoning") return;
      store.applyChunk(chunk.run, chunk.kind, chunk.text);
      break;
    }
    case "PhaseChanged": {
      if (!hasStrings(data, "run", "phase")) return;
      const phase = data as PhaseChanged;
      // 离开模型调用后，以 journal 里结算的条目为准，瞬态分片作废。
      if (phase.phase !== "AwaitingModel") store.clearLive(phase.run);
      void queries.invalidateQueries({ queryKey: SESSIONS_KEY });
      break;
    }
    default:
      break;
  }
}

/** 登录后保持一条 SSE 连接；退出或组件卸载时断开。 */
export function useEventStream(store: ConversationStore, queries: QueryClient): void {
  useEffect(() => {
    resetConnection();
    const stop = connectEvents({
      onEvent: (event) => handleEvent(event, store, queries),
      onConnection: reportConnection,
      onUnauthorized: notifyUnauthorized,
    });
    return stop;
  }, [store, queries]);
}

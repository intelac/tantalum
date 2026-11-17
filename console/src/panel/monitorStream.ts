// 实时监视流（live-monitor 需求 3）：整个页面共用一条 `/api/monitor/stream`，有面板订阅时才开，最后一个走了就关。
// 浏览器的 EventSource 自己会重连；监视只看当下，断线期间的不补。

import { asViewed } from "../api/viewing";
import type { MonitorMessage } from "./protocol";

type Listener = (message: MonitorMessage) => void;

const listeners = new Set<Listener>();
let source: EventSource | null = null;

function open(): void {
  if (source !== null || typeof EventSource === "undefined") return;
  source = new EventSource(asViewed("/api/monitor/stream"));
  source.addEventListener("monitor", (event) => {
    let message: MonitorMessage;
    try {
      message = JSON.parse((event as MessageEvent<string>).data) as MonitorMessage;
    } catch {
      return;
    }
    for (const listener of listeners) listener(message);
  });
}

/** 订阅监视消息；返回退订。 */
export function subscribeMonitor(listener: Listener): () => void {
  listeners.add(listener);
  open();
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0 && source !== null) {
      source.close();
      source = null;
    }
  };
}

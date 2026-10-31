// SSE 连接状态：断线提示和重连后重新拉取都看这里。

import { useSyncExternalStore } from "react";

export interface Connection {
  connected: boolean;
  /** 曾经连上过，之后断开：这时才显示断线提示。 */
  lost: boolean;
  /** 每次重新连上加一。 */
  reconnects: number;
  unauthorized: boolean;
  /** 服务返回过 503：已停止处理事件，重启前不会恢复。 */
  halted: boolean;
}

let state: Connection = { connected: false, lost: false, reconnects: 0, unauthorized: false, halted: false };
const listeners = new Set<() => void>();

function set(next: Connection): void {
  state = next;
  for (const listener of listeners) listener();
}

export function reportConnection(connected: boolean): void {
  if (connected) {
    set({ ...state, connected: true, lost: false, reconnects: state.connected ? state.reconnects : state.reconnects + 1 });
  } else {
    set({ ...state, connected: false, lost: state.reconnects > 0 });
  }
}

export function reportUnauthorized(): void {
  set({ ...state, connected: false, unauthorized: true });
}

export function reportHalted(): void {
  if (!state.halted) set({ ...state, halted: true });
}

export function resetConnection(): void {
  set({ connected: false, lost: false, reconnects: 0, unauthorized: false, halted: false });
}

export function connectionState(): Connection {
  return state;
}

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function useConnection(): Connection {
  return useSyncExternalStore(subscribe, connectionState, connectionState);
}

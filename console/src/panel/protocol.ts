// 插件面板的消息协议 v1（plugin-panel 需求 4；文档 docs/panels.md）。面板在沙箱 iframe 里，和控制台只靠 postMessage 说话。
// 每条消息都是 `{ tantalum: 1, type, ... }`。面板 → 宿主：ready、get、subscribe、open、error；宿主 → 面板：init、context、result、trace。

import type { TraceRecord } from "../api/types";

export const PROTOCOL = 1;
/** 一条消息序列化后的上限，超了丢掉。 */
export const MAX_MESSAGE_BYTES = 1_000_000;
/** 一批实时轨迹记录最多这么多条。 */
export const MAX_TRACE_BATCH = 500;

export type GetWhat = "tree" | "trace" | "runs" | "summary";

/** 一条实时监视消息（live-monitor），和服务器的 `MonitorMessage` 一一对应。 */
export interface MonitorMessage {
  stage:
    | "INTAKE"
    | "INPUT"
    | "LANE"
    | "KERNEL"
    | "JOURNAL"
    | "PUSH"
    | "OUTPUT"
    | "HANDLER_START"
    | "HANDLER_END"
    | "REMOTE_SEND"
    | "REMOTE_DONE";
  session?: string;
  run?: string;
  seq?: number;
  command?: string;
  name?: string;
  detail?: string;
  at: number;
  dropped: number;
}

export interface PanelContext {
  session: string;
  run?: string;
  theme: "light" | "dark";
  language: string;
  readOnly: boolean;
}

export type FromPanel =
  | { tantalum: 1; type: "ready" }
  | { tantalum: 1; type: "get"; id: string | number; what: GetWhat; params?: Record<string, unknown> }
  | { tantalum: 1; type: "subscribe"; what: "trace" | "monitor" }
  | { tantalum: 1; type: "open"; target: { run: string } | { record: string } }
  | { tantalum: 1; type: "error"; message: string };

export type ToPanel =
  | ({ tantalum: 1; type: "init" } & PanelContext)
  | ({ tantalum: 1; type: "context" } & PanelContext)
  | { tantalum: 1; type: "result"; id: string | number; ok: boolean; status?: number; body?: unknown; error?: string }
  | { tantalum: 1; type: "trace"; records: TraceRecord[] }
  | { tantalum: 1; type: "monitor"; messages: MonitorMessage[] };

const WHATS: readonly string[] = ["tree", "trace", "runs", "summary"];

/** 面板发来的消息：格式不对返回 null。 */
export function parseFromPanel(data: unknown): FromPanel | null {
  if (data === null || typeof data !== "object") return null;
  const message = data as Record<string, unknown>;
  if (message.tantalum !== PROTOCOL || typeof message.type !== "string") return null;
  switch (message.type) {
    case "ready":
      return { tantalum: 1, type: "ready" };
    case "get": {
      const id = message.id;
      if ((typeof id !== "string" && typeof id !== "number") || typeof message.what !== "string" || !WHATS.includes(message.what)) return null;
      const params = message.params;
      if (params !== undefined && (params === null || typeof params !== "object" || Array.isArray(params))) return null;
      return { tantalum: 1, type: "get", id, what: message.what as GetWhat, params: params as Record<string, unknown> | undefined };
    }
    case "subscribe":
      return message.what === "trace" || message.what === "monitor" ? { tantalum: 1, type: "subscribe", what: message.what } : null;
    case "open": {
      const target = message.target as Record<string, unknown> | null | undefined;
      if (target === null || typeof target !== "object") return null;
      if (typeof target.run === "string") return { tantalum: 1, type: "open", target: { run: target.run } };
      if (typeof target.record === "string") return { tantalum: 1, type: "open", target: { record: target.record } };
      return null;
    }
    case "error":
      return typeof message.message === "string" ? { tantalum: 1, type: "error", message: message.message.slice(0, 500) } : null;
    default:
      return null;
  }
}

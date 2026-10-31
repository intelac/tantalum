// 用 fetch 流式读 SSE，这样能带 Last-Event-ID 补读。身份来自 Cookie。断线后指数退避重连；401 停止并交给登录流程。

export interface SseEvent {
  id?: string;
  event: string;
  data: string;
}

/** 增量解析 SSE 文本，返回解析出的事件和未完成的部分。 */
export function parseSse(buffer: string): { events: SseEvent[]; rest: string } {
  const events: SseEvent[] = [];
  const normalized = buffer.replace(/\r\n/g, "\n");
  const blocks = normalized.split("\n\n");
  const rest = blocks.pop() ?? "";
  for (const block of blocks) {
    let id: string | undefined;
    let event = "message";
    const data: string[] = [];
    for (const line of block.split("\n")) {
      if (line.startsWith(":")) continue;
      const colon = line.indexOf(":");
      const field = colon === -1 ? line : line.slice(0, colon);
      const value = colon === -1 ? "" : line.slice(colon + 1).replace(/^ /, "");
      if (field === "id") id = value;
      else if (field === "event") event = value;
      else if (field === "data") data.push(value);
    }
    if (data.length > 0) events.push({ id, event, data: data.join("\n") });
  }
  return { events, rest };
}

export interface SseHandlers {
  /** 包括 `PhaseChanged`、`item`、`chunk` 和插件事实。 */
  onEvent: (event: SseEvent) => void;
  onConnection: (connected: boolean) => void;
  /** 服务端返回 401：停止重连。 */
  onUnauthorized?: () => void;
}

export interface SseOptions {
  /** 从哪个位置之后补读；不给时只收新消息，历史由接口加载。 */
  lastEventId?: string;
  /** 首次重连等待的毫秒数，之后翻倍，最多 8 倍。 */
  retryDelayMs?: number;
  fetch?: typeof fetch;
}

/** 连接并在断线后自动重连。返回用于停止的函数。 */
export function connectEvents(handlers: SseHandlers, options: SseOptions = {}): () => void {
  const controller = new AbortController();
  const doFetch = options.fetch ?? fetch;
  const baseDelay = options.retryDelayMs ?? 500;
  let lastEventId = options.lastEventId;

  async function readOnce(): Promise<"closed" | "unauthorized"> {
    const response = await doFetch("/api/events", {
      headers: lastEventId === undefined ? {} : { "Last-Event-ID": lastEventId },
      credentials: "same-origin",
      signal: controller.signal,
    });
    if (response.status === 401) return "unauthorized";
    if (!response.ok || response.body === null) throw new Error(`events: ${response.status}`);
    handlers.onConnection(true);
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    for (;;) {
      const { done, value } = await reader.read();
      if (done) return "closed";
      buffer += decoder.decode(value, { stream: true });
      const parsed = parseSse(buffer);
      buffer = parsed.rest;
      for (const event of parsed.events) {
        if (event.id !== undefined) lastEventId = event.id;
        handlers.onEvent(event);
      }
    }
  }

  async function loop(): Promise<void> {
    let delay = baseDelay;
    while (!controller.signal.aborted) {
      try {
        if ((await readOnce()) === "unauthorized") {
          handlers.onConnection(false);
          handlers.onUnauthorized?.();
          return;
        }
        delay = baseDelay;
      } catch {
        // 下面统一按断线处理。
      }
      if (controller.signal.aborted) return;
      handlers.onConnection(false);
      await new Promise((resolve) => setTimeout(resolve, delay));
      delay = Math.min(delay * 2, baseDelay * 8);
    }
  }

  void loop();
  return () => controller.abort();
}

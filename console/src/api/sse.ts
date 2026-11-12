// 用 fetch 流式读 SSE，这样能带 Last-Event-ID 补读。身份来自 Cookie。断线后指数退避重连；401 停止并交给登录流程。
//
// 有的代理和隧道把长连接的内容憋到响应结束才放行：连上了却一个字节都收不到。服务器一连上就发一行 `:open`，
// 等不到它就改成反复请求 /api/events/poll——订阅留在服务器上，每次请求带回一批就结束，格式和 SSE 一样。

import { asViewed } from "./viewing";

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
  /** 连上以后多久收不到第一个字节就当作被代理憋住了，改用轮询。 */
  firstByteMs?: number;
}

/** 连接并在断线后自动重连。返回用于停止的函数。 */
export function connectEvents(handlers: SseHandlers, options: SseOptions = {}): () => void {
  const controller = new AbortController();
  const doFetch = options.fetch ?? fetch;
  const baseDelay = options.retryDelayMs ?? 500;
  const firstByteMs = options.firstByteMs ?? 4000;
  let lastEventId = options.lastEventId;
  /** 一旦发现长连接读不到内容，这个页面之后都轮询。 */
  let polling = false;
  /** 服务器上那条订阅的编号；失效后清掉，下一次重新开。 */
  let pollStream: string | undefined;

  function resume(): Record<string, string> {
    return lastEventId === undefined ? {} : { "Last-Event-ID": lastEventId };
  }

  function take(events: SseEvent[]): void {
    for (const event of events) {
      if (event.id !== undefined) lastEventId = event.id;
      deliver(event);
    }
  }

  /** 取一批。订阅失效（410）时清掉编号，下一次从 Last-Event-ID 重新开。 */
  async function pollOnce(): Promise<"again" | "unauthorized"> {
    const path = pollStream === undefined ? "/api/events/poll" : `/api/events/poll?stream=${encodeURIComponent(pollStream)}`;
    // 只读查看别人时订阅的是被看的人的事件
    const response = await doFetch(asViewed(path), { headers: resume(), credentials: "same-origin", signal: controller.signal });
    if (response.status === 401) return "unauthorized";
    if (response.status === 410) {
      pollStream = undefined;
      return "again";
    }
    if (!response.ok) throw new Error(`events: ${response.status}`);
    pollStream = response.headers.get("X-Event-Stream") ?? undefined;
    handlers.onConnection(true);
    take(parseSse(await response.text()).events);
    return "again";
  }

  async function readOnce(): Promise<"closed" | "unauthorized" | "buffered"> {
    const response = await doFetch(asViewed("/api/events"), {
      headers: resume(),
      credentials: "same-origin",
      signal: controller.signal,
    });
    if (response.status === 401) return "unauthorized";
    if (!response.ok || response.body === null) throw new Error(`events: ${response.status}`);
    handlers.onConnection(true);
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    let first = true;
    try {
      for (;;) {
        const read = first ? await firstRead(reader) : await reader.read();
        first = false;
        if (read === "silent") return "buffered";
        const { done, value } = read;
        if (done) return "closed";
        buffer += decoder.decode(value, { stream: true });
        const parsed = parseSse(buffer);
        buffer = parsed.rest;
        take(parsed.events);
      }
    } finally {
      // 不管怎么离开（断线、取消、处理器出错），都把旧流关掉，不留下双流。
      await reader.cancel().catch(() => undefined);
    }
  }

  /** 第一次读有时限：服务器一连上就发一行，到时还没有就是中间有东西把内容憋住了。 */
  async function firstRead(reader: ReadableStreamDefaultReader<Uint8Array>): Promise<ReadableStreamReadResult<Uint8Array> | "silent"> {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const silent = new Promise<"silent">((resolve) => {
      timer = setTimeout(() => resolve("silent"), firstByteMs);
    });
    try {
      return await Promise.race([reader.read(), silent]);
    } finally {
      clearTimeout(timer);
    }
  }

  /** 一条事件处理失败只丢这一条，不当作断线重连。 */
  function deliver(event: SseEvent): void {
    try {
      handlers.onEvent(event);
    } catch (failure) {
      console.error("event handler failed", event.event, failure);
    }
  }

  async function loop(): Promise<void> {
    let delay = baseDelay;
    while (!controller.signal.aborted) {
      try {
        const outcome = polling ? await pollOnce() : await readOnce();
        if (outcome === "unauthorized") {
          handlers.onConnection(false);
          handlers.onUnauthorized?.();
          return;
        }
        delay = baseDelay;
        if (outcome === "buffered") polling = true;
        // 轮询是一次接一次：一批取完马上取下一批，不算断线
        if (polling) continue;
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

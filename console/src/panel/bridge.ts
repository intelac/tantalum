// 宿主这一侧的消息桥（plugin-panel 需求 3.3、4）：只认来自这个 iframe 的消息，格式不对、超长的丢掉；
// 面板能要的只有当前会话的调用树、轨迹、运行列表和汇总（白名单写死在这里，没有任意路径），能订阅当前会话的实时轨迹，
// 能请宿主在控制台内跳转。不碰 React，依赖都从外面给，方便测。

import type { TraceRecord } from "../api/types";
import { MAX_MESSAGE_BYTES, MAX_TRACE_BATCH, PROTOCOL, parseFromPanel, type FromPanel, type PanelContext, type ToPanel } from "./protocol";

export interface BridgeApi {
  tree(session: string, run: string): Promise<unknown>;
  trace(session: string, cursor: { before?: number; run?: string }): Promise<unknown>;
  /** `query` 已经限定在当前会话。 */
  runs(query: string): Promise<unknown>;
  summary(query: string): Promise<unknown>;
}

export interface BridgeDeps {
  frame: () => Window | null;
  context: () => PanelContext;
  api: BridgeApi;
  subscribeLive: (session: string, listener: (record: TraceRecord) => void) => () => void;
  /** 控制台内的地址。 */
  navigate: (target: { run: string } | { record: string }, session: string) => void;
  onReady: () => void;
  onError: (message: string) => void;
  zone: () => string;
  /** 实时轨迹按帧合并后再发；测试传同步。 */
  schedule?: (flush: () => void) => void;
  warn?: (message: string) => void;
}

function size(data: unknown): number {
  try {
    return JSON.stringify(data)?.length ?? 0;
  } catch {
    return Infinity;
  }
}

function errorOf(failure: unknown): { status?: number; error: string } {
  const status = (failure as { status?: unknown })?.status;
  const message = failure instanceof Error ? failure.message : String(failure);
  return typeof status === "number" ? { status, error: message } : { error: message };
}

/** 运行 ID 是 `<会话>_<序号>`：只认当前会话的。 */
export function runOfSession(run: unknown, session: string): run is string {
  return typeof run === "string" && run.startsWith(`${session}_`) && /^\d+$/.test(run.slice(session.length + 1));
}

export class PanelBridge {
  private readonly deps: BridgeDeps;
  private unsubscribe: (() => void) | null = null;
  private subscribedSession: string | null = null;
  private pending: TraceRecord[] = [];
  private flushing = false;
  private ready = false;
  private disposed = false;

  constructor(deps: BridgeDeps) {
    this.deps = deps;
  }

  /** `window` 的 message 事件都交给这里；不是这个 iframe 的不理。 */
  receive(event: MessageEvent): void {
    const frame = this.deps.frame();
    if (this.disposed || frame === null || event.source !== frame) return;
    if (size(event.data) > MAX_MESSAGE_BYTES) {
      this.warn("message too large");
      return;
    }
    const message = parseFromPanel(event.data);
    if (message === null) {
      this.warn("unknown message");
      return;
    }
    this.handle(message);
  }

  /** 会话、运行、主题、语言变了。会话变了时实时轨迹改订新会话。 */
  contextChanged(): void {
    if (!this.ready) return;
    const context = this.deps.context();
    this.post({ tantalum: PROTOCOL, type: "context", ...context });
    if (this.subscribedSession !== null && this.subscribedSession !== context.session) this.subscribe(context.session);
  }

  dispose(): void {
    this.disposed = true;
    this.unsubscribe?.();
    this.unsubscribe = null;
  }

  private handle(message: FromPanel): void {
    const context = this.deps.context();
    switch (message.type) {
      case "ready":
        this.ready = true;
        this.deps.onReady();
        this.post({ tantalum: PROTOCOL, type: "init", ...context });
        return;
      case "get":
        void this.get(message.id, message.what, message.params ?? {}, context);
        return;
      case "subscribe":
        this.subscribe(context.session);
        return;
      case "open":
        if ("run" in message.target && !runOfSession(message.target.run, context.session)) {
          this.warn("open: run of another session");
          return;
        }
        this.deps.navigate(message.target, context.session);
        return;
      case "error":
        this.deps.onError(message.message);
        return;
    }
  }

  private async get(id: string | number, what: string, params: Record<string, unknown>, context: PanelContext): Promise<void> {
    const session = context.session;
    const deny = (error: string) => this.post({ tantalum: PROTOCOL, type: "result", id, ok: false, error });
    let request: Promise<unknown>;
    if (what === "tree") {
      if (!runOfSession(params.run, session)) return deny("run must belong to the current session");
      request = this.deps.api.tree(session, params.run);
    } else if (what === "trace") {
      const before = typeof params.before === "number" && Number.isInteger(params.before) && params.before >= 0 ? params.before : undefined;
      const run = runOfSession(params.run, session) ? params.run : undefined;
      request = this.deps.api.trace(session, before !== undefined ? { before } : run !== undefined ? { run } : {});
    } else if (what === "runs") {
      const limit = typeof params.limit === "number" && params.limit >= 1 && params.limit <= 200 ? Math.floor(params.limit) : 20;
      request = this.deps.api.runs(new URLSearchParams({ session, limit: String(limit) }).toString());
    } else if (what === "summary") {
      request = this.deps.api.summary(new URLSearchParams({ session, tz: this.deps.zone() }).toString());
    } else {
      return deny("not allowed");
    }
    try {
      const body = await request;
      this.post({ tantalum: PROTOCOL, type: "result", id, ok: true, status: 200, body });
    } catch (failure) {
      this.post({ tantalum: PROTOCOL, type: "result", id, ok: false, ...errorOf(failure) });
    }
  }

  private subscribe(session: string): void {
    this.unsubscribe?.();
    this.subscribedSession = session;
    this.pending = [];
    this.unsubscribe = this.deps.subscribeLive(session, (record) => {
      this.pending.push(record);
      if (this.flushing) return;
      this.flushing = true;
      (this.deps.schedule ?? ((flush) => requestAnimationFrame(flush)))(() => this.flush());
    });
  }

  private flush(): void {
    this.flushing = false;
    // 同一条记录在一批里只留最新的版本
    const latest = new Map<string, TraceRecord>();
    for (const record of this.pending) latest.set(record.id, record);
    this.pending = [];
    const records = [...latest.values()];
    for (let start = 0; start < records.length; start += MAX_TRACE_BATCH) {
      this.post({ tantalum: PROTOCOL, type: "trace", records: records.slice(start, start + MAX_TRACE_BATCH) });
    }
  }

  private post(message: ToPanel): void {
    if (this.disposed) return;
    // 沙箱 iframe 是不透明的源，只能用 "*"；收件的窗口就是这个 iframe
    this.deps.frame()?.postMessage(message, "*");
  }

  private warn(message: string): void {
    (this.deps.warn ?? ((text: string) => console.warn(`[panel] ${text}`)))(message);
  }
}

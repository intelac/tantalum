import { describe, expect, it, vi } from "vitest";
import type { TraceRecord } from "../api/types";
import { PanelBridge, runOfSession, type BridgeDeps } from "./bridge";
import type { PanelContext, ToPanel } from "./protocol";

const CONTEXT: PanelContext = { session: "s1", run: "s1_4", theme: "light", language: "zh", readOnly: false };

function setup(overrides: Partial<BridgeDeps> = {}) {
  const sent: ToPanel[] = [];
  const frame = { postMessage: vi.fn((message: ToPanel) => sent.push(message)) } as unknown as Window;
  let live: ((record: TraceRecord) => void) | null = null;
  const unsubscribe = vi.fn();
  const deps: BridgeDeps = {
    frame: () => frame,
    context: () => CONTEXT,
    api: {
      tree: vi.fn(() => Promise.resolve({ root: { id: "r" } })),
      trace: vi.fn(() => Promise.resolve({ records: [] })),
      runs: vi.fn(() => Promise.resolve({ runs: [] })),
      summary: vi.fn(() => Promise.reject(Object.assign(new Error("index unavailable"), { status: 503 }))),
    },
    subscribeLive: vi.fn((_session, listener) => {
      live = listener;
      return unsubscribe;
    }),
    navigate: vi.fn(),
    onReady: vi.fn(),
    onError: vi.fn(),
    zone: () => "Asia/Tokyo",
    schedule: (flush) => flush(),
    warn: vi.fn(),
    ...overrides,
  };
  const bridge = new PanelBridge(deps);
  const send = (data: unknown, source: unknown = frame) => bridge.receive({ data, source } as MessageEvent);
  return { bridge, deps, sent, send, frame, live: () => live, unsubscribe };
}

const flushPromises = () => new Promise((resolve) => setTimeout(resolve, 0));

describe("PanelBridge", () => {
  it("answers ready with init and ignores messages from other windows or of the wrong shape", () => {
    const { sent, send, deps } = setup();
    send({ tantalum: 1, type: "ready" }, {});
    expect(sent).toEqual([]);
    send({ type: "ready" });
    send({ tantalum: 1, type: "launch" });
    send({ tantalum: 1, type: "get", id: 1, what: "users" });
    expect(sent).toEqual([]);
    expect(deps.warn).toHaveBeenCalledTimes(3);
    send({ tantalum: 1, type: "ready" });
    expect(sent).toEqual([{ tantalum: 1, type: "init", ...CONTEXT }]);
    expect(deps.onReady).toHaveBeenCalled();
  });

  it("drops messages over the size cap", () => {
    const { sent, send, deps } = setup();
    send({ tantalum: 1, type: "error", message: "x".repeat(1_100_000) });
    expect(sent).toEqual([]);
    expect(deps.onError).not.toHaveBeenCalled();
    expect(deps.warn).toHaveBeenCalledWith("message too large");
  });

  it("serves only the whitelist, scoped to the current session", async () => {
    const { sent, send, deps } = setup();
    send({ tantalum: 1, type: "get", id: 1, what: "tree", params: { run: "s1_4" } });
    send({ tantalum: 1, type: "get", id: 2, what: "tree", params: { run: "s2_4" } });
    send({ tantalum: 1, type: "get", id: 3, what: "runs", params: { limit: 5, session: "s2" } });
    send({ tantalum: 1, type: "get", id: 4, what: "summary" });
    send({ tantalum: 1, type: "get", id: 5, what: "trace", params: { before: 12 } });
    await flushPromises();
    expect(deps.api.tree).toHaveBeenCalledWith("s1", "s1_4");
    expect(deps.api.tree).toHaveBeenCalledTimes(1);
    expect(deps.api.runs).toHaveBeenCalledWith("session=s1&limit=5");
    expect(deps.api.summary).toHaveBeenCalledWith("session=s1&tz=Asia%2FTokyo");
    expect(deps.api.trace).toHaveBeenCalledWith("s1", { before: 12 });
    const byId = new Map(sent.filter((message) => message.type === "result").map((message) => [(message as { id: number }).id, message]));
    expect(byId.get(1)).toEqual({ tantalum: 1, type: "result", id: 1, ok: true, status: 200, body: { root: { id: "r" } } });
    expect(byId.get(2)).toMatchObject({ ok: false, error: "run must belong to the current session" });
    expect(byId.get(4)).toEqual({ tantalum: 1, type: "result", id: 4, ok: false, status: 503, error: "index unavailable" });
  });

  it("forwards live trace records of the session, latest version only, after subscribe", () => {
    const { sent, send, live, deps } = setup();
    send({ tantalum: 1, type: "subscribe", what: "trace" });
    expect(deps.subscribeLive).toHaveBeenCalledWith("s1", expect.any(Function));
    const record = (id: string, end?: string) => ({ id, pos: "1.0", run: "s1_4", kind: "tool", at: "x", end }) as unknown as TraceRecord;
    live()?.(record("a"));
    expect(sent).toEqual([{ tantalum: 1, type: "trace", records: [record("a")] }]);
  });

  it("opens runs of the current session only and reports panel errors", () => {
    const { send, deps } = setup();
    send({ tantalum: 1, type: "open", target: { run: "s1_9" } });
    send({ tantalum: 1, type: "open", target: { run: "s9_1" } });
    send({ tantalum: 1, type: "open", target: { record: "abc" } });
    send({ tantalum: 1, type: "open", target: { url: "https://evil.example" } });
    expect(deps.navigate).toHaveBeenCalledTimes(2);
    expect(deps.navigate).toHaveBeenCalledWith({ run: "s1_9" }, "s1");
    expect(deps.navigate).toHaveBeenCalledWith({ record: "abc" }, "s1");
    send({ tantalum: 1, type: "error", message: "boom" });
    expect(deps.onError).toHaveBeenCalledWith("boom");
  });

  it("pushes context changes after ready and resubscribes on a new session; nothing after dispose", () => {
    let context = CONTEXT;
    const { bridge, sent, send, deps, unsubscribe } = setup({ context: () => context });
    bridge.contextChanged();
    expect(sent).toEqual([]);
    send({ tantalum: 1, type: "ready" });
    send({ tantalum: 1, type: "subscribe", what: "trace" });
    context = { ...CONTEXT, session: "s2", run: undefined, theme: "dark" };
    bridge.contextChanged();
    expect(sent.at(-1)).toEqual({ tantalum: 1, type: "context", ...context });
    expect(deps.subscribeLive).toHaveBeenLastCalledWith("s2", expect.any(Function));
    bridge.dispose();
    expect(unsubscribe).toHaveBeenCalled();
    send({ tantalum: 1, type: "ready" });
    expect(sent.filter((message) => message.type === "init")).toHaveLength(1);
  });
});

describe("monitor forwarding", () => {
  it("forwards only the current session's monitor messages, batched, and unsubscribes on dispose", () => {
    let listener: ((message: import("./protocol").MonitorMessage) => void) | null = null;
    const stop = vi.fn();
    const { bridge, sent, send } = setup({
      subscribeMonitor: (next) => {
        listener = next;
        return stop;
      },
    });
    send({ tantalum: 1, type: "subscribe", what: "monitor" });
    const message = (stage: "LANE" | "KERNEL", session: string) => ({ stage, session, at: 1, dropped: 0 });
    listener!(message("LANE", "s1"));
    listener!(message("KERNEL", "s2"));
    expect(sent).toEqual([{ tantalum: 1, type: "monitor", messages: [message("LANE", "s1")] }]);
    send({ tantalum: 1, type: "subscribe", what: "monitor" });
    bridge.dispose();
    expect(stop).toHaveBeenCalledTimes(1);
  });
});

describe("runOfSession", () => {
  it("accepts <session>_<number> only", () => {
    expect(runOfSession("s1_4", "s1")).toBe(true);
    expect(runOfSession("s1_x", "s1")).toBe(false);
    expect(runOfSession("s10_4", "s1")).toBe(false);
    expect(runOfSession(4, "s1")).toBe(false);
  });
});

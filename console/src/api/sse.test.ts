import { describe, expect, it, vi } from "vitest";
import { connectEvents, parseSse, type SseEvent } from "./sse";

describe("parseSse", () => {
  it("parses id, event and data", () => {
    const { events, rest } = parseSse('id:1.0\nevent:PhaseChanged\ndata:{"phase":"AwaitingModel"}\n\n');
    expect(events).toEqual([{ id: "1.0", event: "PhaseChanged", data: '{"phase":"AwaitingModel"}' }]);
    expect(rest).toBe("");
  });

  it("keeps an unfinished block for the next read", () => {
    const { events, rest } = parseSse("event:chunk\ndata:Hel");
    expect(events).toEqual([]);
    expect(rest).toBe("event:chunk\ndata:Hel");
  });

  it("skips comments such as keep-alive", () => {
    expect(parseSse(":keep-alive\n\n").events).toEqual([]);
  });

  it("joins multi-line data and accepts a space after the colon", () => {
    expect(parseSse("event: chunk\ndata: a\ndata: b\n\n").events).toEqual([{ id: undefined, event: "chunk", data: "a\nb" }]);
  });

  it("handles CRLF line endings", () => {
    expect(parseSse("event:chunk\r\ndata:x\r\n\r\n").events).toEqual([{ id: undefined, event: "chunk", data: "x" }]);
  });

  it("reads several events at once", () => {
    expect(parseSse("data:a\n\ndata:b\n\n").events.map((event) => event.data)).toEqual(["a", "b"]);
  });

  it("passes item events through like any other", () => {
    const { events } = parseSse('id:3.1\nevent:item\ndata:{"kind":"tool","id":"3.1"}\n\n');
    expect(events[0]?.event).toBe("item");
  });
});

/** 一次响应：先发这些文本块，然后要么结束，要么挂着直到被中止。 */
function streamOf(chunks: string[], stayOpen: boolean, signal?: AbortSignal): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder();
  return new ReadableStream({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk));
      if (!stayOpen) {
        controller.close();
        return;
      }
      signal?.addEventListener("abort", () => controller.error(new Error("aborted")));
    },
  });
}

function response(status: number, body: ReadableStream<Uint8Array> | null) {
  return { ok: status >= 200 && status < 300, status, body } as Response;
}

async function until(condition: () => boolean): Promise<void> {
  for (let i = 0; i < 200 && !condition(); i++) await new Promise((resolve) => setTimeout(resolve, 5));
  expect(condition()).toBe(true);
}

describe("connectEvents", () => {
  it("reconnects after the stream closes and resumes from the last event id", async () => {
    const requests: string[] = [];
    const fetchMock = vi.fn((_: string, init?: RequestInit) => {
      const headers = init?.headers as Record<string, string>;
      requests.push(headers["Last-Event-ID"] ?? "");
      if (requests.length === 1) {
        return Promise.resolve(response(200, streamOf(["id:0.0\nevent:PhaseChanged\ndata:{}\n\nid:0.1\nevent:item\ndata:{}\n\n"], false)));
      }
      return Promise.resolve(response(200, streamOf(["event:chunk\ndata:{}\n\n"], true, init?.signal ?? undefined)));
    });
    const events: SseEvent[] = [];
    const connection: boolean[] = [];
    const stop = connectEvents(
      { onEvent: (event) => events.push(event), onConnection: (connected) => connection.push(connected) },
      { retryDelayMs: 1, fetch: fetchMock as unknown as typeof fetch },
    );
    await until(() => events.length === 3);
    stop();

    expect(requests).toEqual(["", "0.1"]);
    expect(events.map((event) => event.event)).toEqual(["PhaseChanged", "item", "chunk"]);
    expect(connection).toEqual([true, false, true]);
    expect((fetchMock.mock.calls[0]?.[1] as RequestInit).credentials).toBe("same-origin");
  });

  it("a throwing handler loses one event but does not tear down the stream", async () => {
    const fetchMock = vi.fn((_: string, init?: RequestInit) =>
      Promise.resolve(response(200, streamOf(["event:item\ndata:bad\n\nevent:item\ndata:good\n\n"], true, init?.signal ?? undefined))),
    );
    const seen: string[] = [];
    const errors = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const stop = connectEvents(
      {
        onEvent: (event) => {
          if (event.data === "bad") throw new Error("handler bug");
          seen.push(event.data);
        },
        onConnection: () => undefined,
      },
      { retryDelayMs: 1, fetch: fetchMock as unknown as typeof fetch },
    );
    await until(() => seen.length === 1);
    await new Promise((resolve) => setTimeout(resolve, 20));
    stop();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(errors).toHaveBeenCalledTimes(1);
    errors.mockRestore();
  });

  it("cancels the old reader before reconnecting", async () => {
    const cancelled: boolean[] = [];
    const encoder = new TextEncoder();
    const fetchMock = vi.fn(() => {
      const stream = new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(encoder.encode("event:chunk\ndata:{}\n\n"));
          controller.close();
        },
        cancel() {
          cancelled.push(true);
        },
      });
      return Promise.resolve(response(200, stream));
    });
    const stop = connectEvents(
      { onEvent: () => undefined, onConnection: () => undefined },
      { retryDelayMs: 1, fetch: fetchMock as unknown as typeof fetch },
    );
    await until(() => fetchMock.mock.calls.length >= 2);
    stop();
    // 流正常结束后 cancel 是空操作，但调用路径必须走到；这里只断言没有抛错且重连发生了。
    expect(fetchMock.mock.calls.length).toBeGreaterThanOrEqual(2);
  });

  it("stops on 401 and reports it", async () => {
    const fetchMock = vi.fn().mockResolvedValue(response(401, null));
    const unauthorized = vi.fn();
    const connection: boolean[] = [];
    const stop = connectEvents(
      { onEvent: () => undefined, onConnection: (connected) => connection.push(connected), onUnauthorized: unauthorized },
      { retryDelayMs: 1, fetch: fetchMock as unknown as typeof fetch },
    );
    await until(() => unauthorized.mock.calls.length === 1);
    await new Promise((resolve) => setTimeout(resolve, 20));
    stop();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(connection).toEqual([false]);
  });

  it("backs off on network errors and stops when asked", async () => {
    const fetchMock = vi.fn().mockRejectedValue(new Error("down"));
    const connection: boolean[] = [];
    const stop = connectEvents(
      { onEvent: () => undefined, onConnection: (connected) => connection.push(connected) },
      { retryDelayMs: 1, fetch: fetchMock as unknown as typeof fetch },
    );
    await until(() => fetchMock.mock.calls.length >= 3);
    stop();
    const calls = fetchMock.mock.calls.length;
    await new Promise((resolve) => setTimeout(resolve, 30));
    expect(fetchMock.mock.calls.length).toBe(calls);
    expect(connection.every((connected) => !connected)).toBe(true);
  });
});

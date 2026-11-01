import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, createMemory, deleteMemory, getMemory, listMemories, listSessions, login, me, onUnauthorized, submit } from "./client";

function reply(status: number, body: string) {
  return { ok: status >= 200 && status < 300, status, text: () => Promise.resolve(body) };
}

describe("client", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    onUnauthorized(() => undefined);
  });

  it("sends the cookie and no identity header", async () => {
    const fetchMock = vi.fn().mockResolvedValue(reply(200, '{"sessions":[]}'));
    vi.stubGlobal("fetch", fetchMock);
    await listSessions();
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.credentials).toBe("same-origin");
    expect(Object.keys(init.headers as Record<string, string>)).not.toContain("X-Principal");
  });

  it("routes a 401 to the login flow and still throws", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(401, '{"error":"not_authenticated"}')));
    const handler = vi.fn();
    onUnauthorized(handler);
    await expect(listSessions()).rejects.toMatchObject({ status: 401 });
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it("login and me handle their own 401 without the login flow", async () => {
    const handler = vi.fn();
    onUnauthorized(handler);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(401, '{"error":"bad_credentials"}')));
    await expect(login("a", "b")).rejects.toBeInstanceOf(ApiError);
    expect(await me()).toBeNull();
    expect(handler).not.toHaveBeenCalled();
  });

  it("maps 429 and 503 to fixed messages", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(503, "")));
    await expect(submit("s", "prompt", "hi", "r1")).rejects.toThrow(/503/);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(429, "")));
    await expect(submit("s", "prompt", "hi", "r1")).rejects.toThrow(/429/);
  });

  it("submits with the caller's requestId so retries are idempotent", async () => {
    const fetchMock = vi.fn().mockResolvedValue(reply(200, '{"submissionId":"x","duplicate":false}'));
    vi.stubGlobal("fetch", fetchMock);
    await submit("s 1", "steer", "hi", "req-7");
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/sessions/s%201/submissions");
    expect(JSON.parse(init.body as string)).toEqual({ requestId: "req-7", mode: "steer", text: "hi" });
  });

  it("memory functions hit the per-user endpoints and surface the 400 reason", async () => {
    const fetchMock = vi.fn().mockResolvedValue(reply(200, '{"entries":[],"used":0,"max":200}'));
    vi.stubGlobal("fetch", fetchMock);
    await listMemories();
    await getMemory("a b");
    await createMemory({ name: "x", type: "user", description: "d", content: "c" });
    await deleteMemory("x");
    const calls = fetchMock.mock.calls as [string, RequestInit][];
    expect(calls.map(([path, init]) => `${init.method ?? "GET"} ${path}`)).toEqual([
      "GET /api/memory",
      "GET /api/memory/a%20b",
      "POST /api/memory",
      "DELETE /api/memory/x",
    ]);
    expect(JSON.parse(calls[2]?.[1].body as string)).toEqual({ name: "x", type: "user", description: "d", content: "c" });

    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(400, '{"error":"name must match [a-z0-9-]{1,64}"}')));
    await expect(createMemory({ name: "Bad", type: "user", description: "d", content: "c" })).rejects.toMatchObject({
      status: 400,
      message: "name must match [a-z0-9-]{1,64}",
    });
  });
});

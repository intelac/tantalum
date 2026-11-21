import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  attachmentUrl,
  getConfig,
  getContextUsage,
  getModels,
  getTracePage,
  listMessages,
  listProjects,
  listSessions,
  logout,
  renameSession,
  submit,
} from "../api/client";
import { connectEvents } from "../api/sse";
import type { SessionSummary } from "../api/types";
import { asViewed, setViewedUser, viewedUser } from "../api/viewing";
import { Sidebar, mainWindowId } from "./Sidebar";
import { ViewingBanner, ViewingBar } from "./ViewingBanner";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  setViewedUser(null);
});

function reply(status: number, body: string) {
  return { ok: status >= 200 && status < 300, status, text: () => Promise.resolve(body) };
}

/** qagent-align 需求 6：管理员只读查看别人的会话，页面这一边。 */
describe("viewing another person's sessions", () => {
  it("adds the person to the addresses that read their sessions, and to nothing else", async () => {
    const paths: string[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn((path: string) => {
        paths.push(path);
        return Promise.resolve(reply(200, "{}"));
      }),
    );
    expect(asViewed("/api/sessions")).toBe("/api/sessions");
    setViewedUser("alice b");
    await listSessions();
    await listProjects();
    await listMessages("s1", 40);
    await getTracePage("s1", { run: "r1" });
    await getContextUsage("s1");
    expect(paths).toEqual([
      "/api/sessions?viewAs=alice%20b",
      "/api/projects?viewAs=alice%20b",
      "/api/sessions/s1/messages?before=40&viewAs=alice%20b",
      "/api/sessions/s1/trace?run=r1&viewAs=alice%20b",
      "/api/sessions/s1/context?viewAs=alice%20b",
    ]);
    expect(attachmentUrl({ id: "a1", name: "shot.png" })).toBe("/api/attachments/a1?name=shot.png&viewAs=alice%20b");
    // 自己的东西照常是自己的
    paths.length = 0;
    await getConfig();
    await getModels();
    expect(paths).toEqual(["/api/config", "/api/models"]);
  });

  it("sends no write at all while viewing, except signing out", async () => {
    const fetchMock = vi.fn().mockResolvedValue(reply(200, "{}"));
    vi.stubGlobal("fetch", fetchMock);
    setViewedUser("alice");
    await expect(renameSession("s1", "mine now")).rejects.toMatchObject({ status: 403, code: "view/read-only" });
    await expect(submit("s1", "queue", "hi", "r")).rejects.toBeInstanceOf(ApiError);
    expect(fetchMock).not.toHaveBeenCalled();
    await logout();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0]?.[0]).toBe("/api/auth/logout");
  });

  it("goes back to one's own sessions when the server says viewing is no longer allowed", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(reply(403, '{"error":"view_forbidden","code":"view/forbidden"}')));
    setViewedUser("alice");
    await expect(listSessions()).rejects.toMatchObject({ code: "view/forbidden" });
    expect(viewedUser()).toBeNull();
  });

  it("subscribes to the events of the person viewed", async () => {
    const paths: string[] = [];
    const fetchMock = vi.fn((path: string, init?: RequestInit) => {
      paths.push(path);
      return new Promise<Response>((_, reject) => init?.signal?.addEventListener("abort", () => reject(new Error("aborted"))));
    });
    setViewedUser("alice");
    const stop = connectEvents({ onEvent: () => undefined, onConnection: () => undefined }, { fetch: fetchMock as unknown as typeof fetch });
    await new Promise((resolve) => setTimeout(resolve, 10));
    stop();
    expect(paths).toEqual(["/api/events?viewAs=alice"]);
  });

  it("shows a banner that says who and read-only, and leaves on request", async () => {
    render(<ViewingBanner />);
    expect(screen.queryByRole("status")).toBeNull();
    act(() => setViewedUser("alice"));
    expect(screen.getByRole("status").textContent).toContain("正在只读查看 alice 的会话");
    await userEvent.click(screen.getByRole("button", { name: "退出查看" }));
    expect(viewedUser()).toBeNull();
    expect(screen.queryByRole("status")).toBeNull();
  });

  it("puts a read-only note where the composer was", () => {
    setViewedUser("alice");
    render(<ViewingBar selected any />);
    expect(screen.getByRole("note").textContent).toBe("这是 alice 的会话，只能看，不能发消息、改标题或做任何改动。");
    cleanup();
    render(<ViewingBar selected={false} any />);
    expect(screen.getByRole("note").textContent).toBe("从左边选一个会话。");
    cleanup();
    render(<ViewingBar selected={false} any={false} />);
    expect(screen.getByRole("note").textContent).toBe("alice 还没有会话。");
  });

  it("the sidebar offers nothing that changes anything", () => {
    const sessions: SessionSummary[] = [{ id: "s1", title: "alice 的会话", lastActivity: new Date().toISOString(), runStatus: "idle" }];
    const props = {
      sessions,
      projects: [],
      loading: false,
      error: null,
      currentSession: "s-new",
      rail: false,
      onToggle: vi.fn(),
      onNewSession: vi.fn(),
      onNewSessionIn: vi.fn(),
      onAddProject: vi.fn(),
      onSelectSession: vi.fn(),
      onSettings: vi.fn(),
      onPlugins: vi.fn(),
      onLogout: vi.fn(),
      username: "root",
    };
    const wrap = (node: React.ReactNode) => (
      <QueryClientProvider client={new QueryClient()}>{node}</QueryClientProvider>
    );
    render(wrap(<Sidebar {...props} />));
    // 平时：能新建、有插件和设置、行上有菜单、新会话有一行占位
    expect(screen.getByRole("button", { name: "新会话" })).toBeTruthy();
    // 主窗口（dispatch 需求 1）：每人一个、永不结束，编号 main-用户名
    fireEvent.click(screen.getByRole("button", { name: "主窗口" }));
    expect(props.onSelectSession).toHaveBeenCalledWith("main-root");
    expect(mainWindowId("alice")).toBe("main-alice");
    expect(screen.getByRole("button", { name: "插件" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "设置" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "添加" })).toBeTruthy();
    expect(screen.getAllByRole("button", { name: /的操作$/ }).length).toBeGreaterThan(0);
    cleanup();

    setViewedUser("alice");
    render(wrap(<Sidebar {...props} />));
    expect(screen.getByText("alice 的会话")).toBeTruthy();
    for (const name of ["新会话", "插件", "设置", "添加"]) expect(screen.queryByRole("button", { name })).toBeNull();
    expect(screen.queryByRole("button", { name: /的操作$/ })).toBeNull();
    expect(screen.getByRole("button", { name: "退出登录" })).toBeTruthy();
    cleanup();
    render(wrap(<Sidebar {...props} rail />));
    for (const name of ["新会话", "插件", "设置"]) expect(screen.queryByRole("button", { name })).toBeNull();
  });
});

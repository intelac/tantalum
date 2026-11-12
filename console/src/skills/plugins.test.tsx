import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { LocalLink, LocalSkills, LocalStatus, LocalWiki } from "../api/types";
import { LocalWorkspaceCard } from "../plugins/LocalWorkspaceCard";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const ON = { "local-workspace": true, "fs-read": true, git: true, "file-edit": true, shell: true, skills: true, wiki: true, browser: true };

const LINK: LocalLink = {
  device: { name: "Yi's MacBook", os: "macos", osVersion: "26.2", arch: "arm64" },
  roots: [{ path: "/Users/yi/work" }],
  git: true,
  write: true,
  shell: { available: true },
  network: true,
  protocol: 4,
  current: true,
  connectedAt: new Date(Date.now() - 60_000).toISOString(),
  expiresAt: new Date(Date.now() + 42 * 60_000).toISOString(),
};

const SKILLS: LocalSkills = {
  dir: "~/work/skills",
  state: "ok",
  path: "/Users/yi/work/skills",
  items: [
    { name: "by-hand", description: "Only when asked.", model: false, user: true, enabled: true },
    { name: "pdf-tools", description: "Convert PDF files to text.", model: true, user: true, enabled: true },
  ],
  problems: [{ folder: "broken", code: "description-missing" }],
};

const WIKI: LocalWiki = { dir: "/Users/yi/work/wiki", state: "ok", path: "/Users/yi/work/wiki", pages: 300, total: 742, index: true, truncated: true };

/** 假后端：/api/local、/api/local/skills、/api/local/wiki 各回一份，PUT 记下来并按请求改。 */
function fakeBackend(initial: { status: LocalStatus; skills: LocalSkills; wiki: LocalWiki }) {
  const calls: { method: string; path: string; body?: string }[] = [];
  const state = { ...initial };
  let refuse: string | null = null;
  vi.stubGlobal(
    "fetch",
    vi.fn((input: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      const body = init?.body as string | undefined;
      calls.push({ method, path: input, body });
      const ok = (value: unknown) => Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(value)) });
      if (input === "/api/local") return ok(state.status);
      if (input === "/api/local/skills" && method === "PUT") {
        if (refuse !== null) {
          const error = { error: "dir-not-absolute", code: refuse };
          return Promise.resolve({ ok: false, status: 400, text: () => Promise.resolve(JSON.stringify(error)) });
        }
        state.skills = { ...state.skills, dir: (JSON.parse(body!) as { dir: string }).dir, state: "missing", items: [], problems: [], path: undefined };
        return ok(state.skills);
      }
      if (input === "/api/local/skills") return ok(state.skills);
      if (input === "/api/local/wiki" && method === "PUT") {
        state.wiki = { ...state.wiki, dir: (JSON.parse(body!) as { dir: string }).dir };
        return ok(state.wiki);
      }
      if (input === "/api/local/wiki") return ok(state.wiki);
      if (input.startsWith("/api/plugins/local/")) {
        const name = decodeURIComponent(input.split("/").pop()!);
        state.status = { ...state.status, switches: { ...state.status.switches, [name]: (JSON.parse(body!) as { enabled: boolean }).enabled } };
      }
      return ok({});
    }),
  );
  return {
    calls,
    set: (next: Partial<typeof initial>) => Object.assign(state, next),
    refuseDir: (code: string) => (refuse = code),
  };
}

function open() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>
      <LocalWorkspaceCard open />
    </QueryClientProvider>,
  );
}

const connected = (switches = ON): LocalStatus => ({ switches, link: LINK });

/** skills-wiki 任务 21（需求 2、6.1、8）：插件页“本机工作区”下面的“技能”和“知识库”。 */
describe("Skills and Knowledge base on the plugins page", () => {
  it("lists the skills of the directory, with a switch each and the broken ones greyed with a reason", async () => {
    const backend = fakeBackend({ status: connected(), skills: SKILLS, wiki: WIKI });
    open();
    const list = await screen.findByRole("list", { name: "技能" });
    expect(within(list).getByText("pdf-tools")).toBeTruthy();
    expect(within(list).getByText("Convert PDF files to text.")).toBeTruthy();
    expect(within(list).getByText("只能手动使用")).toBeTruthy();
    expect(within(list).getByText("broken")).toBeTruthy();
    expect(within(list).getByText("没有写描述")).toBeTruthy();
    expect(screen.getByText("找到 2 个技能")).toBeTruthy();
    expect(screen.getByText("电脑上的位置：/Users/yi/work/skills")).toBeTruthy();
    expect((screen.getByLabelText("技能目录") as HTMLInputElement).value).toBe("~/work/skills");

    // 关掉一个：先在界面上变，再发请求
    await userEvent.click(within(list).getByRole("switch", { name: "技能 pdf-tools" }));
    expect(within(list).getByRole("switch", { name: "技能 pdf-tools" }).getAttribute("aria-checked")).toBe("false");
    await waitFor(() =>
      expect(backend.calls).toContainEqual({ method: "PUT", path: "/api/local/skills/pdf-tools", body: JSON.stringify({ enabled: false }) }),
    );
    expect(within(list).queryByRole("switch", { name: /broken/ })).toBeNull();
  });

  it("saves the directory on Enter and shows what the computer says about it", async () => {
    const backend = fakeBackend({ status: connected(), skills: { ...SKILLS, dir: "", state: "unset", items: [], problems: [], path: undefined }, wiki: WIKI });
    open();
    const field = (await screen.findByLabelText("技能目录")) as HTMLInputElement;
    await waitFor(() => expect(field.disabled).toBe(false));
    expect(field.placeholder).toBe("~/.agents/skills");
    expect(field.value).toBe("");
    expect(screen.getByText("还没填目录")).toBeTruthy();
    await userEvent.type(field, " ~/later {Enter}");
    await waitFor(() => expect(backend.calls).toContainEqual({ method: "PUT", path: "/api/local/skills", body: JSON.stringify({ dir: "~/later" }) }));
    expect(await screen.findByText("目录还不存在")).toBeTruthy();
    expect(screen.queryByRole("list", { name: "技能" })).toBeNull();
  });

  it("says why a directory was not saved and keeps what was typed", async () => {
    const backend = fakeBackend({ status: connected(), skills: SKILLS, wiki: WIKI });
    backend.refuseDir("local/dir-invalid");
    open();
    const field = (await screen.findByLabelText("技能目录")) as HTMLInputElement;
    await waitFor(() => expect(field.value).toBe("~/work/skills"));
    await userEvent.clear(field);
    await userEvent.type(field, "skills");
    await userEvent.click(screen.getAllByRole("button", { name: "保存" })[0]!);
    expect((await screen.findByRole("alert")).textContent).toContain("没有保存：目录要写电脑上的绝对路径");
    expect(field.value).toBe("skills");
  });

  it.each([
    ["offline", "电脑没连上，连上后才能读这个目录"],
    ["old", "连接程序需要更新"],
    ["late", "电脑没有按时回应"],
    ["outside", "这个目录不在电脑允许的范围里，启动连接程序时用 --allow 加上它"],
    ["unreadable", "这个目录读不出来"],
  ] as const)("explains the %s state", async (state, words) => {
    fakeBackend({ status: connected(), skills: { ...SKILLS, state, items: [], problems: [], path: undefined }, wiki: { ...WIKI, state: "ok" } });
    open();
    expect(await screen.findByText(new RegExp(words))).toBeTruthy();
    expect(screen.queryByRole("list", { name: "技能" })).toBeNull();
  });

  it("shows how many wiki pages there are, that the list is cut, and whether there is an index", async () => {
    const backend = fakeBackend({ status: connected(), skills: SKILLS, wiki: WIKI });
    open();
    expect(await screen.findByText("共 742 页，只列出前 300 页 · 有 index.md")).toBeTruthy();
    backend.set({ wiki: { ...WIKI, pages: 2, total: 2, index: false, truncated: false } });
    const refresh = screen.getAllByRole("button", { name: "刷新" });
    await userEvent.click(refresh[refresh.length - 1]!);
    expect(await screen.findByText("共 2 页 · 没有 index.md")).toBeTruthy();
  });

  it("asks the computer nothing while a switch is off, and greys both items with the master switch", async () => {
    const backend = fakeBackend({ status: connected({ ...ON, skills: false, "local-workspace": true }), skills: SKILLS, wiki: WIKI });
    open();
    await screen.findByText(/共 742 页/);
    expect(screen.queryByLabelText("技能目录")).toBeNull();
    expect(backend.calls.some((call) => call.path === "/api/local/skills")).toBe(false);

    cleanup();
    const off = fakeBackend({ status: connected({ ...ON, "local-workspace": false }), skills: SKILLS, wiki: WIKI });
    open();
    const master = await screen.findByRole("switch", { name: "本机工作区" });
    expect(master.getAttribute("aria-checked")).toBe("false");
    expect((screen.getByRole("switch", { name: "技能" }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("switch", { name: "知识库" }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByLabelText("技能目录") as HTMLInputElement).disabled).toBe(true);
    expect(off.calls.some((call) => call.path.startsWith("/api/local/skills") || call.path.startsWith("/api/local/wiki"))).toBe(false);
  });

  it("asks again when the computer connects", async () => {
    const backend = fakeBackend({ status: { switches: ON, link: null }, skills: { ...SKILLS, state: "offline", items: [], problems: [], path: undefined }, wiki: WIKI });
    open();
    expect(await screen.findByText("电脑没连上，连上后才能读这个目录")).toBeTruthy();
    backend.set({ status: connected(), skills: SKILLS });
    expect(await screen.findByRole("list", { name: "技能" }, { timeout: 4000 })).toBeTruthy();
  });
});

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { LocalLink, LocalStatus } from "../api/types";
import { LocalWorkspaceCard } from "./LocalWorkspaceCard";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const ALL_ON = { "local-workspace": true, "fs-read": true, git: true, "file-edit": true, shell: true };

function link(extra: Partial<LocalLink> = {}): LocalLink {
  return {
    device: { name: "Yi's MacBook", os: "macos", osVersion: "26.2", arch: "arm64" },
    roots: [{ path: "/Users/yi/work/shop" }],
    git: true,
    write: true,
    shell: { available: true },
    network: true,
    connectedAt: new Date(Date.now() - 60_000).toISOString(),
    expiresAt: new Date(Date.now() + 42 * 60_000).toISOString(),
    ...extra,
  };
}

/** 假后端：GET /api/local 回 {@code status}，PUT 改开关，POST 发令牌，DELETE 撤销；记下每次调用。 */
function fakeBackend(initial: LocalStatus) {
  const calls: { method: string; path: string; body?: string }[] = [];
  let status = initial;
  vi.stubGlobal(
    "fetch",
    vi.fn((input: string, init?: RequestInit) => {
      const method = init?.method ?? "GET";
      calls.push({ method, path: input, body: init?.body as string | undefined });
      const ok = (body: unknown) => Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(body)) });
      if (method === "GET") return ok(status);
      if (method === "PUT") {
        const name = decodeURIComponent(input.split("/").pop()!);
        const enabled = (JSON.parse(init?.body as string) as { enabled: boolean }).enabled;
        status = { ...status, switches: { ...status.switches, [name]: enabled } };
        return ok({});
      }
      if (method === "POST") {
        return ok({
          token: "tc_secret-token",
          command: "TANTALUM_TOKEN=tc_secret-token tantalum-connect --server http://127.0.0.1:8091 --allow .",
          expiresAt: new Date(Date.now() + 3_600_000).toISOString(),
        });
      }
      status = { ...status, link: null };
      return ok({ revoked: true });
    }),
  );
  return calls;
}

function open() {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>
      <LocalWorkspaceCard open />
    </QueryClientProvider>,
  );
}

async function card() {
  return screen.findByRole("region", { name: "本机工作区" });
}

/** local-workspace 需求 6、L17：插件页的本机工作区卡片。 */
describe("LocalWorkspaceCard", () => {
  it("says how to connect when no computer is connected", async () => {
    fakeBackend({ switches: ALL_ON, link: null });
    open();
    const region = await card();
    expect(within(region).getByText(/没有连上电脑/)).toBeTruthy();
    for (const name of ["本机工作区", "读文件", "Git 查询", "改文件", "命令行"]) {
      expect(within(region).getByRole("switch", { name }).getAttribute("aria-checked")).toBe("true");
    }
    expect(within(region).queryByText(/连接程序没有允许/)).toBeNull();
    expect(within(region).queryByRole("button", { name: "撤销" })).toBeNull();
  });

  it("shows the connected computer, its directories, what it allows and the countdown", async () => {
    fakeBackend({ switches: ALL_ON, link: link({ shell: { available: false, reason: "--allow-shell was not given" }, write: false }) });
    open();
    const region = await card();
    const state = await within(region).findByRole("status");
    expect(state.textContent).toContain("已连接：Yi's MacBook（macOS 26.2）");
    expect(state.textContent).toContain("允许的目录：/Users/yi/work/shop");
    expect(state.textContent).toContain("不能改文件 · 不能跑命令");
    expect(state.textContent).toMatch(/到期，还有 4[12] 分钟/);
    // 子开关开着、连接程序没允许：写明
    expect(within(region).getByText("连接程序没有允许：启动时加 --allow-write 才能改文件")).toBeTruthy();
    expect(within(region).getByText("连接程序没有允许：启动时加 --allow-shell 才能跑命令")).toBeTruthy();
    expect(within(region).getByRole("button", { name: "撤销" })).toBeTruthy();
  });

  /** B1：Git 查询不再要求主目录是仓库，只在电脑上没有 git 时说明。 */
  it("only explains Git when the computer has no git", async () => {
    fakeBackend({ switches: ALL_ON, link: link() });
    open();
    const region = await card();
    await within(region).findByRole("status");
    expect(within(region).queryByText(/Git 仓库/)).toBeNull();
    expect(within(region).queryByText(/没有装 git/)).toBeNull();
    cleanup();
    fakeBackend({ switches: ALL_ON, link: link({ git: false }) });
    open();
    expect(await within(await card()).findByText("这台电脑上没有装 git，Git 查询用不了")).toBeTruthy();
  });

  it("explains a computer that cannot sandbox commands, and stays quiet once the switch is off", async () => {
    const calls = fakeBackend({ switches: ALL_ON, link: link({ shell: { available: false, reason: "mxc-exec-mac was not found" } }) });
    open();
    const region = await card();
    expect(await within(region).findByText("这台电脑不支持隔离执行命令：mxc-exec-mac was not found")).toBeTruthy();
    await userEvent.click(within(region).getByRole("switch", { name: "命令行" }));
    await waitFor(() => expect(within(region).queryByText(/不支持隔离执行命令/)).toBeNull());
    expect(calls.some((call) => call.method === "PUT" && call.path === "/api/plugins/local/shell" && call.body === '{"enabled":false}')).toBe(true);
  });

  // console-i18n I12：新版连接程序送代码和参数，按看的人的语言说；上面那条只有 reason 的是老版本连接程序，照原样显示。
  it("says the sandbox reason from its code, keeping the MXC output as written", async () => {
    const shell = { available: false, reason: "MXC could not start the sandbox: denied", code: "sandbox/start-failed", params: { detail: "denied" } };
    fakeBackend({ switches: ALL_ON, link: link({ shell }) });
    open();
    expect(await within(await card()).findByText("这台电脑不支持隔离执行命令：MXC 不能启动沙箱：denied")).toBeTruthy();
  });

  it("says when the link has expired", async () => {
    fakeBackend({ switches: ALL_ON, link: link({ expiresAt: new Date(Date.now() - 1000).toISOString() }) });
    open();
    const region = await card();
    expect((await within(region).findByRole("status")).textContent).toContain("已到期，连接马上断开");
  });

  it("turning the master switch off disables the child switches", async () => {
    fakeBackend({ switches: { ...ALL_ON, "local-workspace": false }, link: link() });
    open();
    const region = await card();
    expect(within(region).getByRole("switch", { name: "改文件" }).hasAttribute("disabled")).toBe(true);
    expect(within(region).getByRole("switch", { name: "本机工作区" }).hasAttribute("disabled")).toBe(false);
  });

  /** L2：选有效期生成令牌，令牌和命令只显示这一次；撤销后断开。 */
  it("issues a token with the chosen validity, shows the command once and can revoke it", async () => {
    const calls = fakeBackend({ switches: ALL_ON, link: null });
    open();
    const region = await card();
    await userEvent.click(within(region).getByRole("radio", { name: "4 小时" }));
    await userEvent.click(within(region).getByRole("button", { name: "连接这台电脑" }));
    const command = await within(region).findByText(/^TANTALUM_TOKEN=tc_secret-token tantalum-connect/);
    expect(command.textContent).toContain("--server http://127.0.0.1:8091");
    expect(within(region).getByText(/令牌只显示这一次/)).toBeTruthy();
    // B2：运行前先装好连接程序、先 cd 到项目目录；--allow . 是当前目录
    const steps = within(region).getByRole("list", { name: "怎么运行" }).textContent ?? "";
    expect(steps).toContain("先装好连接程序 tantalum-connect");
    expect(steps).toContain("README“本机工作区 › 安装”");
    expect(steps).toContain("先 cd 到要交给 agent 的项目目录");
    expect(steps).toContain("--allow . 的 . 就是当前目录");
    expect(calls.find((call) => call.method === "POST")?.body).toBe('{"validity":"4h"}');

    await userEvent.click(within(region).getByRole("button", { name: "撤销" }));
    await waitFor(() => expect(within(region).queryByText(/tc_secret-token/)).toBeNull());
    expect(calls.some((call) => call.method === "DELETE" && call.path === "/api/local/tokens")).toBe(true);
  });

  it("polls the status every 2 seconds while open", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const calls = fakeBackend({ switches: ALL_ON, link: null });
    open();
    await card();
    const before = calls.filter((call) => call.method === "GET").length;
    await vi.advanceTimersByTimeAsync(4100);
    expect(calls.filter((call) => call.method === "GET").length).toBeGreaterThanOrEqual(before + 2);
  });
});

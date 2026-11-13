import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { LocalLink, LocalStatus } from "../api/types";
import { LocalWorkspaceCard, runCommand } from "./LocalWorkspaceCard";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

const ALL_ON = { "local-workspace": true, "fs-read": true, git: true, "file-edit": true, shell: true, skills: false, wiki: false, browser: true, "local-mcp": true };

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
      if (method === "PUT" || (method === "DELETE" && input.startsWith("/api/plugins/local/"))) {
        const name = decodeURIComponent(input.split("/").pop()!);
        // 恢复默认（DELETE）就是回到勾着
        const enabled = method === "DELETE" ? true : (JSON.parse(init?.body as string) as { enabled: boolean }).enabled;
        if (name in status.switches) status = { ...status, switches: { ...status.switches, [name]: enabled } };
        else if (status.tools !== undefined) {
          const tools = Object.fromEntries(Object.entries(status.tools).map(([child, list]) => [child, list.map((tool) => (tool.name === name ? { ...tool, enabled } : tool))]));
          status = { ...status, tools };
        }
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
  it("says when the connected connector is too old", async () => {
    fakeBackend({ switches: ALL_ON, link: link({ protocol: 1, current: false }) });
    open();
    const state = await within(await card()).findByRole("status");
    expect(state.textContent).toContain("连接程序太旧");
    cleanup();
    fakeBackend({ switches: ALL_ON, link: link() });
    open();
    expect((await within(await card()).findByRole("status")).textContent).not.toContain("连接程序太旧");
  });

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

  it("explains a computer that cannot run commands, and stays quiet once the switch is off", async () => {
    const calls = fakeBackend({ switches: ALL_ON, link: link({ shell: { available: false, reason: "mxc-exec-mac was not found" } }) });
    open();
    const region = await card();
    expect(await within(region).findByText("这台电脑上不能跑命令：mxc-exec-mac was not found")).toBeTruthy();
    await userEvent.click(within(region).getByRole("switch", { name: "命令行" }));
    await waitFor(() => expect(within(region).queryByText(/这台电脑上不能跑命令/)).toBeNull());
    expect(calls.some((call) => call.method === "PUT" && call.path === "/api/plugins/local/shell" && call.body === '{"enabled":false}')).toBe(true);
  });

  // console-i18n I12：新版连接程序送代码和参数，按看的人的语言说；上面那条只有 reason 的是老版本连接程序，照原样显示。
  it("says the shell reason from its code", async () => {
    const shell = { available: false, reason: "cannot find bash on this computer", code: "shell/not-found", params: { shell: "bash" } };
    fakeBackend({ switches: ALL_ON, link: link({ shell }) });
    open();
    expect(await within(await card()).findByText("这台电脑上不能跑命令：这台电脑上找不到 bash")).toBeTruthy();
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
    expect(steps).toContain("在终端里先 cd 到放 tantalum-connect.jar 的目录");
    expect(steps).toContain("--allow . 的 . 是当前目录");
    expect(steps).toContain("--allow-shell（默认每条先问你）");
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

  /** local-browser 需求 7.1、7.3：“浏览器”一项在本机工作区下面，写明现在能不能用、为什么。 */
  describe("the browser item", () => {
    async function browserRow() {
      const region = await card();
      const toggle = within(region).getByRole("switch", { name: "浏览器" });
      return { region, toggle, row: toggle.closest("li")! };
    }

    it("says which browser is used and that it has a window when the computer allows it", async () => {
      fakeBackend({ switches: ALL_ON, link: link({ protocol: 5, current: true, browser: { available: true, name: "Google Chrome", headless: false } }) });
      open();
      const { row, toggle } = await browserRow();
      expect(toggle.getAttribute("aria-checked")).toBe("true");
      expect(within(row).getByText("可用：Google Chrome，有窗口（用到时才打开）")).toBeTruthy();
      expect(within(row).getByText(/和你平时的浏览器分开/)).toBeTruthy();
      expect(within(row).getByText(/页面内容会送到服务器和你选的模型/)).toBeTruthy();
    });

    it("says headless when the connector hides the window", async () => {
      fakeBackend({ switches: ALL_ON, link: link({ protocol: 5, current: true, browser: { available: true, name: "Microsoft Edge", headless: true } }) });
      open();
      const { row } = await browserRow();
      expect(within(row).getByText("可用：Microsoft Edge，不显示窗口")).toBeTruthy();
    });

    it("explains a connector that was started without --allow-browser", async () => {
      fakeBackend({ switches: ALL_ON, link: link({ protocol: 5, current: true, browser: { available: false, code: "browser/not-allowed" } }) });
      open();
      const { row } = await browserRow();
      expect(within(row).getByText("连接程序没有允许：启动时加 --allow-browser 才能用浏览器")).toBeTruthy();
      expect(within(row).queryByText(/^可用：/)).toBeNull();
    });

    it("explains a computer without Chrome or Edge", async () => {
      fakeBackend({ switches: ALL_ON, link: link({ protocol: 5, current: true, browser: { available: false, code: "browser/not-found" } }) });
      open();
      const { row } = await browserRow();
      expect(within(row).getByText(/没有找到 Chrome 或 Edge：装一个，或者启动连接程序时用 --browser-path/)).toBeTruthy();
    });

    it("asks to update a connector that is too old for the browser", async () => {
      fakeBackend({ switches: ALL_ON, link: link({ protocol: 4, current: false }) });
      open();
      const { row } = await browserRow();
      expect(within(row).getByText(/连接程序需要更新才能用浏览器/)).toBeTruthy();
    });

    it("shows no reason while no computer is connected or the switch is off, and sends the switch change", async () => {
      const calls = fakeBackend({ switches: ALL_ON, link: null });
      open();
      const { row, toggle } = await browserRow();
      expect(within(row).queryByText(/连接程序|可用：|没有找到/)).toBeNull();
      await userEvent.click(toggle);
      await waitFor(() => expect(calls.some((call) => call.method === "PUT" && call.path.endsWith("/browser"))).toBe(true));
      expect(calls.find((call) => call.method === "PUT")!.body).toBe(JSON.stringify({ enabled: false }));
    });

    it("tells in the connect steps to add --allow-browser", async () => {
      fakeBackend({ switches: ALL_ON, link: null });
      open();
      const region = await card();
      await userEvent.click(within(region).getByRole("button", { name: "连接这台电脑" }));
      expect(await within(region).findByText(/要用浏览器加 --allow-browser/)).toBeTruthy();
    });
  });

  /** qagent-align 需求 2：浏览器下面的工具能逐个勾。 */
  describe("the browser's tools", () => {
    const NAMES = ["browser_navigate", "browser_snapshot", "browser_click", "browser_evaluate"];
    const tools = (off: string[] = []) => ({ browser: NAMES.map((name) => ({ name, enabled: !off.includes(name) })) });

    async function browserRow() {
      const region = await card();
      const toggle = within(region).getByRole("switch", { name: "浏览器" });
      return { region, row: toggle.closest("li")! };
    }

    it("lists every tool with a line of explanation, all ticked by default, and nothing says changed", async () => {
      fakeBackend({ switches: ALL_ON, tools: tools(), link: null });
      open();
      const { row } = await browserRow();
      expect(within(row).queryByRole("list", { name: "浏览器的工具" })).toBeNull();
      await userEvent.click(within(row).getByRole("button", { name: "已选 4 / 4 个工具" }));
      const list = within(row).getByRole("list", { name: "浏览器的工具" });
      expect(within(list).getAllByRole("checkbox").map((box) => box.getAttribute("aria-label"))).toEqual(NAMES);
      expect(within(list).getAllByRole("checkbox").every((box) => (box as HTMLInputElement).checked)).toBe(true);
      expect(within(list).getByText("在页面里执行脚本")).toBeTruthy();
      expect(within(row).queryByText("我改的")).toBeNull();
      await userEvent.click(within(row).getByRole("button", { name: "收起" }));
      expect(within(row).queryByRole("list", { name: "浏览器的工具" })).toBeNull();
    });

    it("unticking a tool saves it, counts it, and offers to restore the default", async () => {
      const calls = fakeBackend({ switches: ALL_ON, tools: tools(), link: null });
      open();
      const { row } = await browserRow();
      await userEvent.click(within(row).getByRole("button", { name: "已选 4 / 4 个工具" }));
      await userEvent.click(within(row).getByRole("checkbox", { name: "browser_evaluate" }));
      await waitFor(() => expect(calls.some((call) => call.method === "PUT" && call.path === "/api/plugins/local/browser_evaluate")).toBe(true));
      expect(calls.find((call) => call.method === "PUT")!.body).toBe(JSON.stringify({ enabled: false }));
      expect(await within(row).findByText("我改的")).toBeTruthy();
      expect((within(row).getByRole("checkbox", { name: "browser_evaluate" }) as HTMLInputElement).checked).toBe(false);

      await userEvent.click(within(row).getByRole("button", { name: "浏览器 恢复默认" }));
      await waitFor(() => expect(calls.some((call) => call.method === "DELETE" && call.path === "/api/plugins/local/browser_evaluate")).toBe(true));
      await waitFor(() => expect(within(row).queryByText("我改的")).toBeNull());
      expect((within(row).getByRole("checkbox", { name: "browser_evaluate" }) as HTMLInputElement).checked).toBe(true);
    });

    it("shows how many are picked when it opens with some unticked", async () => {
      fakeBackend({ switches: ALL_ON, tools: tools(["browser_evaluate", "browser_click"]), link: null });
      open();
      const { row } = await browserRow();
      expect(within(row).getByRole("button", { name: "已选 2 / 4 个工具" })).toBeTruthy();
      expect(within(row).getByText("我改的")).toBeTruthy();
    });

    it("cannot be picked while the browser or the whole workspace is off", async () => {
      fakeBackend({ switches: { ...ALL_ON, browser: false }, tools: tools(["browser_evaluate"]), link: null });
      open();
      const { row } = await browserRow();
      expect(within(row).queryByRole("button", { name: /个工具/ })).toBeNull();
      expect(within(row).queryByText("我改的")).toBeNull();
      cleanup();
      fakeBackend({ switches: { ...ALL_ON, "local-workspace": false }, tools: tools(), link: null });
      open();
      expect(within((await browserRow()).row).queryByRole("button", { name: /个工具/ })).toBeNull();
    });

    it("can be picked whatever the computer says about the browser, and the reason stays", async () => {
      fakeBackend({ switches: ALL_ON, tools: tools(), link: link({ protocol: 5, current: true, browser: { available: false, code: "browser/not-allowed" } }) });
      open();
      const { row } = await browserRow();
      expect(within(row).getByText(/启动时加 --allow-browser/)).toBeTruthy();
      await userEvent.click(within(row).getByRole("button", { name: "已选 4 / 4 个工具" }));
      expect((within(row).getByRole("checkbox", { name: "browser_click" }) as HTMLInputElement).disabled).toBe(false);
    });

    it("a tool the dictionary does not know is listed by its name alone; other switches have no tools", async () => {
      fakeBackend({ switches: ALL_ON, tools: { browser: [{ name: "browser_new_thing", enabled: true }] }, link: null });
      open();
      const { region, row } = await browserRow();
      expect(within(region).getAllByRole("button", { name: /个工具/ }).length).toBe(1);
      await userEvent.click(within(row).getByRole("button", { name: "已选 1 / 1 个工具" }));
      expect(within(row).getByRole("checkbox", { name: "browser_new_thing" })).toBeTruthy();
    });

    it("an older server that sends no tools shows the browser as before", async () => {
      fakeBackend({ switches: ALL_ON, link: null });
      open();
      expect(within((await browserRow()).row).queryByRole("button", { name: /个工具/ })).toBeNull();
    });
  });

  // connector-jar 需求 2、3、5.5、6.7、7.2：跨平台的 jar、三步、执行前先问我、本机 MCP。
  describe("connector-jar", () => {
    it("shows the three steps with the download link and its version", async () => {
      fakeBackend({ switches: ALL_ON, link: null, connector: { available: true, version: "0.1.0", sha256: "a".repeat(64), size: 1 } });
      open();
      const region = await card();
      const download = await within(region).findByRole("link", { name: "下载连接程序 tantalum-connect.jar" });
      expect(download.getAttribute("href")).toBe("/api/local/connector.jar");
      expect(within(region).getByText("0.1.0")).toBeTruthy();
      expect(within(region).getByText(/需要电脑上有 Java 25/)).toBeTruthy();
      expect(within(region).getByText("生成令牌")).toBeTruthy();
    });

    it("says when the server has no connector to offer", async () => {
      fakeBackend({ switches: ALL_ON, link: null, connector: { available: false } });
      open();
      expect(await within(await card()).findByText(/服务器上没有放连接程序/)).toBeTruthy();
    });

    it("writes the command for the system looking at the page", () => {
      expect(runCommand("tc_x", "https://t.example", false)).toBe("TANTALUM_TOKEN=tc_x java -jar tantalum-connect.jar --server https://t.example --allow .");
      expect(runCommand("tc_x", "https://t.example", true)).toBe(
        '$env:TANTALUM_TOKEN="tc_x"; java -jar tantalum-connect.jar --server https://t.example --allow .',
      );
    });

    it("warns when the computer runs another build of the connector", async () => {
      fakeBackend({ switches: ALL_ON, link: link({ protocol: 6, current: true, connectorMatches: false }) });
      open();
      const alert = await within(await card()).findByRole("alert");
      expect(alert.textContent).toContain("这台电脑上的连接程序不是这里提供的版本");
    });

    it("shows the shell, and turns asking before each command off and on", async () => {
      const calls = fakeBackend({
        switches: ALL_ON,
        commandApproval: true,
        link: link({ protocol: 6, current: true, shell: { available: true, kind: "powershell", path: "C:\\Program Files\\PowerShell\\7\\pwsh.exe" } }),
      });
      open();
      const region = await card();
      expect(await within(region).findByText("PowerShell：C:\\Program Files\\PowerShell\\7\\pwsh.exe")).toBeTruthy();
      await userEvent.click(within(region).getByRole("switch", { name: "执行前先问我" }));
      await waitFor(() => expect(calls.some((call) => call.method === "PUT" && call.path === "/api/plugins/plugin/commandApproval")).toBe(true));
      expect(calls.find((call) => call.path === "/api/plugins/plugin/commandApproval")?.body).toBe('{"enabled":false}');
    });

    it("lists the local MCP servers with their tools and a switch each", async () => {
      const calls = fakeBackend({
        switches: ALL_ON,
        link: link({
          protocol: 6,
          current: true,
          mcp: [
            { name: "sqlite", state: "ready", enabled: true, tools: [{ name: "query", description: "Run SQL" }] },
            { name: "broken", state: "failed", reason: "cannot start: spawn uvx ENOENT", enabled: true, tools: [] },
          ],
        }),
      });
      open();
      const region = await card();
      const list = await within(region).findByRole("list", { name: "本机 MCP" });
      expect(within(list).getByText("可用")).toBeTruthy();
      expect(within(list).getByText("cannot start: spawn uvx ENOENT")).toBeTruthy();
      await userEvent.click(within(list).getByRole("button", { name: "1 个工具" }));
      expect(within(list).getByText("query")).toBeTruthy();
      await userEvent.click(within(list).getByRole("switch", { name: "本机 MCP 服务器 sqlite" }));
      await waitFor(() => expect(calls.some((call) => call.method === "PUT" && call.path === "/api/plugins/local/local-mcp%3Asqlite")).toBe(true));
    });

    it("says what to do when there are no servers, an old connector, or no Node.js for the browser", async () => {
      fakeBackend({ switches: ALL_ON, link: link({ protocol: 6, current: true, mcp: [], browser: { available: false, code: "browser/node-missing" } }) });
      open();
      const region = await card();
      expect(await within(region).findByText(/电脑上没有配置本机 MCP 服务器/)).toBeTruthy();
      expect(within(region).getByText(/这台电脑上没有找到 Node.js/)).toBeTruthy();
      cleanup();
      fakeBackend({ switches: ALL_ON, link: link({ protocol: 5, current: false }) });
      open();
      expect(await within(await card()).findByText(/连接程序需要更新才能用本机 MCP/)).toBeTruthy();
    });
  });
});

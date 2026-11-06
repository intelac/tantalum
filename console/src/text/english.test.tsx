// 用英文渲染主要页面，画面上不能有中日文字符（console-i18n I6 第 ④ 层，需求 2.5）。
// 抓第 ③ 层的源码扫描漏掉的，以及在模块顶层取了字典、切换语言后不跟着变的地方。测试数据里用户和模型写的内容都是英文。

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactElement } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import itemsJsonl from "../../../tantalum-shell/src/test/resources/wire/items.jsonl?raw";
import pluginsJson from "../../../tantalum-shell/src/test/resources/wire/plugins.json?raw";
import traceJson from "../../../tantalum-shell/src/test/resources/wire/trace.json?raw";
import type { Item, Plugins, TracePage } from "../api/types";
import { LoginPage } from "../auth/LoginPage";
import { ConversationPage } from "../conversation/ConversationPage";
import { PluginsDialog } from "../plugins/PluginsDialog";
import { SettingsDialog } from "../settings/SettingsDialog";
import { Sidebar } from "../shell/Sidebar";
import { StatsPage } from "../stats/StatsPage";
import { ConversationStore } from "../store/ConversationStore";
import { TracePage as TraceView } from "../trace/TracePage";
import { TraceStore } from "../trace/TraceStore";
import { setLanguage } from "./index";
import { NAMES } from "./language";

const CJK = /[぀-ヿ㐀-鿿＀-￯]/;
const items = itemsJsonl.trim().split("\n").map((line) => JSON.parse(line) as Item);
const trace = JSON.parse(traceJson) as TracePage;
// 带一个三种语言写了说明的钩子插件和一个 MCP 服务器（说明是 MCP 服务器写的英文）
const plugins: Plugins = {
  ...(JSON.parse(pluginsJson) as Plugins),
  mcp: [{ name: "docs", enabled: true, state: "FAILED", tools: [{ name: "mcp__docs__search", description: "Search the docs", enabled: true }] }],
  plugins: [{ name: "dailyQuota", description: { en: "Daily token limit", zh: "每天的上限", ja: "1 日の上限" }, points: ["beforeModelCall", "work:quota"], mandatory: true, enabled: true }],
};

function backend() {
  vi.stubGlobal(
    "fetch",
    vi.fn((path: string) => {
      const ok = (body: unknown) => Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(body)) });
      if (path.startsWith("/api/sessions/s/messages")) return ok({ seq: 99, items, start: 0, hasMore: false });
      if (path.startsWith("/api/sessions/s/trace")) return ok(trace);
      if (path === "/api/plugins") return ok(plugins);
      if (path === "/api/local") return ok({ switches: { "local-workspace": true, "fs-read": true, git: true, "file-edit": true, shell: true }, link: null });
      if (path === "/api/settings") return ok({ compaction: { percent: 85, points: [{ percent: 50, tokens: 100000 }] }, language: "en" });
      if (path === "/api/auth/me") return ok({ username: "yi", language: "en" });
      if (path === "/api/memory") return ok({ entries: [{ name: "style", type: "feedback", description: "Prefers tabs", updatedAt: "2026-11-01T00:00:00Z" }], used: 1, max: 200 });
      if (path === "/api/models") return ok({ routes: [], default: null, mine: null, percent: 85 });
      return Promise.resolve({ ok: false, status: 404, text: () => Promise.resolve("{}") });
    }),
  );
}

function shown(ui: ReactElement) {
  render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{ui}</QueryClientProvider>);
}

/** 画面上的中日文字符，连同它所在的那段文字。 */
function cjkOnScreen(): string[] {
  const texts = [document.body.textContent ?? "", ...[...document.body.querySelectorAll("[aria-label],[title],[placeholder]")].flatMap((node) =>
    ["aria-label", "title", "placeholder"].map((name) => node.getAttribute(name) ?? ""),
  )];
  return texts.flatMap((text) => text.split(/(?<=[.!?\n])/)).filter((part) => CJK.test(part));
}

beforeEach(() => {
  setLanguage("en");
  backend();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("English everywhere", () => {
  it("login page", () => {
    shown(<LoginPage onLoggedIn={() => undefined} />);
    expect(cjkOnScreen()).toEqual([]);
  });

  it("sidebar", () => {
    const sessions = [{ id: "s", title: "Fix the build", lastActivity: "2026-11-06T09:00:00Z", runStatus: "idle" }];
    shown(
      <Sidebar sessions={sessions} loading={false} error={null} currentSession="s" rail={false} onToggle={() => undefined} onNewSession={() => undefined}
        onSelectSession={() => undefined} onSettings={() => undefined} onPlugins={() => undefined} onLogout={() => undefined} username="yi" />,
    );
    expect(cjkOnScreen()).toEqual([]);
  });

  it("conversation with a failed run, a tool call and a compaction", async () => {
    shown(<ConversationPage store={new ConversationStore()} session="s" />);
    expect(await screen.findAllByText(/q1/)).not.toHaveLength(0);
    for (const button of screen.queryAllByRole("button", { expanded: false })) await userEvent.click(button);
    expect(cjkOnScreen()).toEqual([]);
  });

  it("trajectory and stats", async () => {
    const store = new TraceStore(() => new Promise<TracePage>(() => {}), (flush) => flush());
    shown(<TraceView store={store} session="s" />);
    await screen.findByRole("toolbar");
    expect(cjkOnScreen()).toEqual([]);
    cleanup();
    shown(<StatsPage store={store} session="s" />);
    await screen.findByRole("table");
    expect(cjkOnScreen()).toEqual([]);
  });

  it("plugins page", async () => {
    shown(<PluginsDialog open onClose={() => undefined} />);
    await screen.findByText("Daily token limit");
    expect(cjkOnScreen()).toEqual([]);
  });

  it("every settings pane", async () => {
    shown(<SettingsDialog open onClose={() => undefined} config={undefined} username="yi" onLogout={() => undefined} />);
    const nav = await screen.findByRole("navigation", { name: "Settings" });
    for (const pane of [...nav.querySelectorAll("button")]) {
      await act(async () => {
        await userEvent.click(pane);
      });
      // 语言一栏里的“中文”“日本語”是各语言自己的名字，照设计就是这样写（需求“可用性”）
      const found = cjkOnScreen().filter((text) => CJK.test(Object.values(NAMES).reduce((rest, name) => rest.replaceAll(name, ""), text)));
      expect(found, pane.textContent ?? "").toEqual([]);
    }
  });
});

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Config, ModelsResponse, UserSettingsView } from "../api/types";
import { MODELS } from "../composer/fixtures";
import { StatusBar } from "../composer/StatusBar";
import { ModelPane } from "./ModelPane";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const CONFIG: Config = { provider: "claude", model: "claude-sonnet-5-5", cloud: true, contextWindow: 1_000_000, windowSource: "model" };

const POINTS = [
  { percent: 50, tokens: 500_000 },
  { percent: 70, tokens: 700_000 },
  { percent: 85, tokens: 850_000 },
  { percent: 100, tokens: 936_000 },
];

/** 假后端：设置接口有状态（PUT 成功就记下），`putStatus` 不是 200 时 PUT 失败；`down` 之后 GET 也失败。另答状态栏要的轮次。 */
function fakeBackend(start: UserSettingsView, putStatus = 200, input = 0, models?: ModelsResponse) {
  let current = start;
  let down = false;
  const puts: string[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn((path: string, init?: RequestInit) => {
      const ok = (body: unknown) => Promise.resolve({ ok: true, status: 200, text: () => Promise.resolve(JSON.stringify(body)) });
      const fail = (status: number) => Promise.resolve({ ok: false, status, text: () => Promise.resolve('{"error":"no"}') });
      if (path === "/api/settings/compaction") {
        puts.push(init?.body as string);
        if (putStatus === 502) down = true;
        if (putStatus !== 200) return fail(putStatus);
        current = { compaction: { ...current.compaction, percent: (JSON.parse(init?.body as string) as { percent: number }).percent } };
        return ok(current);
      }
      if (path === "/api/settings") return down ? fail(502) : ok(current);
      if (path === "/api/models") return models === undefined ? fail(502) : ok(models);
      if (path.endsWith("/turns")) return ok({ turns: [{ number: 1 }] });
      if (path.includes("/turns/")) return ok({ reply: { usage: { input, output: 10, cacheRead: 0, cacheWrite: 0 } } });
      return fail(404);
    }),
  );
  return puts;
}

function wrap(ui: React.ReactElement) {
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>{ui}</QueryClientProvider>,
  );
}

describe("compaction point", () => {
  /** model-picker 需求 8.1：按路线列出模型，每个写窗口和来源、收不收图片；下面是四个压缩点。 */
  it("lists each route's models with where the window came from, and the four choices with their tokens", async () => {
    fakeBackend({ compaction: { percent: 100, points: POINTS } }, 200, 0, MODELS);
    wrap(<ModelPane config={CONFIG} />);
    const local = await screen.findByRole("region", { name: "本地" });
    expect(local.textContent).toContain("qwen3.8131.1K来自配置（没能从模型读到）不收图片");
    const claude = screen.getByRole("region", { name: "Claude" });
    expect(claude.textContent).toContain("Claude Sonnet 5.51M模型报告收图片");
    expect(screen.getByRole("region", { name: "Kimi" }).textContent).toContain("暂时连不上（401）");
    const radios = await screen.findAllByRole("radio");
    expect(radios.map((radio) => radio.textContent)).toEqual(["50%500K", "70%700K", "85%850K", "用满936K"]);
    expect(screen.getByRole("radio", { name: "用满" }).getAttribute("aria-checked")).toBe("true");
    expect(screen.getByText(/从下一次提问开始生效/)).not.toBeNull();
  });

  /** 需求 8.2：压缩点的 token 数按当前会话选的模型算。 */
  it("counts the points on the session's model", async () => {
    fakeBackend({ compaction: { percent: 100, points: POINTS } }, 200, 0, MODELS);
    wrap(<ModelPane config={CONFIG} choice={{ route: "local", model: "qwen3.8", effort: "on" }} />);
    await screen.findByRole("region", { name: "本地" });
    const radios = await screen.findAllByRole("radio");
    expect(radios.map((radio) => radio.textContent)).toEqual(["50%65.5K", "70%91.8K", "85%111.4K", "用满114.7K"]);
    expect(screen.getByText("压缩点按 qwen3.8 计算")).not.toBeNull();
  });

  it("saves a choice", async () => {
    const puts = fakeBackend({ compaction: { percent: 100, points: POINTS } });
    wrap(<ModelPane config={CONFIG} />);
    await userEvent.click(await screen.findByRole("radio", { name: "50%" }));
    await waitFor(() => expect(screen.getByRole("radio", { name: "50%" }).getAttribute("aria-checked")).toBe("true"));
    expect(puts).toEqual(['{"percent":50}']);
  });

  it("goes back to the old choice when saving fails", async () => {
    fakeBackend({ compaction: { percent: 100, points: POINTS } }, 500);
    wrap(<ModelPane config={CONFIG} />);
    await userEvent.click(await screen.findByRole("radio", { name: "70%" }));
    expect(await screen.findByRole("alert")).not.toBeNull();
    await waitFor(() => expect(screen.getByRole("radio", { name: "用满" }).getAttribute("aria-checked")).toBe("true"));
  });

  it("goes back even when the server cannot be reached afterwards", async () => {
    fakeBackend({ compaction: { percent: 85, points: POINTS } }, 502);
    wrap(<ModelPane config={CONFIG} />);
    await userEvent.click(await screen.findByRole("radio", { name: "50%" }));
    expect(await screen.findByRole("alert")).not.toBeNull();
    expect(screen.getByRole("radio", { name: "85%" }).getAttribute("aria-checked")).toBe("true");
  });

  it("cannot be chosen when the window is unknown", async () => {
    fakeBackend({ compaction: { percent: 100, points: [] } });
    wrap(<ModelPane config={{ ...CONFIG, contextWindow: undefined, windowSource: undefined }} />);
    expect(await screen.findByText("不知道模型的窗口大小，不做自动压缩。")).not.toBeNull();
    expect(screen.queryAllByRole("radio")).toHaveLength(0);
  });

  it("the status bar warns near the chosen point and follows a new choice without reloading", async () => {
    // 输入 46 万：用满（93.6 万）时不提醒，选 50% 后到了 50 万的 90% 以上
    fakeBackend({ compaction: { percent: 100, points: POINTS } }, 200, 460_000);
    wrap(
      <>
        <StatusBar session="s" run="r" running={false} version="1" contextWindow={1_000_000} />
        <ModelPane config={CONFIG} />
      </>,
    );
    const context = await screen.findByText("上下文 46%");
    expect(context.className).not.toContain("text-danger");
    await userEvent.click(await screen.findByRole("radio", { name: "50%" }));
    await waitFor(() => expect(screen.getByText("上下文 46%").className).toContain("text-danger"));
    expect(screen.getByText("上下文 46%").getAttribute("title")).toContain("50%");
  });
});

import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { ModelChoice, ModelsResponse } from "../api/types";
import { MODELS, model } from "./fixtures";
import { ModelPicker } from "./ModelPicker";

afterEach(cleanup);

const SONNET: ModelChoice = { route: "claude", model: "claude-sonnet-5-5", effort: "medium" };

function mount(choice: ModelChoice | null, options: { pending?: boolean; toolChanges?: boolean; models?: ModelsResponse } = {}) {
  const onSelect = vi.fn();
  render(
    <ModelPicker
      models={options.models ?? MODELS}
      choice={choice}
      pending={options.pending ?? false}
      toolChangesInHistory={options.toolChanges ?? false}
      onSelect={onSelect}
    />,
  );
  return onSelect;
}

async function open() {
  await userEvent.click(screen.getByRole("button", { name: /^模型和思考档位/ }));
  return screen.getByRole("dialog", { name: "模型和思考档位" });
}

describe("ModelPicker", () => {
  /** 需求 3.1：按钮写“模型 · 档位”；没有档位的模型只写模型名。 */
  it("shows the model and its level, or only the model", () => {
    mount(SONNET);
    expect(screen.getByRole("button", { name: /^模型和思考档位/ }).textContent).toBe("Claude Sonnet 5.5 · 中");
    cleanup();
    mount({ route: "local", model: "plain", effort: null });
    expect(screen.getByRole("button", { name: /^模型和思考档位/ }).textContent).toBe("plain");
  });

  /** 需求 3.2：按路线分组；连不上的路线是灰色的一组，写明原因，没有可选的模型。 */
  it("groups models by route and shows an unreachable route with its reason", async () => {
    mount(SONNET);
    const panel = await open();
    const groups = within(panel).getAllByRole("group").map((group) => group.getAttribute("aria-label"));
    expect(groups).toEqual(["Claude", "本地", "Kimi", "思考档位"]);
    const kimi = within(panel).getByRole("group", { name: "Kimi" });
    expect(kimi.textContent).toContain("暂时连不上（401）");
    expect(within(kimi).queryAllByRole("button")).toHaveLength(0);
  });

  /** 需求 3.3：有档位的模型列出档位；没有档位不显示那一栏。 */
  it("lists the levels of a model with levels and none for a model without", async () => {
    mount({ route: "local", model: "qwen3.8", effort: "on" });
    let panel = await open();
    const levels = within(panel).getByRole("group", { name: "思考档位" });
    expect(within(levels).getAllByRole("button").map((button) => button.textContent)).toEqual(["关", "开"]);
    cleanup();
    mount({ route: "local", model: "plain", effort: null });
    panel = await open();
    expect(within(panel).queryByRole("group", { name: "思考档位" })).toBeNull();
  });

  /** 需求 3.4：换模型时新模型有当前档位就留着，没有就用新模型的默认。 */
  it("keeps the level when switching to a model that has it", async () => {
    const onSelect = mount({ route: "claude", model: "claude-sonnet-5-5", effort: "xhigh" });
    const panel = await open();
    await userEvent.click(within(panel).getByRole("button", { name: "Claude Sonnet 4.6" }));
    expect(onSelect).toHaveBeenLastCalledWith({ route: "claude", model: "claude-sonnet-4-6", effort: "medium" });
    await userEvent.click(within(panel).getByRole("button", { name: "qwen3.8" }));
    expect(onSelect).toHaveBeenLastCalledWith({ route: "local", model: "qwen3.8", effort: "on" });
  });

  it("picks a level", async () => {
    const onSelect = mount(SONNET);
    const panel = await open();
    await userEvent.click(within(within(panel).getByRole("group", { name: "思考档位" })).getByRole("button", { name: "高" }));
    expect(onSelect).toHaveBeenLastCalledWith({ ...SONNET, effort: "high" });
  });

  /** 需求 3.5：会话中途改过工具时，不支持的 Claude 模型灰掉并写明原因。 */
  it("greys out models without mid-session tool changes when the session has them", async () => {
    mount(SONNET, { toolChanges: true });
    const panel = await open();
    const sonnet5 = within(panel).getByRole("button", { name: "Claude Sonnet 5" });
    expect((sonnet5 as HTMLButtonElement).disabled).toBe(true);
    expect(sonnet5.getAttribute("title")).toBe("这个会话中途改过工具，这个模型不支持");
    expect((within(panel).getByRole("button", { name: "Claude Sonnet 4.6" }) as HTMLButtonElement).disabled).toBe(false);
  });

  /** 需求 3.6：选的和已经生效的不同时写“下一次提问生效”。 */
  it("says the choice applies from the next question", () => {
    mount(SONNET, { pending: true });
    expect(screen.getByText("下一次提问生效")).not.toBeNull();
    cleanup();
    mount(SONNET);
    expect(screen.queryByText("下一次提问生效")).toBeNull();
  });

  /** 需求 3.2：多于 8 个模型时有搜索框；NFR：200 个模型打开到能点在 100 ms 内（jsdom 粗测）。 */
  it("searches when there are many models and opens quickly with 200", async () => {
    const many: ModelsResponse = {
      ...MODELS,
      routes: [{ ...MODELS.routes[1]!, models: Array.from({ length: 200 }, (_, index) => model(`m${index}`, `Model ${index}`, [], null)) }],
    };
    mount({ route: "local", model: "m0", effort: null }, { models: many });
    const started = performance.now();
    const panel = await open();
    const elapsed = performance.now() - started;
    expect(within(panel).getAllByRole("button")).toHaveLength(200);
    console.info(`picker with 200 models opened in ${elapsed.toFixed(1)} ms`);
    expect(elapsed).toBeLessThan(100);
    await userEvent.type(within(panel).getByRole("searchbox", { name: "搜索模型" }), "Model 19");
    expect(within(panel).getAllByRole("button").map((button) => button.textContent)).toEqual([
      "Model 19",
      "Model 190",
      "Model 191",
      "Model 192",
      "Model 193",
      "Model 194",
      "Model 195",
      "Model 196",
      "Model 197",
      "Model 198",
      "Model 199",
    ]);
  });
});

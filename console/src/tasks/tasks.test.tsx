import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Item, QuestionItem, TaskItem, TaskResultItem, ToolStepItem } from "../api/types";
import { ApprovalCard } from "../composer/approval/ApprovalCard";
import { ConversationStore } from "../store/ConversationStore";
import { TaskCard } from "./TaskCard";
import { TaskResultNote } from "./TaskResultNote";
import { TaskStrip } from "./TaskStrip";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const at = "2026-11-18T01:00:00Z";

const RUNNING: TaskItem = {
  kind: "task", id: "5.0", session: "s1", run: "s1_1", at, task: "t5-0", taskSession: "s1-t5-0",
  brief: "检查 billing 模块", prompt: "检查 billing 模块\n目录是 src/billing。",
};

const STEP: ToolStepItem = {
  kind: "tool", id: "9.0", session: "s1-t5-0", run: "s1-t5-0_6", at, status: "DONE", startedAt: at, endedAt: at,
  call: { id: "c9", name: "grep", arguments: { pattern: "throw" } },
  result: { toolCallId: "c9", text: "3 matches", error: false },
} as ToolStepItem;

const ASKING: QuestionItem = {
  kind: "question", id: "q:approval-c9", session: "s1-t5-0", run: "s1-t5-0_6", at, askedBy: "toolApproval", questionId: "approval-c9",
  title: "允许运行这条命令吗？", detail: "rm -rf build/", choices: [{ id: "once", label: "允许一次" }, { id: "deny", label: "拒绝" }],
  freeText: false, status: "OPEN", toolCallId: "c9",
};

/** 假后端：任务会话的条目按 `items` 回；其余请求记下来。 */
function backend(items: Item[], status = 200) {
  const calls: { path: string; method: string; body?: string }[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn((input: string, init?: RequestInit) => {
      calls.push({ path: input, method: init?.method ?? "GET", body: init?.body as string | undefined });
      const body = input.endsWith("/messages") ? { seq: 9, items, start: 0, hasMore: false } : status < 400 ? {} : { error: "no", code: "request/invalid" };
      return Promise.resolve({ ok: status < 400, status, text: () => Promise.resolve(JSON.stringify(body)) });
    }),
  );
  return calls;
}

function wrap(node: React.ReactNode) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>{node}</QueryClientProvider>,
  );
}

function card(item: TaskItem) {
  const store = new ConversationStore();
  store.applyItem(item);
  wrap(<TaskCard store={store} id={item.id} />);
  return store;
}

describe("任务卡片", () => {
  it("收起时一行：交代的第一行、编号和 agent、进行中、停止；展开看交代的全部和最近的几步", async () => {
    backend([STEP]);
    card({ ...RUNNING, agent: "reviewer" });
    const region = screen.getByRole("region", { name: "检查 billing 模块" });
    expect(region.textContent).toContain("任务 t5-0 · reviewer");
    expect(region.textContent).toContain("进行中");
    expect(screen.getByRole("button", { name: "停止" })).toBeTruthy();
    expect(region.textContent).not.toContain("目录是 src/billing。");

    await userEvent.click(screen.getByRole("button", { name: "展开任务" }));
    expect(region.textContent).toContain("交代的内容");
    expect(region.textContent).toContain("目录是 src/billing。");
    await waitFor(() => expect(region.textContent).toContain("grep"));
    expect(screen.getByRole("button", { name: "收起任务" })).toBeTruthy();
  });

  it("没写 agent 的显示“通用”；还没动手时说明", async () => {
    backend([]);
    card(RUNNING);
    expect(screen.getByRole("region", { name: "检查 billing 模块" }).textContent).toContain("任务 t5-0 · 通用");
    await userEvent.click(screen.getByRole("button", { name: "展开任务" }));
    await waitFor(() => expect(screen.getByRole("region", { name: "检查 billing 模块" }).textContent).toContain("还没有开始动手"));
  });

  it("任务的会话里有没答的问题：状态是“等你回答”", async () => {
    backend([STEP, ASKING]);
    card(RUNNING);
    await waitFor(() => expect(screen.getByRole("region", { name: "检查 billing 模块" }).textContent).toContain("等你回答"));
  });

  it("点停止：只停这一个任务，交上以后按钮禁用，等结果回来", async () => {
    const calls = backend([]);
    card(RUNNING);
    await userEvent.click(screen.getByRole("button", { name: "停止" }));
    expect(calls.filter((call) => call.method === "POST")).toEqual([
      { path: "/api/sessions/s1/tasks/stop", method: "POST", body: '{"task":"t5-0"}' },
    ]);
    await waitFor(() => expect((screen.getByRole("button", { name: "停止" }) as HTMLButtonElement).disabled).toBe(true));
  });

  it.each([
    ["COMPLETED", "完成"],
    ["FAILED", "失败"],
    ["STOPPED", "已停止"],
    ["TIMED_OUT", "超时"],
    ["INTERRUPTED", "中断"],
  ] as const)("回来以后（%s）：显示结局，没有停止按钮，收起时不再读任务的会话", (outcome, label) => {
    const calls = backend([]);
    card({ ...RUNNING, outcome, endedAt: "2026-11-18T01:00:48Z" });
    const region = screen.getByRole("region", { name: "检查 billing 模块" });
    expect(region.textContent).toContain(label);
    expect(region.textContent).toContain("48 秒");
    expect(screen.queryByRole("button", { name: "停止" })).toBeNull();
    expect(calls).toEqual([]);
  });

  it("没有交代的第一行时用编号当标题", () => {
    backend([]);
    card({ ...RUNNING, brief: undefined, prompt: undefined });
    expect(screen.getByRole("region", { name: "任务 t5-0" })).toBeTruthy();
  });
});

describe("任务结果", () => {
  function note(item: TaskResultItem) {
    const store = new ConversationStore();
    store.applyItem(item);
    wrap(<TaskResultNote store={store} id={item.id} />);
  }
  const result: TaskResultItem = { kind: "taskResult", id: "20.0", session: "s1", run: "s1_19", at, task: "t5-0", outcome: "COMPLETED", text: "billing 有 2 处问题。", paused: false };

  it("写明是哪个任务的结果和结局，带回来的文字原样显示", () => {
    note(result);
    const region = screen.getByRole("region", { name: "任务 t5-0 的结果" });
    expect(region.textContent).toContain("完成");
    expect(region.textContent).toContain("billing 有 2 处问题。");
  });

  it("完成了却没带回文字时说明；别的结局没有文字就只有标题", () => {
    note({ ...result, text: undefined });
    expect(screen.getByRole("region", { name: "任务 t5-0 的结果" }).textContent).toContain("没有带回文字");
    cleanup();
    note({ ...result, id: "21.0", outcome: "STOPPED", text: undefined });
    const stopped = screen.getByRole("region", { name: "任务 t5-0 的结果" });
    expect(stopped.textContent).toContain("已停止");
    expect(stopped.textContent).not.toContain("没有带回文字");
  });
});

describe("输入框上方的任务提示", () => {
  it("有任务在外面：数量和全部停止", async () => {
    const calls = backend([]);
    wrap(<TaskStrip session="s1" open={[RUNNING, { ...RUNNING, id: "5.1", task: "t5-1" }]} paused={[]} />);
    expect(screen.getByText("2 个任务进行中")).toBeTruthy();
    await userEvent.click(screen.getByRole("button", { name: "全部停止" }));
    expect(calls).toEqual([{ path: "/api/sessions/s1/tasks/stop", method: "POST", body: "{}" }]);
  });

  it("自动继续到了上限：说明有几个结果没处理、说一句话就接着做", () => {
    const paused: TaskResultItem = { kind: "taskResult", id: "30.0", session: "s1", run: "s1_19", at, task: "t5-0", outcome: "FAILED", paused: true };
    wrap(<TaskStrip session="s1" open={[]} paused={[paused, { ...paused, id: "31.0", task: "t5-1" }]} />);
    expect(screen.getByText(/上面 2 个结果还没处理，说一句话就接着做/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "全部停止" })).toBeNull();
  });

  it("都没有时什么都不显示；停不掉时写明原因", async () => {
    const { container } = wrap(<TaskStrip session="s1" open={[]} paused={[]} />);
    expect(container.textContent).toBe("");
    cleanup();
    backend([], 429);
    wrap(<TaskStrip session="s1" open={[RUNNING]} paused={[]} />);
    await userEvent.click(screen.getByRole("button", { name: "全部停止" }));
    expect(await screen.findByRole("alert")).toBeTruthy();
  });
});

describe("任务里的批准", () => {
  it("卡片上写明来自哪个任务；回答发给任务自己的会话，交上以后通知调用方重读", async () => {
    const calls = backend([]);
    const answered = vi.fn();
    wrap(<ApprovalCard pending={{ question: ASKING }} from="来自任务 t5-0「检查 billing 模块」" onAnswered={answered} />);
    const group = screen.getByRole("group", { name: "允许运行这条命令吗？" });
    expect(group.textContent).toContain("来自任务 t5-0「检查 billing 模块」");
    await userEvent.click(screen.getByRole("button", { name: "允许一次" }));
    expect(calls).toEqual([{ path: "/api/sessions/s1-t5-0/questions/approval-c9", method: "POST", body: '{"choice":"once"}' }]);
    await waitFor(() => expect(answered).toHaveBeenCalledTimes(1));
  });
});

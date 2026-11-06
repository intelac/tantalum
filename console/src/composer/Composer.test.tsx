import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import { Composer } from "./Composer";

vi.mock("../api/client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/client")>();
  return { ...actual, submit: vi.fn(), cancelRun: vi.fn() };
});

const client = await import("../api/client");
const submit = vi.mocked(client.submit);
const cancelRun = vi.mocked(client.cancelRun);

function mount(activeRun: string | null = null, session = "s1") {
  return render(<Composer session={session} activeRun={activeRun} modelName="qwen" cloud={false} />);
}

beforeEach(() => {
  submit.mockReset();
  cancelRun.mockReset();
  submit.mockResolvedValue({ submissionId: "x", duplicate: false });
  cancelRun.mockResolvedValue({});
});

afterEach(() => {
  cleanup();
  sessionStorage.clear();
});

describe("Composer", () => {
  it("submits on Enter with a prompt and a fresh requestId, then clears", async () => {
    mount();
    const box = screen.getByRole("textbox");
    await userEvent.type(box, "你好{Enter}");
    expect(submit).toHaveBeenCalledTimes(1);
    const [session, mode, text, requestId] = submit.mock.calls[0] as [string, string, string, string];
    expect([session, mode, text]).toEqual(["s1", "prompt", "你好"]);
    expect(requestId).toMatch(/^[0-9a-f-]{36}$/);
    expect((box as HTMLTextAreaElement).value).toBe("");
  });

  it("inserts a newline on Shift+Enter without submitting", async () => {
    mount();
    const box = screen.getByRole("textbox") as HTMLTextAreaElement;
    await userEvent.type(box, "第一行{Shift>}{Enter}{/Shift}第二行");
    expect(submit).not.toHaveBeenCalled();
    expect(box.value).toBe("第一行\n第二行");
  });

  it("does not submit while the input method is composing", async () => {
    mount();
    const box = screen.getByRole("textbox");
    await userEvent.type(box, "ni");
    fireEvent.keyDown(box, { key: "Enter", isComposing: true });
    fireEvent.keyDown(box, { key: "Enter", keyCode: 229 });
    expect(submit).not.toHaveBeenCalled();
  });

  it("keeps the text and reuses the requestId when the server rejects", async () => {
    submit.mockRejectedValueOnce(new ApiError(429, null, {}, ""));
    mount();
    const box = screen.getByRole("textbox") as HTMLTextAreaElement;
    await userEvent.type(box, "再试{Enter}");
    expect((await screen.findByRole("alert")).textContent).toContain("429");
    expect(box.value).toBe("再试");

    await userEvent.type(box, "{Enter}");
    expect(submit).toHaveBeenCalledTimes(2);
    expect(submit.mock.calls[1]?.[3]).toBe(submit.mock.calls[0]?.[3]);
    expect(box.value).toBe("");
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("queues as a follow-up while running and offers a stop button that cancels", async () => {
    mount("run-3");
    expect(screen.queryByRole("button", { name: "插话" })).toBeNull();
    await userEvent.type(screen.getByRole("textbox"), "然后呢{Enter}");
    expect(submit.mock.calls[0]?.[1]).toBe("follow-up");

    await userEvent.click(screen.getByRole("button", { name: "停止" }));
    expect(cancelRun).toHaveBeenCalledWith("run-3");
  });

  it("has no stop button when idle and disables send when empty", () => {
    mount();
    expect(screen.queryByRole("button", { name: "停止" })).toBeNull();
    expect((screen.getByRole("button", { name: "发送" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("keeps a draft per session across switches", async () => {
    const view = mount(null, "a");
    await userEvent.type(screen.getByRole("textbox"), "给 a 的草稿");
    view.rerender(<Composer session="b" activeRun={null} modelName="qwen" cloud={false} />);
    expect((screen.getByRole("textbox") as HTMLTextAreaElement).value).toBe("");
    await userEvent.type(screen.getByRole("textbox"), "给 b 的");
    view.rerender(<Composer session="a" activeRun={null} modelName="qwen" cloud={false} />);
    expect((screen.getByRole("textbox") as HTMLTextAreaElement).value).toBe("给 a 的草稿");
    expect(sessionStorage.getItem("tantalum.draft.b")).toBe("给 b 的");
  });

  /** model-picker M6、M8：选择随开始新运行的提交发出（提问和排队都是），发出后告诉选择器。 */
  it("sends the chosen model with a prompt and a follow-up", async () => {
    const choice = { route: "local", model: "qwen3.8", effort: "on" };
    const onSubmitted = vi.fn();
    const view = render(<Composer session="s1" activeRun={null} modelName="qwen" cloud={false} choice={choice} onSubmitted={onSubmitted} />);
    await userEvent.type(screen.getByRole("textbox"), "问{Enter}");
    expect(submit.mock.calls[0]?.[5]).toEqual(choice);
    expect(onSubmitted).toHaveBeenCalledWith(choice);
    view.rerender(<Composer session="s1" activeRun="run-1" modelName="qwen" cloud={false} choice={choice} onSubmitted={onSubmitted} />);
    await userEvent.type(screen.getByRole("textbox"), "再问{Enter}");
    expect(submit.mock.calls[1]?.[1]).toBe("follow-up");
    expect(submit.mock.calls[1]?.[5]).toEqual(choice);
  });

  it("does not report a choice as sent when the submission fails", async () => {
    submit.mockRejectedValueOnce(new ApiError(400, "model/not-offered", { model: "x" }, "model x is not offered now"));
    const onSubmitted = vi.fn();
    render(<Composer session="s1" activeRun={null} modelName="qwen" cloud={false} choice={{ route: "claude", model: "x", effort: "low" }} onSubmitted={onSubmitted} />);
    await userEvent.type(screen.getByRole("textbox"), "问{Enter}");
    expect((await screen.findByRole("alert")).textContent).toBe("模型 x 现在不提供，请换一个");
    expect(onSubmitted).not.toHaveBeenCalled();
  });
});

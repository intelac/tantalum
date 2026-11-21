import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../../api/client";
import type { UserItem } from "../../api/types";
import { preview, QueueDock } from "./QueueDock";
import { useQueueActions } from "./useQueueActions";

vi.mock("../../api/client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../../api/client")>();
  return { ...actual, changeQueue: vi.fn() };
});

const client = await import("../../api/client");
const changeQueue = vi.mocked(client.changeQueue);

function item(id: string, text: string, attachments: UserItem["attachments"] = undefined): UserItem {
  return { kind: "user", id, session: "s1", at: "2026-11-11T00:00:00Z", text, attachments, queued: true };
}

function Dock({ queued, running = true }: { queued: UserItem[]; running?: boolean }) {
  const actions = useQueueActions("s1");
  return <QueueDock queued={queued} running={running} actions={actions} />;
}

beforeEach(() => {
  changeQueue.mockReset();
  changeQueue.mockResolvedValue({ submissionId: "x" });
});

afterEach(cleanup);

/** input-area 需求 1：队列条。 */
describe("QueueDock", () => {
  it("is absent when nothing is queued", () => {
    const { container } = render(<Dock queued={[]} />);
    expect(container.textContent).toBe("");
  });

  it("shows a single queued message directly, on one line", () => {
    render(<Dock queued={[item("5.0", "第一行\n  第二行")]} />);
    expect(screen.getByText("第一行 第二行")).not.toBeNull();
    expect(screen.queryByRole("button", { name: "展开排队的消息" })).toBeNull();
  });

  it("collapses several behind a count and lists them when opened", async () => {
    render(<Dock queued={[item("5.0", "甲"), item("6.0", "乙")]} />);
    expect(screen.queryByText("甲")).toBeNull();
    await userEvent.click(screen.getByRole("button", { name: "展开排队的消息" }));
    expect(screen.getAllByRole("listitem").map((row) => row.textContent)).toEqual(["甲", "乙"]);
    await userEvent.click(screen.getByRole("button", { name: "收起排队的消息" }));
    expect(screen.queryByText("甲")).toBeNull();
  });

  it("removes and steers by the item's id", async () => {
    render(<Dock queued={[item("5.0", "甲")]} />);
    await userEvent.click(screen.getByRole("button", { name: "移除" }));
    await userEvent.click(screen.getByRole("button", { name: /^插话/ }));
    expect(changeQueue.mock.calls).toEqual([
      ["s1", "5.0", "remove"],
      ["s1", "5.0", "join"],
    ]);
  });

  it("saves an edit on Enter and keeps the line breaks", async () => {
    render(<Dock queued={[item("5.0", "甲")]} />);
    await userEvent.click(screen.getByRole("button", { name: "编辑" }));
    const box = screen.getByRole("textbox", { name: "改排队的消息" }) as HTMLTextAreaElement;
    expect(box.value).toBe("甲");
    await userEvent.type(box, "{Shift>}{Enter}{/Shift}乙{Enter}");
    expect(changeQueue.mock.calls).toEqual([["s1", "5.0", "edit", "甲\n乙"]]);
    await waitFor(() => expect(screen.queryByRole("textbox")).toBeNull());
  });

  it("cancels on Esc, does not save an empty or unchanged text, and ignores the input method's Enter", async () => {
    render(<Dock queued={[item("5.0", "甲")]} />);
    await userEvent.click(screen.getByRole("button", { name: "编辑" }));
    let box = screen.getByRole("textbox");
    fireEvent.keyDown(box, { key: "Enter", isComposing: true });
    await userEvent.clear(box);
    expect((screen.getByRole("button", { name: "保存" }) as HTMLButtonElement).disabled).toBe(true);
    await userEvent.type(box, "{Enter}");
    expect(screen.getByRole("textbox")).not.toBeNull();
    await userEvent.type(box, "丙{Escape}");
    expect(screen.queryByRole("textbox")).toBeNull();
    expect(screen.getByText("甲")).not.toBeNull();

    await userEvent.click(screen.getByRole("button", { name: "编辑" }));
    box = screen.getByRole("textbox");
    await userEvent.type(box, "{Enter}");
    expect(screen.queryByRole("textbox")).toBeNull();
    expect(changeQueue).not.toHaveBeenCalled();
  });

  it("cannot edit a message with attachments, and says why", () => {
    render(<Dock queued={[item("5.0", "", [{ id: "a1", name: "图.png" } as never])]} />);
    const edit = screen.getByRole("button", { name: "编辑" }) as HTMLButtonElement;
    expect(edit.disabled).toBe(true);
    expect(edit.title).toBe("带附件的消息暂不支持编辑");
    expect(screen.getByText("附件：图.png")).not.toBeNull();
  });

  it("cannot steer when nothing is running", () => {
    render(<Dock queued={[item("5.0", "甲")]} running={false} />);
    const steer = screen.getByRole("button", { name: /^插话/ }) as HTMLButtonElement;
    expect(steer.disabled).toBe(true);
    expect(steer.title).toBe("现在没有在运行，不能插话");
  });

  it("disables every button while one operation is in flight", async () => {
    let finish: (value: { submissionId: string }) => void = () => {};
    changeQueue.mockReturnValueOnce(new Promise((resolve) => (finish = resolve)));
    render(<Dock queued={[item("5.0", "甲")]} />);
    await userEvent.click(screen.getByRole("button", { name: "移除" }));
    for (const button of screen.getAllByRole("button")) expect((button as HTMLButtonElement).disabled).toBe(true);
    finish({ submissionId: "x" });
    await waitFor(() => expect((screen.getByRole("button", { name: "移除" }) as HTMLButtonElement).disabled).toBe(false));
  });

  it("says the message may already have been sent when it is gone", async () => {
    changeQueue.mockRejectedValueOnce(new ApiError(404, "queue/gone", {}, ""));
    render(<Dock queued={[item("5.0", "甲")]} />);
    await userEvent.click(screen.getByRole("button", { name: "移除" }));
    expect((await screen.findByRole("alert")).textContent).toContain("可能已经开始发送");
  });

  it("keeps the editor open when saving fails", async () => {
    changeQueue.mockRejectedValueOnce(new ApiError(409, "queue/attachments", {}, ""));
    render(<Dock queued={[item("5.0", "甲")]} />);
    await userEvent.click(screen.getByRole("button", { name: "编辑" }));
    await userEvent.type(screen.getByRole("textbox"), "乙{Enter}");
    expect(await screen.findByRole("alert")).not.toBeNull();
    expect((screen.getByRole("textbox") as HTMLTextAreaElement).value).toBe("甲乙");
  });
});

describe("preview", () => {
  it.each([
    ["a\n\n b\tc ", "a b c"],
    ["", ""],
  ])("%j → %j", (text, line) => expect(preview(text)).toBe(line));
});

describe("steerAll", () => {
  function All({ queued }: { queued: UserItem[] }) {
    const actions = useQueueActions("s1");
    return (
      <>
        <button type="button" onClick={() => void actions.steerAll(queued)}>
          all
        </button>
        {actions.error !== null && <p role="alert">failed</p>}
      </>
    );
  }

  it("steers in order and skips one that has just started", async () => {
    changeQueue.mockResolvedValueOnce({ submissionId: "x" }).mockRejectedValueOnce(new ApiError(404, "queue/gone", {}, ""));
    render(<All queued={[item("5.0", "甲"), item("6.0", "乙"), item("7.0", "丙")]} />);
    await userEvent.click(screen.getByRole("button", { name: "all" }));
    await waitFor(() => expect(changeQueue).toHaveBeenCalledTimes(3));
    expect(changeQueue.mock.calls.map((call) => call.slice(1))).toEqual([
      ["5.0", "join"],
      ["6.0", "join"],
      ["7.0", "join"],
    ]);
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("stops at any other failure", async () => {
    changeQueue.mockRejectedValueOnce(new ApiError(500, null, {}, ""));
    render(<All queued={[item("5.0", "甲"), item("6.0", "乙")]} />);
    await userEvent.click(screen.getByRole("button", { name: "all" }));
    expect(await screen.findByRole("alert")).not.toBeNull();
    expect(changeQueue).toHaveBeenCalledTimes(1);
  });
});

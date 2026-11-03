import { act, cleanup, createEvent, fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "../api/client";
import type { AttachmentRef } from "../api/types";
import { Composer } from "../composer/Composer";
import { resetDraftAttachments } from "./drafts";
import { ATTACHMENT_MIME } from "./dragData";

vi.mock("../api/client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/client")>();
  return { ...actual, submit: vi.fn(), cancelRun: vi.fn(), uploadAttachment: vi.fn() };
});

const client = await import("../api/client");
const submit = vi.mocked(client.submit);
const upload = vi.mocked(client.uploadAttachment);

/** 每次上传留下一个句柄，测试决定何时完成。 */
let finishers: Array<(ref: AttachmentRef) => void> = [];

function ref(name: string): AttachmentRef {
  return { kind: "file", id: `sha256:${"a".repeat(63)}${finishers.length}`, name, mediaType: "text/plain", bytes: 5 };
}

function file(name: string, type = "text/plain"): File {
  return new File(["hello"], name, { type });
}

function mount() {
  return render(<Composer session="s1" activeRun={null} modelName="m" cloud />);
}

/** 构造带文件的 DataTransfer：jsdom 没有，自己拼。 */
function transfer(files: File[], extra: { items?: unknown[]; types?: string[]; data?: Record<string, string> } = {}) {
  return {
    files,
    types: extra.types ?? ["Files"],
    items: extra.items ?? files.map((entry) => ({ kind: "file", getAsFile: () => entry, webkitGetAsEntry: () => ({ isDirectory: false }) })),
    getData: (type: string) => extra.data?.[type] ?? "",
    dropEffect: "none",
  };
}

beforeEach(() => {
  finishers = [];
  submit.mockReset();
  submit.mockResolvedValue({ submissionId: "x", duplicate: false });
  upload.mockReset();
  upload.mockImplementation(() => new Promise((resolve) => finishers.push(resolve)));
  URL.createObjectURL = vi.fn(() => "blob:preview");
  URL.revokeObjectURL = vi.fn();
});

afterEach(() => {
  cleanup();
  resetDraftAttachments();
  sessionStorage.clear();
});

describe("Composer attachments", () => {
  it("the button, paste and drop all add to the same tray", async () => {
    mount();
    await userEvent.upload(screen.getByTestId("attachment-picker"), file("picked.txt"));
    fireEvent.paste(screen.getByRole("textbox"), { clipboardData: { files: [file("pasted.png", "image/png")], getData: () => "" } });
    const drop = createEvent.drop(document);
    Object.defineProperty(drop, "dataTransfer", { value: transfer([file("dropped.txt")]) });
    act(() => {
      document.dispatchEvent(drop);
    });
    const tray = screen.getByRole("list", { name: "待发送附件" });
    expect(tray.textContent).toContain("picked.txt");
    expect(tray.querySelector("img")?.getAttribute("alt")).toBe("pasted.png");
    expect(tray.textContent).toContain("dropped.txt");
    expect(upload).toHaveBeenCalledTimes(2);
  });

  it("refuses a dropped folder and says why", () => {
    mount();
    const drop = createEvent.drop(document);
    Object.defineProperty(drop, "dataTransfer", {
      value: transfer([file("photos")], { items: [{ kind: "file", webkitGetAsEntry: () => ({ isDirectory: true }) }] }),
    });
    act(() => {
      document.dispatchEvent(drop);
    });
    expect(screen.getByRole("alert").textContent).toBe("不能添加文件夹，请添加单个文件");
    expect(upload).not.toHaveBeenCalled();
  });

  it("an attachment dragged back from the conversation joins without uploading", () => {
    mount();
    const existing: AttachmentRef = { kind: "image", id: `sha256:${"b".repeat(64)}`, name: "cat.png", mediaType: "image/png", bytes: 9, width: 4, height: 3 };
    const drop = createEvent.drop(document);
    Object.defineProperty(drop, "dataTransfer", { value: transfer([], { types: [ATTACHMENT_MIME], data: { [ATTACHMENT_MIME]: JSON.stringify(existing) } }) });
    act(() => {
      document.dispatchEvent(drop);
    });
    expect(screen.getByRole("img", { name: "cat.png" })).toBeTruthy();
    expect(upload).not.toHaveBeenCalled();
  });

  it("sending waits for the uploads and then submits their refs", async () => {
    mount();
    await userEvent.upload(screen.getByTestId("attachment-picker"), file("notes.txt"));
    await userEvent.type(screen.getByRole("textbox"), "read this{Enter}");
    expect(screen.getByRole("status").textContent).toBe("等附件上传完就发送…");
    expect(submit).not.toHaveBeenCalled();
    const uploaded = ref("notes.txt");
    await act(async () => {
      finishers[0]?.(uploaded);
    });
    expect(submit).toHaveBeenCalledTimes(1);
    expect(submit.mock.calls[0]?.slice(0, 3)).toEqual(["s1", "prompt", "read this"]);
    expect(submit.mock.calls[0]?.[4]).toEqual([uploaded]);
    expect(screen.queryByRole("list", { name: "待发送附件" })).toBeNull();
  });

  it("attachments alone can be sent", async () => {
    mount();
    await userEvent.upload(screen.getByTestId("attachment-picker"), file("notes.txt"));
    await act(async () => {
      finishers[0]?.(ref("notes.txt"));
    });
    await userEvent.click(screen.getByRole("button", { name: "发送" }));
    expect(submit.mock.calls[0]?.[2]).toBe("");
  });

  it("a rejected submission keeps the text and the attachments", async () => {
    submit.mockRejectedValue(new ApiError(400, "当前模型不支持图片"));
    mount();
    await userEvent.upload(screen.getByTestId("attachment-picker"), file("notes.txt"));
    await act(async () => {
      finishers[0]?.(ref("notes.txt"));
    });
    await userEvent.type(screen.getByRole("textbox"), "look{Enter}");
    expect(screen.getByRole("alert").textContent).toBe("当前模型不支持图片");
    expect((screen.getByRole("textbox") as HTMLTextAreaElement).value).toBe("look");
    expect(screen.getByRole("list", { name: "待发送附件" }).textContent).toContain("notes.txt");
  });

  it("checks the limits before uploading", async () => {
    render(<Composer session="s1" activeRun={null} modelName="m" cloud vision={false} />);
    await userEvent.upload(screen.getByTestId("attachment-picker"), file("photo.png", "image/png"));
    expect(screen.getByRole("alert").textContent).toBe("当前模型不支持图片，请切换支持图片的模型");
    expect(upload).not.toHaveBeenCalled();
  });
});

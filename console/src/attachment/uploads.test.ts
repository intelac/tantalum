import { afterEach, describe, expect, it, vi } from "vitest";
import type { AttachmentRef } from "../api/types";
import { draftAttachments, resetDraftAttachments } from "./drafts";
import { formatBytes } from "../text/format";
import { admit, DEFAULT_LIMITS, isImage } from "./limits";
import { localPreview, type Uploader, UploadQueue } from "./uploads";

/** 可控的上传：每次调用留下一个句柄，测试决定何时报进度、成功或失败。 */
function controlledUploader() {
  const calls: Array<{
    file: File;
    progress: (sent: number, total: number) => void;
    signal: AbortSignal;
    resolve: (ref: AttachmentRef) => void;
    reject: (failure: Error) => void;
  }> = [];
  const upload: Uploader = (file, progress, signal) =>
    new Promise((resolve, reject) => {
      calls.push({ file, progress, signal, resolve, reject });
    });
  return { upload, calls };
}

function file(name: string, size = 10, type = ""): File {
  return new File([new Uint8Array(size)], name, { type });
}

function ref(name: string, kind: "image" | "file" = "file"): AttachmentRef {
  const id = `sha256:${name.charCodeAt(0).toString(16).padStart(64, "0")}`;
  return kind === "image"
    ? { kind, id, name, mediaType: "image/png", bytes: 10, width: 4, height: 3 }
    : { kind, id, name, mediaType: "text/plain", bytes: 10 };
}

const tick = () => new Promise((resolve) => setTimeout(resolve, 0));
let urls = 0;
const options = { createUrl: () => `blob:${++urls}`, revokeUrl: vi.fn() };

afterEach(() => {
  resetDraftAttachments();
  options.revokeUrl.mockReset();
});

describe("UploadQueue", () => {
  it("uploads at most two at a time and starts the next when one finishes", async () => {
    const { upload, calls } = controlledUploader();
    const queue = new UploadQueue(upload, options);
    queue.add([file("a.txt"), file("b.txt"), file("c.txt")]);
    expect(calls.map((call) => call.file.name)).toEqual(["a.txt", "b.txt"]);
    expect(queue.items().map((item) => item.status)).toEqual(["uploading", "uploading", "queued"]);
    calls[0].resolve(ref("a.txt"));
    await tick();
    expect(calls.map((call) => call.file.name)).toEqual(["a.txt", "b.txt", "c.txt"]);
    expect(queue.items()[0].status).toBe("done");
  });

  it("reports progress and keeps the server's ref in add order", async () => {
    const { upload, calls } = controlledUploader();
    const queue = new UploadQueue(upload, options);
    const seen: number[] = [];
    queue.subscribe(() => seen.push(queue.items()[0].sent));
    queue.add([file("a.txt", 100), file("b.txt")]);
    calls[0].progress(40, 100);
    expect(queue.items()[0].sent).toBe(40);
    expect(queue.refs()).toBeNull();
    calls[1].resolve(ref("b.txt"));
    calls[0].resolve(ref("a.txt"));
    await queue.settled();
    expect(seen).toContain(40);
    expect(queue.refs()?.map((item) => item.name)).toEqual(["a.txt", "b.txt"]);
  });

  it("keeps a failed upload with its reason until retried", async () => {
    const { upload, calls } = controlledUploader();
    const queue = new UploadQueue(upload, options);
    queue.add([file("a.txt")]);
    const quota = new Error("quota full");
    calls[0].reject(quota);
    await queue.settled();
    expect(queue.items()[0]).toMatchObject({ status: "failed", error: quota });
    expect(queue.refs()).toBeNull();
    queue.retry(queue.items()[0].key);
    expect(calls).toHaveLength(2);
    calls[1].resolve(ref("a.txt"));
    await queue.settled();
    expect(queue.refs()).toHaveLength(1);
  });

  it("removing an upload cancels it and lets the next one start", async () => {
    const { upload, calls } = controlledUploader();
    const queue = new UploadQueue(upload, options);
    queue.add([file("a.png", 10, "image/png"), file("b.txt"), file("c.txt")]);
    const preview = queue.items()[0].preview;
    queue.remove(queue.items()[0].key);
    expect(calls[0].signal.aborted).toBe(true);
    expect(options.revokeUrl).toHaveBeenCalledWith(preview);
    expect(calls.map((call) => call.file.name)).toEqual(["a.png", "b.txt", "c.txt"]);
    calls[0].resolve(ref("a.png", "image"));
    await tick();
    expect(queue.items().map((item) => item.name)).toEqual(["b.txt", "c.txt"]);
  });

  it("an attachment dragged back from the conversation is added without uploading", () => {
    const { upload, calls } = controlledUploader();
    const queue = new UploadQueue(upload, options);
    queue.addExisting(ref("cat.png", "image"));
    expect(calls).toHaveLength(0);
    expect(queue.refs()?.[0].name).toBe("cat.png");
    expect(queue.busy()).toBe(false);
  });

  it("remembers the local preview of an uploaded image for the bubble", async () => {
    const { upload, calls } = controlledUploader();
    const queue = new UploadQueue(upload, options);
    queue.add([file("photo.jpg", 10, "image/jpeg")]);
    const preview = queue.items()[0].preview;
    const uploaded = ref("photo.jpg", "image");
    calls[0].resolve(uploaded);
    await queue.settled();
    queue.clear();
    expect(localPreview(uploaded.id)).toBe(preview);
    expect(options.revokeUrl).not.toHaveBeenCalled();
  });
});

describe("limits", () => {
  const image = (size: number) => ({ name: "a.png", size, image: true });
  const doc = (size: number) => ({ name: "a.txt", size, image: false });

  it("counts only the four image types as images", () => {
    expect(isImage({ name: "a.png", type: "image/png" })).toBe(true);
    expect(isImage({ name: "a.JPG", type: "" })).toBe(true);
    expect(isImage({ name: "logo.svg", type: "image/svg+xml" })).toBe(false);
    expect(isImage({ name: "a.txt", type: "text/plain" })).toBe(false);
  });

  it("names the first limit that a batch breaks and keeps what fits", () => {
    const limits = { ...DEFAULT_LIMITS, maxImagesPerMessage: 2, maxFilesPerMessage: 1, maxImageBytes: 100, maxFileBytes: 100 };
    expect(admit([image(10)], [image(10), image(10), doc(10)], limits, true)).toEqual({ accepted: [0, 2], reason: "一条消息最多添加 2 张图片" });
    expect(admit([], [doc(10), doc(10)], limits, true).reason).toBe("一条消息最多添加 1 个文件");
    expect(admit([], [image(101)], limits, true).reason).toBe("单张图片不能超过 100 B");
    expect(admit([], [doc(101)], limits, true).reason).toBe("单个文件不能超过 100 B");
    expect(admit([], [image(10)], limits, false)).toEqual({ accepted: [], reason: "当前模型不支持图片，请切换支持图片的模型" });
    expect(admit([], [doc(10)], limits, false)).toEqual({ accepted: [0] });
  });

  it("checks the total image size of the message", () => {
    const limits = { ...DEFAULT_LIMITS, maxImageBytesPerMessage: 25 };
    expect(admit([image(10)], [image(10), image(10)], limits, true)).toEqual({ accepted: [0], reason: "图片总大小超过 25 B，请移除部分图片" });
  });

  it("formats sizes for people", () => {
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(2048)).toBe("2 KB");
    expect(formatBytes(50 * 1024 * 1024)).toBe("50 MB");
    expect(formatBytes(1.5 * 1024 * 1024)).toBe("1.5 MB");
  });
});

describe("draft attachments", () => {
  it("each session keeps its own draft across switches", () => {
    const { upload } = controlledUploader();
    const first = draftAttachments("s1", () => upload);
    first.addExisting(ref("a.txt"));
    expect(draftAttachments("s2", () => upload).items()).toHaveLength(0);
    expect(draftAttachments("s1", () => upload)).toBe(first);
    expect(draftAttachments("s1", () => upload).items()).toHaveLength(1);
  });
});

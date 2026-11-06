// 一条草稿的附件和它们的上传：最多同时传两个，其余排队；有进度、可重试，移除即取消（需求 1.6、1.7）。

import type { AttachmentRef } from "../api/types";
import { isImage } from "./limits";

export type PendingStatus = "queued" | "uploading" | "done" | "failed";

export interface Pending {
  /** 草稿里的标识，不是附件 ID。 */
  key: string;
  name: string;
  size: number;
  image: boolean;
  status: PendingStatus;
  /** 已发送的字节数。 */
  sent: number;
  /** 上传失败的原因；显示时才变成文字（text/errors.ts），切换语言也跟着换。 */
  error?: unknown;
  /** 上传完成（或从对话里拖回来）后才有。 */
  ref?: AttachmentRef;
  /** 图片的本地预览地址（`URL.createObjectURL`）；拖回来的图片没有，直接用服务器地址。 */
  preview?: string;
}

export type Uploader = (file: File, onProgress: (sent: number, total: number) => void, signal: AbortSignal) => Promise<AttachmentRef>;

interface Entry {
  pending: Pending;
  file?: File;
  abort?: AbortController;
}

export interface QueueOptions {
  concurrency?: number;
  createUrl?: (file: File) => string;
  revokeUrl?: (url: string) => void;
}

let counter = 0;

export class UploadQueue {
  private readonly entries: Entry[] = [];
  private readonly listeners = new Set<() => void>();
  private readonly settledWaiters: Array<() => void> = [];
  private readonly concurrency: number;
  private readonly createUrl: (file: File) => string;
  private readonly revokeUrl: (url: string) => void;
  private snapshot: readonly Pending[] = [];

  constructor(
    private readonly upload: Uploader,
    { concurrency = 2, createUrl = (file) => URL.createObjectURL(file), revokeUrl = (url) => URL.revokeObjectURL(url) }: QueueOptions = {},
  ) {
    this.concurrency = concurrency;
    this.createUrl = createUrl;
    this.revokeUrl = revokeUrl;
  }

  /** 加入并开始上传。限制由调用方先查过。 */
  add(files: readonly File[]): void {
    for (const file of files) {
      const image = isImage(file);
      this.entries.push({
        file,
        pending: { key: `p${++counter}`, name: file.name, size: file.size, image, status: "queued", sent: 0, ...(image ? { preview: this.createUrl(file) } : {}) },
      });
    }
    this.pump();
    this.changed();
  }

  /** 已经在服务器上的附件（从对话里拖回来）：直接加入，不再上传（需求 1.4）。 */
  addExisting(ref: AttachmentRef): void {
    this.entries.push({ pending: { key: `p${++counter}`, name: ref.name, size: ref.bytes, image: ref.kind === "image", status: "done", sent: ref.bytes, ref } });
    this.changed();
  }

  retry(key: string): void {
    const entry = this.find(key);
    if (entry === undefined || entry.pending.status !== "failed") return;
    entry.pending = { ...entry.pending, status: "queued", sent: 0, error: undefined };
    this.pump();
    this.changed();
  }

  /** 移除；正在传的立即取消。 */
  remove(key: string): void {
    const index = this.entries.findIndex((entry) => entry.pending.key === key);
    if (index < 0) return;
    const [entry] = this.entries.splice(index, 1);
    entry.abort?.abort();
    if (entry.pending.preview !== undefined && entry.pending.ref === undefined) this.revokeUrl(entry.pending.preview);
    this.pump();
    this.changed();
  }

  /** 发送成功后清空。上传完的图片预览留着，对话里的气泡还在用。 */
  clear(): void {
    for (const entry of this.entries) {
      entry.abort?.abort();
      if (entry.pending.preview !== undefined && entry.pending.ref === undefined) this.revokeUrl(entry.pending.preview);
    }
    this.entries.length = 0;
    this.changed();
  }

  items(): readonly Pending[] {
    return this.snapshot;
  }

  /** 都传完了时的引用，按加入顺序；还有没传完或失败的时为 null。 */
  refs(): AttachmentRef[] | null {
    const refs = this.entries.map((entry) => entry.pending.ref);
    return refs.every((ref) => ref !== undefined) ? (refs as AttachmentRef[]) : null;
  }

  busy(): boolean {
    return this.entries.some((entry) => entry.pending.status === "queued" || entry.pending.status === "uploading");
  }

  /** 等到没有排队和正在传的（成功或失败都算结束）。 */
  settled(): Promise<void> {
    if (!this.busy()) return Promise.resolve();
    return new Promise((resolve) => this.settledWaiters.push(resolve));
  }

  subscribe(listener: () => void): () => void {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  }

  private find(key: string): Entry | undefined {
    return this.entries.find((entry) => entry.pending.key === key);
  }

  private pump(): void {
    let running = this.entries.filter((entry) => entry.pending.status === "uploading").length;
    for (const entry of this.entries) {
      if (running >= this.concurrency) break;
      if (entry.pending.status !== "queued" || entry.file === undefined) continue;
      running += 1;
      this.start(entry, entry.file);
    }
  }

  private start(entry: Entry, file: File): void {
    const abort = new AbortController();
    entry.abort = abort;
    entry.pending = { ...entry.pending, status: "uploading" };
    const live = () => this.entries.includes(entry) && entry.abort === abort;
    this.upload(
      file,
      (sent) => {
        if (!live()) return;
        entry.pending = { ...entry.pending, sent };
        this.changed();
      },
      abort.signal,
    ).then(
      (ref) => {
        if (!live()) return;
        entry.pending = { ...entry.pending, status: "done", sent: file.size, ref };
        if (entry.pending.preview !== undefined) rememberPreview(ref.id, entry.pending.preview);
        this.finished(entry);
      },
      (failure: unknown) => {
        if (!live()) return;
        entry.pending = { ...entry.pending, status: "failed", error: failure };
        this.finished(entry);
      },
    );
  }

  private finished(entry: Entry): void {
    entry.abort = undefined;
    this.pump();
    this.changed();
  }

  private changed(): void {
    this.snapshot = this.entries.map((entry) => entry.pending);
    if (!this.busy()) this.settledWaiters.splice(0).forEach((resolve) => resolve());
    this.listeners.forEach((listener) => listener());
  }
}

// ---- 本地预览：发送后对话里的气泡先用它，服务器的图取到后再换（需求 5.4） ----

const previews = new Map<string, string>();

function rememberPreview(id: string, url: string): void {
  previews.set(id, url);
}

/** 这个附件在这个页面里上传时的本地预览；别处来的附件没有。 */
export function localPreview(id: string): string | undefined {
  return previews.get(id);
}

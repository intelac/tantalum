// 每个会话一份附件草稿，切换会话再回来还在；只在当前页面内有效，刷新就没了（需求 1.9）。
// 文字草稿在 composer/drafts.ts，存在 sessionStorage 里；附件是文件对象，存不进去。

import { uploadAttachment } from "../api/client";
import { type Uploader, UploadQueue } from "./uploads";

const queues = new Map<string, UploadQueue>();

/** 这个会话的附件草稿。第一次用到时建，上传都发往这个会话。 */
export function draftAttachments(session: string, upload: (session: string) => Uploader = defaultUploader): UploadQueue {
  let queue = queues.get(session);
  if (queue === undefined) {
    queue = new UploadQueue(upload(session));
    queues.set(session, queue);
  }
  return queue;
}

function defaultUploader(session: string): Uploader {
  return (file, onProgress, signal) => uploadAttachment(session, file, onProgress, signal);
}

/** 测试用：忘掉所有草稿。 */
export function resetDraftAttachments(): void {
  queues.forEach((queue) => queue.clear());
  queues.clear();
}

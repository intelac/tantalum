// 对话里的附件拖回输入框时带的数据：只是引用，不是文件，加入草稿不用重新上传（需求 1.4）。

import type { AttachmentRef } from "../api/types";

export const ATTACHMENT_MIME = "application/x-tantalum-attachment";

export function writeDragRef(data: DataTransfer, ref: AttachmentRef): void {
  data.setData(ATTACHMENT_MIME, JSON.stringify(ref));
  data.effectAllowed = "copy";
}

export function readDragRef(data: DataTransfer): AttachmentRef | null {
  const raw = data.getData(ATTACHMENT_MIME);
  if (raw === "") return null;
  try {
    const ref = JSON.parse(raw) as AttachmentRef;
    return (ref.kind === "image" || ref.kind === "file") && typeof ref.id === "string" ? ref : null;
  } catch {
    return null;
  }
}

/** 拖的是文件、文件夹或我们自己的附件引用。拖页面上的文字时不出放置提示。 */
export function carriesAttachments(data: DataTransfer | null): boolean {
  if (data === null) return false;
  const types = Array.from(data.types);
  return types.includes("Files") || types.includes(ATTACHMENT_MIME);
}

/** 拖进来的东西里有文件夹。浏览器里只能拿到文件夹本身，读不到里面的文件。 */
export function hasDirectory(data: DataTransfer): boolean {
  return Array.from(data.items ?? []).some((item) => item.kind === "file" && item.webkitGetAsEntry?.()?.isDirectory === true);
}

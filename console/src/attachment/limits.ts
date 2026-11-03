// 附件的限制，前端先查一遍，挡住明显超限的（需求 2.1）；服务器会再查，前端漏掉的以服务器为准。

import type { AttachmentLimits } from "../api/types";
import { zh } from "../text/zh";

const MIB = 1024 * 1024;

/** 和服务器 `AttachmentLimits.defaults()` 相同；旧服务的 `/api/config` 没有这一项时用它。 */
export const DEFAULT_LIMITS: AttachmentLimits = {
  maxImagesPerMessage: 20,
  maxImageBytesPerMessage: 200 * MIB,
  maxImageBytes: 50 * MIB,
  maxFilesPerMessage: 20,
  maxFileBytes: 50 * MIB,
};

const IMAGE_TYPES = new Set(["image/png", "image/jpeg", "image/webp", "image/gif"]);
const IMAGE_EXTENSIONS = /\.(png|jpe?g|webp|gif)$/i;

/** 只有这四种算图片（A4），SVG 不算。浏览器没报类型时看后缀；最终以服务器读文件头为准。 */
export function isImage(file: { name: string; type: string }): boolean {
  return IMAGE_TYPES.has(file.type) || (file.type === "" && IMAGE_EXTENSIONS.test(file.name));
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < MIB) return `${Math.round(bytes / 1024)} KB`;
  const mib = bytes / MIB;
  return `${Number.isInteger(mib) ? mib : mib.toFixed(1)} MB`;
}

export interface Candidate {
  name: string;
  size: number;
  image: boolean;
}

export interface Admission {
  /** 收下的新附件在 `incoming` 里的下标，按原顺序。 */
  accepted: number[];
  /** 第一条没收下的原因；全收下时没有。 */
  reason?: string;
}

/** 一批新附件里能收下哪些：和已有的合在一起不超过一条消息的限制（A4、A5、A7）。按顺序收，超限的跳过。 */
export function admit(existing: readonly Candidate[], incoming: readonly Candidate[], limits: AttachmentLimits, vision: boolean): Admission {
  let images = existing.filter((item) => item.image).length;
  let imageBytes = existing.filter((item) => item.image).reduce((sum, item) => sum + item.size, 0);
  let files = existing.length - images;
  const accepted: number[] = [];
  let reason: string | undefined;
  incoming.forEach((item, index) => {
    const refusal = item.image
      ? !vision
        ? zh.attachment.modelUnsupported
        : item.size > limits.maxImageBytes
          ? zh.attachment.imageFileTooLarge(formatBytes(limits.maxImageBytes))
          : images + 1 > limits.maxImagesPerMessage
            ? zh.attachment.imageTooMany(limits.maxImagesPerMessage)
            : imageBytes + item.size > limits.maxImageBytesPerMessage
              ? zh.attachment.imageTotalTooLarge(formatBytes(limits.maxImageBytesPerMessage))
              : undefined
      : item.size > limits.maxFileBytes
        ? zh.attachment.fileTooLarge(formatBytes(limits.maxFileBytes))
        : files + 1 > limits.maxFilesPerMessage
          ? zh.attachment.fileTooMany(limits.maxFilesPerMessage)
          : undefined;
    if (refusal !== undefined) {
      reason ??= refusal;
      return;
    }
    accepted.push(index);
    if (item.image) {
      images += 1;
      imageBytes += item.size;
    } else {
      files += 1;
    }
  });
  return reason === undefined ? { accepted } : { accepted, reason };
}

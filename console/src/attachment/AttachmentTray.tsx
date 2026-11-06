import { FileText, RotateCw, X } from "lucide-react";
import { attachmentUrl } from "../api/client";
import { useText } from "../text";
import { cx } from "../ui/cx";
import { formatBytes } from "../text/format";
import type { Pending } from "./uploads";
import { errorLine } from "../text/errors";

interface Props {
  items: readonly Pending[];
  onRemove: (key: string) => void;
  onRetry: (key: string) => void;
}

/** 输入框上方的附件条：图片 64 px 缩略图，文件是带名字和大小的卡片；上传中显示进度，失败可重试，都能移除。 */
export function AttachmentTray({ items, onRemove, onRetry }: Props) {
  const t = useText();
  if (items.length === 0) return null;
  return (
    <ul aria-label={t.attachment.pending} className="flex gap-2 overflow-x-auto px-3 pt-3 pb-1">
      {items.map((item) => (
        <li key={item.key} className="relative shrink-0">
          {item.image ? <Thumb item={item} /> : <Card item={item} />}
          {item.status === "failed" && (
            <button
              type="button"
              aria-label={t.attachment.retry(item.name)}
              title={item.error === undefined ? t.attachment.uploadFailed : errorLine(item.error)}
              onClick={() => onRetry(item.key)}
              className="absolute inset-0 flex flex-col items-center justify-center gap-0.5 rounded-lg bg-danger-soft/90 text-[11px] text-danger"
            >
              <RotateCw size={14} />
              <span className="px-1 leading-tight">{t.attachment.uploadFailed}</span>
            </button>
          )}
          <button
            type="button"
            aria-label={t.attachment.remove(item.name)}
            onClick={() => onRemove(item.key)}
            className="absolute -top-1.5 -right-1.5 flex h-5 w-5 items-center justify-center rounded-full border border-line bg-raised text-fg-muted shadow-sm hover:text-fg"
          >
            <X size={12} />
          </button>
        </li>
      ))}
    </ul>
  );
}

function Progress({ item }: { item: Pending }) {
  const t = useText();
  if (item.status !== "uploading" && item.status !== "queued") return null;
  const percent = item.size === 0 ? 0 : Math.round((item.sent / item.size) * 100);
  return (
    <div
      role="progressbar"
      aria-label={t.attachment.uploading}
      aria-valuenow={percent}
      aria-valuemin={0}
      aria-valuemax={100}
      className="absolute inset-x-1.5 bottom-1.5 h-1 overflow-hidden rounded-full bg-muted"
    >
      <div className="h-full bg-primary transition-[width]" style={{ width: `${percent}%` }} />
    </div>
  );
}

function Thumb({ item }: { item: Pending }) {
  const src = item.preview ?? (item.ref !== undefined ? attachmentUrl(item.ref) : undefined);
  return (
    <div className="relative h-16 w-16 overflow-hidden rounded-lg border border-line bg-muted" title={item.name}>
      {src !== undefined && <img src={src} alt={item.name} className="h-full w-full object-cover" />}
      <Progress item={item} />
    </div>
  );
}

function Card({ item }: { item: Pending }) {
  const t = useText();
  return (
    <div className={cx("relative flex h-16 w-60 items-center gap-2.5 rounded-lg border border-line bg-muted px-3")} title={item.name}>
      <FileText size={22} className="shrink-0 text-fg-muted" aria-hidden="true" />
      <div className="min-w-0">
        <p className="truncate text-sm text-fg">{item.name}</p>
        <p className="text-xs text-fg-faint">{item.status === "uploading" ? t.attachment.uploading : formatBytes(item.size)}</p>
      </div>
      <Progress item={item} />
    </div>
  );
}

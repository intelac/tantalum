import { FileText } from "lucide-react";
import { useEffect, useState } from "react";
import { attachmentUrl } from "../api/client";
import type { AttachmentRef, FileAttachment, ImageAttachment } from "../api/types";
import { useText } from "../text";
import { writeDragRef } from "./dragData";
import { Lightbox } from "./Lightbox";
import { formatBytes } from "../text/format";
import { localPreview } from "./uploads";

interface Props {
  attachments: readonly AttachmentRef[];
}

/** 单张图片的长边（需求 5.1）。 */
export const SINGLE_EDGE = 240;
/** 多个附件时图片的方块边长。 */
export const TILE = 64;

/** 单张图片的显示尺寸：长边 240，宽高比限制在 1:4 到 4:1 之间，超出的部分裁掉。 */
export function singleImageSize(image: Pick<ImageAttachment, "width" | "height">): { width: number; height: number } {
  const ratio = Math.min(4, Math.max(0.25, image.width / image.height));
  return ratio >= 1
    ? { width: SINGLE_EDGE, height: Math.round(SINGLE_EDGE / ratio) }
    : { width: Math.round(SINGLE_EDGE * ratio), height: SINGLE_EDGE };
}

/** 用户气泡里的附件：一张图片时按比例显示；多个时图片是 64 px 方块、文件是 240×64 的卡片。 */
export function AttachmentView({ attachments }: Props) {
  const [open, setOpen] = useState<{ image: ImageAttachment; src: string } | null>(null);
  if (attachments.length === 0) return null;
  const single = attachments.length === 1 && attachments[0]?.kind === "image";
  return (
    <div className="flex max-w-[85%] flex-wrap justify-end gap-2">
      {attachments.map((ref, index) =>
        ref.kind === "image" ? (
          <ImageTile key={`${ref.id}-${index}`} image={ref} single={single} onOpen={(src) => setOpen({ image: ref, src })} />
        ) : (
          <FileCard key={`${ref.id}-${index}`} file={ref} />
        ),
      )}
      {open !== null && <Lightbox image={open.image} src={open.src} onClose={() => setOpen(null)} />}
    </div>
  );
}

/**
 * 图片的地址：这个页面里刚上传的先用本地预览（需求 5.4），服务器的图在后台取到后再换上。服务器的图取不到时显示“点击重试”。
 */
function useImageSource(image: ImageAttachment) {
  const server = attachmentUrl(image);
  const [src, setSrc] = useState(() => localPreview(image.id) ?? server);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    if (src === server) return;
    const probe = new Image();
    probe.onload = () => setSrc(server);
    probe.src = server;
    return () => {
      probe.onload = null;
    };
  }, [server, src]);
  return {
    src,
    failed,
    attempt,
    onError: () => {
      if (src === server) setFailed(true);
    },
    retry: () => {
      setFailed(false);
      setAttempt((value) => value + 1);
    },
  };
}

function ImageTile({ image, single, onOpen }: { image: ImageAttachment; single: boolean; onOpen: (src: string) => void }) {
  const t = useText();
  const { src, failed, attempt, onError, retry } = useImageSource(image);
  const size = single ? singleImageSize(image) : { width: TILE, height: TILE };
  if (failed) {
    return (
      <button
        type="button"
        onClick={retry}
        style={size}
        className="flex items-center justify-center rounded-lg border border-line bg-muted px-1 text-center text-[11px] text-fg-muted"
      >
        {t.attachment.loadFailed}
      </button>
    );
  }
  return (
    <button
      type="button"
      draggable
      onDragStart={(event) => writeDragRef(event.dataTransfer, image)}
      onClick={() => onOpen(src)}
      aria-label={t.attachment.openOriginalLabel(image.name)}
      title={image.name}
      style={size}
      className="overflow-hidden rounded-lg border border-line bg-muted"
    >
      <img key={attempt} src={src} alt={image.name} onError={onError} className="h-full w-full object-cover" />
    </button>
  );
}

function FileCard({ file }: { file: FileAttachment }) {
  const t = useText();
  const detail = [formatBytes(file.bytes), file.pages === undefined ? "" : t.attachment.pages(file.pages)].filter((part) => part !== "").join(" · ");
  return (
    <a
      href={attachmentUrl(file, true)}
      download={file.name}
      draggable
      onDragStart={(event) => writeDragRef(event.dataTransfer, file)}
      aria-label={t.attachment.downloadNamed(file.name)}
      title={file.name}
      className="flex h-16 w-60 items-center gap-2.5 rounded-lg border border-line bg-raised px-3 text-left hover:bg-hover"
    >
      <FileText size={22} className="shrink-0 text-fg-muted" aria-hidden="true" />
      <span className="min-w-0">
        <span className="block truncate text-sm text-fg">{file.name}</span>
        <span className="block text-xs text-fg-faint">{detail}</span>
      </span>
    </a>
  );
}

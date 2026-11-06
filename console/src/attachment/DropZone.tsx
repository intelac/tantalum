import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import type { AttachmentRef } from "../api/types";
import { currentText, useText } from "../text";
import { carriesAttachments, hasDirectory, readDragRef } from "./dragData";

interface Props {
  /** 写明图片的数量和大小限制；不能添加时为空。 */
  limitsText: string | null;
  onFiles: (files: File[]) => void;
  onRef: (ref: AttachmentRef) => void;
  onRefused: (reason: string) => void;
}

/**
 * 整页的放置区：拖着文件经过页面时盖一层提示（同 DSH `DropOverlay`），放下就加进草稿。
 * 提示层不接事件，计数靠 document 上的 dragenter / dragleave，拖过子元素时不会闪。文件夹拒绝并说明。
 */
export function DropZone({ limitsText, onFiles, onRef, onRefused }: Props) {
  const t = useText();
  const [visible, setVisible] = useState(false);
  const depth = useRef(0);
  const handlers = useRef({ onFiles, onRef, onRefused });
  handlers.current = { onFiles, onRef, onRefused };

  useEffect(() => {
    function enter(event: DragEvent) {
      if (!carriesAttachments(event.dataTransfer)) return;
      event.preventDefault();
      depth.current += 1;
      setVisible(true);
    }
    function over(event: DragEvent) {
      if (!carriesAttachments(event.dataTransfer)) return;
      event.preventDefault();
      if (event.dataTransfer !== null) event.dataTransfer.dropEffect = "copy";
    }
    function leave(event: DragEvent) {
      if (!carriesAttachments(event.dataTransfer)) return;
      depth.current = Math.max(0, depth.current - 1);
      if (depth.current === 0) setVisible(false);
    }
    function drop(event: DragEvent) {
      const data = event.dataTransfer;
      if (!carriesAttachments(data) || data === null) return;
      event.preventDefault();
      depth.current = 0;
      setVisible(false);
      const ref = readDragRef(data);
      if (ref !== null) {
        handlers.current.onRef(ref);
        return;
      }
      if (hasDirectory(data)) {
        handlers.current.onRefused(currentText().attachment.directory);
        return;
      }
      const files = Array.from(data.files);
      if (files.length > 0) handlers.current.onFiles(files);
    }
    document.addEventListener("dragenter", enter);
    document.addEventListener("dragover", over);
    document.addEventListener("dragleave", leave);
    document.addEventListener("drop", drop);
    return () => {
      document.removeEventListener("dragenter", enter);
      document.removeEventListener("dragover", over);
      document.removeEventListener("dragleave", leave);
      document.removeEventListener("drop", drop);
    };
  }, []);

  if (!visible) return null;
  return createPortal(
    <div role="status" className="pointer-events-none fixed inset-0 z-50 flex items-center justify-center bg-overlay">
      <div className="flex flex-col items-center gap-2 rounded-2xl border-2 border-dashed border-line-strong bg-raised px-10 py-8 text-center shadow-xl">
        <p className="text-base font-medium text-fg">{limitsText === null ? t.attachment.dropBlocked : t.attachment.dropTitle}</p>
        {limitsText !== null && <p className="text-sm text-fg-muted">{limitsText}</p>}
      </div>
    </div>,
    document.body,
  );
}

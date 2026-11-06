import { Download, Maximize2, Minimize2, X } from "lucide-react";
import type { KeyboardEvent } from "react";
import { useEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { attachmentUrl } from "../api/client";
import type { ImageAttachment } from "../api/types";
import { useText } from "../text";

interface Props {
  image: ImageAttachment;
  /** 正在显示的地址：发送中是本地预览，之后是服务器地址。 */
  src: string;
  onClose: () => void;
}

const FOCUSABLE = 'a[href], button:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * 看大图（需求 5.2）：Esc、点遮罩或关闭按钮关闭；可以在“适应窗口”和“实际大小”之间切换，可以下载。
 * 打开时焦点进灯箱、Tab 在灯箱里循环，关闭后焦点回到打开它的图片。
 */
export function Lightbox({ image, src, onClose }: Props) {
  const t = useText();
  const panel = useRef<HTMLDivElement>(null);
  const [actual, setActual] = useState(false);

  useEffect(() => {
    const opener = document.activeElement;
    panel.current?.querySelector<HTMLElement>(FOCUSABLE)?.focus();
    return () => {
      if (opener instanceof HTMLElement) opener.focus();
    };
  }, []);

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>): void {
    if (event.key === "Escape") {
      event.stopPropagation();
      onClose();
      return;
    }
    if (event.key !== "Tab" || panel.current === null) return;
    const focusable = Array.from(panel.current.querySelectorAll<HTMLElement>(FOCUSABLE));
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    if (first === undefined || last === undefined) return;
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }

  const control = "flex h-9 w-9 items-center justify-center rounded-full bg-raised/90 text-fg shadow hover:bg-raised";
  return createPortal(
    <div
      ref={panel}
      role="dialog"
      aria-modal="true"
      aria-label={t.attachment.preview}
      onKeyDown={onKeyDown}
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
      className="fixed inset-0 z-50 flex items-center justify-center overflow-auto bg-overlay p-6"
    >
      <div className="absolute top-3 right-3 flex gap-2">
        <button
          type="button"
          className={control}
          aria-label={actual ? t.attachment.fitWindow : t.attachment.actualSize}
          title={actual ? t.attachment.fitWindow : t.attachment.actualSize}
          onClick={() => setActual((value) => !value)}
        >
          {actual ? <Minimize2 size={16} /> : <Maximize2 size={16} />}
        </button>
        <a className={control} href={attachmentUrl(image, true)} download={image.name} aria-label={t.attachment.downloadNamed(image.name)}>
          <Download size={16} />
        </a>
        <button type="button" className={control} aria-label={t.attachment.closePreview} onClick={onClose}>
          <X size={16} />
        </button>
      </div>
      <img
        src={src}
        alt={image.name}
        width={image.width}
        height={image.height}
        className={actual ? "max-w-none" : "max-h-full max-w-full object-contain"}
        style={actual ? undefined : { width: "auto", height: "auto" }}
      />
    </div>,
    document.body,
  );
}

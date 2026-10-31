import type { KeyboardEvent, ReactNode } from "react";
import { useEffect, useId, useRef } from "react";
import { zh } from "../text/zh";
import { Button } from "./Button";
import { cx } from "./cx";

interface Props {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  className?: string;
  /** 不显示标题栏时由内容自己放标题，标题仍用于无障碍名称。 */
  hideTitle?: boolean;
}

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/** 居中大圆角弹窗。Esc 和遮罩关闭；打开时聚焦第一个控件，关闭后把焦点还给打开它的控件；Tab 在弹窗内循环。 */
export function Dialog({ open, onClose, title, children, className, hideTitle = false }: Props) {
  const panel = useRef<HTMLDivElement>(null);
  const opener = useRef<Element | null>(null);
  const titleId = useId();

  useEffect(() => {
    if (!open) return;
    opener.current = document.activeElement;
    const first = panel.current?.querySelector<HTMLElement>(FOCUSABLE) ?? panel.current;
    first?.focus();
    return () => {
      const back = opener.current;
      if (back instanceof HTMLElement) back.focus();
    };
  }, [open]);

  if (!open) return null;

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>): void {
    if (event.key === "Escape") {
      event.stopPropagation();
      onClose();
      return;
    }
    if (event.key !== "Tab" || panel.current === null) return;
    const focusable = Array.from(panel.current.querySelectorAll<HTMLElement>(FOCUSABLE));
    if (focusable.length === 0) return;
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

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-overlay p-4"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onClose();
      }}
    >
      <div
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        onKeyDown={onKeyDown}
        className={cx(
          "max-h-full w-full max-w-2xl overflow-auto rounded-2xl border border-line bg-raised text-fg shadow-xl outline-none",
          className,
        )}
      >
        <div className={cx("flex items-center justify-between gap-2 px-6 pt-5", hideTitle && "sr-only")}>
          <h2 id={titleId} className="text-base font-semibold">
            {title}
          </h2>
          {!hideTitle && (
            <Button variant="icon" size="sm" aria-label={zh.common.close} onClick={onClose}>
              <CloseIcon />
            </Button>
          )}
        </div>
        {children}
      </div>
    </div>
  );
}

function CloseIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
      <path d="M18 6 6 18M6 6l12 12" />
    </svg>
  );
}

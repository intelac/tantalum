import type { KeyboardEvent, ReactNode } from "react";
import { useEffect, useId, useRef } from "react";
import { useText } from "../text";
import { Button } from "./Button";
import { cx } from "./cx";

interface Props {
  open: boolean;
  onClose: () => void;
  title: string;
  children: ReactNode;
  className?: string;
}

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/** 居中大圆角弹窗。Esc 和遮罩关闭（焦点掉到弹窗外也算）；打开时聚焦第一个控件，关闭后把焦点还给打开它的控件；Tab 在弹窗内循环。 */
export function Dialog({ open, onClose, title, children, className }: Props) {
  const t = useText();
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

  // 焦点所在的按钮被禁用（比如点了“保存”）后，焦点掉到 body 上，弹窗自己收不到按键：这时 Esc 照样关，Tab 回到弹窗里
  const close = useRef(onClose);
  close.current = onClose;
  useEffect(() => {
    if (!open) return;
    function onStray(event: globalThis.KeyboardEvent): void {
      const box = panel.current;
      if (box === null || box.contains(document.activeElement)) return;
      // 焦点在更上面一层弹窗或菜单里时不管
      if (document.activeElement !== document.body && document.activeElement !== null) return;
      // 叠着两个弹窗时只有最上面那个响应
      const dialogs = document.querySelectorAll('[role="dialog"]');
      if (dialogs[dialogs.length - 1] !== box) return;
      if (event.key === "Escape") {
        close.current();
      } else if (event.key === "Tab") {
        event.preventDefault();
        (box.querySelector<HTMLElement>(FOCUSABLE) ?? box).focus();
      }
    }
    document.addEventListener("keydown", onStray);
    return () => document.removeEventListener("keydown", onStray);
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
        <div className="flex items-center justify-between gap-2 px-6 pt-5">
          <h2 id={titleId} className="text-base font-semibold">
            {title}
          </h2>
          <Button variant="icon" size="sm" aria-label={t.common.close} onClick={onClose}>
            <CloseIcon />
          </Button>
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

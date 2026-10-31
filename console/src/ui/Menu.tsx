import type { KeyboardEvent, ReactNode } from "react";
import { useEffect, useId, useRef, useState } from "react";
import { Button, type ButtonProps } from "./Button";
import { cx } from "./cx";

export interface MenuItem {
  id: string;
  label: string;
  icon?: ReactNode;
  danger?: boolean;
  onSelect: () => void;
}

interface Props {
  /** 触发按钮的内容。 */
  children: ReactNode;
  items: readonly MenuItem[];
  label: string;
  align?: "start" | "end";
  /** 弹出方向，侧栏底部的菜单向上弹。 */
  direction?: "down" | "up";
  buttonProps?: Omit<ButtonProps, "children" | "onClick">;
}

/** 弹出菜单。方向键移动，Enter 选中，Esc 或点击外部关闭；关闭后焦点回到触发按钮。 */
export function Menu({ children, items, label, align = "start", direction = "down", buttonProps }: Props) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const list = useRef<HTMLDivElement>(null);
  const menuId = useId();

  useEffect(() => {
    if (!open) return;
    list.current?.querySelector<HTMLElement>("[role=menuitem]")?.focus();
    function onPointerDown(event: PointerEvent): void {
      if (root.current !== null && !root.current.contains(event.target as Node)) setOpen(false);
    }
    document.addEventListener("pointerdown", onPointerDown);
    return () => document.removeEventListener("pointerdown", onPointerDown);
  }, [open]);

  function close(): void {
    setOpen(false);
    trigger.current?.focus();
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>): void {
    const options = Array.from(list.current?.querySelectorAll<HTMLElement>("[role=menuitem]") ?? []);
    const index = options.indexOf(document.activeElement as HTMLElement);
    if (event.key === "Escape") {
      event.preventDefault();
      close();
    } else if (event.key === "ArrowDown") {
      event.preventDefault();
      options[(index + 1) % options.length]?.focus();
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      options[(index - 1 + options.length) % options.length]?.focus();
    } else if (event.key === "Home") {
      event.preventDefault();
      options[0]?.focus();
    } else if (event.key === "End") {
      event.preventDefault();
      options[options.length - 1]?.focus();
    } else if (event.key === "Tab") {
      setOpen(false);
    }
  }

  return (
    <div ref={root} className="relative inline-block">
      <Button
        ref={trigger}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        aria-label={label}
        onClick={() => setOpen((current) => !current)}
        {...buttonProps}
      >
        {children}
      </Button>
      {open && (
        <div
          ref={list}
          id={menuId}
          role="menu"
          aria-label={label}
          onKeyDown={onKeyDown}
          className={cx(
            "absolute z-40 min-w-44 rounded-md border border-line bg-raised p-1 shadow-lg",
            align === "end" ? "right-0" : "left-0",
            direction === "up" ? "bottom-full mb-1" : "top-full mt-1",
          )}
        >
          {items.map((item) => (
            <button
              key={item.id}
              type="button"
              role="menuitem"
              tabIndex={-1}
              onClick={() => {
                close();
                item.onSelect();
              }}
              className={cx(
                "flex w-full items-center gap-2 rounded-sm px-2.5 py-1.5 text-left text-sm hover:bg-hover focus:bg-hover focus:outline-none",
                item.danger ? "text-danger" : "text-fg",
              )}
            >
              {item.icon}
              {item.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

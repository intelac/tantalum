import type { KeyboardEvent } from "react";
import { useRef } from "react";
import { cx } from "./cx";

export interface Tab {
  id: string;
  label: string;
}

interface Props {
  tabs: readonly Tab[];
  selected: string;
  onSelect: (id: string) => void;
  label: string;
  className?: string;
}

/** 选中项带下划线。方向键在标签间移动并选中，Home/End 到两端。 */
export function Tabs({ tabs, selected, onSelect, label, className }: Props) {
  const list = useRef<HTMLDivElement>(null);

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>): void {
    const index = tabs.findIndex((tab) => tab.id === selected);
    let next = index;
    if (event.key === "ArrowRight") next = (index + 1) % tabs.length;
    else if (event.key === "ArrowLeft") next = (index - 1 + tabs.length) % tabs.length;
    else if (event.key === "Home") next = 0;
    else if (event.key === "End") next = tabs.length - 1;
    else return;
    event.preventDefault();
    const target = tabs[next];
    if (target === undefined) return;
    onSelect(target.id);
    list.current?.querySelectorAll<HTMLButtonElement>("[role=tab]")[next]?.focus();
  }

  return (
    <div ref={list} role="tablist" aria-label={label} className={cx("flex gap-4", className)} onKeyDown={onKeyDown}>
      {tabs.map((tab) => {
        const active = tab.id === selected;
        return (
          <button
            key={tab.id}
            type="button"
            role="tab"
            id={`tab-${tab.id}`}
            aria-selected={active}
            tabIndex={active ? 0 : -1}
            onClick={() => onSelect(tab.id)}
            className={cx(
              "-mb-px border-b-2 px-1 py-2 text-sm transition-colors duration-[var(--duration-fast)]",
              active ? "border-fg font-medium text-fg" : "border-transparent text-fg-muted hover:text-fg",
            )}
          >
            {tab.label}
          </button>
        );
      })}
    </div>
  );
}

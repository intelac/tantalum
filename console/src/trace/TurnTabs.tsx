import type { KeyboardEvent } from "react";
import { useEffect, useRef } from "react";
import { useText } from "../text";
import { cx } from "../ui/cx";
import type { TurnTab } from "./turns";

interface Props {
  tabs: readonly TurnTab[];
  /** 选中的运行；null 是“全部”。 */
  selected: string | null;
  onSelect: (run: string | null) => void;
  /** 还有更早的轮没加载。 */
  hasEarlier: boolean;
  onEarlier: () => void;
}

const TAB = "flex h-7 shrink-0 items-center gap-1.5 rounded-md px-2 text-xs whitespace-nowrap";

/**
 * 轮次标签（trace-rounds 需求 1）：“全部”和已加载的每一轮。点一轮只看这一轮；悬停或聚焦显示这一轮的提问。
 * 轮多了横向滚动；左右方向键在标签间移动，回车或空格选中。
 */
export function TurnTabs({ tabs, selected, onSelect, hasEarlier, onEarlier }: Props) {
  const t = useText().trace;
  const list = useRef<HTMLDivElement>(null);

  // 选中的标签滚进可见范围（轮多、从地址或别处选中时）
  useEffect(() => {
    const current = list.current?.querySelector<HTMLElement>('[aria-selected="true"]');
    current?.scrollIntoView?.({ block: "nearest", inline: "nearest" });
  }, [selected, tabs.length]);

  function move(event: KeyboardEvent<HTMLDivElement>): void {
    if (event.key !== "ArrowLeft" && event.key !== "ArrowRight" && event.key !== "Home" && event.key !== "End") return;
    const buttons = [...(list.current?.querySelectorAll<HTMLButtonElement>('[role="tab"]') ?? [])];
    const at = buttons.findIndex((button) => button === document.activeElement);
    if (buttons.length === 0) return;
    event.preventDefault();
    const next =
      event.key === "Home" ? 0 : event.key === "End" ? buttons.length - 1 : (Math.max(0, at) + (event.key === "ArrowRight" ? 1 : -1) + buttons.length) % buttons.length;
    buttons[next]?.focus();
  }

  return (
    <div className="flex min-w-0 flex-1 items-center gap-1 overflow-x-auto">
      {hasEarlier && (
        <button type="button" onClick={onEarlier} className={cx(TAB, "text-fg-faint hover:bg-hover hover:text-fg")}>
          {t.turnTabs.earlier}
        </button>
      )}
      <div ref={list} role="tablist" aria-label={t.turnTabs.aria} className="flex items-center gap-1" onKeyDown={move}>
        <button
          type="button"
          role="tab"
          aria-selected={selected === null}
          tabIndex={selected === null ? 0 : -1}
          onClick={() => onSelect(null)}
          className={cx(TAB, selected === null ? "bg-active font-medium text-fg" : "text-fg-muted hover:bg-hover hover:text-fg")}
        >
          {t.turnTabs.all}
        </button>
        {tabs.map((tab) => {
          const active = selected === tab.run;
          const state = tab.state === "done" ? "" : t.turnTabs.state[tab.state];
          // 提示：这一轮的提问；没有人写的提问时说它是怎么触发的
          const about = tab.prompt !== undefined && tab.prompt !== "" ? tab.prompt : tab.trigger === undefined ? "" : (t.trigger[tab.trigger] ?? tab.trigger);
          return (
            <button
              key={tab.run}
              type="button"
              role="tab"
              aria-selected={active}
              tabIndex={active ? 0 : -1}
              title={[about, state].filter((part) => part !== "").join(" · ")}
              data-state={tab.state}
              onClick={() => onSelect(tab.run)}
              className={cx(
                TAB,
                active ? "bg-active font-medium text-fg" : "text-fg-muted hover:bg-hover hover:text-fg",
                tab.state === "failed" && "text-danger",
              )}
            >
              {t.turnLabel(tab.turn)}
              {tab.state === "running" && <span aria-hidden className="size-1.5 animate-pulse rounded-full bg-accent" />}
              {tab.state === "waiting" && <span aria-hidden className="size-1.5 rounded-full bg-warning" />}
              {state !== "" && <span className="sr-only">{state}</span>}
            </button>
          );
        })}
      </div>
    </div>
  );
}

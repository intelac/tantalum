import { ChevronUp } from "lucide-react";
import { type RefObject, useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import type { ConversationStore } from "../store/ConversationStore";
import { useText } from "../text";
import { cx } from "../ui/cx";
import type { Block } from "./blocks";
import { MIN_WIDTH, currentTurn, turnLabel } from "./turns";

/** 滚动时最多每隔多久重算一次当前轮。 */
const TRACK_MS = 50;

interface Props {
  store: ConversationStore;
  blocks: readonly Block[];
  /** 消息列表的滚动区，用它的宽度决定显不显示，用它的滚动算当前轮。 */
  scroller: RefObject<HTMLDivElement | null>;
  hasMore: boolean;
  onLoadOlder: () => void;
  /** 跳转之前调用：停止跟随底部。 */
  onJump: () => void;
}

interface Turn {
  id: string;
  label: string;
}

/**
 * 轮次导航（session-find-pin R4，DSH TurnNavigator）：对话右侧一列刻度，一个用户消息一个，间距固定；悬停或聚焦时在左边显示提问开头；
 * 点击把那条消息滚到顶部；当前读到的那一轮加粗。只列已加载的轮次，前面还有时顶上一个“加载更早”。
 * 至少两轮、对话区宽于 {@link MIN_WIDTH} 时才显示。
 */
export function TurnRail({ store, blocks, scroller, hasMore, onLoadOlder, onJump }: Props) {
  const t = useText();
  const rail = useRef<HTMLDivElement>(null);
  const [wide, setWide] = useState(false);
  const [active, setActive] = useState(-1);
  const [peek, setPeek] = useState<{ index: number; top: number } | null>(null);

  const turns: Turn[] = [];
  for (const block of blocks) {
    if (block.kind !== "user") continue;
    const item = store.getItem(block.id);
    if (item === undefined || item.kind !== "user" || item.removed === true) continue;
    turns.push({ id: block.id, label: turnLabel(item.text, t.conversation.attachmentOnly) });
  }
  const ids = turns.map((turn) => turn.id).join("\n");

  useEffect(() => {
    const element = scroller.current;
    if (element === null) return;
    const measure = () => setWide(element.clientWidth > MIN_WIDTH);
    measure();
    if (typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(measure);
    observer.observe(element);
    return () => observer.disconnect();
  }, [scroller]);

  const track = useCallback(() => {
    const element = scroller.current;
    if (element === null) return;
    const top = element.getBoundingClientRect().top;
    const tops = ids === "" ? [] : ids.split("\n").map((id) => {
      const bubble = element.querySelector(`[data-turn="${CSS.escape(id)}"]`);
      return bubble === null ? Number.POSITIVE_INFINITY : bubble.getBoundingClientRect().top - top;
    });
    setActive(currentTurn(tops, element.clientHeight / 3));
  }, [scroller, ids]);

  useEffect(() => {
    const element = scroller.current;
    if (element === null || !wide) return;
    // 节流用定时器而不用 requestAnimationFrame：页面忙或在后台时动画帧可能迟迟不来，当前轮就停在旧值
    let timer = 0;
    const onScroll = () => {
      if (timer === 0)
        timer = window.setTimeout(() => {
          timer = 0;
          track();
        }, TRACK_MS);
    };
    track();
    // 新的一轮刚加进来时，跟随到底部的滚动可能还没发生（或者它的滚动事件落在重新订阅之前）：过一会儿再算两次，
    // 不然当前轮会停在上一轮（Windows CI 上偶尔看到）
    // 机器忙时跟随滚动可能更晚（2026-11-16 本机回归里三次有一次停在上一轮）：再晚一点多算两次，算一次很便宜
    const late = [150, 600, 1500, 3000].map((delay) => window.setTimeout(track, delay));
    element.addEventListener("scroll", onScroll, { passive: true });
    // 内容变高（回复流进来、展开）不一定有滚动事件，也要重算
    const observer = typeof ResizeObserver === "undefined" ? null : new ResizeObserver(onScroll);
    if (element.firstElementChild !== null) observer?.observe(element.firstElementChild);
    return () => {
      element.removeEventListener("scroll", onScroll);
      observer?.disconnect();
      late.forEach((each) => window.clearTimeout(each));
      if (timer !== 0) window.clearTimeout(timer);
    };
  }, [scroller, wide, track]);

  // 刻度多到一屏放不下时，导航自己滚动；没在悬停时让当前轮的刻度留在可见范围里
  useLayoutEffect(() => {
    const list = rail.current;
    if (list === null || active < 0 || peek !== null) return;
    const tick = list.children[active] as HTMLElement | undefined;
    if (tick === undefined) return;
    if (tick.offsetTop < list.scrollTop) list.scrollTop = tick.offsetTop;
    else if (tick.offsetTop + tick.offsetHeight > list.scrollTop + list.clientHeight) list.scrollTop = tick.offsetTop + tick.offsetHeight - list.clientHeight;
  }, [active, peek]);

  if (!wide || turns.length < 2) return null;

  function jump(id: string): void {
    const bubble = scroller.current?.querySelector(`[data-turn="${CSS.escape(id)}"]`);
    if (bubble === null || bubble === undefined) return;
    onJump();
    bubble.scrollIntoView({ block: "start" });
  }

  function show(index: number, target: HTMLElement): void {
    const list = rail.current;
    setPeek({ index, top: target.offsetTop - (list?.scrollTop ?? 0) + (list?.offsetTop ?? 0) + target.offsetHeight / 2 });
  }

  const peeked = peek === null ? undefined : turns[peek.index];
  return (
    <nav aria-label={t.conversation.turns} className="absolute top-1/2 right-1 flex -translate-y-1/2 flex-col items-end" onMouseLeave={() => setPeek(null)}>
      {hasMore && (
        <button
          type="button"
          aria-label={t.conversation.earlierTurns}
          title={t.conversation.earlierTurns}
          onClick={onLoadOlder}
          className="mb-1 flex h-5 w-6 items-center justify-center rounded-sm text-fg-faint hover:bg-hover hover:text-fg"
        >
          <ChevronUp size={14} />
        </button>
      )}
      <div ref={rail} className="flex max-h-[60vh] flex-col overflow-y-auto [scrollbar-width:none]">
        {turns.map((turn, index) => (
          <button
            key={turn.id}
            type="button"
            aria-label={t.conversation.jumpToTurn(index + 1, turn.label)}
            aria-current={index === active ? "location" : undefined}
            onClick={() => jump(turn.id)}
            onMouseEnter={(event) => show(index, event.currentTarget)}
            onFocus={(event) => show(index, event.currentTarget)}
            onBlur={() => setPeek(null)}
            className="flex h-2.5 w-6 shrink-0 items-center justify-end rounded-xs focus-visible:outline-1 focus-visible:outline-accent"
          >
            <span
              aria-hidden="true"
              className={cx(
                "h-0.5 rounded-full transition-[width,background-color] duration-[var(--duration-fast)]",
                index === active ? "w-4 bg-fg" : "w-2.5 bg-fg-faint",
                peek?.index === index && "w-4 bg-fg-muted",
              )}
            />
          </button>
        ))}
      </div>
      {peeked !== undefined && peek !== null && (
        <div
          role="tooltip"
          style={{ top: peek.top }}
          className="pointer-events-none absolute right-full mr-2 max-w-72 -translate-y-1/2 truncate rounded-md border border-line bg-raised px-2.5 py-1.5 text-xs text-fg shadow-md"
        >
          {peeked.label}
        </div>
      )}
    </nav>
  );
}

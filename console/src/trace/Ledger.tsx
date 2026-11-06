// 记录表：虚拟滚动，跟随末尾，滚到顶部加载更早的轮。参数照 DSH TrajectoryTable.tsx（BOTTOM_FOLLOW_THRESHOLD_PX、
// OLDER_LOAD_THRESHOLD_PX、HISTORY_LOAD_ROW_HEIGHT_PX、VIRTUAL_OVERSCAN_ROWS，以及 useVirtualizer 的 anchorTo / followOnAppend）。

import { useVirtualizer } from "@tanstack/react-virtual";
import { useCallback, useEffect, useRef, useState } from "react";
import { useText } from "../text";
import { cx } from "../ui/cx";
import type { Row } from "./rows";
import { LedgerHeader, LedgerRow } from "./LedgerRow";
import type { TracePaging } from "./TraceStore";

export const BOTTOM_FOLLOW_THRESHOLD_PX = 2;
export const OLDER_LOAD_THRESHOLD_PX = 48;
export const HISTORY_LOAD_ROW_HEIGHT_PX = 30;
const OVERSCAN_ROWS = 12;
/** 容器窄于这个宽度时收起输入、输出、思考三列（DSH `@container trajectory-table (max-width: 620px)`）。 */
export const COMPACT_WIDTH_PX = 620;

function estimate(row: Row | undefined): number {
  if (row === undefined) return HISTORY_LOAD_ROW_HEIGHT_PX;
  switch (row.type) {
    case "turn":
      return 40;
    case "group":
      return 24;
    case "summary":
      return 20;
    case "record":
      return row.record.kind === "request" ? 46 : 30;
  }
}

interface Props {
  rows: readonly Row[];
  paging: TracePaging;
  selected: string | null;
  /** 命中的记录 ID；摘要行藏着命中的记录时也标出。 */
  matches: ReadonlySet<string>;
  /** 变了就滚到这一行（行键），用于定位和搜索跳转。 */
  scrollTo: { key: string; nonce: number } | null;
  onLoadOlder: () => void;
  onSelect: (id: string) => void;
  onToggleTurn: (run: string) => void;
  onToggleCalls: (request: string) => void;
}

export function Ledger({ rows, paging, selected, matches, scrollTo, onLoadOlder, onSelect, onToggleTurn, onToggleCalls }: Props) {
  const t = useText().trace;
  const scroller = useRef<HTMLDivElement>(null);
  const [compact, setCompact] = useState(false);
  // 第一次定位到末尾之前，用加载行遮住真实记录（DSH README“使用本包”第 1 段）。
  const [positioned, setPositioned] = useState(false);
  const loadRow = paging.hasMore || paging.loading;
  const offset = loadRow ? 1 : 0;
  const count = rows.length + offset;

  const virtualizer = useVirtualizer<HTMLDivElement, HTMLDivElement>({
    count,
    estimateSize: (index) => estimate(rows[index - offset]),
    getItemKey: (index) => rows[index - offset]?.key ?? "load",
    getScrollElement: () => scroller.current,
    initialRect: { width: 0, height: 600 },
    anchorTo: "end",
    followOnAppend: "auto",
    scrollEndThreshold: BOTTOM_FOLLOW_THRESHOLD_PX,
    overscan: OVERSCAN_ROWS,
  });

  useEffect(() => {
    const element = scroller.current;
    if (element === null || typeof ResizeObserver === "undefined") return;
    const observer = new ResizeObserver(([entry]) => setCompact((entry?.contentRect.width ?? COMPACT_WIDTH_PX) < COMPACT_WIDTH_PX));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  // 第一次定位：有定位目标（地址里的记录或运行）就滚到目标，否则滚到末尾。之后的目标变化由下面的效果处理。
  useEffect(() => {
    if (positioned || !paging.loaded) return;
    const target = scrollTo === null ? -1 : rows.findIndex((row) => row.key === scrollTo.key);
    if (target >= 0) virtualizer.scrollToIndex(target + offset, { align: "center" });
    else if (rows.length > 0) virtualizer.scrollToIndex(count - 1, { align: "end" });
    setPositioned(true);
  }, [positioned, paging.loaded, rows, count, offset, scrollTo, virtualizer]);

  useEffect(() => {
    if (scrollTo === null || !positioned) return;
    const index = rows.findIndex((row) => row.key === scrollTo.key);
    if (index >= 0) virtualizer.scrollToIndex(index + offset, { align: "center" });
    // 只在目标变化时滚动；行变化不重新滚。
  }, [scrollTo]);

  const onScroll = useCallback(() => {
    const element = scroller.current;
    if (element === null || !positioned) return;
    if (element.scrollTop <= OLDER_LOAD_THRESHOLD_PX && paging.hasMore && !paging.loading) onLoadOlder();
  }, [positioned, paging.hasMore, paging.loading, onLoadOlder]);

  const items = virtualizer.getVirtualItems();
  return (
    <div role="table" aria-label={t.title} aria-rowcount={count} className="flex h-full min-h-0 flex-col">
      <LedgerHeader compact={compact} />
      <div ref={scroller} onScroll={onScroll} className="relative min-h-0 flex-1 overflow-y-auto">
        {!positioned && (
          <div className="absolute inset-0 z-10 flex items-start bg-canvas px-3 pt-2 text-xs text-fg-faint">{t.history.loadingTrajectory}</div>
        )}
        <div role="rowgroup" style={{ height: virtualizer.getTotalSize(), position: "relative" }}>
          {items.map((item) => {
            const row = rows[item.index - offset];
            return (
              <div
                key={item.key}
                data-index={item.index}
                ref={virtualizer.measureElement}
                aria-rowindex={item.index + 1}
                className="absolute left-0 w-full"
                style={{ transform: `translateY(${item.start}px)` }}
              >
                {row === undefined ? (
                  <LoadRow loading={paging.loading} onLoad={onLoadOlder} />
                ) : (
                  <LedgerRow
                    row={row}
                    compact={compact}
                    selected={row.type === "record" && row.record.id === selected}
                    matched={row.type === "record" ? matches.has(row.record.id) : row.type === "summary" && row.hidden.some((id) => matches.has(id))}
                    onSelect={onSelect}
                    onToggleTurn={onToggleTurn}
                    onToggleCalls={onToggleCalls}
                  />
                )}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

function LoadRow({ loading, onLoad }: { loading: boolean; onLoad: () => void }) {
  const t = useText().trace;
  return (
    <div role="row" className="flex items-center px-2" style={{ height: HISTORY_LOAD_ROW_HEIGHT_PX }}>
      <button
        type="button"
        disabled={loading}
        onClick={onLoad}
        className={cx("text-xs", loading ? "text-fg-faint" : "text-accent hover:underline")}
        aria-label={loading ? t.history.loadingEarlier : t.history.loadEarlier}
      >
        {loading ? t.history.loadingEarlier : t.history.loadEarlier}
      </button>
    </div>
  );
}

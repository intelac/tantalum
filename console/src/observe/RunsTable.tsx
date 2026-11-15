// 运行列表（observe-page 需求 2.3、2.4、2.7）：虚拟滚动，滚到底自动取下一页；点一行或选中后回车打开。
// 宽屏一行一个运行；窄屏每行两层：时刻、状态、耗时在上，提问和数字在下。

import { useVirtualizer } from "@tanstack/react-virtual";
import { useEffect, type RefObject } from "react";
import { navigate, runPath } from "../app/router";
import type { RunListItem } from "../api/types";
import { useText } from "../text";
import { clockTime } from "../text/format";
import { formatMs, formatTokens } from "../trace/format";
import { EndBadge, runEnd } from "./ends";

interface Props {
  runs: readonly RunListItem[];
  scroller: RefObject<HTMLDivElement | null>;
  /** 列表在滚动区里的起点（上面是筛选和图）。 */
  offset: number;
  hasMore: boolean;
  loadingMore: boolean;
  onMore: () => void;
}

const ROW = 60;
const COLUMNS = "md:grid-cols-[5.5rem_minmax(0,1fr)_minmax(0,2fr)_6rem_5rem_3.5rem_6rem_4.5rem_minmax(0,1fr)]";

function tokensOf(run: RunListItem): number {
  const tokens = run.tally.tokens;
  return tokens.input + tokens.output + tokens.cacheRead + tokens.cacheWrite;
}

export function RunsTable({ runs, scroller, offset, hasMore, loadingMore, onMore }: Props) {
  const text = useText();
  const t = text.observe;
  const virtual = useVirtualizer({
    count: runs.length,
    getScrollElement: () => scroller.current,
    estimateSize: () => ROW,
    overscan: 8,
    scrollMargin: offset,
  });
  const items = virtual.getVirtualItems();
  const last = items.at(-1)?.index ?? -1;
  useEffect(() => {
    if (hasMore && !loadingMore && last >= runs.length - 5) onMore();
  }, [hasMore, loadingMore, last, runs.length, onMore]);

  return (
    <div role="table" aria-label={t.table.label} aria-rowcount={runs.length + 1} className="text-sm">
      <div role="rowgroup" className="hidden md:block">
        <div role="row" className={`grid ${COLUMNS} gap-3 border-b border-line px-3 py-2 text-xs text-fg-faint`}>
          <span role="columnheader">{t.table.start}</span>
          <span role="columnheader">{t.table.session}</span>
          <span role="columnheader">{t.table.prompt}</span>
          <span role="columnheader">{t.table.status}</span>
          <span role="columnheader" className="text-right">{t.table.duration}</span>
          <span role="columnheader" className="text-right">{t.table.requests}</span>
          <span role="columnheader" className="text-right">{t.table.tools}</span>
          <span role="columnheader" className="text-right">{t.table.tokens}</span>
          <span role="columnheader">{t.table.model}</span>
        </div>
      </div>
      <div role="rowgroup" className="relative" style={{ height: virtual.getTotalSize() }}>
        {items.map((item) => {
          const run = runs[item.index] as RunListItem;
          const end = runEnd(run.end, run.endReason);
          const prompt = run.prompt ?? t.noPrompt;
          const open = () => navigate(runPath(run.session, run.run));
          return (
            <div
              key={run.session + run.run}
              role="row"
              tabIndex={0}
              aria-label={t.table.open(prompt)}
              data-index={item.index}
              ref={virtual.measureElement}
              onClick={open}
              onKeyDown={(event) => {
                if (event.key === "Enter") open();
              }}
              className={`absolute left-0 top-0 grid w-full cursor-pointer grid-cols-[auto_1fr_auto] items-center gap-x-3 gap-y-1 border-b border-line px-3 py-2 hover:bg-hover focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-focus ${COLUMNS}`}
              style={{ transform: `translateY(${item.start - virtual.options.scrollMargin}px)` }}
            >
              <span role="cell" className="text-xs tabular-nums text-fg-muted" title={run.start}>
                {clockTime(run.start)}
              </span>
              <span role="cell" className="hidden min-w-0 truncate text-xs text-fg-muted md:block" title={run.title}>
                {run.title === "" ? t.untitled : run.title}
              </span>
              <span role="cell" className="col-span-3 row-start-2 min-w-0 truncate md:col-span-1 md:row-start-auto" title={prompt}>
                {prompt}
              </span>
              <span role="cell" className="justify-self-start">
                <EndBadge end={end} />
              </span>
              <span role="cell" className="text-right text-xs tabular-nums text-fg-muted">
                {run.tally.ms === undefined ? "–" : formatMs(run.tally.ms)}
              </span>
              <span role="cell" className="hidden text-right text-xs tabular-nums text-fg-muted md:block">
                {run.tally.requests}
              </span>
              <span role="cell" className="hidden text-right text-xs tabular-nums md:block">
                <span className="text-fg-muted">{run.tally.toolCalls}</span>
                {run.tally.toolErrors > 0 && <span className="ml-1 text-danger">{t.table.errors(run.tally.toolErrors)}</span>}
              </span>
              <span role="cell" className="hidden text-right text-xs tabular-nums text-fg-muted md:block">
                {formatTokens(tokensOf(run))}
              </span>
              <span role="cell" className="hidden min-w-0 truncate text-xs text-fg-faint md:block" title={run.tally.models.join(", ")}>
                {run.tally.models.join(", ")}
              </span>
            </div>
          );
        })}
      </div>
      {(loadingMore || (!hasMore && runs.length > 0)) && (
        <p className="px-3 py-3 text-center text-xs text-fg-faint">{loadingMore ? t.table.loadingMore : t.table.end}</p>
      )}
    </div>
  );
}

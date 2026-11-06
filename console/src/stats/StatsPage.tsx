// 统计页签：和轨迹共用 TraceStore 和同一套分页（需求 8.4）：顶部的“加载更早的轮次”和轨迹的滚动加载是同一个请求，
// 轨迹正在加载同一页时这里显示加载中，不会误报已加载完。页脚开关：合计行、上下文增量，记在本机。

import { useMemo, useState } from "react";
import { useText } from "../text";
import { Button } from "../ui/Button";
import { localArea, readStored, writeStored } from "../ui/storage";
import { useLatestTrace, useTracePaging, useTraceRecords } from "../trace/hooks";
import type { TraceStore } from "../trace/TraceStore";
import { buildStats, overallTotals } from "./metrics";
import { StatsTable } from "./StatsTable";
import { errorLine } from "../text/errors";

const TOTALS_KEY = "tantalum.stats.totals";
const DELTA_KEY = "tantalum.stats.delta";

function useStoredFlag(key: string, fallback: boolean): [boolean, (value: boolean) => void] {
  const [value, setValue] = useState(() => {
    const stored = readStored(key, localArea());
    return stored === null ? fallback : stored === "true";
  });
  return [
    value,
    (next) => {
      setValue(next);
      writeStored(key, String(next), localArea());
    },
  ];
}

interface Props {
  store: TraceStore;
  session: string;
}

export function StatsPage({ store, session }: Props) {
  const text = useText();
  const t = text.stats;
  const records = useTraceRecords(store, session);
  const paging = useTracePaging(store, session);
  const error = useLatestTrace(store, session);
  const turns = useMemo(() => buildStats(records), [records]);
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set());
  const [showTotals, setShowTotals] = useStoredFlag(TOTALS_KEY, true);
  // DSH 默认关闭上下文增量。
  const [showDelta, setShowDelta] = useStoredFlag(DELTA_KEY, false);

  if (error !== null && !paging.loaded) {
    return (
      <p role="alert" className="p-4 text-sm text-danger">
        {errorLine(error)}
      </p>
    );
  }
  if (!paging.loaded) return <p className="p-4 text-xs text-fg-faint">{text.trace.history.loadingTrajectory}</p>;
  if (turns.length === 0) return <div className="flex h-full items-center justify-center px-4 text-sm text-fg-faint">{t.empty}</div>;

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="min-h-0 flex-1 overflow-auto">
        {paging.hasMore && (
          <div className="flex h-[30px] items-center px-2">
            <Button size="sm" variant="ghost" disabled={paging.loading} onClick={() => void store.loadOlder(session).catch(() => {})}>
              {paging.loading ? t.loadingEarlier : t.loadEarlier}
            </Button>
          </div>
        )}
        <StatsTable
          turns={turns}
          expanded={expanded}
          onToggle={(run) =>
            setExpanded((current) => {
              const next = new Set(current);
              if (next.has(run)) next.delete(run);
              else next.add(run);
              return next;
            })
          }
          totals={showTotals ? overallTotals(turns) : undefined}
          showDelta={showDelta}
        />
      </div>
      <div className="flex shrink-0 flex-wrap items-center gap-4 border-t border-line px-3 py-2 text-xs text-fg-muted">
        <label className="flex items-center gap-1.5">
          <input type="checkbox" checked={showTotals} onChange={(event) => setShowTotals(event.target.checked)} />
          {t.showTotals}
        </label>
        <label className="flex items-center gap-1.5" title={t.deltaTitle}>
          <input type="checkbox" checked={showDelta} onChange={(event) => setShowDelta(event.target.checked)} />
          {t.showDelta}
        </label>
      </div>
    </div>
  );
}

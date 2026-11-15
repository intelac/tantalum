// 观测页面（observe-page 需求 1–3）：跨会话的运行。上面是筛选、汇总数字和按天的两张图，下面是运行列表。
// 筛选条件在地址里；列表有进行中的运行时自动刷新，汇总跟着列表一起变。

import { useQueryClient } from "@tanstack/react-query";
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { navigate, newSessionId, runsPath, sessionPath } from "../app/router";
import { useViewedUser } from "../api/viewing";
import { useText } from "../text";
import { errorLine } from "../text/errors";
import { Button } from "../ui/Button";
import { fillDays } from "./charts";
import { DayCharts } from "./DayCharts";
import { FilterBar } from "./FilterBar";
import { apiQuery, parseFilters, serializeFilters, windowStart, type Filters } from "./filters";
import { RunsTable } from "./RunsTable";
import { SummaryStrip } from "./SummaryStrip";
import { useRunList, useRunsSummary } from "./useRuns";

export function ObservePage({ query }: { query: string }) {
  const t = useText().observe;
  const queries = useQueryClient();
  const viewing = useViewedUser() !== null;
  const filters = useMemo(() => parseFilters(query), [query]);
  // 时间窗的起点在筛选变了或点刷新时才重算，不随时间漂移（否则每次渲染都换查询）
  const [now, setNow] = useState(() => Date.now());
  const filterKey = serializeFilters(filters);
  useEffect(() => setNow(Date.now()), [filterKey]);
  const listQuery = apiQuery(filters, now);
  const summaryQuery = apiQuery(filters, now, true);
  const list = useRunList(listQuery);
  const summary = useRunsSummary(summaryQuery, list.dataUpdatedAt);
  const runs = useMemo(() => list.data?.pages.flatMap((page) => page.runs) ?? [], [list.data]);
  const bars = useMemo(
    () => (summary.data === undefined ? [] : fillDays(summary.data, windowStart(filters.window, now), now)),
    [summary.data, filters.window, now],
  );

  const scroller = useRef<HTMLDivElement>(null);
  const tableStart = useRef<HTMLDivElement>(null);
  const [offset, setOffset] = useState(0);
  useLayoutEffect(() => {
    const element = tableStart.current;
    if (element !== null) setOffset(element.offsetTop);
  });

  const change = (next: Filters) => navigate(runsPath(serializeFilters(next)), true);
  const refresh = () => {
    setNow(Date.now());
    void queries.invalidateQueries({ queryKey: ["runs"] });
  };
  const more = useCallback(() => void list.fetchNextPage(), [list]);
  const error = list.error ?? summary.error;

  return (
    <div ref={scroller} className="h-full overflow-y-auto">
      <div className="mx-auto flex max-w-6xl flex-col gap-5 px-4 py-4 md:px-6">
        <FilterBar
          filters={filters}
          tools={summary.data?.tools ?? []}
          models={summary.data?.models ?? []}
          onChange={change}
          onRefresh={refresh}
          refreshing={list.isFetching && !list.isFetchingNextPage}
        />
        {error !== null && (
          <div role="alert" className="flex items-center gap-3 rounded-md border border-danger/40 bg-danger-soft px-3 py-2 text-sm text-danger">
            <span className="min-w-0 flex-1">{errorLine(error)}</span>
            <Button size="sm" onClick={refresh}>
              {t.retry}
            </Button>
          </div>
        )}
        <SummaryStrip summary={summary.data} />
        {summary.data !== undefined && summary.data.runs > 0 && <DayCharts bars={bars} />}
        <div ref={tableStart}>
          {list.isPending ? (
            <div aria-busy="true" className="flex flex-col gap-2">
              {[0, 1, 2, 3].map((row) => (
                <div key={row} className="h-12 animate-pulse rounded-md bg-muted" />
              ))}
            </div>
          ) : runs.length === 0 && error === null ? (
            <div className="flex flex-col items-center gap-3 py-16 text-center text-sm text-fg-muted">
              <p className="max-w-md">{t.empty}</p>
              {!viewing && (
                <Button variant="primary" onClick={() => navigate(sessionPath(newSessionId()))}>
                  {t.newConversation}
                </Button>
              )}
            </div>
          ) : (
            <RunsTable
              runs={runs}
              scroller={scroller}
              offset={offset}
              hasMore={list.hasNextPage}
              loadingMore={list.isFetchingNextPage}
              onMore={more}
            />
          )}
        </div>
      </div>
    </div>
  );
}

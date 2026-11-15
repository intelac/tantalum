// 观测页面的数据（observe-page 需求 2.4、2.5、3.3、4.6）：运行列表按游标一页页取，汇总和列表用同一套筛选；
// 有进行中的运行时每 5 秒刷新，运行详情在运行没结束时每 3 秒刷新；页面不在前台时都不刷新。

import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { getRunTree, getRunsSummary, listAllRuns } from "../api/client";
import type { RunListPage, RunNode } from "../api/types";

export const LIST_REFRESH_MS = 5_000;
export const TREE_REFRESH_MS = 3_000;

function visible(): boolean {
  return typeof document === "undefined" || document.visibilityState === "visible";
}

/** `query` 是 `apiQuery` 拼好的参数。 */
export function useRunList(query: string) {
  return useInfiniteQuery({
    queryKey: ["runs", query],
    queryFn: ({ pageParam }) => listAllRuns(query, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last: RunListPage) => last.next ?? undefined,
    refetchInterval: (state) => {
      const pages = state.state.data?.pages ?? [];
      const running = pages.some((page) => page.runs.some((run) => run.end === undefined));
      return running && visible() ? LIST_REFRESH_MS : false;
    },
  });
}

/** 汇总跟着列表刷新：`version` 变了（列表刷新拿到了新数据）就重新取。 */
export function useRunsSummary(query: string, version: number) {
  return useQuery({ queryKey: ["runs-summary", query, version], queryFn: () => getRunsSummary(query), placeholderData: (previous) => previous });
}

export function useRunTree(session: string, run: string) {
  return useQuery({
    queryKey: ["run-tree", session, run],
    queryFn: () => getRunTree(session, run),
    retry: false,
    refetchInterval: (state) => {
      const root: RunNode | undefined = state.state.data?.root;
      return root !== undefined && (root.state === "running" || root.state === "waiting") && visible() ? TREE_REFRESH_MS : false;
    },
  });
}

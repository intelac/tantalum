import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { getTodayUsage } from "../api/client";

/**
 * 自己今天的用量和上限。进入会话取一次，一次运行结束再取（{@code version} 跟着运行状态变）；运行中不取，也不轮询。
 * 取不到就当没有，状态行不显示。
 */
export function useTodayUsage(session: string, running: boolean, version: string) {
  return useQuery({
    queryKey: ["usage-today", session, version],
    queryFn: getTodayUsage,
    enabled: !running,
    placeholderData: keepPreviousData,
    retry: false,
  });
}

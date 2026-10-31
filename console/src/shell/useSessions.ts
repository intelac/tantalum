import { useQuery } from "@tanstack/react-query";
import { listSessions } from "../api/client";

export const SESSIONS_KEY = ["sessions"] as const;

/** 会话列表。SSE 收到事实或条目时由调用方让它失效，不轮询。 */
export function useSessions() {
  return useQuery({ queryKey: SESSIONS_KEY, queryFn: listSessions, select: (data) => data.sessions });
}

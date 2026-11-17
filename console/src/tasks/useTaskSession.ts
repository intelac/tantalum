import { useQueries, useQuery } from "@tanstack/react-query";
import { listMessages } from "../api/client";
import type { Item, TaskItem } from "../api/types";

/** 任务还在跑时多久读一次它的会话。它的会话不走这个页面的推送，所以定时读；任务结束后不再读。 */
export const TASK_POLL_MS = 2000;

export function taskSessionKey(session: string): readonly unknown[] {
  return ["task-session", session];
}

/** 一个任务自己的会话里的条目（只读）。`live`：任务还没回来，隔一会儿重读。 */
export function useTaskSession(session: string, live: boolean, enabled = true) {
  return useQuery({
    queryKey: taskSessionKey(session),
    queryFn: () => listMessages(session),
    enabled,
    refetchInterval: live ? TASK_POLL_MS : false,
  });
}

/** 还没回来的各个任务的会话，一起读：找等着批准的问题用。返回按会话号取条目的函数。 */
export function useOpenTaskSessions(tasks: readonly TaskItem[]): (session: string) => readonly Item[] | undefined {
  const results = useQueries({
    queries: tasks.map((task) => ({
      queryKey: taskSessionKey(task.taskSession),
      queryFn: () => listMessages(task.taskSession),
      refetchInterval: TASK_POLL_MS,
    })),
  });
  return (session) => {
    const index = tasks.findIndex((task) => task.taskSession === session);
    return index < 0 ? undefined : results[index]?.data?.items;
  };
}

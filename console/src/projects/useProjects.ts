// 工作区、会话改名和归档的读写（workspaces 需求 1、5、6）。改完让会话列表和工作区列表失效，侧栏跟着刷新。

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { addProject, deleteProject, listProjects, renameProject, renameSession, setArchived } from "../api/client";
import type { SessionSummary } from "../api/types";
import { SESSIONS_KEY } from "../shell/useSessions";

export const PROJECTS_KEY = ["projects"] as const;

export function useProjects() {
  return useQuery({ queryKey: PROJECTS_KEY, queryFn: listProjects, select: (data) => data.projects });
}

export function useAddProject() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: ({ path, title }: { path: string; title?: string }) => addProject(path, title),
    onSuccess: () => queries.invalidateQueries({ queryKey: PROJECTS_KEY }),
  });
}

export function useRenameProject() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: ({ id, title }: { id: string; title: string }) => renameProject(id, title),
    onSuccess: () => queries.invalidateQueries({ queryKey: PROJECTS_KEY }),
  });
}

export function useDeleteProject() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => deleteProject(id),
    onSuccess: () => queries.invalidateQueries({ queryKey: PROJECTS_KEY }),
  });
}

/** 先在本地改，接口失败再改回：侧栏立刻看到新名字。服务器把空名字换成自动标题，所以成功后再取一次。 */
export function useRenameSession() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: ({ id, title }: { id: string; title: string }) => renameSession(id, title),
    onMutate: ({ id, title }) => patchSession(queries, id, title.trim() === "" ? {} : { title: title.trim() }),
    onError: (_failure, _input, before) => restore(queries, before),
    onSettled: () => queries.invalidateQueries({ queryKey: SESSIONS_KEY }),
  });
}

export function useSetArchived() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: ({ id, archived }: { id: string; archived: boolean }) => setArchived(id, archived),
    onMutate: ({ id, archived }) => patchSession(queries, id, { archived }),
    onError: (_failure, _input, before) => restore(queries, before),
    onSettled: () => queries.invalidateQueries({ queryKey: SESSIONS_KEY }),
  });
}

type Cached = { sessions: SessionSummary[] } | undefined;

function patchSession(queries: ReturnType<typeof useQueryClient>, id: string, change: Partial<SessionSummary>): Cached {
  const before = queries.getQueryData<{ sessions: SessionSummary[] }>(SESSIONS_KEY);
  if (before !== undefined) {
    queries.setQueryData(SESSIONS_KEY, { sessions: before.sessions.map((session) => (session.id === id ? { ...session, ...change } : session)) });
  }
  return before;
}

function restore(queries: ReturnType<typeof useQueryClient>, before: Cached): void {
  if (before !== undefined) queries.setQueryData(SESSIONS_KEY, before);
}

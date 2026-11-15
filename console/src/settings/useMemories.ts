// 记忆面板的数据：列表和单条按名字缓存；任何写入后让列表和那条失效。面板不订阅 SSE，打开时查询即可。

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  addNow,
  confirmPending,
  createMemory,
  deleteMemory,
  forgetMemory,
  getMemory,
  getNow,
  getPending,
  listMemories,
  type NewMemory,
  updateNow,
  withdrawPending,
} from "../api/client";
import type { WorkingItem } from "../api/types";
import { NOTES_KEY } from "./useNotes";

export const MEMORY_KEY = ["memory"] as const;

export function useMemories() {
  return useQuery({ queryKey: MEMORY_KEY, queryFn: listMemories });
}

export function useMemory(name: string | null) {
  return useQuery({
    queryKey: [...MEMORY_KEY, name],
    queryFn: () => getMemory(name as string),
    enabled: name !== null,
  });
}

export function useCreateMemory() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: (entry: NewMemory) => createMemory(entry),
    onSuccess: () => queries.invalidateQueries({ queryKey: MEMORY_KEY }),
  });
}

export function useDeleteMemory() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => deleteMemory(name),
    onSuccess: () => queries.invalidateQueries({ queryKey: MEMORY_KEY }),
  });
}

export const PENDING_KEY = ["memory-pending"] as const;
export const NOW_KEY = ["memory-now"] as const;

/** 等你确认的（memory-final-plan 第 7 节）。 */
export function usePending(enabled = true) {
  return useQuery({ queryKey: PENDING_KEY, queryFn: getPending, select: (data) => data.items, enabled });
}

/** 确认或撤销一条；做完让等确认的、记忆和工作记忆都重新取。 */
export function useDecidePending() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: ({ id, confirm }: { id: string; confirm: boolean }) => (confirm ? confirmPending(id) : withdrawPending(id)),
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: PENDING_KEY });
      void queries.invalidateQueries({ queryKey: MEMORY_KEY });
      void queries.invalidateQueries({ queryKey: NOW_KEY });
    },
  });
}

/** 工作记忆（memory-final-plan 第 5 节）。 */
export function useNow() {
  return useQuery({ queryKey: NOW_KEY, queryFn: getNow, select: (data) => data.items });
}

export function useAddNow() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: ({ kind, title }: { kind: WorkingItem["kind"]; title: string }) => addNow(kind, title),
    onSuccess: () => queries.invalidateQueries({ queryKey: NOW_KEY }),
  });
}

export function useUpdateNow() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: ({ item, change }: { item: WorkingItem; change: { status?: WorkingItem["status"]; title?: string } }) =>
      updateNow(item.id, item.version, change),
    onSettled: () => queries.invalidateQueries({ queryKey: NOW_KEY }),
  });
}

/** 按一句话忘掉；做完让记忆、笔记、工作记忆都重新取。 */
export function useForget() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: (text: string) => forgetMemory(text),
    onSuccess: () => {
      void queries.invalidateQueries({ queryKey: MEMORY_KEY });
      void queries.invalidateQueries({ queryKey: NOW_KEY });
      void queries.invalidateQueries({ queryKey: NOTES_KEY });
    },
  });
}

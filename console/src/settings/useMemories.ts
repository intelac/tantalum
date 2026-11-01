// 记忆面板的数据：列表和单条按名字缓存；任何写入后让列表和那条失效。面板不订阅 SSE，打开时查询即可。

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { createMemory, deleteMemory, getMemory, listMemories, type NewMemory } from "../api/client";

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

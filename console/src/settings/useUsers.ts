// “用户”页的数据（ops-hardening-2 需求 3.2）：列表一份缓存，任何写入后让它失效。页面不订阅 SSE，打开时查询即可。

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { assignParams, createUser, listAssignedParams, listUsers, resetUserPassword, updateUser } from "../api/client";
import type { ParamValue } from "../api/types";

export const USERS_KEY = ["users"] as const;

export function useUsers() {
  return useQuery({ queryKey: USERS_KEY, queryFn: listUsers });
}

export function useCreateUser() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: (user: { name: string; password: string; admin: boolean }) => createUser(user),
    onSuccess: () => queries.invalidateQueries({ queryKey: USERS_KEY }),
  });
}

export function useUpdateUser() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: ({ name, change }: { name: string; change: { disabled?: boolean; admin?: boolean } }) => updateUser(name, change),
    onSuccess: () => queries.invalidateQueries({ queryKey: USERS_KEY }),
  });
}

export function useResetUserPassword() {
  return useMutation({
    mutationFn: ({ name, password }: { name: string; password: string }) => resetUserPassword(name, password),
  });
}

/** 管理员给一个人指定的参数（plugin-settings P14）：存完等新值查回来再算完成，表单这时才丢掉草稿。 */
export function useAssignedParams(user: string) {
  const queries = useQueryClient();
  const key = ["assigned-params", user] as const;
  return {
    query: useQuery({ queryKey: key, queryFn: () => listAssignedParams(user) }),
    save: async (kind: string, plugin: string, values: Record<string, ParamValue | null>) => {
      await assignParams(user, kind, plugin, values);
      await queries.invalidateQueries({ queryKey: key });
    },
  };
}

// “用户”页的数据（ops-hardening-2 需求 3.2）：列表一份缓存，任何写入后让它失效。页面不订阅 SSE，打开时查询即可。

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { assignParams, createUser, listAssignedParams, listUsers, resetUserPassword, setPluginDefault, updateUser } from "../api/client";
import type { DailyLimitSource, ParamValue } from "../api/types";
import { DEFAULTS_KEY } from "../plugins/usePlugins";

export const USERS_KEY = ["users"] as const;

/** 运维表格开着时每 10 秒取一次（用量、会话数、在跑的运行都会变）；页面关了组件卸载，就不取了。 */
export const USERS_POLL_MS = 10_000;

export function useUsers() {
  return useQuery({ queryKey: USERS_KEY, queryFn: listUsers, refetchInterval: USERS_POLL_MS });
}

/**
 * 在表格里改每日上限（qagent-align 需求 5）：给一个人单独设、恢复默认（`null`），或改所有人的默认。用的是现成的参数接口，
 * 平台只知道“报上限的插件说的那个参数”。存完让表格和这个人的参数表单都重新取。
 */
export function useSetLimit(source: DailyLimitSource | undefined) {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async ({ user, tokens }: { user?: string; tokens: number | null }) => {
      if (source === undefined) return;
      if (user === undefined) await setPluginDefault("plugin", source.plugin, { params: { [source.key]: tokens } });
      else await assignParams(user, "plugin", source.plugin, { [source.key]: tokens });
    },
    onSuccess: async (_done, { user }) => {
      await queries.invalidateQueries({ queryKey: USERS_KEY });
      await queries.invalidateQueries({ queryKey: user === undefined ? DEFAULTS_KEY : ["assigned-params", user] });
    },
  });
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

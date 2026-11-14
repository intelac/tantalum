// 本机工作区卡片的数据：页面开着时每 2 秒取一次状态（电脑连上、断开、到期都要很快看到）；开关先在界面上改，失败时退回。

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getLocal, issueLocalToken, resetPlugin, revokeLocal, setPlugin } from "../api/client";
import type { LocalStatus, LocalSwitch, LocalValidity } from "../api/types";

export const LOCAL_KEY = ["local"] as const;
export const LOCAL_POLL_MS = 2000;

export function useLocal(enabled = true) {
  return useQuery({ queryKey: LOCAL_KEY, queryFn: getLocal, enabled, refetchInterval: enabled ? LOCAL_POLL_MS : false });
}

export function useToggleLocal() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: ({ name, enabled }: { name: LocalSwitch; enabled: boolean }) => setPlugin("local", name, enabled),
    onMutate: async ({ name, enabled }) => {
      await queries.cancelQueries({ queryKey: LOCAL_KEY });
      const before = queries.getQueryData<LocalStatus>(LOCAL_KEY);
      if (before !== undefined) queries.setQueryData(LOCAL_KEY, { ...before, switches: { ...before.switches, [name]: enabled } });
      return { before };
    },
    onError: (_error, _toggle, context) => {
      if (context?.before !== undefined) queries.setQueryData(LOCAL_KEY, context.before);
    },
    onSettled: () => queries.invalidateQueries({ queryKey: LOCAL_KEY }),
  });
}

/** 把 `tools` 里叫这些名字的工具改成 `enabled`（不管在哪个子开关下面）。 */
function withTools(status: LocalStatus, names: readonly string[], enabled: boolean): LocalStatus {
  if (status.tools === undefined) return status;
  const tools = Object.fromEntries(
    Object.entries(status.tools).map(([child, list]) => [child, list.map((tool) => (names.includes(tool.name) ? { ...tool, enabled } : tool))]),
  );
  return { ...status, tools };
}

/** 勾或去掉一个工具：和开关存在同一个地方，名字就是工具名。先在界面上改，失败时退回。 */
export function useToggleLocalTool() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: ({ name, enabled }: { name: string; enabled: boolean }) => setPlugin("local", name, enabled),
    onMutate: async ({ name, enabled }) => {
      await queries.cancelQueries({ queryKey: LOCAL_KEY });
      const before = queries.getQueryData<LocalStatus>(LOCAL_KEY);
      if (before !== undefined) queries.setQueryData(LOCAL_KEY, withTools(before, [name], enabled));
      return { before };
    },
    onError: (_error, _toggle, context) => {
      if (context?.before !== undefined) queries.setQueryData(LOCAL_KEY, context.before);
    },
    onSettled: () => queries.invalidateQueries({ queryKey: LOCAL_KEY }),
  });
}

/** 恢复默认：把去掉勾的工具一个个忘掉，默认是全勾。 */
export function useResetLocalTools() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: async (names: string[]) => {
      for (const name of names) await resetPlugin("local", name);
    },
    onSettled: () => queries.invalidateQueries({ queryKey: LOCAL_KEY }),
  });
}

/** 命令先问：内置插件 commandApproval 的开关。 */
export function useToggleCommandApproval() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: (enabled: boolean) => setPlugin("plugin", "commandApproval", enabled),
    onMutate: async (enabled) => {
      await queries.cancelQueries({ queryKey: LOCAL_KEY });
      const before = queries.getQueryData<LocalStatus>(LOCAL_KEY);
      if (before !== undefined) queries.setQueryData(LOCAL_KEY, { ...before, commandApproval: enabled });
      return { before };
    },
    onError: (_error, _toggle, context) => {
      if (context?.before !== undefined) queries.setQueryData(LOCAL_KEY, context.before);
    },
    onSettled: () => queries.invalidateQueries({ queryKey: LOCAL_KEY }),
  });
}

export function useIssueToken() {
  return useMutation({ mutationFn: (validity: LocalValidity) => issueLocalToken(validity) });
}

export function useRevokeLocal() {
  const queries = useQueryClient();
  return useMutation({ mutationFn: revokeLocal, onSettled: () => queries.invalidateQueries({ queryKey: LOCAL_KEY }) });
}

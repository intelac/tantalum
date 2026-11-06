// 本机工作区卡片的数据：页面开着时每 2 秒取一次状态（电脑连上、断开、到期都要很快看到）；开关先在界面上改，失败时退回。

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getLocal, issueLocalToken, revokeLocal, setPlugin } from "../api/client";
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

export function useIssueToken() {
  return useMutation({ mutationFn: (validity: LocalValidity) => issueLocalToken(validity) });
}

export function useRevokeLocal() {
  const queries = useQueryClient();
  return useMutation({ mutationFn: revokeLocal, onSettled: () => queries.invalidateQueries({ queryKey: LOCAL_KEY }) });
}

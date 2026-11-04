// 设置页的压缩点：选了先在界面上改（乐观更新），失败时退回原来的选择，成功后状态栏跟着用新的压缩点。

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getSettings, setCompactionPercent } from "../api/client";
import type { UserSettingsView } from "../api/types";

export const SETTINGS_KEY = ["settings"] as const;

export function useSettings(enabled = true) {
  return useQuery({ queryKey: SETTINGS_KEY, queryFn: getSettings, enabled });
}

/** 这个用户现在的压缩点（token 数）；窗口未知或还没读到时是 undefined。 */
export function compactionPoint(settings: UserSettingsView | undefined): number | undefined {
  if (settings === undefined) return undefined;
  return settings.compaction.points.find((point) => point.percent === settings.compaction.percent)?.tokens;
}

export function useSetCompaction() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: setCompactionPercent,
    onMutate: async (percent: number) => {
      await queries.cancelQueries({ queryKey: SETTINGS_KEY });
      const before = queries.getQueryData<UserSettingsView>(SETTINGS_KEY);
      if (before !== undefined) queries.setQueryData(SETTINGS_KEY, { compaction: { ...before.compaction, percent } });
      return { before };
    },
    onError: (_error, _percent, context) => {
      if (context?.before !== undefined) queries.setQueryData(SETTINGS_KEY, context.before);
    },
    onSettled: () => queries.invalidateQueries({ queryKey: SETTINGS_KEY }),
  });
}

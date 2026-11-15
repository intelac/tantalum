// 插件页的数据：一次查全部；开关先在界面上改（乐观更新），失败时退回原状，成功后再查一次服务端。

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { clearSecret, listPluginDefaults, listPlugins, resetParams, resetPlugin, saveParams, setPlugin, setPluginDefault } from "../api/client";
import type { ParamValue, PluginDefaultChange, PluginKind, Plugins } from "../api/types";

export const PLUGINS_KEY = ["plugins"] as const;

export function usePlugins(enabled = true) {
  return useQuery({ queryKey: PLUGINS_KEY, queryFn: listPlugins, enabled });
}

export interface Toggle {
  kind: PluginKind;
  name: string;
  enabled: boolean;
}

/** 把一个开关的新状态写进列表：工具在内置列表里找；联网的写进 `web.switches`。 */
export function applyToggle(plugins: Plugins, { kind, name, enabled }: Toggle): Plugins {
  const tool = <T extends { name: string; enabled: boolean }>(item: T) => (item.name === name ? { ...item, enabled } : item);
  if (kind === "web") return { ...plugins, web: { ...plugins.web, switches: { ...plugins.web.switches, [name]: enabled } } };
  if (kind === "plugin") return { ...plugins, plugins: plugins.plugins.map(tool) };
  return { ...plugins, tools: plugins.tools.map(tool) };
}

export function useTogglePlugin() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: (toggle: Toggle) => setPlugin(toggle.kind, toggle.name, toggle.enabled),
    onMutate: async (toggle: Toggle) => {
      await queries.cancelQueries({ queryKey: PLUGINS_KEY });
      const before = queries.getQueryData<Plugins>(PLUGINS_KEY);
      if (before !== undefined) queries.setQueryData(PLUGINS_KEY, applyToggle(before, toggle));
      return { before };
    },
    onError: (_error, _toggle, context) => {
      if (context?.before !== undefined) queries.setQueryData(PLUGINS_KEY, context.before);
    },
    onSettled: () => {
      void queries.invalidateQueries({ queryKey: PLUGINS_KEY });
      // 插件开关也决定有哪些面板（plugin-panel）
      void queries.invalidateQueries({ queryKey: ["panels"] });
    },
  });
}

/** 开关恢复默认：服务器删掉我的设置，新状态（跟着管理员的默认）查回来才知道，所以不做乐观更新。 */
export function useResetPlugin() {
  const queries = useQueryClient();
  return useMutation({
    mutationFn: ({ kind, name }: { kind: PluginKind; name: string }) => resetPlugin(kind, name),
    onSettled: () => {
      void queries.invalidateQueries({ queryKey: PLUGINS_KEY });
      void queries.invalidateQueries({ queryKey: ["panels"] });
    },
  });
}

/** 我自己的参数：保存、恢复默认、清除密码。每个都等列表查回来再算完成，表单这时才丢掉草稿。 */
export function useOwnParams(kind: PluginKind, name: string) {
  const queries = useQueryClient();
  const refresh = () => queries.invalidateQueries({ queryKey: PLUGINS_KEY });
  return {
    save: async (values: Record<string, ParamValue | null>) => {
      const given = Object.fromEntries(Object.entries(values).filter((entry): entry is [string, ParamValue] => entry[1] !== null));
      await saveParams(kind, name, given);
      await refresh();
    },
    reset: async () => {
      await resetParams(kind, name);
      await refresh();
    },
    clearSecret: async (key: string) => {
      await clearSecret(kind, name, key);
      await refresh();
    },
  };
}

export const DEFAULTS_KEY = ["plugin-defaults"] as const;

/** 管理员的“插件默认值”。不是管理员时不查（接口会 403）。 */
export function usePluginDefaults(enabled = true) {
  return useQuery({ queryKey: DEFAULTS_KEY, queryFn: listPluginDefaults, enabled });
}

/** 改一项的默认；改完“我的插件”也跟着变（默认、锁），两份都重新查。 */
export function useSetPluginDefault() {
  const queries = useQueryClient();
  return async (kind: string, name: string, change: PluginDefaultChange) => {
    await setPluginDefault(kind, name, change);
    await Promise.all([queries.invalidateQueries({ queryKey: DEFAULTS_KEY }), queries.invalidateQueries({ queryKey: PLUGINS_KEY })]);
  };
}

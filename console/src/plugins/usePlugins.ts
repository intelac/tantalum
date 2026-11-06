// 插件页的数据：一次查全部；开关先在界面上改（乐观更新），失败时退回原状，成功后再查一次服务端。

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { listPlugins, setPlugin } from "../api/client";
import type { PluginKind, Plugins } from "../api/types";

export const PLUGINS_KEY = ["plugins"] as const;

export function usePlugins(enabled = true) {
  return useQuery({ queryKey: PLUGINS_KEY, queryFn: listPlugins, enabled });
}

export interface Toggle {
  kind: PluginKind;
  name: string;
  enabled: boolean;
}

/** 把一个开关的新状态写进列表：工具在内置列表和各 MCP 服务器里都找；联网的写进 `web.switches`。 */
export function applyToggle(plugins: Plugins, { kind, name, enabled }: Toggle): Plugins {
  const tool = <T extends { name: string; enabled: boolean }>(item: T) => (item.name === name ? { ...item, enabled } : item);
  if (kind === "web") return { ...plugins, web: { ...plugins.web, switches: { ...plugins.web.switches, [name]: enabled } } };
  if (kind === "plugin") return { ...plugins, plugins: plugins.plugins.map(tool) };
  if (kind === "mcp") return { ...plugins, mcp: plugins.mcp.map(tool) };
  return {
    ...plugins,
    tools: plugins.tools.map(tool),
    mcp: plugins.mcp.map((server) => ({ ...server, tools: server.tools.map(tool) })),
  };
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
    onSettled: () => queries.invalidateQueries({ queryKey: PLUGINS_KEY }),
  });
}

// 插件页的三组（plugin-settings P11）：规则、工具、工具输出处理。钩子插件归哪一组由服务器算好放在 `group` 里；
// 旧服务器不给时都算规则。

import type { HookPlugin, PluginDefault, PluginGroup } from "../api/types";

export interface HookGroups {
  rule: HookPlugin[];
  output: HookPlugin[];
}

export function groupHooks(plugins: readonly HookPlugin[]): HookGroups {
  const of = (group: PluginGroup) => plugins.filter((plugin) => (plugin.group ?? "rule") === group);
  return { rule: of("rule"), output: of("output") };
}

export type DefaultGroup = "rule" | "tool" | "output";

/** “插件默认值”里的三组，顺序和“我的插件”一样；MCP 服务器和内置工具都在“工具”里。空的组不出现。 */
export function groupDefaults(items: readonly PluginDefault[]): { group: DefaultGroup; items: PluginDefault[] }[] {
  const of = (item: PluginDefault): DefaultGroup => (item.group === "rule" || item.group === "output" ? item.group : "tool");
  return (["rule", "tool", "output"] as const)
    .map((group) => ({ group, items: items.filter((item) => of(item) === group) }))
    .filter((entry) => entry.items.length > 0);
}

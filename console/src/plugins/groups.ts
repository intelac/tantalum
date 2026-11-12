// 插件页的分组（plugin-settings P11，qagent-align 需求 1）：规则、工具、工具输出处理，加上“只有管理员能改”。钩子插件归哪一组由服务器算好放在 `group` 里；
// 旧服务器不给时都算规则。

import type { HookPlugin, McpServerView, PluginDefault, PluginGroup, Plugins, PluginTool } from "../api/types";

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

/** “我的插件”的四组：能改的留在原来三组，锁住的（代码、服务器配置、管理员三种都算）单独一组。 */
export interface Split {
  rule: HookPlugin[];
  tools: PluginTool[];
  servers: McpServerView[];
  output: HookPlugin[];
  /** “只有管理员能改”：规则插件、内置工具、MCP 服务器、输出插件，各自保持原来的顺序。 */
  locked: { rule: HookPlugin[]; tools: PluginTool[]; servers: McpServerView[]; output: HookPlugin[] };
}

/** MCP 服务器里单个被锁的工具留在服务器下面，只有服务器整个被锁才挪走。 */
export function splitLocked(plugins: Pick<Plugins, "tools" | "mcp" | "plugins">): Split {
  const hooks = groupHooks(plugins.plugins);
  const free = <T extends { lock?: unknown }>(items: readonly T[]) => items.filter((item) => item.lock === undefined);
  const held = <T extends { lock?: unknown }>(items: readonly T[]) => items.filter((item) => item.lock !== undefined);
  return {
    rule: free(hooks.rule),
    tools: free(plugins.tools),
    servers: free(plugins.mcp),
    output: free(hooks.output),
    locked: { rule: held(hooks.rule), tools: held(plugins.tools), servers: held(plugins.mcp), output: held(hooks.output) },
  };
}

export function anyLocked(split: Split): boolean {
  return split.locked.rule.length + split.locked.tools.length + split.locked.servers.length + split.locked.output.length > 0;
}

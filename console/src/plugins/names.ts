import type { PluginTool } from "../api/types";
import { lookup, type Text } from "../text";

/** 按用途排：中文文案里列出的顺序（读附件，然后记忆），其余按名字跟在后面。 */
export function byUse<T extends Pick<PluginTool, "name">>(tools: readonly T[], text: Text["plugins"]): T[] {
  const order = Object.keys(text.tools);
  const rank = (tool: T) => (order.includes(tool.name) ? order.indexOf(tool.name) : order.length);
  return [...tools].sort((a, b) => rank(a) - rank(b) || a.name.localeCompare(b.name));
}

/** 内置工具一律用字典里的名字和说明，不显示给模型看的英文说明（console-i18n I8）；字典里没有的只显示工具名，plugins.test.tsx 会失败。 */
export function toolText(name: string, text: Text["plugins"]): [string, string] {
  const known = lookup(text.tools, name);
  return known !== undefined ? [known[0], known[1]] : [name, ""];
}

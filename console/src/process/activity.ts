// 过程区标题里“做了什么”的那一半：把工具调用按名字归类，结束后数出最多的几类，运行中取最近一个的细节。

import type { ToolStepItem } from "../api/types";

export type ActivityKind = "read" | "search" | "write" | "edit" | "command" | "git" | "web" | "memory" | "question" | "tool";

const KINDS: Record<string, ActivityKind> = {
  read_file: "read",
  read_attachment: "read",
  list_dir: "read",
  grep: "search",
  find_files: "search",
  write_file: "write",
  edit_file: "edit",
  bash: "command",
  web_fetch: "web",
  ask_user: "question",
};

const PREFIXES: readonly (readonly [string, ActivityKind])[] = [
  ["git_", "git"],
  ["memory_", "memory"],
  ["browser_", "web"],
];

/** 工具归哪一类；不认识的（包括 MCP 工具）都是 tool。 */
export function kindOf(toolName: string): ActivityKind {
  // 用 hasOwn：工具名是外面来的，不能让 "constructor" 这样的名字查到原型上的东西
  if (Object.hasOwn(KINDS, toolName)) return KINDS[toolName] as ActivityKind;
  for (const [prefix, kind] of PREFIXES) if (toolName.startsWith(prefix)) return kind;
  return "tool";
}

/** 标题里最多写几类。 */
export const MAX_KINDS = 3;

/** 结束后的标题用：调用次数最多的几类（次数相同按先出现的排前），more 表示还有没写出来的类。 */
export function doneKinds(steps: readonly ToolStepItem[]): { kinds: ActivityKind[]; more: boolean } {
  const counts = new Map<ActivityKind, number>();
  for (const step of steps) {
    const kind = kindOf(step.call.name);
    counts.set(kind, (counts.get(kind) ?? 0) + 1);
  }
  // Map 按第一次放进去的顺序迭代，sort 是稳定的，所以次数相同时先出现的在前
  const ordered = [...counts.entries()].sort((a, b) => b[1] - a[1]).map(([kind]) => kind);
  return { kinds: ordered.slice(0, MAX_KINDS), more: ordered.length > MAX_KINDS };
}

/** 运行中的标题说的是哪一步：最近开始的还在跑的那个；没有在跑的返回 undefined。 */
export function runningStep(steps: readonly ToolStepItem[]): ToolStepItem | undefined {
  for (let i = steps.length - 1; i >= 0; i--) if (steps[i]?.status === "RUNNING") return steps[i];
  return undefined;
}

/** 细节取这几个参数里第一个有的。 */
const DETAIL_KEYS = ["command", "query", "pattern", "url", "path"] as const;
export const MAX_DETAIL = 160;

/** 运行中标题后面的细节：命令、查询、模式、网址、路径里第一个有的，空白折叠，最长 160 个字符；没有返回空串。 */
export function runningDetail(step: ToolStepItem): string {
  const args = step.call.arguments;
  if (args === undefined || args === null || typeof args !== "object" || Array.isArray(args)) return "";
  for (const key of DETAIL_KEYS) {
    const value = (args as Record<string, unknown>)[key];
    if (typeof value !== "string") continue;
    const text = value.replace(/\s+/g, " ").trim();
    if (text === "") continue;
    const chars = Array.from(text);
    return chars.length > MAX_DETAIL ? `${chars.slice(0, MAX_DETAIL - 1).join("")}…` : text;
  }
  return "";
}

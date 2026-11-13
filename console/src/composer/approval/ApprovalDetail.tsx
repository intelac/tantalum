import type { ToolStepItem } from "../../api/types";
import { formatArguments } from "../../process/chain";

interface Props {
  step: ToolStepItem;
}

/** 只看路径就够的工具。 */
const PATH_TOOLS = new Set(["write_file", "edit_file", "read_file"]);

function text(args: unknown, key: string): string | null {
  if (args === null || typeof args !== "object" || Array.isArray(args)) return null;
  const value = (args as Record<string, unknown>)[key];
  return typeof value === "string" && value !== "" ? value : null;
}

/**
 * 批准卡片里“要做什么”：bash、powershell 是命令，读写改文件是路径，别的工具是参数原样（同过程区的参数显示）。
 * 浏览器操作带了元素说明（`element`，QAgent 问题 71）时先单独写一行，用户不用从 ref 猜要点的是什么。
 */
export function ApprovalDetail({ step }: Props) {
  const command = step.call.name === "bash" || step.call.name === "powershell" ? text(step.call.arguments, "command") : null;
  const path = PATH_TOOLS.has(step.call.name) ? text(step.call.arguments, "path") : null;
  const element = text(step.call.arguments, "element");
  if (path !== null) return <p className="rounded-sm bg-code px-2 py-1.5 font-mono text-xs break-all text-fg">{path}</p>;
  return (
    <>
      {element !== null && <p className="text-sm font-medium text-fg">{element}</p>}
      <pre className="max-h-40 overflow-auto rounded-sm bg-code px-2 py-1.5 font-mono text-xs whitespace-pre-wrap break-all text-fg">{command ?? formatArguments(step)}</pre>
    </>
  );
}

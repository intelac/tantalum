// 文件工具在对话页上的样子（core-tools 需求 6）：改文件看差异，写文件看开头几行，读文件看读了哪几行。
// 全部从调用参数和结果文字算出，不另存；发给模型的仍只是简短的结果文字。

import { useState } from "react";
import type { ToolStepItem } from "../api/types";
import { zh } from "../text/zh";
import { LineDiff } from "../ui/LineDiff";

export const PREVIEW_LINES = 20;

function args(step: ToolStepItem): Record<string, unknown> {
  const value = step.call.arguments;
  return value !== null && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
}

function text(value: unknown): string | undefined {
  return typeof value === "string" ? value : undefined;
}

function number(value: unknown): number | undefined {
  if (typeof value === "number" && Number.isInteger(value)) return value;
  if (typeof value === "string" && /^\d+$/.test(value.trim())) return Number(value.trim());
  return undefined;
}

const SHOWING = /\[Showing lines (\d+)-(\d+) of (\d+)\./;

/** read_file 读了哪几行：结果里有续读说明时照它；否则只知道参数。整文件读完、不带参数时为 null。 */
export function readRange(step: ToolStepItem): string | null {
  if (step.call.name !== "read_file") return null;
  const shown = step.result !== undefined && !step.result.error ? SHOWING.exec(step.result.text) : null;
  if (shown !== null) return zh.process.lines(Number(shown[1]), Number(shown[2]), Number(shown[3]));
  const offset = number(args(step).offset);
  const limit = number(args(step).limit);
  if (offset !== undefined) return zh.process.fromLine(offset);
  if (limit !== undefined) return zh.process.upTo(limit);
  return null;
}

/** 有专门显示的文件工具。 */
export function hasFileView(step: ToolStepItem): boolean {
  const a = args(step);
  if (step.call.name === "edit_file") return text(a.old_string) !== undefined && text(a.new_string) !== undefined;
  if (step.call.name === "write_file") return text(a.content) !== undefined;
  return false;
}

/** 展开后的内容：edit_file 的差异、write_file 的预览。路径在行的摘要里已经有了。 */
export function FileChange({ step }: { step: ToolStepItem }) {
  const a = args(step);
  if (step.call.name === "edit_file") {
    return (
      <section>
        <h4 className="mb-1 flex items-center gap-2 text-xs font-medium text-fg-muted">
          {zh.process.change}
          {a.replace_all === true && <span className="rounded-xs bg-muted px-1.5 py-0.5 text-[11px]">{zh.process.replaceAll}</span>}
        </h4>
        <LineDiff before={text(a.old_string) ?? ""} after={text(a.new_string) ?? ""} hunkHeaders={false} />
      </section>
    );
  }
  return <WritePreview content={text(a.content) ?? ""} />;
}

function WritePreview({ content }: { content: string }) {
  const [all, setAll] = useState(false);
  const lines = content.split("\n");
  if (lines.at(-1) === "") lines.pop();
  const shown = all ? lines : lines.slice(0, PREVIEW_LINES);
  const bytes = new TextEncoder().encode(content).length;
  return (
    <section>
      <h4 className="mb-1 flex items-center gap-2 text-xs font-medium text-fg-muted">
        {zh.process.content}
        <span className="font-normal text-fg-faint">{zh.process.bytes(bytes)}</span>
      </h4>
      <pre className="max-h-96 overflow-auto rounded-sm bg-code p-2 font-mono text-xs whitespace-pre-wrap text-fg">{shown.join("\n")}</pre>
      {lines.length > PREVIEW_LINES && (
        <button type="button" className="mt-1 text-xs text-accent hover:underline" onClick={() => setAll(!all)}>
          {all ? zh.process.collapse : zh.process.showAll(lines.length)}
        </button>
      )}
    </section>
  );
}

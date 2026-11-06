// 行差异：diff 包的 structuredPatch，上下文 3 行，按块显示（DSH TrajectoryTable.tsx 的 promptDiff）。
// 轨迹页比较 system，对话页和检查器比较 edit_file 的原文和新文字。

import { structuredPatch } from "diff";
import { useText } from "../text";
import { cx } from "./cx";

export const DIFF_CONTEXT_LINES = 3;

/** {@code hunkHeaders} 为 false 时不显示 @@ 行：比较的只是文件里的一段时，那里的行号对不上文件。 */
export function LineDiff({ before, after, hunkHeaders = true }: { before: string; after: string; hunkHeaders?: boolean }) {
  const t = useText();
  const patch = structuredPatch("before", "after", before, after, "", "", { context: DIFF_CONTEXT_LINES });
  if (patch.hunks.length === 0) return <p className="text-xs text-fg-faint">{t.trace.details.noChange}</p>;
  return (
    <div className="overflow-x-auto rounded-md border border-line bg-code font-mono text-xs leading-5">
      {patch.hunks.map((hunk) => (
        <div key={`${hunk.oldStart}:${hunk.newStart}`} className="border-b border-line last:border-b-0">
          {hunkHeaders && <div className="bg-muted px-2 text-fg-faint">{`@@ -${hunk.oldStart},${hunk.oldLines} +${hunk.newStart},${hunk.newLines} @@`}</div>}
          {hunk.lines.map((line, index) => (
            <div
              key={index}
              className={cx(
                "px-2 whitespace-pre-wrap break-words",
                line.startsWith("+") ? "bg-success/10 text-success" : line.startsWith("-") ? "bg-danger-soft text-danger" : "text-fg-muted",
              )}
            >
              {line}
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}

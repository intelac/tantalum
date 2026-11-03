// system 的行差异：diff 包的 structuredPatch，上下文 3 行，按块显示（DSH TrajectoryTable.tsx 的 promptDiff）。

import { structuredPatch } from "diff";
import { zh } from "../../text/zh";
import { cx } from "../../ui/cx";

export const DIFF_CONTEXT_LINES = 3;

export function LineDiff({ before, after }: { before: string; after: string }) {
  const patch = structuredPatch("before", "after", before, after, "", "", { context: DIFF_CONTEXT_LINES });
  if (patch.hunks.length === 0) return <p className="text-xs text-fg-faint">{zh.trace.details.noChange}</p>;
  return (
    <div className="overflow-x-auto rounded-md border border-line bg-code font-mono text-xs leading-5">
      {patch.hunks.map((hunk) => (
        <div key={`${hunk.oldStart}:${hunk.newStart}`} className="border-b border-line last:border-b-0">
          <div className="bg-muted px-2 text-fg-faint">{`@@ -${hunk.oldStart},${hunk.oldLines} +${hunk.newStart},${hunk.newLines} @@`}</div>
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

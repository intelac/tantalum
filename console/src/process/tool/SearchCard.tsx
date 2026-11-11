import { ChevronDown, ChevronRight } from "lucide-react";
import { useState } from "react";
import type { ToolStepItem } from "../../api/types";
import { useText } from "../../text";
import { type GrepResult, parseGrep } from "./grepResult";

/** 先显示这么多行（文件标题也算一行），其余点“还有 N 行”再看。 */
export const PREVIEW_LINES = 8;

/** grep 的结果认得出来才画搜索卡片；出错、合成的结果、拆不开的文字都返回 null，照普通方式显示。 */
export function searchResult(step: ToolStepItem): GrepResult | null {
  if (step.call.name !== "grep" || step.result === undefined || step.result.error || step.result.code !== undefined) return null;
  return parseGrep(step.result.text);
}

interface Props {
  step: ToolStepItem;
  result: GrepResult;
}

/** 搜索卡片：几处匹配、几个文件，按文件分组，每行是行号和内容。 */
export function SearchCard({ step, result }: Props) {
  const t = useText();
  const [all, setAll] = useState(false);
  const [closed, setClosed] = useState<ReadonlySet<string>>(new Set());
  const value = step.call.arguments;
  const args = value !== null && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
  const note = result.capped ? t.process.matchCap : result.truncated ? t.process.cutShort : null;

  let budget = all ? Number.POSITIVE_INFINITY : PREVIEW_LINES;
  const shown = result.files.flatMap((file) => {
    // 标题占一行；剩下不够放一行匹配时这个文件整个留到展开后
    const matches = file.matches.slice(0, Math.max(0, budget - 1));
    budget -= matches.length + 1;
    return matches.length === 0 ? [] : [{ path: file.path, count: file.matches.length, matches }];
  });
  const hidden = result.total - shown.reduce((sum, file) => sum + file.matches.length, 0);

  function toggle(path: string): void {
    setClosed((current) => {
      const next = new Set(current);
      if (!next.delete(path)) next.add(path);
      return next;
    });
  }

  return (
    <section aria-label={t.process.search} className="overflow-hidden rounded-sm border border-line bg-code text-xs">
      <div className="border-b border-line px-2 py-1.5">
        <p className="font-mono break-words text-fg">
          {typeof args.pattern === "string" ? args.pattern : ""}
          {typeof args.path === "string" && args.path !== "" && <span className="ml-2 font-sans text-fg-faint">{t.process.searchIn(args.path)}</span>}
        </p>
        <p className="mt-0.5 text-fg-muted">
          {result.total === 0 ? t.process.noMatches : t.process.matches(result.total, result.files.length)}
          {note !== null && <span className="ml-2 text-warning">{note}</span>}
        </p>
      </div>
      {shown.map((file) => {
        const open = !closed.has(file.path);
        return (
          <div key={file.path} className="border-b border-line last:border-b-0">
            <button
              type="button"
              aria-expanded={open}
              onClick={() => toggle(file.path)}
              className="flex w-full items-center gap-1 px-2 py-1 text-left text-fg-muted hover:bg-hover"
            >
              {open ? <ChevronDown size={12} className="shrink-0" aria-hidden="true" /> : <ChevronRight size={12} className="shrink-0" aria-hidden="true" />}
              <span className="min-w-0 flex-1 truncate font-mono" title={file.path}>
                {file.path}
              </span>
              <span className="shrink-0 tabular-nums text-fg-faint">{file.count}</span>
            </button>
            {open && (
              <ol className="pb-1 font-mono">
                {file.matches.map((match) => (
                  <li key={match.line} className="flex gap-2 px-2 py-0.5">
                    <span className="w-10 shrink-0 text-right tabular-nums select-none text-fg-faint">{match.line}</span>
                    <span className="min-w-0 flex-1 whitespace-pre-wrap break-words text-fg">{match.text}</span>
                  </li>
                ))}
              </ol>
            )}
          </div>
        );
      })}
      {hidden > 0 && (
        <button type="button" onClick={() => setAll(true)} className="w-full border-t border-line px-2 py-1 text-left text-accent hover:bg-hover">
          {t.process.moreLines(hidden)}
        </button>
      )}
    </section>
  );
}

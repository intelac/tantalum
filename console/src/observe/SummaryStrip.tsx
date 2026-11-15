// 汇总数字（observe-page 需求 3.1）：运行数、失败率、有工具错误的运行、token、耗时中位数和 P90。

import type { RunsSummary } from "../api/types";
import { useText } from "../text";
import { formatMs, formatTokens } from "../trace/format";

export function SummaryStrip({ summary }: { summary: RunsSummary | undefined }) {
  const t = useText().observe;
  const ends = summary?.ends ?? {};
  const ended = (ends.COMPLETED ?? 0) + (ends.FAILED ?? 0) + (ends.CANCELLED ?? 0) + (ends.STOPPED ?? 0);
  const dash = "–";
  const figures: [string, string][] = [
    [t.summary.runs, summary === undefined ? dash : String(summary.runs)],
    [t.summary.failRate, summary === undefined || ended === 0 ? dash : `${Math.round(((ends.FAILED ?? 0) / ended) * 1000) / 10}%`],
    [t.summary.toolErrorRuns, summary === undefined ? dash : String(summary.toolErrorRuns)],
    [t.summary.tokens, summary === undefined ? dash : formatTokens(summary.tokens)],
    [t.summary.p50, summary?.p50Ms === undefined ? dash : formatMs(summary.p50Ms)],
    [t.summary.p90, summary?.p90Ms === undefined ? dash : formatMs(summary.p90Ms)],
  ];
  return (
    <dl aria-label={t.summary.label} className="grid grid-cols-3 gap-x-6 gap-y-3 sm:grid-cols-6">
      {figures.map(([label, value]) => (
        <div key={label} className="min-w-0">
          <dt className="truncate text-xs text-fg-faint">{label}</dt>
          <dd className="text-lg font-semibold tabular-nums text-fg">{value}</dd>
        </div>
      ))}
    </dl>
  );
}

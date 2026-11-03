// 统计表：每轮一行，点一行展开它的每一步。列照 DSH client-ui-stats 的 StatsTable；总用时条按平方根刻度，
// 步只和同一轮的步比，轮只和轮比。没有记录的格显示“未记录”。

import { ChevronDown, ChevronRight } from "lucide-react";
import { Fragment, type ReactNode } from "react";
import { zh } from "../text/zh";
import { cx } from "../ui/cx";
import { formatMs, formatTokens } from "../trace/format";
import type { Metrics, StepStats, TurnStats } from "./metrics";

const t = zh.stats;

const COLUMNS: [string, string][] = [
  [t.column.scope, t.column.scopeTitle],
  [t.column.prompt, t.column.promptTitle],
  [t.column.did, t.column.didTitle],
  [t.column.context, t.column.contextTitle],
  [t.column.output, t.column.outputTitle],
  [t.column.cache, t.column.cacheTitle],
  [t.column.ttft, t.column.ttftTitle],
  [t.column.model, t.column.modelTitle],
  [t.column.tool, t.column.toolTitle],
  [t.column.speed, t.column.speedTitle],
  [t.column.time, t.column.timeTitle],
];

function missing(): ReactNode {
  return <span className="text-fg-faint">{t.notRecorded}</span>;
}

function tokens(value: number | undefined, delta: number | undefined, showDelta: boolean): ReactNode {
  if (value === undefined) return missing();
  return (
    <>
      {formatTokens(value)}
      {showDelta && delta !== undefined && delta !== 0 && (
        <span className={cx("ml-1 text-[10px]", delta < 0 ? "text-danger" : "text-fg-faint")} title={t.deltaTitle}>
          {delta > 0 ? "+" : "−"}
          {formatTokens(Math.abs(delta))}
        </span>
      )}
    </>
  );
}

function percent(value: number | undefined): ReactNode {
  return value === undefined ? missing() : `${(value * 100).toFixed(1)}%`;
}

function duration(value: number | undefined): ReactNode {
  return value === undefined ? missing() : formatMs(value);
}

function speed(metrics: Metrics): ReactNode {
  if (metrics.speed !== undefined) return `${metrics.speed.toFixed(1)} tok/s`;
  return metrics.tooShort === true ? <span className="text-fg-faint">{zh.trace.timing.durationTooShort}</span> : missing();
}

/** 合计行里不做合计的列。 */
function notApplicable(): ReactNode {
  return <span className="text-fg-faint">—</span>;
}

/** 总用时条：浅蓝 首 token、蓝 生成、橙 工具、灰 其余；长度按 √(总用时 ÷ 同组最长)。 */
function TimeBar({ metrics, longest }: { metrics: Metrics; longest: number }) {
  if (metrics.total === undefined) return missing();
  const total = Math.max(1, metrics.total);
  const ttft = Math.min(metrics.ttft ?? 0, total);
  const generation = Math.max(0, Math.min((metrics.model ?? 0) - ttft, total - ttft));
  const tool = Math.max(0, Math.min(metrics.tool ?? 0, total - ttft - generation));
  const length = longest <= 0 ? 0 : Math.sqrt(metrics.total / longest) * 100;
  const share = (part: number) => `${(part / total) * 100}%`;
  return (
    <span className="flex items-center gap-2">
      <span className="flex h-2 w-16 shrink-0 overflow-hidden rounded-xs bg-transparent" aria-hidden>
        <span className="flex h-full overflow-hidden rounded-xs bg-muted" style={{ width: `${length}%` }}>
          <span className="h-full bg-bar-model/50" style={{ width: share(ttft) }} />
          <span className="h-full bg-bar-model" style={{ width: share(generation) }} />
          <span className="h-full bg-warning" style={{ width: share(tool) }} />
        </span>
      </span>
      <span className="font-mono text-[11px] text-fg-muted">{formatMs(metrics.total)}</span>
    </span>
  );
}

function did(step: StepStats): string {
  if (step.tools.length > 0) return step.tools.map((tool) => (tool.count > 1 ? `${tool.name} ×${tool.count}` : tool.name)).join(", ");
  return (step.request?.text ?? "").replace(/\s+/g, " ").trim();
}

function MetricCells({ metrics, longest, delta, showDelta, aggregate = false }: { metrics: Metrics; longest: number; delta?: number; showDelta: boolean; aggregate?: boolean }) {
  const cell = "px-2 py-1.5 text-right font-mono text-[11px] whitespace-nowrap";
  return (
    <>
      <td className={cell}>{tokens(metrics.context, delta, showDelta)}</td>
      <td className={cell}>{metrics.output === undefined ? missing() : formatTokens(metrics.output)}</td>
      <td className={cell}>{percent(metrics.cacheRate)}</td>
      <td className={cell}>{aggregate ? notApplicable() : duration(metrics.ttft)}</td>
      <td className={cell}>{duration(metrics.model)}</td>
      <td className={cell}>{metrics.tool === undefined ? (metrics.model === undefined ? missing() : "—") : formatMs(metrics.tool)}</td>
      <td className={cell}>{aggregate ? notApplicable() : speed(metrics)}</td>
      <td className="px-2 py-1.5">
        <TimeBar metrics={metrics} longest={longest} />
      </td>
    </>
  );
}

interface Props {
  turns: readonly TurnStats[];
  expanded: ReadonlySet<string>;
  onToggle: (run: string) => void;
  totals?: Metrics;
  showDelta: boolean;
}

export function StatsTable({ turns, expanded, onToggle, totals, showDelta }: Props) {
  const longestTurn = Math.max(0, ...turns.map((turn) => turn.total ?? 0));
  return (
    <table aria-label={t.aria} className="w-full min-w-[880px] border-collapse text-xs">
      <thead className="sticky top-0 z-10 bg-canvas">
        <tr className="border-b border-line text-[11px] text-fg-faint">
          {COLUMNS.map(([label, title], index) => (
            <th key={label} scope="col" title={title} className={cx("px-2 py-2 font-normal whitespace-nowrap", index < 3 ? "text-left" : index === 10 ? "text-left" : "text-right")}>
              {label}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {turns.map((turn) => {
          const open = expanded.has(turn.run);
          const label = turn.turn === undefined ? t.betweenTurns : t.turn(turn.turn);
          const longestStep = Math.max(0, ...turn.steps.map((step) => step.total ?? 0));
          const Chevron = open ? ChevronDown : ChevronRight;
          return (
            <Fragment key={turn.run}>
              <tr
                tabIndex={0}
                aria-expanded={open}
                aria-label={t.toggle(label)}
                onClick={() => onToggle(turn.run)}
                onKeyDown={(event) => {
                  if (event.key !== "Enter" && event.key !== " ") return;
                  event.preventDefault();
                  onToggle(turn.run);
                }}
                className="cursor-pointer border-b border-line hover:bg-hover"
              >
                <td className="px-2 py-1.5 whitespace-nowrap">
                  <span className="flex items-center gap-1 font-medium">
                    <Chevron size={13} aria-hidden className="text-fg-faint" />
                    {label}
                  </span>
                </td>
                <td className="max-w-[200px] truncate px-2 py-1.5" title={turn.prompt?.text}>
                  <span className="mr-1 text-fg-faint">{turn.head === undefined ? "" : (t.trigger[turn.head.trigger] ?? turn.head.trigger)}</span>
                  {turn.prompt?.text.replace(/\s+/g, " ").trim()}
                </td>
                <td className="px-2 py-1.5 text-fg-faint">{zh.trace.summary.steps(turn.steps.filter((step) => !step.compaction).length)}</td>
                <MetricCells metrics={turn} longest={longestTurn} delta={turn.contextDelta} showDelta={showDelta} />
              </tr>
              {open &&
                turn.steps.map((step) => (
                  <tr key={`${turn.run}:${step.key}`} className={cx("border-b border-line bg-raised", step.compaction && "text-fg-muted")}>
                    <td className="py-1.5 pr-2 pl-7 whitespace-nowrap">{step.compaction ? t.compaction : step.step === undefined ? "" : t.step(step.step)}</td>
                    <td className="px-2 py-1.5 text-fg-faint">{step.continued ? t.continued : ""}</td>
                    <td className="max-w-[200px] truncate px-2 py-1.5" title={did(step)}>
                      {did(step)}
                    </td>
                    <MetricCells metrics={step} longest={longestStep} delta={step.contextDelta} showDelta={showDelta} />
                  </tr>
                ))}
            </Fragment>
          );
        })}
        {totals !== undefined && (
          <tr className="border-t-2 border-line-strong font-medium">
            <td className="px-2 py-1.5">{t.total}</td>
            <td />
            <td />
            <MetricCells metrics={totals} longest={totals.total ?? 0} showDelta={false} aggregate />
          </tr>
        )}
      </tbody>
    </table>
  );
}

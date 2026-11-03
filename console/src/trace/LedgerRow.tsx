// 记录表的各种行：轮标题、步标题、记录行（请求行带“请求 #n”分隔）、收起摘要、加载更早。列照 DSH TrajectoryTurnHeader 的 column.*。

import { ChevronDown, ChevronRight } from "lucide-react";
import type { KeyboardEvent, ReactNode } from "react";
import type { ModelRequest, TraceRecord } from "../api/types";
import { zh } from "../text/zh";
import { cx } from "../ui/cx";
import { between, formatMs, formatTokens } from "./format";
import { toolLabel, type Row, type TurnSummary } from "./rows";

const t = zh.trace;

/** 一行文字：去掉换行，长文本省略。 */
function line(text: string | undefined): string {
  return (text ?? "").replace(/\s+/g, " ").trim();
}

const TAG: Record<string, string> = {
  system: "bg-muted text-fg-muted",
  user: "bg-bar-input/30 text-fg",
  context: "bg-success/15 text-success",
  compacted: "bg-muted text-fg-muted",
  request: "bg-bar-model/30 text-fg",
  tool: "bg-bar-tool/30 text-fg",
  work: "bg-warning/15 text-warning",
  hook: "bg-accent-soft text-accent",
};

/** 出错的记录：失败的请求、出错或被拦下的工具、失败的工作、出错的钩子、没采用的压缩。 */
export function isError(record: TraceRecord): boolean {
  switch (record.kind) {
    case "request":
      return record.status === "FAILED" || record.status === "INTERRUPTED";
    case "tool":
      return record.status === "ERROR" || record.status === "BLOCKED" || record.status === "UNKNOWN";
    case "work":
      return record.status === "FAILED" || record.status === "INTERRUPTED";
    case "hook":
      return record.result === "ERROR";
    case "compacted":
      return !record.adopted;
    default:
      return false;
  }
}

function sourceLabel(source: { type: string; name?: string }): string {
  if (source.type === "plugin") return t.source.plugin(source.name ?? "");
  return source.type === "user" ? t.source.user : source.type === "context" ? t.source.context : t.source.unknown;
}

/** 内容列的文字。工具行另有调用和结果两栏，见 {@link toolCells}。 */
export function contentOf(record: TraceRecord): string {
  switch (record.kind) {
    case "run":
      return t.trigger[record.trigger] ?? record.trigger;
    case "system": {
      if (record.initial) return t.layout.initialSystemPrompt;
      const parts = [
        record.added.length > 0 ? t.layout.toolsAdded(record.added.join(", ")) : "",
        record.removed.length > 0 ? t.layout.toolsRemoved(record.removed.join(", ")) : "",
      ].filter(Boolean);
      return parts.length === 0 ? t.layout.systemPromptUpdated : `${t.layout.toolsUpdated} · ${parts.join(" · ")}`;
    }
    case "user": {
      const prefix = record.mode === "PROMPT" ? "" : `${t.userMode[record.mode]} · `;
      const origin = record.source.type === "plugin" ? `${sourceLabel(record.source)} · ` : "";
      const attachments = record.attachments ?? [];
      const images = attachments.filter((ref) => ref.kind === "image").length;
      const counts = attachments.length === 0 ? "" : zh.attachment.summary(images, attachments.length - images);
      const text = line(record.text);
      return `${prefix}${origin}${[text, counts].filter((part) => part !== "").join(" · ")}`;
    }
    case "context":
      return t.layout.contextChanged(record.sections.map((section) => section.name).join(", "));
    case "compacted":
      if (!record.adopted) return `${t.layout.compactionFailed}${record.reason === undefined ? "" : ` · ${line(record.reason)}`}`;
      return `${record.trimOnly ? t.layout.trimmed : t.layout.compacted} · ${formatTokens(record.estimateBefore)} → ${formatTokens(record.estimateAfter)}`;
    case "request": {
      if (record.status === "FAILED" || record.status === "INTERRUPTED") {
        const partial = line(record.partial);
        return `${t.status.failed} · ${record.failure ?? ""}${partial === "" ? "" : ` · ${partial}`}`;
      }
      const text = line(record.text) || line(record.partial);
      if (text !== "") return text;
      if (record.status === "WAITING") return t.status.pending;
      return record.toolCalls > 0 ? t.layout.toolCallOnly : t.layout.noContent;
    }
    case "tool":
      return `${toolLabel(record)} ${line(record.rawArguments)}`;
    case "work": {
      const status = t.workStatus[record.status] ?? record.status;
      const after = record.after === undefined ? "" : ` · ${t.workAfter[record.after]}`;
      return `${record.workType} · ${record.hook} · ${status}${after}`;
    }
    case "hook": {
      const point = record.point === undefined ? "" : ` · ${t.hookPoint[record.point]}`;
      const reason = record.reason === undefined ? "" : ` · ${line(record.reason)}`;
      const applied = record.applied === undefined ? "" : ` · ${t.hookApplied[record.applied]}`;
      return `${record.hook}${point} · ${t.hookResult[record.result]}${applied}${reason}`;
    }
  }
}

/** 工具行的调用栏和结果栏。出错时原样保留结果文字，没有文字才显示错误码。 */
export function toolCells(record: Extract<TraceRecord, { kind: "tool" }>): { call: string; result: string } {
  const status = t.toolStatus[record.status] ?? record.status;
  const call = `${toolLabel(record)} ${line(record.rawArguments)}`;
  if (record.status === "WAITING" || record.status === "RUNNING") return { call, result: status };
  const text = record.result !== undefined && record.result !== "" ? line(record.result) || record.result : (record.code ?? record.reason ?? "");
  return { call, result: record.status === "DONE" ? text : `${status} · ${text}` };
}

function requestTokens(record: ModelRequest): { input?: number; output?: number } {
  const usage = record.usage;
  if (usage === undefined) return {};
  return { input: usage.input + usage.cacheRead + usage.cacheWrite, output: usage.output };
}

function duration(record: TraceRecord): string {
  if (record.end === undefined) return t.running;
  const span = between(record.at, record.end);
  return span === undefined || span === 0 ? "" : formatMs(span);
}

function summaryText(summary: TurnSummary, names: readonly string[]): string {
  const parts = [summary.steps > 0 ? t.summary.steps(summary.steps) : "", t.summary.toolCalls(summary.toolCalls)].filter(Boolean);
  return names.length > 0 ? `${parts.join(" · ")} · ${names.join(", ")}` : parts.join(" · ");
}

function activate(event: KeyboardEvent, action: () => void): void {
  if (event.key !== "Enter" && event.key !== " ") return;
  event.preventDefault();
  action();
}

interface Props {
  row: Row;
  selected: boolean;
  matched: boolean;
  /** 记录行：选中；轮标题：收起或展开；收起的请求行旁的箭头：收起或展开调用；摘要行：展开。 */
  onSelect: (id: string) => void;
  onToggleTurn: (run: string) => void;
  onToggleCalls: (request: string) => void;
  /** 列宽紧凑时只留类型、内容、时间三列。 */
  compact: boolean;
}

function Cells({ tag, tagClass, children, input, output, time, compact }: {
  tag: string;
  tagClass: string;
  children: ReactNode;
  input?: number;
  output?: number;
  time: string;
  compact: boolean;
}) {
  return (
    <>
      <span role="cell" className="w-16 shrink-0">
        <span className={cx("inline-flex h-[19px] items-center rounded-xs px-1.5 text-[10px] leading-4 whitespace-nowrap", tagClass)}>{tag}</span>
      </span>
      <span role="cell" className="flex min-w-0 flex-1 items-center gap-2 text-[13px]">
        {children}
      </span>
      {!compact && (
        <>
          <span role="cell" className="w-14 shrink-0 text-right font-mono text-[11px] text-fg-muted">{input === undefined ? "" : formatTokens(input)}</span>
          <span role="cell" className="w-14 shrink-0 text-right font-mono text-[11px] text-fg-muted">{output === undefined ? "" : formatTokens(output)}</span>
          <span role="cell" className="w-12 shrink-0" />
        </>
      )}
      <span role="cell" className="w-16 shrink-0 text-right font-mono text-[11px] text-fg-faint">{time}</span>
    </>
  );
}

export function LedgerRow({ row, selected, matched, onSelect, onToggleTurn, onToggleCalls, compact }: Props) {
  switch (row.type) {
    case "turn": {
      const label = row.turn === undefined ? t.betweenTurns : t.turnLabel(row.turn);
      const head = row.head;
      const state = head === undefined ? "" : head.endReason === undefined ? t.running : (t.endReason[head.endReason] ?? head.endReason);
      const Chevron = row.collapsed ? ChevronRight : ChevronDown;
      return (
        <div
          role="row"
          tabIndex={0}
          aria-expanded={!row.collapsed}
          onClick={() => onToggleTurn(row.run)}
          onKeyDown={(event) => activate(event, () => onToggleTurn(row.run))}
          className={cx(
            "mt-2 flex h-8 cursor-pointer items-center gap-2 border-b border-line-strong px-2 text-xs font-medium text-fg hover:bg-hover",
            head?.endReason === "FAILED" && "text-danger",
          )}
        >
          <Chevron size={14} aria-hidden className="shrink-0 text-fg-faint" />
          <span>{label}</span>
          {head !== undefined && <span className="font-normal text-fg-faint">{t.trigger[head.trigger]}</span>}
          <span className="min-w-0 flex-1 truncate font-normal text-fg-faint">{summaryText(row.summary, [])}</span>
          <span className="shrink-0 font-normal text-fg-muted">{state}</span>
        </div>
      );
    }
    case "group":
      return (
        <div role="row" className="flex h-6 items-end px-2 pb-0.5 text-[11px] text-fg-faint">
          {row.step !== undefined ? t.step(row.step) : t.compactionGroup}
        </div>
      );
    case "summary": {
      const expand = () => (row.of === "turn" ? onToggleTurn(row.key.slice(4)) : onToggleCalls(row.key.slice(4)));
      return (
        <div
          role="row"
          tabIndex={0}
          aria-label={`${row.of === "turn" ? t.summary.collapsedTurn : t.summary.collapsedCalls}，${summaryText(row.summary, row.names)}`}
          onClick={expand}
          onKeyDown={(event) => activate(event, expand)}
          className={cx("flex h-5 cursor-pointer items-center pl-[72px] text-xs text-fg-faint hover:bg-hover hover:text-fg-muted", matched && "bg-warning/15")}
        >
          <span className="truncate">… {summaryText(row.summary, row.names)}</span>
        </div>
      );
    }
    case "record": {
      const { record } = row;
      const error = isError(record);
      const tokens = record.kind === "request" ? requestTokens(record) : {};
      const tag = t.kind[record.kind] ?? record.kind;
      return (
        <div
          role="row"
          tabIndex={0}
          aria-selected={selected}
          data-record={record.id}
          onClick={() => onSelect(record.id)}
          onKeyDown={(event) => activate(event, () => onSelect(record.id))}
          className={cx(
            "relative flex cursor-pointer flex-col border-l-2 px-2 hover:bg-hover",
            selected ? "border-accent bg-accent-soft" : error ? "border-danger" : "border-transparent",
            matched && !selected && "bg-warning/15",
          )}
        >
          {record.kind === "request" && (
            <span className={cx("flex h-4 items-center gap-2 pl-[64px] text-[10px]", error ? "text-danger" : "text-fg-faint")}>
              <span>{record.purpose === "SUMMARY" ? t.requestCompaction(record.number) : t.request(record.number)}</span>
              {record.attempts.length > 1 && <span>{t.retryProgress(record.attempts.length, record.maxAttempts)}</span>}
              {row.collapsed !== undefined && (
                <button
                  type="button"
                  onClick={(event) => {
                    event.stopPropagation();
                    onToggleCalls(record.id);
                  }}
                  aria-expanded={!row.collapsed}
                  aria-label={row.collapsed ? t.toolbar.expandCalls : t.toolbar.collapseCalls}
                  className="text-fg-faint hover:text-fg"
                >
                  {row.collapsed ? <ChevronRight size={12} aria-hidden /> : <ChevronDown size={12} aria-hidden />}
                </button>
              )}
            </span>
          )}
          <span className="flex h-[30px] items-center gap-2">
            <Cells
              tag={tag}
              tagClass={error ? "bg-danger-soft text-danger" : (TAG[record.kind] ?? "bg-muted text-fg-muted")}
              input={tokens.input}
              output={tokens.output}
              time={duration(record)}
              compact={compact}
            >
              {record.kind === "tool" ? (
                <ToolContent record={record} />
              ) : (
                <span className={cx("min-w-0 truncate", error ? "text-danger" : "text-fg")}>{contentOf(record)}</span>
              )}
            </Cells>
          </span>
        </div>
      );
    }
  }
}

function ToolContent({ record }: { record: Extract<TraceRecord, { kind: "tool" }> }) {
  const cells = toolCells(record);
  return (
    <>
      <span className="min-w-0 shrink-0 truncate font-mono text-xs text-fg" style={{ width: "clamp(180px, calc(36% - 56px), 480px)" }}>
        {cells.call}
      </span>
      <span className={cx("min-w-0 flex-1 truncate text-xs", isError(record) ? "text-danger" : "text-fg-muted")}>{cells.result}</span>
    </>
  );
}

/** 列标题。 */
export function LedgerHeader({ compact }: { compact: boolean }) {
  return (
    <div role="row" className="flex h-[30px] shrink-0 items-center gap-2 border-b border-line px-2 text-[11px] text-fg-faint">
      <span role="columnheader" className="w-16 shrink-0">{t.column.event}</span>
      <span role="columnheader" className="min-w-0 flex-1">{t.column.content}</span>
      {!compact && (
        <>
          <span role="columnheader" className="w-14 shrink-0 text-right">{t.column.input}</span>
          <span role="columnheader" className="w-14 shrink-0 text-right">{t.column.output}</span>
          <span role="columnheader" className="w-12 shrink-0 text-right">{t.column.think}</span>
        </>
      )}
      <span role="columnheader" className="w-16 shrink-0 text-right">{t.column.time}</span>
    </div>
  );
}

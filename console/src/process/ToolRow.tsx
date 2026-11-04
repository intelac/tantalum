import { Ban, Check, ChevronDown, ChevronRight, Clock, Loader2, Wrench, X } from "lucide-react";
import { useState } from "react";
import type { ToolStepItem } from "../api/types";
import { formatDuration } from "../text/time";
import { zh } from "../text/zh";
import { cx } from "../ui/cx";
import { navigate, traceRecordPath } from "../app/router";
import { elapsedMs, formatArguments, summarizeArguments } from "./chain";
import { FileChange, hasFileView, readRange } from "./FileChange";
import { useNow } from "../ui/useNow";

interface Props {
  step: ToolStepItem;
}

const STATUS_TEXT = {
  WAITING: zh.process.toolWaiting,
  RUNNING: zh.process.toolRunning,
  DONE: zh.process.toolDone,
  FAILED: zh.process.toolFailed,
  BLOCKED: zh.process.toolBlocked,
} as const;

function StatusIcon({ status }: { status: ToolStepItem["status"] }) {
  switch (status) {
    case "WAITING":
      return <Clock size={14} className="text-fg-faint" aria-hidden="true" />;
    case "RUNNING":
      return <Loader2 size={14} className="animate-spin text-accent" aria-hidden="true" />;
    case "DONE":
      return <Check size={14} className="text-success" aria-hidden="true" />;
    case "FAILED":
      return <X size={14} className="text-danger" aria-hidden="true" />;
    case "BLOCKED":
      return <Ban size={14} className="text-warning" aria-hidden="true" />;
  }
}

/** 一行工具调用：名称、参数摘要、状态、耗时；展开后看完整参数和结果。错误和合成结果有明显标记。 */
export function ToolRow({ step }: Props) {
  const [open, setOpen] = useState(false);
  const now = useNow(step.status === "RUNNING");
  const elapsed = elapsedMs(step, now);
  const failed = step.status === "FAILED" || step.status === "BLOCKED";
  const synthesized = step.result?.code !== undefined;
  const range = readRange(step);

  return (
    <div className="text-sm">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
        className={cx(
          "flex w-full items-center gap-2 rounded-xs py-1 text-left hover:bg-hover",
          step.status === "WAITING" ? "text-fg-faint" : "text-fg-muted",
        )}
      >
        <StatusIcon status={step.status} />
        <Wrench size={13} className="shrink-0 text-fg-faint" aria-hidden="true" />
        <span className="shrink-0 font-medium text-fg">{step.call.name}</span>
        <span className="min-w-0 flex-1 truncate font-mono text-xs">
          {summarizeArguments(step)}
          {range !== null && <span className="ml-2 font-sans text-fg-faint">{range}</span>}
        </span>
        <span className={cx("shrink-0 text-xs", failed ? (step.status === "BLOCKED" ? "text-warning" : "text-danger") : "")}>
          {STATUS_TEXT[step.status]}
        </span>
        {elapsed !== null && <span className="w-14 shrink-0 text-right text-xs tabular-nums text-fg-faint">{formatDuration(elapsed)}</span>}
        {open ? <ChevronDown size={14} className="shrink-0" /> : <ChevronRight size={14} className="shrink-0" />}
      </button>
      {open && (
        <div className="mt-1 mb-2 ml-6 flex flex-col gap-2">
          <a
            href={traceRecordPath(step.session, `t:${step.id}`)}
            onClick={(event) => {
              event.preventDefault();
              navigate(traceRecordPath(step.session, `t:${step.id}`));
            }}
            className="self-start text-xs text-accent hover:underline"
          >
            {zh.conversation.viewInTrace}
          </a>
          {hasFileView(step) ? (
            <FileChange step={step} />
          ) : (
            <section>
              <h4 className="mb-1 text-xs font-medium text-fg-muted">{zh.process.arguments}</h4>
              <pre className="max-h-64 overflow-auto rounded-sm bg-code p-2 font-mono text-xs whitespace-pre-wrap text-fg">{formatArguments(step)}</pre>
            </section>
          )}
          {step.result !== undefined && (
            <section>
              <h4 className="mb-1 flex items-center gap-2 text-xs font-medium text-fg-muted">
                {zh.process.result}
                {step.result.error && (
                  <span className={cx("rounded-xs px-1.5 py-0.5 text-[11px]", step.status === "BLOCKED" ? "bg-warning/15 text-warning" : "bg-danger-soft text-danger")}>
                    {STATUS_TEXT[step.status]}
                  </span>
                )}
                {synthesized && (
                  <span className="rounded-xs bg-danger-soft px-1.5 py-0.5 font-mono text-[11px] text-danger" title={zh.process.synthesized}>
                    {step.result.code}
                  </span>
                )}
              </h4>
              {synthesized && (
                <p className="mb-1 text-xs text-danger">
                  {step.result.code === "TOOL_OUTCOME_UNKNOWN" ? zh.process.outcomeUnknown : zh.process.abortedBeforeDispatch}
                </p>
              )}
              <pre
                className={cx(
                  "max-h-80 overflow-auto rounded-sm p-2 font-mono text-xs whitespace-pre-wrap",
                  step.result.error ? "border border-danger/30 bg-danger-soft text-danger" : "bg-code text-fg",
                )}
              >
                {step.result.text}
              </pre>
            </section>
          )}
        </div>
      )}
    </div>
  );
}

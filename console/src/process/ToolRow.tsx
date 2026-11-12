import { Ban, BookOpen, Check, ChevronDown, ChevronRight, Clock, Loader2, Wrench, X } from "lucide-react";
import { useState } from "react";
import type { ToolStepItem } from "../api/types";
import { formatDuration } from "../text/format";
import { useText } from "../text";
import { cx } from "../ui/cx";
import { navigate, traceRecordPath } from "../app/router";
import { skillName } from "./activity";
import { elapsedMs, formatArguments, summarizeArguments } from "./chain";
import { FileChange, hasFileView, readRange } from "./FileChange";
import { useNow } from "../ui/useNow";
import { SearchCard, searchResult } from "./tool/SearchCard";
import { exitCodeOf, hasTerminalView, TerminalCard } from "./tool/TerminalCard";
import { TodoCard, todoList, TodoSummary } from "./tool/TodoCard";

interface Props {
  step: ToolStepItem;
}

/** 状态在字典 process 里的键。 */
const STATUS_KEY = {
  WAITING: "toolWaiting",
  RUNNING: "toolRunning",
  DONE: "toolDone",
  FAILED: "toolFailed",
  BLOCKED: "toolBlocked",
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
  const t = useText();
  const [open, setOpen] = useState(false);
  const now = useNow(step.status === "RUNNING");
  const elapsed = elapsedMs(step, now);
  const failed = step.status === "FAILED" || step.status === "BLOCKED";
  const synthesized = step.result?.code !== undefined;
  const range = readRange(step);
  const exitCode = exitCodeOf(step);
  const terminal = hasTerminalView(step);
  const search = open && !terminal ? searchResult(step) : null;
  // 加载技能写成一句话（skills-wiki 需求 4.6），展开看到的结果就是技能的全文
  const skill = skillName(step);
  // 待办清单（todo-tool 需求 3.3）：一行进度摘要，展开是整张清单；写错被拒的那次照普通方式显示错误
  const todos = todoList(step);

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
        {skill === null ? (
          <Wrench size={13} className="shrink-0 text-fg-faint" aria-hidden="true" />
        ) : (
          <BookOpen size={13} className="shrink-0 text-fg-faint" aria-hidden="true" />
        )}
        <span className={skill === null ? "shrink-0 font-medium text-fg" : "min-w-0 truncate font-medium text-fg"}>
          {skill === null
            ? step.call.name
            : failed
              ? t.process.skillNotLoaded(skill)
              : step.status === "DONE"
                ? t.process.skillLoaded(skill)
                : t.process.skillLoading(skill)}
        </span>
        <span className="min-w-0 flex-1 truncate font-mono text-xs">
          {skill === null && (todos === null ? summarizeArguments(step) : <TodoSummary todos={todos} />)}
          {range !== null && <span className="ml-2 font-sans text-fg-faint">{range}</span>}
          {exitCode !== undefined && <span className="ml-2 font-sans text-danger">{t.process.exitCode(exitCode)}</span>}
        </span>
        <span className={cx("shrink-0 text-xs", failed ? (step.status === "BLOCKED" ? "text-warning" : "text-danger") : "")}>
          {t.process[STATUS_KEY[step.status]]}
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
            {t.conversation.viewInTrace}
          </a>
          {todos !== null ? (
            <TodoCard todos={todos} />
          ) : terminal ? (
            <TerminalCard step={step} />
          ) : search !== null ? (
            <SearchCard step={step} result={search} />
          ) : (
            <>
              {hasFileView(step) ? (
                <FileChange step={step} />
              ) : (
                <section>
                  <h4 className="mb-1 text-xs font-medium text-fg-muted">{t.process.arguments}</h4>
                  <pre className="max-h-64 overflow-auto rounded-sm bg-code p-2 font-mono text-xs whitespace-pre-wrap text-fg">{formatArguments(step)}</pre>
                </section>
              )}
              {step.result !== undefined && (
                <section>
                  <h4 className="mb-1 flex items-center gap-2 text-xs font-medium text-fg-muted">
                    {t.process.result}
                    {step.result.error && (
                      <span className={cx("rounded-xs px-1.5 py-0.5 text-[11px]", step.status === "BLOCKED" ? "bg-warning/15 text-warning" : "bg-danger-soft text-danger")}>
                        {t.process[STATUS_KEY[step.status]]}
                      </span>
                    )}
                    {synthesized && (
                      <span className="rounded-xs bg-danger-soft px-1.5 py-0.5 font-mono text-[11px] text-danger" title={t.process.synthesized}>
                        {step.result.code}
                      </span>
                    )}
                  </h4>
                  {synthesized && (
                    <p className="mb-1 text-xs text-danger">
                      {step.result.code === "TOOL_OUTCOME_UNKNOWN" ? t.process.outcomeUnknown : t.process.abortedBeforeDispatch}
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
            </>
          )}
        </div>
      )}
    </div>
  );
}

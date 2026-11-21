import { navigate, sessionPath } from "../app/router";
import { useMutation } from "@tanstack/react-query";
import { Check, ChevronDown, ChevronRight, CircleHelp, CircleSlash, Clock, Loader2, X } from "lucide-react";
import { useState } from "react";
import { stopTasks } from "../api/client";
import type { TaskItem, TaskOutcome } from "../api/types";
import { useViewedUser } from "../api/viewing";
import { ToolRow } from "../process/ToolRow";
import type { ConversationStore } from "../store/ConversationStore";
import { useItem } from "../store/hooks";
import { useText } from "../text";
import { errorLine } from "../text/errors";
import { formatDuration } from "../text/format";
import { Button } from "../ui/Button";
import { cx } from "../ui/cx";
import { useNow } from "../ui/useNow";
import { recentSteps, waitingForUser } from "./taskState";
import { useTaskSession } from "./useTaskSession";

interface Props {
  store: ConversationStore;
  id: string;
}

/** 展开时最多显示任务最近的几步。 */
const STEPS = 8;

export function OutcomeIcon({ outcome, waiting }: { outcome: TaskOutcome | undefined; waiting: boolean }) {
  if (outcome === undefined) {
    return waiting ? (
      <CircleHelp size={14} className="shrink-0 text-warning" aria-hidden="true" />
    ) : (
      <Loader2 size={14} className="shrink-0 animate-spin text-accent" aria-hidden="true" />
    );
  }
  switch (outcome) {
    case "COMPLETED":
      return <Check size={14} className="shrink-0 text-success" aria-hidden="true" />;
    case "FAILED":
      return <X size={14} className="shrink-0 text-danger" aria-hidden="true" />;
    case "TIMED_OUT":
      return <Clock size={14} className="shrink-0 text-warning" aria-hidden="true" />;
    case "STOPPED":
    case "INTERRUPTED":
      return <CircleSlash size={14} className="shrink-0 text-fg-faint" aria-hidden="true" />;
  }
}

/** 状态的小标签：进行中、等你回答，或结局。 */
export function OutcomeBadge({ outcome, waiting }: { outcome: TaskOutcome | undefined; waiting: boolean }) {
  const t = useText().tasks;
  const label = outcome === undefined ? (waiting ? t.waiting : t.running) : t.outcome[outcome];
  const tone =
    outcome === undefined
      ? waiting
        ? "bg-warning/15 text-warning"
        : "bg-accent-soft text-accent"
      : outcome === "COMPLETED"
        ? "text-success"
        : outcome === "FAILED"
          ? "bg-danger-soft text-danger"
          : outcome === "TIMED_OUT"
            ? "bg-warning/15 text-warning"
            : "bg-muted text-fg-muted";
  return <span className={cx("shrink-0 whitespace-nowrap rounded-full px-2 py-px text-xs", tone)}>{label}</span>;
}

/**
 * 放出去的一个任务（subagent 需求 10.2）：收起时一行（交代的第一行、编号和 agent、状态、用时、停止），
 * 展开看交代的全部内容和它最近在做什么。只读：任务的会话不接受提问。
 */
export function TaskCard({ store, id }: Props) {
  const item = useItem(store, id);
  if (item?.kind !== "task") return null;
  return <TaskCardView item={item} />;
}

function TaskCardView({ item }: { item: TaskItem }) {
  const t = useText().tasks;
  const viewing = useViewedUser() !== null;
  const [open, setOpen] = useState(false);
  const live = item.outcome === undefined;
  // 还在跑的任务收起时也读：要知道它是不是在等你回答
  const child = useTaskSession(item.taskSession, live, live || open);
  const items = child.data?.items ?? [];
  const waiting = live && waitingForUser(items);
  const now = useNow(live);
  const elapsed = Math.max(0, (item.endedAt === undefined ? now : new Date(item.endedAt).getTime()) - new Date(item.at).getTime());
  const stop = useMutation({ mutationFn: () => stopTasks(item.session, item.task) });
  const steps = recentSteps(items, STEPS);
  const title = item.brief === undefined || item.brief === "" ? t.id(item.task) : item.brief;

  return (
    <section aria-label={title} className="mt-2 rounded-xl border border-line bg-raised text-sm">
      <div className="flex min-w-0 items-center gap-2.5 px-3 py-2">
        <button
          type="button"
          aria-expanded={open}
          aria-label={open ? t.hide : t.show}
          onClick={() => setOpen((current) => !current)}
          className="flex min-w-0 flex-1 items-center gap-2.5 rounded-xs text-left hover:bg-hover"
        >
          {open ? (
            <ChevronDown size={14} className="shrink-0 text-fg-faint" aria-hidden="true" />
          ) : (
            <ChevronRight size={14} className="shrink-0 text-fg-faint" aria-hidden="true" />
          )}
          <OutcomeIcon outcome={item.outcome} waiting={waiting} />
          <span className="flex min-w-0 flex-1 flex-col">
            <span className="truncate font-semibold text-fg">{title}</span>
            <span className="truncate text-xs text-fg-faint">
              {t.id(item.task)} · {item.agent ?? t.general}
            </span>
          </span>
        </button>
        <OutcomeBadge outcome={item.outcome} waiting={waiting} />
        <span className="shrink-0 text-xs tabular-nums text-fg-faint">{formatDuration(elapsed)}</span>
        {/* 主窗口派活开的是普通会话（dispatch）：能点过去直接说；帮手只看这张卡片 */}
        {item.taskSession.startsWith("work-") && (
          <a
            href={sessionPath(item.taskSession)}
            onClick={(event) => {
              event.preventDefault();
              navigate(sessionPath(item.taskSession));
            }}
            className="shrink-0 text-xs text-accent underline-offset-2 hover:underline"
          >
            {t.openSession}
          </a>
        )}
        {live && !viewing && (
          <Button size="sm" disabled={stop.isPending || stop.isSuccess} onClick={() => stop.mutate()}>
            {t.stop}
          </Button>
        )}
      </div>
      {stop.error !== null && (
        <p role="alert" className="px-3 pb-2 text-xs text-danger">
          {errorLine(stop.error)}
        </p>
      )}
      {open && (
        <div className="flex flex-col gap-2 rounded-b-xl border-t border-line bg-sidebar py-2.5 pl-9 pr-3">
          {item.prompt !== undefined && (
            <>
              <p className="text-[11px] tracking-wide text-fg-faint">{t.brief}</p>
              <p className="whitespace-pre-line border-l-2 border-line-strong pl-2.5 text-[13px] text-fg-muted">{item.prompt}</p>
            </>
          )}
          <p className="text-[11px] tracking-wide text-fg-faint">{t.doing}</p>
          {steps.length === 0 ? (
            <p className="text-[13px] text-fg-faint">{t.nothingYet}</p>
          ) : (
            <div className="flex flex-col">
              {steps.map((step) => (
                <ToolRow key={step.id} step={step} />
              ))}
            </div>
          )}
        </div>
      )}
    </section>
  );
}

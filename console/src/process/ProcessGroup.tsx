import { ChevronDown, ChevronUp, Loader2 } from "lucide-react";
import { useMemo, useState } from "react";
import { runOutcome } from "../conversation/runOutcome";
import { Markdown } from "../markdown/Markdown";
import type { ConversationStore } from "../store/ConversationStore";
import { useItems, useLive, useRunStatus } from "../store/hooks";
import { useText } from "../text";
import { useSettled } from "../ui/useSettled";
import { doneKinds, kindOf, runningDetail, runningStep } from "./activity";
import { ThinkingBlock } from "./ThinkingBlock";
import { processRows } from "./chain";
import { ToolGroup } from "./ToolGroup";

interface Props {
  store: ConversationStore;
  run: string;
  /** 思考、工具步骤和中间文本的 ID，按发生顺序。 */
  ids: readonly string[];
}

/** 过程区：标题显示状态、用时和做了什么；运行中展开并实时更新，结束后折叠，可再次展开。 */
export function ProcessGroup({ store, run, ids }: Props) {
  const t = useText();
  const runStatus = useRunStatus(store, run);
  const live = useLive(store, run);
  const items = useItems(store, ids);
  const rows = useMemo(() => processRows(items), [items]);
  const ended = runStatus?.endReason !== undefined;
  const [open, setOpen] = useState(!ended);
  // 结束时在同一次渲染里折叠（React 的“渲染时调整状态”）。以前放在 useEffect 里，标题先变成“已完成”、下一帧才折叠，
  // 机器忙时 vitest 正好卡在这一帧之间读到 aria-expanded="true"。
  const [wasEnded, setWasEnded] = useState(ended);
  if (wasEnded !== ended) {
    setWasEnded(ended);
    setOpen(!ended);
  }

  // 标题的后一半：结束后是做得最多的几类，运行中是正在跑的那个工具和它的细节
  const steps = useMemo(() => items.filter((item) => item?.kind === "tool"), [items]);
  const outcome = runOutcome(runStatus, t);
  const done = useMemo(() => doneKinds(steps), [steps]);
  const current = ended ? undefined : runningStep(steps);
  const detail = current === undefined ? "" : runningDetail(current);
  const doing = useSettled(
    runStatus?.phase === "AwaitingAnswer"
      ? t.conversation.awaitingAnswer
      : current === undefined
        ? outcome.header
        : `${t.process.doing[kindOf(current.call.name)]}${detail === "" ? "" : ` · ${detail}`}`,
  );

  const hasLiveReasoning = live.reasoning !== "";
  if (ids.length === 0 && !hasLiveReasoning) return null;

  return (
    <section className="my-2 border-b border-line pb-2" aria-label={outcome.header}>
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
        className="flex w-full items-center gap-1.5 py-1 text-left text-sm text-fg-muted hover:text-fg"
      >
        {!ended && <Loader2 size={14} className="shrink-0 animate-spin text-accent" aria-hidden="true" />}
        {ended ? (
          <>
            <span className="shrink-0">{outcome.header}</span>
            {done.kinds.length > 0 && (
              <span className="min-w-0 truncate text-fg-faint">
                {"· "}
                {t.process.didList(
                  done.kinds.map((kind) => t.process.did[kind]),
                  done.more,
                )}
              </span>
            )}
          </>
        ) : (
          <span className="min-w-0 truncate">{doing}</span>
        )}
        {open ? <ChevronUp size={14} className="shrink-0" aria-hidden="true" /> : <ChevronDown size={14} className="shrink-0" aria-hidden="true" />}
      </button>
      {open && (
        <div className="flex flex-col pt-1">
          {rows.map((row) =>
            row.kind === "tools" ? (
              <div key={row.groups[0]?.[0]?.id} className="flex flex-col">
                {row.groups.map((group) => (
                  <ToolGroup key={group[0]?.id} steps={group} />
                ))}
              </div>
            ) : row.kind === "thinking" ? (
              <ThinkingBlock key={row.item.id} text={row.item.text} redacted={row.item.redacted} />
            ) : (
              <Markdown key={row.item.id} text={row.item.text} muted className="py-1 text-sm" />
            ),
          )}
          {hasLiveReasoning && <ThinkingBlock text={live.reasoning} streaming />}
        </div>
      )}
    </section>
  );
}

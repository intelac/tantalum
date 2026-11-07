import { ChevronDown, ChevronUp, Loader2 } from "lucide-react";
import { useMemo, useState } from "react";
import { runOutcome } from "../conversation/runOutcome";
import { Markdown } from "../markdown/Markdown";
import type { ConversationStore } from "../store/ConversationStore";
import { useItems, useLive, useRunStatus } from "../store/hooks";
import { useText } from "../text";
import { ThinkingBlock } from "./ThinkingBlock";
import { processRows } from "./chain";
import { ToolGroup } from "./ToolGroup";

interface Props {
  store: ConversationStore;
  run: string;
  /** 思考、工具步骤和中间文本的 ID，按发生顺序。 */
  ids: readonly string[];
}

/** 过程区：标题显示状态和用时；运行中展开并实时更新，结束后折叠，可再次展开。 */
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

  const hasLiveReasoning = live.reasoning !== "";
  if (ids.length === 0 && !hasLiveReasoning) return null;

  return (
    <section className="my-2 border-b border-line pb-2" aria-label={runOutcome(runStatus, t).header}>
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
        className="flex w-full items-center gap-1.5 py-1 text-left text-sm text-fg-muted hover:text-fg"
      >
        {!ended && <Loader2 size={14} className="animate-spin text-accent" aria-hidden="true" />}
        <span>{!ended && runStatus?.phase === "AwaitingAnswer" ? t.conversation.awaitingAnswer : runOutcome(runStatus, t).header}</span>
        {open ? <ChevronUp size={14} aria-hidden="true" /> : <ChevronDown size={14} aria-hidden="true" />}
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

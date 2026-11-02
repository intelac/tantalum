import { ChevronDown, ChevronUp, Loader2 } from "lucide-react";
import { useEffect, useState } from "react";
import { INTERRUPTED_BY_RESTART, type RunStatusItem } from "../api/types";
import { Markdown } from "../markdown/Markdown";
import type { ConversationStore } from "../store/ConversationStore";
import { useItem, useLive } from "../store/hooks";
import { formatDuration } from "../text/time";
import { zh } from "../text/zh";
import { ThinkingBlock } from "./ThinkingBlock";
import { ToolChain } from "./ToolChain";

interface Props {
  store: ConversationStore;
  run: string;
  statusId?: string;
  /** 思考、工具步骤和中间文本的 ID，按发生顺序。 */
  ids: readonly string[];
}

export function headerText(status: RunStatusItem | undefined): string {
  if (status === undefined || status.endReason === undefined) return zh.process.running;
  const elapsed = formatDuration(new Date(status.updatedAt).getTime() - new Date(status.at).getTime());
  switch (status.endReason) {
    case "COMPLETED":
      return zh.process.done(elapsed);
    case "FAILED":
      return zh.process.failed(elapsed);
    case "CANCELLED":
      return status.detail === INTERRUPTED_BY_RESTART ? zh.process.interrupted(elapsed) : zh.process.cancelled(elapsed);
    case "STOPPED":
      return zh.process.stopped(elapsed);
  }
}

/** 一段连续 ID 的类型，用来把相邻的工具步骤合成一条工具链。 */
function segments(store: ConversationStore, ids: readonly string[]): { kind: "tools"; ids: string[] }[] | { kind: "one"; id: string }[] {
  const result: ({ kind: "tools"; ids: string[] } | { kind: "one"; id: string })[] = [];
  for (const id of ids) {
    const kind = store.getItem(id)?.kind;
    const last = result[result.length - 1];
    if (kind === "tool") {
      if (last?.kind === "tools") last.ids.push(id);
      else result.push({ kind: "tools", ids: [id] });
    } else {
      result.push({ kind: "one", id });
    }
  }
  return result as { kind: "tools"; ids: string[] }[] | { kind: "one"; id: string }[];
}

function ThinkingItem({ store, id }: { store: ConversationStore; id: string }) {
  const item = useItem(store, id);
  if (item?.kind !== "thinking") return null;
  return <ThinkingBlock text={item.text} redacted={item.redacted} />;
}

function IntermediateText({ store, id }: { store: ConversationStore; id: string }) {
  const item = useItem(store, id);
  if (item?.kind !== "text") return null;
  return <Markdown text={item.text} muted className="py-1 text-sm" />;
}

/** 过程区：标题显示状态和用时；运行中展开并实时更新，结束后折叠，可再次展开。 */
export function ProcessGroup({ store, run, statusId, ids }: Props) {
  const status = useItem(store, statusId ?? "");
  const live = useLive(store, run);
  const runStatus = status?.kind === "run" ? status : undefined;
  const ended = runStatus?.endReason !== undefined;
  const [open, setOpen] = useState(!ended);

  useEffect(() => {
    setOpen(!ended);
  }, [ended]);

  const hasLiveReasoning = live.reasoning !== "";
  if (ids.length === 0 && !hasLiveReasoning) return null;

  return (
    <section className="my-2 border-b border-line pb-2" aria-label={headerText(runStatus)}>
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
        className="flex w-full items-center gap-1.5 py-1 text-left text-sm text-fg-muted hover:text-fg"
      >
        {!ended && <Loader2 size={14} className="animate-spin text-accent" aria-hidden="true" />}
        <span>{headerText(runStatus)}</span>
        {open ? <ChevronUp size={14} aria-hidden="true" /> : <ChevronDown size={14} aria-hidden="true" />}
      </button>
      {open && (
        <div className="flex flex-col pt-1">
          {segments(store, ids).map((segment) =>
            segment.kind === "tools" ? (
              <ToolChain key={segment.ids[0]} store={store} ids={segment.ids} />
            ) : store.getItem(segment.id)?.kind === "thinking" ? (
              <ThinkingItem key={segment.id} store={store} id={segment.id} />
            ) : (
              <IntermediateText key={segment.id} store={store} id={segment.id} />
            ),
          )}
          {hasLiveReasoning && <ThinkingBlock text={live.reasoning} streaming />}
        </div>
      )}
    </section>
  );
}

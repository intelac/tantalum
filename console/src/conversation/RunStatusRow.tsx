import { AlertCircle, Ban, OctagonX } from "lucide-react";
import type { RunStatusItem } from "../api/types";
import type { ConversationStore } from "../store/ConversationStore";
import { useItem } from "../store/hooks";
import { zh } from "../text/zh";

interface Props {
  store: ConversationStore;
  id: string;
}

/** 运行没有正常完成时的说明行：失败按原因解释，取消和钩子停止各有说法。 */
export function RunStatusRow({ store, id }: Props) {
  const item = useItem(store, id);
  if (item === undefined || item.kind !== "run") return null;
  const text = explain(item);
  if (text === null) return null;
  const Icon = item.endReason === "CANCELLED" ? Ban : item.endReason === "STOPPED" ? OctagonX : AlertCircle;
  const danger = item.endReason === "FAILED";
  return (
    <div
      role={danger ? "alert" : "status"}
      className={`my-2 flex items-start gap-2 rounded-md border px-3 py-2 text-sm ${
        danger ? "border-danger/40 bg-danger-soft text-danger" : "border-line bg-muted text-fg-muted"
      }`}
    >
      <Icon size={16} className="mt-0.5 shrink-0" aria-hidden="true" />
      <div className="min-w-0">
        <p>{text}</p>
        {danger && item.detail !== undefined && item.detail !== "" && text !== item.detail && (
          <p className="mt-1 font-mono text-xs break-all opacity-80">{item.detail}</p>
        )}
      </div>
    </div>
  );
}

export function explain(item: RunStatusItem): string | null {
  switch (item.endReason) {
    case "FAILED":
      if (item.failure !== undefined) return zh.failure[item.failure] ?? zh.failure.UNKNOWN;
      if (item.detail?.includes("output ring full")) return zh.failure.dropped;
      return zh.failure.generic;
    case "CANCELLED":
      return zh.failure.cancelled;
    case "STOPPED":
      return item.detail === undefined || item.detail === "" ? zh.failure.stopped : `${zh.failure.stopped}：${item.detail}`;
    default:
      return null;
  }
}

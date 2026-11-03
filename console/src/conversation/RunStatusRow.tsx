import { AlertCircle, Ban, OctagonX } from "lucide-react";
import type { ConversationStore } from "../store/ConversationStore";
import { useRunStatus } from "../store/hooks";
import { runOutcome } from "./runOutcome";
import { navigate, tracePath } from "../app/router";
import { zh } from "../text/zh";

interface Props {
  store: ConversationStore;
  run: string;
}

/** 运行没有正常完成时的说明行：失败按原因解释，取消、重启中断和钩子停止各有说法。 */
export function RunStatusRow({ store, run }: Props) {
  const item = useRunStatus(store, run);
  if (item === undefined) return null;
  const text = runOutcome(item).explanation;
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
        <a
          href={tracePath(item.session, run)}
          onClick={(event) => {
            event.preventDefault();
            navigate(tracePath(item.session, run));
          }}
          className="mt-1 inline-block text-xs text-accent hover:underline"
        >
          {zh.conversation.viewInTrace}
        </a>
      </div>
    </div>
  );
}

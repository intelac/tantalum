// 运行怎么结束的，界面上的两处说法都从这里来：过程区标题和结束说明行。

import { INTERRUPTED_BY_RESTART, type RunStatusItem, SWITCH_COMPACTION_FAILED } from "../api/types";
import { formatDuration } from "../text/format";
import { currentText, type Text } from "../text";

export interface RunOutcome {
  /** 过程区标题：进行中，或“已完成，用时 …”这样的结果和用时。 */
  header: string;
  /** 没有正常完成时的说明；进行中或正常完成时为 null。 */
  explanation: string | null;
}

/** {@code t} 默认取当前语言的字典；组件传 useText() 的结果，换语言时跟着重新渲染。 */
export function runOutcome(status: RunStatusItem | undefined, t: Text = currentText()): RunOutcome {
  if (status === undefined || status.endReason === undefined) return { header: t.process.running, explanation: null };
  const elapsed = formatDuration(new Date(status.updatedAt).getTime() - new Date(status.at).getTime());
  switch (status.endReason) {
    case "COMPLETED":
      return { header: t.process.done(elapsed), explanation: null };
    case "FAILED":
      return { header: t.process.failed(elapsed), explanation: failure(status, t) };
    case "CANCELLED":
      return status.detail === INTERRUPTED_BY_RESTART
        ? { header: t.process.interrupted(elapsed), explanation: t.failure.restarted }
        : { header: t.process.cancelled(elapsed), explanation: t.failure.cancelled };
    case "STOPPED":
      return {
        header: t.process.stopped(elapsed),
        explanation: status.detail === undefined || status.detail === "" ? t.failure.stopped : t.failure.stoppedWith(status.detail),
      };
  }
}

function failure(status: RunStatusItem, t: Text): string {
  if (status.detail?.startsWith(SWITCH_COMPACTION_FAILED)) return t.failure.switchCompaction(status.detail.slice(SWITCH_COMPACTION_FAILED.length));
  if (status.failure !== undefined) return t.failure[status.failure] ?? t.failure.UNKNOWN;
  if (status.detail?.includes("output ring full")) return t.failure.dropped;
  return t.failure.generic;
}

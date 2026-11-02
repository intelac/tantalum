// 运行怎么结束的，界面上的两处说法都从这里来：过程区标题和结束说明行。

import { INTERRUPTED_BY_RESTART, type RunStatusItem } from "../api/types";
import { formatDuration } from "../text/time";
import { zh } from "../text/zh";

export interface RunOutcome {
  /** 过程区标题：进行中，或“已完成，用时 …”这样的结果和用时。 */
  header: string;
  /** 没有正常完成时的说明；进行中或正常完成时为 null。 */
  explanation: string | null;
}

export function runOutcome(status: RunStatusItem | undefined): RunOutcome {
  if (status === undefined || status.endReason === undefined) return { header: zh.process.running, explanation: null };
  const elapsed = formatDuration(new Date(status.updatedAt).getTime() - new Date(status.at).getTime());
  switch (status.endReason) {
    case "COMPLETED":
      return { header: zh.process.done(elapsed), explanation: null };
    case "FAILED":
      return { header: zh.process.failed(elapsed), explanation: failure(status) };
    case "CANCELLED":
      return status.detail === INTERRUPTED_BY_RESTART
        ? { header: zh.process.interrupted(elapsed), explanation: zh.failure.restarted }
        : { header: zh.process.cancelled(elapsed), explanation: zh.failure.cancelled };
    case "STOPPED":
      return {
        header: zh.process.stopped(elapsed),
        explanation: status.detail === undefined || status.detail === "" ? zh.failure.stopped : `${zh.failure.stopped}：${status.detail}`,
      };
  }
}

function failure(status: RunStatusItem): string {
  if (status.failure !== undefined) return zh.failure[status.failure] ?? zh.failure.UNKNOWN;
  if (status.detail?.includes("output ring full")) return zh.failure.dropped;
  return zh.failure.generic;
}

import type { PendingItem } from "../api/types";
import { relativeTime } from "../text/format";
import { errorLine } from "../text/errors";
import { useText } from "../text";
import { Button } from "../ui/Button";
import { useDecidePending, usePending } from "./useMemories";

/** 一条等确认的：文字、从哪来、确认 / 撤销。输入框上方的提示条和记忆页共用。 */
export function PendingRow({ item }: { item: PendingItem }) {
  const t = useText().memory.pending;
  const decide = useDecidePending();
  return (
    <div className="flex flex-col gap-1.5 px-3 py-2">
      <p className="text-sm break-words text-fg">{item.text}</p>
      <p className="text-xs text-fg-faint">
        {t.source(item.source)}
        {item.page !== undefined && ` · ${item.page}`} · {relativeTime(item.created)}
      </p>
      {item.conflictsWith !== undefined && <p className="text-xs text-warning">{t.conflict}</p>}
      <div className="flex gap-2">
        <Button size="sm" variant="secondary" disabled={decide.isPending} onClick={() => decide.mutate({ id: item.id, confirm: true })}>
          {t.confirm}
        </Button>
        <Button size="sm" variant="ghost" disabled={decide.isPending} onClick={() => decide.mutate({ id: item.id, confirm: false })}>
          {t.withdraw}
        </Button>
      </div>
      {decide.error !== null && (
        <p role="alert" className="text-xs text-danger">
          {errorLine(decide.error)}
        </p>
      )}
    </div>
  );
}

/** 记忆页的“待确认”（memory-final-plan 第 7 节）：外部来源和推断出来的，确认后才生效，撤销后不再出现。 */
export function PendingSection() {
  const t = useText();
  const pending = usePending();
  return (
    <div className="flex flex-col gap-3">
      <p className="text-xs text-fg-faint">{t.memory.pending.hint}</p>
      {pending.isError && (
        <p role="alert" className="text-sm text-danger">
          {t.memory.loadFailed}
        </p>
      )}
      {pending.data !== undefined && pending.data.length === 0 && <p className="text-sm text-fg-muted">{t.memory.pending.empty}</p>}
      {pending.data !== undefined && pending.data.length > 0 && (
        <ul className="flex flex-col divide-y divide-line rounded-md border border-line">
          {pending.data.map((item) => (
            <li key={item.id}>
              <PendingRow item={item} />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

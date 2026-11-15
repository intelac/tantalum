import type { FormEvent } from "react";
import { useState } from "react";
import type { WorkingItem } from "../api/types";
import { errorLine } from "../text/errors";
import { useText } from "../text";
import { Button } from "../ui/Button";
import { useAddNow, useNow, useUpdateNow } from "./useMemories";

const KINDS: readonly WorkingItem["kind"][] = ["task", "commitment", "waiting", "noticed"];
const STATUSES: readonly WorkingItem["status"][] = ["active", "waiting", "done", "dropped"];

/** 记忆页的“现在”（memory-final-plan 第 5 节）：在做的、答应的、在等的、最近注意到的；改状态、新加。7 天没动的标出来。 */
export function NowSection() {
  const t = useText().memory.now;
  const now = useNow();
  const update = useUpdateNow();
  const open = (now.data ?? []).filter((item) => item.status === "active" || item.status === "waiting" || item.kind === "noticed");
  const closed = (now.data ?? []).filter((item) => !open.includes(item));
  return (
    <div className="flex flex-col gap-4">
      <p className="text-xs text-fg-faint">{t.hint}</p>
      {now.data !== undefined && open.length === 0 && <p className="text-sm text-fg-muted">{t.empty}</p>}
      {KINDS.map((kind) => {
        const items = open.filter((item) => item.kind === kind);
        if (items.length === 0) return null;
        return (
          <section key={kind} aria-label={t.kinds[kind]} className="flex flex-col gap-1">
            <h4 className="text-xs font-medium text-fg-muted">{t.kinds[kind]}</h4>
            <ul className="flex flex-col divide-y divide-line rounded-md border border-line">
              {items.map((item) => (
                <li key={item.id} className="flex items-start justify-between gap-3 px-3 py-2">
                  <div className="min-w-0">
                    <p className="text-sm break-words text-fg">{item.title}</p>
                    <p className="text-xs text-fg-faint">
                      {[item.next && `${t.next}${item.next}`, item.due && `${t.due}${item.due}`, item.stale && t.stale].filter(Boolean).join(" · ")}
                    </p>
                  </div>
                  {kind !== "noticed" && (
                    <select
                      aria-label={t.status}
                      className="shrink-0 rounded-sm border border-line bg-raised px-1 py-0.5 text-xs text-fg"
                      value={item.status}
                      disabled={update.isPending}
                      onChange={(event) => update.mutate({ item, change: { status: event.target.value as WorkingItem["status"] } })}
                    >
                      {STATUSES.map((status) => (
                        <option key={status} value={status}>
                          {t.statuses[status]}
                        </option>
                      ))}
                    </select>
                  )}
                </li>
              ))}
            </ul>
          </section>
        );
      })}
      {update.error !== null && (
        <p role="alert" className="text-xs text-danger">
          {errorLine(update.error)}
        </p>
      )}
      <AddNow />
      {closed.length > 0 && <p className="text-xs text-fg-faint">{t.closed(closed.length)}</p>}
    </div>
  );
}

function AddNow() {
  const t = useText().memory.now;
  const add = useAddNow();
  const [kind, setKind] = useState<WorkingItem["kind"]>("task");
  const [title, setTitle] = useState("");
  function onSubmit(event: FormEvent) {
    event.preventDefault();
    if (title.trim() === "") return;
    add.mutate({ kind, title: title.trim() }, { onSuccess: () => setTitle("") });
  }
  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-2">
      <div className="flex gap-2">
        <select
          aria-label={t.kind}
          className="rounded-sm border border-line bg-raised px-1 text-sm text-fg"
          value={kind}
          onChange={(event) => setKind(event.target.value as WorkingItem["kind"])}
        >
          {KINDS.map((each) => (
            <option key={each} value={each}>
              {t.kinds[each]}
            </option>
          ))}
        </select>
        <input
          aria-label={t.title}
          className="min-w-0 flex-1 rounded-sm border border-line bg-raised px-2 py-1 text-sm text-fg"
          value={title}
          maxLength={500}
          placeholder={t.placeholder}
          onChange={(event) => setTitle(event.target.value)}
        />
        <Button type="submit" size="sm" variant="secondary" disabled={add.isPending || title.trim() === ""}>
          {t.add}
        </Button>
      </div>
      {add.error !== null && (
        <p role="alert" className="text-xs text-danger">
          {errorLine(add.error)}
        </p>
      )}
    </form>
  );
}

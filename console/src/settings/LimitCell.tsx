import type { FormEvent, KeyboardEvent } from "react";
import { useState } from "react";
import type { Account, DailyLimitSource } from "../api/types";
import { type Text, useText } from "../text";
import { errorLine } from "../text/errors";
import { formatNumber } from "../text/format";
import { Button } from "../ui/Button";
import { useSetLimit } from "./useUsers";

const FIELD = "focus-plain w-28 rounded-md border border-line bg-raised px-2 py-1 text-right text-sm text-fg tabular-nums focus:border-line-strong";

/** 填的数合不合这个参数的规矩；合就是 null。服务器还会再查一遍。 */
export function limitProblem(text: Text, raw: string, source: DailyLimitSource): string | null {
  if (!/^\d+$/.test(raw.trim())) return text.users.limit.whole;
  const value = Number(raw.trim());
  if (!Number.isSafeInteger(value)) return text.users.limit.whole;
  if (source.min !== undefined && value < source.min) return text.users.limit.atLeast(formatNumber(source.min));
  if (source.max !== undefined && value > source.max) return text.users.limit.atMost(formatNumber(source.max));
  return null;
}

/**
 * 就地改一个数：回车保存，Esc 取消；不合规矩或服务器拒绝时原因写在框下面，不关框（qagent-align 需求 5.3）。
 */
function LimitEditor({ label, start, source, onSave, onCancel }: {
  label: string;
  start: number;
  source: DailyLimitSource;
  onSave: (tokens: number) => Promise<void>;
  onCancel: () => void;
}) {
  const t = useText();
  const [raw, setRaw] = useState(String(start));
  const [problem, setProblem] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  async function submit(event: FormEvent): Promise<void> {
    event.preventDefault();
    const wrong = limitProblem(t, raw, source);
    if (wrong !== null) {
      setProblem(wrong);
      return;
    }
    setSaving(true);
    try {
      await onSave(Number(raw.trim()));
    } catch (failure) {
      setProblem(errorLine(failure));
      setSaving(false);
    }
  }

  function keyDown(event: KeyboardEvent): void {
    if (event.key === "Escape") {
      // 只取消这个框，不要顺带关掉整个设置对话框
      event.stopPropagation();
      onCancel();
    }
  }

  return (
    <form onSubmit={(event) => void submit(event)} className="flex flex-col items-end gap-1" onKeyDown={keyDown}>
      <span className="flex items-center gap-1">
        <input
          className={FIELD}
          inputMode="numeric"
          autoFocus
          aria-label={label}
          aria-invalid={problem !== null}
          value={raw}
          onChange={(event) => {
            setRaw(event.target.value);
            setProblem(null);
          }}
        />
        <Button size="sm" variant="primary" type="submit" disabled={saving}>
          {t.users.limit.save}
        </Button>
        <Button size="sm" variant="ghost" onClick={onCancel}>
          {t.common.cancel}
        </Button>
      </span>
      {problem !== null && (
        <span role="alert" className="text-xs text-danger">
          {problem}
        </span>
      )}
    </form>
  );
}

/**
 * 一个人的每日上限（qagent-align 需求 5）：现在生效的数，标“默认”还是“单独设置”；能改时有“单独设置 / 修改 / 恢复默认”。
 * 他没开报上限的插件时写“没有上限”。能不能改看服务器给没给 {@code source}（报上限的插件有没有说是哪个参数）。
 */
export function LimitCell({ account, source }: { account: Account; source: DailyLimitSource | undefined }) {
  const t = useText();
  const text = t.users.limit;
  const set = useSetLimit(source);
  const [editing, setEditing] = useState(false);
  const limit = account.limit;
  if (limit === undefined) return null;
  if (limit.off === true) {
    return (
      <span className="text-fg-faint" title={text.noneHint}>
        {text.none}
      </span>
    );
  }
  if (editing && source !== undefined) {
    return (
      <LimitEditor
        label={text.field(account.name)}
        start={limit.tokens ?? source.value}
        source={source}
        onSave={async (tokens) => {
          await set.mutateAsync({ user: account.name, tokens });
          setEditing(false);
        }}
        onCancel={() => setEditing(false)}
      />
    );
  }
  const own = limit.own === true;
  return (
    <span className="flex flex-col items-end gap-1">
      <span className="flex items-center gap-1.5">
        <span className="tabular-nums">{limit.tokens === undefined ? text.none : formatNumber(limit.tokens)}</span>
        <span className={own ? "rounded-xs bg-accent-soft px-1.5 py-0.5 text-[11px] text-fg" : "rounded-xs bg-muted px-1.5 py-0.5 text-[11px] text-fg-muted"}>
          {own ? text.own : text.byDefault}
        </span>
      </span>
      {source !== undefined && (
        <span className="flex gap-1">
          <Button size="sm" variant="ghost" aria-label={own ? text.changeFor(account.name) : text.setOwnFor(account.name)} onClick={() => setEditing(true)}>
            {own ? text.change : text.setOwn}
          </Button>
          {own && (
            <Button size="sm" variant="ghost" aria-label={text.restoreFor(account.name)} disabled={set.isPending} onClick={() => set.mutate({ user: account.name, tokens: null })}>
              {text.restore}
            </Button>
          )}
        </span>
      )}
      {set.isError && !editing && (
        <span role="alert" className="text-xs text-danger">
          {errorLine(set.error)}
        </span>
      )}
    </span>
  );
}

/** 表格上面那一行：所有人的默认每日上限，管理员能就地改（改它等于改“插件默认值”里那个参数）。 */
export function DefaultLimit({ source }: { source: DailyLimitSource }) {
  const t = useText();
  const text = t.users.limit;
  const set = useSetLimit(source);
  const [editing, setEditing] = useState(false);
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-md border border-line px-3 py-2 text-sm">
      <span className="text-fg">{text.defaultLabel}</span>
      {editing ? (
        <LimitEditor
          label={text.defaultField}
          start={source.value}
          source={source}
          onSave={async (tokens) => {
            await set.mutateAsync({ tokens });
            setEditing(false);
          }}
          onCancel={() => setEditing(false)}
        />
      ) : (
        <>
          <span className="font-medium text-fg tabular-nums">{formatNumber(source.value)}</span>
          <Button size="sm" variant="ghost" aria-label={text.changeDefault} onClick={() => setEditing(true)}>
            {text.change}
          </Button>
        </>
      )}
      <span className="basis-full text-xs text-fg-faint">{text.defaultHint}</span>
    </div>
  );
}

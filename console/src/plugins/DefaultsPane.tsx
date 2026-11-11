import type { FormEvent } from "react";
import { useEffect, useId, useRef, useState } from "react";
import type { PluginDefault, PluginDefaultChange } from "../api/types";
import { useText } from "../text";
import { Button } from "../ui/Button";
import { cx } from "../ui/cx";
import { Switch } from "../ui/Switch";
import { groupDefaults } from "./groups";
import { byUse, toolText } from "./names";
import { failureOf, type ParamFailure, ParamFields, useParamDraft } from "./ParamForm";
import { Group } from "./Rows";
import { usePluginDefaults, useSetPluginDefault } from "./usePlugins";

/** 从“我的插件”里锁定的那一行跳过来时，要滚到并标出来的那一项。 */
export interface Focus {
  kind: string;
  name: string;
}

const FIELD = "focus-plain w-full rounded-md border border-line bg-raised px-3 py-1.5 text-sm text-fg focus:border-line-strong";
const REASON_MAX = 500;

/** 管理员的“插件默认值”（plugin-settings P13，需求 7）：每一项定默认开不开、锁不锁和原因、参数的默认值，各自保存。 */
export function DefaultsPane({ focus }: { focus: Focus | null }) {
  const t = useText();
  const defaults = usePluginDefaults();
  return (
    <>
      <p className="text-xs text-fg-faint">{t.plugins.defaults.hint}</p>
      {defaults.isPending && <p className="text-sm text-fg-faint">{t.common.loading}</p>}
      {defaults.isError && (
        <p role="alert" className="text-sm text-danger">
          {t.plugins.defaults.loadFailed}
        </p>
      )}
      {groupDefaults(defaults.data?.items ?? []).map(({ group, items }) => (
        <Group key={group} title={t.plugins.groups[group]}>
          <ul className="flex flex-col gap-2">
            {(group === "tool" ? byUse(items, t.plugins) : items).map((item) => (
              <Item key={`${item.kind}/${item.name}`} item={item} focused={focus !== null && focus.kind === item.kind && focus.name === item.name} />
            ))}
          </ul>
        </Group>
      ))}
    </>
  );
}

/** 还没保存的改动：没动过的不在里面；`enabled: null` 是“用自带的默认”。 */
interface Draft {
  enabled?: boolean | null;
  locked?: boolean;
  reason?: string;
}

function Item({ item, focused }: { item: PluginDefault; focused: boolean }) {
  const t = useText();
  const text = t.plugins.defaults;
  const save = useSetPluginDefault();
  const form = useParamDraft(item.params, "default");
  const [draft, setDraft] = useState<Draft>({});
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<ParamFailure | null>(null);
  const row = useRef<HTMLLIElement>(null);
  const reasonId = useId();
  useEffect(() => {
    if (focused) row.current?.scrollIntoView?.({ block: "center" });
  }, [focused]);

  const label = item.kind === "tool" ? toolText(item.name, t.plugins)[0] : item.name;
  // 代码或配置锁的：一定开着，开关和锁在这里改不了，参数照样能定
  const fixed = item.lock !== undefined && item.lock.source !== "admin";
  const saved = { locked: item.lock?.source === "admin", reason: item.lock?.source === "admin" ? item.lock.reason : "" };
  const enabled = fixed ? true : draft.enabled === null ? item.builtin : (draft.enabled ?? item.byAdmin ?? item.builtin);
  const locked = fixed || (draft.locked ?? saved.locked);
  const reason = draft.reason ?? saved.reason;
  const overridden = draft.enabled === undefined ? item.byAdmin !== undefined : draft.enabled !== null;

  function change(): PluginDefaultChange {
    const result: PluginDefaultChange = {};
    if (draft.enabled !== undefined && draft.enabled !== (item.byAdmin ?? null)) result.enabled = draft.enabled;
    if (locked !== saved.locked || (locked && reason !== saved.reason)) {
      result.locked = locked;
      if (locked) result.reason = reason;
    }
    return fixed ? {} : result;
  }
  const dirty = Object.keys(change()).length > 0 || form.dirty;

  async function submit(event: FormEvent): Promise<void> {
    event.preventDefault();
    const collected = form.collect();
    if ("invalid" in collected) {
      setFailure({ key: collected.invalid, message: t.plugins.params.needNumber });
      return;
    }
    const body = change();
    if (Object.keys(collected.values).length > 0) body.params = collected.values;
    setBusy(true);
    try {
      await save(item.kind, item.name, body);
      setDraft({});
      form.clear();
      setFailure(null);
    } catch (error) {
      setFailure(failureOf(error, item.params));
    } finally {
      setBusy(false);
    }
  }

  return (
    <li ref={row} className={cx("rounded-md border", focused ? "border-fg" : "border-line")}>
      <form aria-label={label} onSubmit={(event) => void submit(event)} className="flex flex-col gap-3 px-3 py-2.5">
        <div className="text-sm text-fg">
          {label}
          {label !== item.name && <span className="ml-2 font-mono text-[11px] text-fg-faint">{item.name}</span>}
        </div>
        {fixed && <p className="text-xs break-words text-fg-muted">{item.lock?.source === "code" ? text.byCode : text.byConfig}</p>}
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
          <Switch checked={enabled} onChange={(on) => setDraft({ ...draft, enabled: on })} label={text.enabledFor(label)} disabled={fixed} />
          <span className="text-xs text-fg-muted">{text.enabled}</span>
          {!fixed && <span className="text-xs text-fg-faint">{text.builtin(item.builtin)}</span>}
          {!fixed && overridden && (
            <button type="button" className="text-xs text-fg-muted underline hover:text-fg" onClick={() => setDraft({ ...draft, enabled: null })}>
              {text.useBuiltin}
            </button>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
          <Switch checked={locked} onChange={(on) => setDraft({ ...draft, locked: on })} label={text.lockedFor(label)} disabled={fixed} />
          <span className="text-xs text-fg-muted">{text.locked}</span>
        </div>
        {locked && !fixed && (
          <div className="flex flex-col gap-1 text-xs text-fg-muted">
            <label htmlFor={reasonId}>{text.reason}</label>
            <input id={reasonId} className={FIELD} maxLength={REASON_MAX} value={reason} onChange={(event) => setDraft({ ...draft, reason: event.target.value })} />
            <p className="text-fg-faint">{text.reasonHint}</p>
          </div>
        )}
        <ParamFields form={form} failure={failure} />
        <div className="flex flex-wrap items-center gap-2">
          <Button variant="primary" size="sm" type="submit" aria-label={text.saveFor(label)} disabled={!dirty || busy}>
            {busy ? t.plugins.params.saving : t.plugins.params.save}
          </Button>
          {dirty && <span className="text-xs text-fg-muted">{t.plugins.params.unsaved}</span>}
          {failure !== null && failure.key === null && (
            <p role="alert" className="text-xs text-danger">
              {failure.message}
            </p>
          )}
        </div>
      </form>
    </li>
  );
}

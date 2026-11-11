import type { FormEvent } from "react";
import { useId, useState } from "react";
import { ApiError } from "../api/client";
import type { ParamValue, ParamView } from "../api/types";
import { useLanguage, useText } from "../text";
import { errorLine } from "../text/errors";
import { formatNumber } from "../text/format";
import { pick } from "../text/localText";
import { Button } from "../ui/Button";
import { cx } from "../ui/cx";
import { Switch } from "../ui/Switch";

/**
 * 表单填的是哪一层的值：`own` 我自己的（“我的插件”），`default` 管理员定的默认（“插件默认值”），`assigned` 管理员给一个人指定的（运维）。
 * 后两种每一项都能改，清掉一项就回到下一层的默认。
 */
export type ParamMode = "own" | "default" | "assigned";

/** 这一层自己存着的值在 `source` 里叫什么；不是它的就是从下面继承来的，标“默认”。 */
const LAYER: Record<ParamMode, ParamView["source"]> = { own: "own", default: "admin", assigned: "assigned" };

/** 框里的草稿：文字、开关，或者 `null`（管理员点了“用默认”，保存时清掉这一项）。 */
type DraftValue = string | boolean | null;

const FIELD =
  "focus-plain w-full rounded-md border border-line bg-raised px-3 py-1.5 text-sm text-fg focus:border-line-strong disabled:bg-muted disabled:text-fg-muted";

/** 服务器的值在框里的样子：名单一行一个，密码框总是空的。 */
function shown(param: ParamView): string | boolean {
  if (param.type === "secret") return "";
  if (param.type === "toggle") return param.value === true;
  if (Array.isArray(param.value)) return param.value.join("\n");
  return param.value === undefined ? "" : String(param.value);
}

/** 草稿变成要发的值；数字框里不是数时没有结果。 */
function parse(param: ParamView, draft: string | boolean): ParamValue | undefined {
  if (param.type === "toggle") return draft === true;
  const text = String(draft);
  if (param.type === "number") {
    const digits = text.trim();
    return digits !== "" && Number.isFinite(Number(digits)) ? Number(digits) : undefined;
  }
  if (param.type === "list") {
    return text
      .split("\n")
      .map((line) => line.trim())
      .filter((line) => line !== "");
  }
  return text;
}

export interface ParamDraft {
  params: readonly ParamView[];
  mode: ParamMode;
  /** 框里现在显示的：有草稿用草稿，没有用服务器的值。 */
  current: (param: ParamView) => string | boolean;
  /** 这一项动过、还没保存。 */
  touched: (param: ParamView) => boolean;
  /** 点了“用默认”还没保存。 */
  resetting: (param: ParamView) => boolean;
  editable: (param: ParamView) => boolean;
  set: (param: ParamView, value: DraftValue) => void;
  /** 有和服务器不一样的草稿。 */
  dirty: boolean;
  /** 要发的值（只有改了的）；有数字框填的不是数时说是哪一项。 */
  collect: () => { values: Record<string, ParamValue | null> } | { invalid: string };
  clear: () => void;
}

/** 参数表单的草稿（plugin-settings P12）：只记改了的项，所以服务器的值重新查回来时没动过的框跟着变，动过的不丢。 */
export function useParamDraft(params: readonly ParamView[], mode: ParamMode): ParamDraft {
  const [draft, setDraft] = useState<Record<string, DraftValue>>({});
  const editable = (param: ParamView) => mode !== "own" || param.editable;
  const changed = params.filter((param) => editable(param) && param.key in draft);
  return {
    params,
    mode,
    editable,
    current: (param) => draft[param.key] ?? shown(param),
    touched: (param) => param.key in draft,
    resetting: (param) => draft[param.key] === null,
    set: (param, value) =>
      setDraft((before) => {
        const next = { ...before };
        // 改回和服务器一样就不算改动
        if (value !== null && value === shown(param)) delete next[param.key];
        else next[param.key] = value;
        return next;
      }),
    dirty: changed.length > 0,
    collect: () => {
      const values: Record<string, ParamValue | null> = {};
      for (const param of changed) {
        const value = draft[param.key];
        if (value === null || value === undefined) {
          values[param.key] = null;
          continue;
        }
        const parsed = parse(param, value);
        if (parsed !== undefined) values[param.key] = parsed;
        // 管理员把数字框清空 = 不指定，回到默认；用户自己的必须是个数
        else if (mode !== "own" && String(value).trim() === "") values[param.key] = null;
        else return { invalid: param.key };
      }
      return { values };
    },
    clear: () => setDraft({}),
  };
}

/** 保存失败说的是哪一项：`key` 是表单里的一项时错误写在那个框下面，否则写在“保存”旁边。 */
export interface ParamFailure {
  key: string | null;
  message: string;
}

export function failureOf(error: unknown, params: readonly ParamView[]): ParamFailure {
  const key = error instanceof ApiError && typeof error.params.key === "string" ? error.params.key : null;
  return { key: key !== null && params.some((param) => param.key === key) ? key : null, message: errorLine(error) };
}

/** 一个表单的全部框，按声明的类型画（需求 6.4–6.7）。不带“保存”：谁用谁决定一起存什么。 */
export function ParamFields({ form, failure, onClearSecret }: {
  form: ParamDraft;
  failure: ParamFailure | null;
  onClearSecret?: (key: string) => void;
}) {
  const t = useText();
  const text = t.plugins.params;
  const language = useLanguage();
  const prefix = useId();
  return (
    <>
      {form.params.map((param) => {
        const id = `${prefix}-${param.key}`;
        const label = pick(param.label, language) || param.key;
        const hint = pick(param.hint, language);
        const editable = form.editable(param);
        const value = form.current(param);
        const inherited = param.source !== undefined && param.source !== LAYER[form.mode];
        const marker = form.resetting(param)
          ? text.willReset
          : form.mode === "own" && !editable
            ? text.byAdmin
            : inherited && !form.touched(param)
              ? text.byDefault
              : null;
        const range =
          param.type === "number" && (param.min !== undefined || param.max !== undefined)
            ? text.range(param.min === undefined ? null : formatNumber(param.min), param.max === undefined ? null : formatNumber(param.max))
            : "";
        const below = [hint, range].filter((part) => part !== "").join(" · ");
        return (
          <div key={param.key} className="flex flex-col gap-1">
            <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-fg-muted">
              {param.type === "toggle" ? <span>{label}</span> : <label htmlFor={id}>{label}</label>}
              {marker !== null && <span className="text-fg-faint">· {marker}</span>}
              {form.mode !== "own" && !inherited && !form.resetting(param) && (
                <button type="button" className="text-fg-muted underline hover:text-fg" aria-label={text.useDefaultFor(label)} onClick={() => form.set(param, null)}>
                  {text.useDefault}
                </button>
              )}
            </div>
            {param.type === "toggle" && (
              <span>
                <Switch checked={value === true} onChange={(checked) => form.set(param, checked)} label={label} disabled={!editable} />
              </span>
            )}
            {param.type === "list" && (
              <textarea
                id={id}
                className={cx(FIELD, "font-mono text-xs")}
                rows={Math.min(10, Math.max(3, String(value).split("\n").length))}
                value={String(value)}
                disabled={!editable}
                spellCheck={false}
                onChange={(event) => form.set(param, event.target.value)}
              />
            )}
            {(param.type === "text" || param.type === "number") && (
              <input
                id={id}
                className={FIELD}
                inputMode={param.type === "number" ? "numeric" : undefined}
                autoComplete="off"
                value={String(value)}
                disabled={!editable}
                onChange={(event) => form.set(param, event.target.value)}
              />
            )}
            {param.type === "secret" && (
              <div className="flex flex-wrap items-center gap-2">
                <input
                  id={id}
                  className={cx(FIELD, "min-w-40 flex-1")}
                  type="password"
                  autoComplete="off"
                  placeholder={text.secretPlaceholder}
                  value={String(value)}
                  onChange={(event) => form.set(param, event.target.value)}
                />
                {param.stale === true ? (
                  <span className="text-xs text-warning">{text.secretStale}</span>
                ) : (
                  param.set === true && <span className="text-xs text-fg-muted">{text.secretSet}</span>
                )}
                {param.set === true && onClearSecret !== undefined && (
                  <Button size="sm" aria-label={text.clearFor(label)} onClick={() => onClearSecret(param.key)}>
                    {text.clear}
                  </Button>
                )}
              </div>
            )}
            {below !== "" && <p className="text-xs break-words text-fg-faint">{below}</p>}
            {failure !== null && failure.key === param.key && (
              <p role="alert" className="text-xs text-danger">
                {failure.message}
              </p>
            )}
          </div>
        );
      })}
    </>
  );
}

interface Props {
  /** 插件或工具的名字，读屏时区分页面上的多个表单。 */
  name: string;
  params: readonly ParamView[];
  mode: ParamMode;
  /** 只带改了的项；`null` 只在管理员的两种表单里出现（清掉这一项）。成功后服务器的新值应该已经在 `params` 里。 */
  onSave: (values: Record<string, ParamValue | null>) => Promise<unknown>;
  /** “参数恢复默认”：只有我自己的表单有。 */
  onReset?: () => Promise<unknown>;
  onClearSecret?: (key: string) => Promise<unknown>;
  className?: string;
}

/**
 * 一个插件的参数表单，自己带“保存”（plugin-settings P12，需求 6.4–6.7）：和服务器的值不同时“保存”可点、旁边说有没保存的改动；
 * 保存失败把原因写在那一项下面，草稿不丢；保存成功后草稿清空，密码框也跟着空。
 */
export function ParamForm({ name, params, mode, onSave, onReset, onClearSecret, className }: Props) {
  const t = useText();
  const text = t.plugins.params;
  const form = useParamDraft(params, mode);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<ParamFailure | null>(null);

  async function run(action: () => Promise<unknown>, after?: () => void): Promise<void> {
    setBusy(true);
    try {
      await action();
      after?.();
      setFailure(null);
    } catch (error) {
      setFailure(failureOf(error, params));
    } finally {
      setBusy(false);
    }
  }

  function submit(event: FormEvent): void {
    event.preventDefault();
    const collected = form.collect();
    if ("invalid" in collected) {
      setFailure({ key: collected.invalid, message: text.needNumber });
      return;
    }
    void run(() => onSave(collected.values), form.clear);
  }

  const canSave = params.some(form.editable);
  const canReset = mode === "own" && onReset !== undefined && params.some((param) => param.source === "own" && param.editable);
  return (
    <form aria-label={text.formFor(name)} onSubmit={submit} className={cx("flex flex-col gap-3", className)}>
      <ParamFields
        form={form}
        failure={failure}
        onClearSecret={onClearSecret === undefined ? undefined : (key) => void run(() => onClearSecret(key))}
      />
      {(canSave || failure !== null) && (
        <div className="flex flex-wrap items-center gap-2">
          {canSave && (
            <Button variant="primary" size="sm" type="submit" aria-label={t.plugins.defaults.saveFor(name)} disabled={!form.dirty || busy}>
              {busy ? text.saving : text.save}
            </Button>
          )}
          {form.dirty && <span className="text-xs text-fg-muted">{text.unsaved}</span>}
          {failure !== null && failure.key === null && (
            <p role="alert" className="text-xs text-danger">
              {failure.message}
            </p>
          )}
          {canReset && (
            <Button size="sm" className="ml-auto" disabled={busy} onClick={() => void run(onReset, form.clear)}>
              {text.reset}
            </Button>
          )}
        </div>
      )}
    </form>
  );
}

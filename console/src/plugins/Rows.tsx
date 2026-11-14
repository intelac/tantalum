import type { ReactNode } from "react";
import type { SwitchState } from "../api/types";
import { useText } from "../text";
import { Button } from "../ui/Button";
import { Switch } from "../ui/Switch";
import { LockNote } from "./LockNote";

/** 插件页的一组：规则、工具、工具输出处理（plugin-settings P11）。 */
export function Group({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div role="group" aria-label={title} className="flex flex-col gap-3">
      <h3 className="text-sm font-semibold text-fg">{title}</h3>
      {children}
    </div>
  );
}

/** 插件页的一块：标题和一个带边框的列表；没有内容时显示一句说明。不给标题时只有列表（组的标题已经说了）。 */
export function Section({ title, label, empty, children }: { title?: string; label?: string; empty?: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-2" aria-label={title ?? label}>
      {title !== undefined && <h4 className="text-sm font-medium text-fg">{title}</h4>}
      {empty !== undefined ? (
        <p className="text-sm text-fg-muted">{empty}</p>
      ) : (
        <ul className="flex flex-col divide-y divide-line rounded-md border border-line">{children}</ul>
      )}
    </section>
  );
}

/**
 * 一行：开关在左，名字和一句说明在中间，右边是锁定条或“我改的 · 恢复默认”（`aside`）。
 * 窄屏时右边这一段换到说明下面，开关不挤掉。`children` 在整行下面：参数表单、子开关下面的设置和工具列表。
 */
export function Row({ name, detail, label, checked, disabled, onChange, aside, children }: {
  name: ReactNode;
  detail?: ReactNode;
  label: string;
  checked: boolean;
  disabled?: boolean;
  onChange: (checked: boolean) => void;
  aside?: ReactNode;
  children?: ReactNode;
}) {
  return (
    <li className="flex flex-col">
      <div className="flex flex-wrap items-start gap-x-3 gap-y-1.5 px-3 py-2.5">
        <span className="pt-0.5">
          <Switch checked={checked} onChange={onChange} label={label} disabled={disabled} />
        </span>
        <div className="min-w-0 flex-1">
          <div className="text-sm text-fg">{name}</div>
          {detail !== undefined && <div className="mt-0.5 text-xs break-words text-fg-muted">{detail}</div>}
        </div>
        {aside !== undefined && aside !== null && (
          <div className="flex min-w-0 flex-wrap items-center gap-2 max-sm:basis-full max-sm:pl-12">{aside}</div>
        )}
      </div>
      {children}
    </li>
  );
}

/** 一行右边的那一段：锁着就是锁定条；没锁、改过就是“我改的”和“恢复默认”；都不是就没有。 */
export function SwitchNotes({ state, label, onDefaults, onReset }: {
  state: SwitchState;
  /** 这一行的名字，读屏用。 */
  label: string;
  /** 管理员才给：跳到“插件默认值”的这一项。 */
  onDefaults?: () => void;
  onReset: () => void;
}) {
  const t = useText();
  if (state.lock !== undefined) return <LockNote lock={state.lock} onDefaults={onDefaults} />;
  if (state.changed !== true) return null;
  return (
    <>
      <span className="rounded-xs bg-muted px-1.5 py-0.5 text-[11px] text-fg-muted">{t.plugins.changed}</span>
      <Button size="sm" aria-label={t.plugins.resetFor(label)} onClick={onReset}>
        {t.plugins.reset}
      </Button>
    </>
  );
}

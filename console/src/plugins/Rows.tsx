import type { ReactNode } from "react";
import { Switch } from "../ui/Switch";

/** 插件页的一块：标题和一个带边框的列表；没有内容时显示一句说明。 */
export function Section({ title, empty, children }: { title: string; empty?: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-2" aria-label={title}>
      <h3 className="text-sm font-medium text-fg">{title}</h3>
      {empty !== undefined ? (
        <p className="text-sm text-fg-muted">{empty}</p>
      ) : (
        <ul className="flex flex-col divide-y divide-line rounded-md border border-line">{children}</ul>
      )}
    </section>
  );
}

/** 一行：名字和一句说明在左，开关在右。窄屏时说明换行，开关不挤掉。 */
export function Row({ name, detail, label, checked, disabled, onChange, children }: {
  name: ReactNode;
  detail?: ReactNode;
  label: string;
  checked: boolean;
  disabled?: boolean;
  onChange: (checked: boolean) => void;
  children?: ReactNode;
}) {
  return (
    <li className="flex flex-col">
      <div className="flex items-start gap-3 px-3 py-2.5">
        <div className="min-w-0 flex-1">
          <div className="text-sm text-fg">{name}</div>
          {detail !== undefined && <div className="mt-0.5 text-xs break-words text-fg-muted">{detail}</div>}
        </div>
        <Switch checked={checked} onChange={onChange} label={label} disabled={disabled} />
      </div>
      {children}
    </li>
  );
}


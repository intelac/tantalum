import { ChevronDown, ChevronRight } from "lucide-react";
import { useState } from "react";
import type { LocalTool } from "../api/types";
import { lookup, useText } from "../text";
import { Button } from "../ui/Button";
import { useResetLocalTools, useToggleLocalTool } from "./useLocal";

/**
 * 一个子开关下面可以逐个勾的工具（qagent-align 需求 2）。服务器说哪些子开关有；现在只有“浏览器”。
 * 勾是用户自己的设置，和电脑连没连无关。字典里有说明的工具带一句说明，没有的只显示名字。
 */
export function useToolChoices(tools: readonly LocalTool[] | undefined) {
  const [open, setOpen] = useState(false);
  const list = tools ?? [];
  const off = list.filter((tool) => !tool.enabled).map((tool) => tool.name);
  return { open, setOpen, list, off, any: list.length > 0 };
}

/** 展开和收起的那个按钮，写着现在选了几个。放在这一行的说明里。 */
export function ChoicesToggle({ open, onToggle, picked, total }: { open: boolean; onToggle: () => void; picked: number; total: number }) {
  const t = useText();
  const Chevron = open ? ChevronDown : ChevronRight;
  return (
    <button type="button" className="mt-1 inline-flex items-center gap-1 hover:text-fg" aria-expanded={open} onClick={onToggle}>
      <Chevron size={12} aria-hidden="true" />
      {open ? t.plugins.hideTools : t.plugins.local.toolsPicked(picked, total)}
    </button>
  );
}

/** “我改的”和“恢复默认”：有工具被去掉勾时才有。 */
export function ChoicesNotes({ off, label }: { off: readonly string[]; label: string }) {
  const t = useText();
  const reset = useResetLocalTools();
  if (off.length === 0) return null;
  return (
    <>
      <span className="rounded-xs bg-muted px-1.5 py-0.5 text-[11px] text-fg-muted">{t.plugins.changed}</span>
      <Button size="sm" aria-label={t.plugins.resetFor(label)} disabled={reset.isPending} onClick={() => reset.mutate([...off])}>
        {t.plugins.reset}
      </Button>
    </>
  );
}

export function ChoicesList({ tools, label }: { tools: readonly LocalTool[]; label: string }) {
  const t = useText();
  const toggle = useToggleLocalTool();
  return (
    <ul aria-label={t.plugins.local.toolsOf(label)} className="mx-3 mb-2 ml-7 grid gap-x-4 rounded-md border border-line px-3 py-2 sm:grid-cols-2">
      {tools.map((tool) => {
        const detail = lookup(t.plugins.local.browserTools, tool.name);
        return (
          <li key={tool.name} className="min-w-0 py-1">
            <label className="flex min-w-0 cursor-pointer items-start gap-2 text-xs text-fg-muted">
              <input
                type="checkbox"
                className="mt-0.5 shrink-0 accent-accent"
                checked={tool.enabled}
                aria-label={tool.name}
                onChange={(event) => toggle.mutate({ name: tool.name, enabled: event.target.checked })}
              />
              <span className="min-w-0 break-words">
                <span className="font-mono text-fg">{tool.name}</span>
                {detail !== undefined && <span className="ml-1.5">{detail}</span>}
              </span>
            </label>
          </li>
        );
      })}
    </ul>
  );
}

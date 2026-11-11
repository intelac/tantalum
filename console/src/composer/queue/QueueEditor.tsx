import type { KeyboardEvent } from "react";
import { useEffect, useRef, useState } from "react";
import { useText } from "../../text";
import { Button } from "../../ui/Button";

interface Props {
  text: string;
  busy: boolean;
  onSave: (text: string) => void;
  onCancel: () => void;
}

const MAX_ROWS = 6;

/** 排队消息的行内编辑（input-area 需求 1.2）：保留换行；Enter 保存，Shift+Enter 换行，Esc 取消；全空不能保存。 */
export function QueueEditor({ text, busy, onSave, onCancel }: Props) {
  const t = useText().composer.queue;
  const [draft, setDraft] = useState(text);
  const area = useRef<HTMLTextAreaElement>(null);
  const empty = draft.trim() === "";

  useEffect(() => {
    const element = area.current;
    if (element === null) return;
    element.focus();
    element.setSelectionRange(element.value.length, element.value.length);
  }, []);

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>): void {
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      onCancel();
      return;
    }
    if (event.key !== "Enter" || event.shiftKey) return;
    // 输入法还在组字时的 Enter 是选字，不是保存。
    if (event.nativeEvent.isComposing || event.keyCode === 229) return;
    event.preventDefault();
    if (!empty && !busy) onSave(draft);
  }

  return (
    <div className="flex min-w-0 flex-1 flex-col gap-1">
      <textarea
        ref={area}
        value={draft}
        rows={Math.min(MAX_ROWS, Math.max(1, draft.split("\n").length))}
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={onKeyDown}
        aria-label={t.editText}
        className="block w-full resize-none rounded-md border border-line bg-raised px-2 py-1 text-sm leading-relaxed text-fg"
      />
      <div className="flex items-center gap-2">
        <span className="min-w-0 flex-1 truncate text-[11px] text-fg-faint">{t.editHint}</span>
        <Button variant="ghost" size="sm" onClick={onCancel}>
          {t.cancel}
        </Button>
        <Button variant="primary" size="sm" disabled={empty || busy} onClick={() => onSave(draft)}>
          {t.save}
        </Button>
      </div>
    </div>
  );
}

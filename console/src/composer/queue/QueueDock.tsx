import { ChevronDown, ChevronRight, CornerDownRight, ListOrdered, Pencil, X } from "lucide-react";
import { useState } from "react";
import type { UserItem } from "../../api/types";
import { useText } from "../../text";
import { errorLine } from "../../text/errors";
import { Button } from "../../ui/Button";
import { QueueEditor } from "./QueueEditor";
import type { QueueActions } from "./useQueueActions";

interface Props {
  /** 排队的消息，按轮到的顺序。 */
  queued: readonly UserItem[];
  /** 现在有没有在运行：没有就不能插话。 */
  running: boolean;
  actions: QueueActions;
}

/** 一行的预览：空白折叠成一个空格。超长由样式省略。 */
export function preview(text: string): string {
  return text.replace(/\s+/g, " ").trim();
}

/**
 * 输入框上方的队列条（input-area 需求 1；DSH `QueueDock.tsx`）：只有一条时直接显示，多于一条先显示“N 条排队中”，点开才列出。
 * 每条可以改文字、移除、转成插话；一个操作还没返回时全部按钮不可用。运行开始后条目不再排队，这里自动消失。
 */
export function QueueDock({ queued, running, actions }: Props) {
  const t = useText();
  const text = t.composer.queue;
  const [open, setOpen] = useState(false);
  const [editing, setEditing] = useState<string | null>(null);
  if (queued.length === 0) return null;
  const single = queued.length === 1;
  const listed = single || open;

  return (
    <div className="mb-2 rounded-md border border-line bg-muted px-3 py-2 text-xs text-fg-muted" role="group" aria-label={t.composer.queueTitle}>
      {!single && (
        <button
          type="button"
          aria-expanded={open}
          aria-label={open ? text.collapse : text.expand}
          onClick={() => setOpen((value) => !value)}
          className="flex w-full items-center gap-1.5 text-left font-medium text-fg"
        >
          {open ? <ChevronDown size={13} aria-hidden="true" /> : <ChevronRight size={13} aria-hidden="true" />}
          <ListOrdered size={13} aria-hidden="true" />
          {t.composer.queued(queued.length)}
        </button>
      )}
      {listed && (
        <ol className={single ? "flex flex-col" : "mt-1 flex flex-col gap-0.5"}>
          {queued.map((item) => {
            const attachments = item.attachments ?? [];
            const names = attachments.map((attachment) => attachment.name).join("、");
            const line = preview(item.text);
            // 点名的技能在排队那一刻已经读好附上了：改了文字就对不上，所以不能改（服务器也会拒绝）
            const skills = (item.skills ?? []).map((skill) => skill.name).join("、");
            const fixed = attachments.length > 0 || skills !== "";
            return (
              <li key={item.id} className="flex min-h-7 items-start gap-1">
                {editing === item.id ? (
                  <QueueEditor
                    text={item.text}
                    busy={actions.busy}
                    onCancel={() => setEditing(null)}
                    onSave={(next) => {
                      // 没改就不用问服务器
                      if (next === item.text) {
                        setEditing(null);
                        return;
                      }
                      void actions.edit(item.id, next).then((saved) => {
                        if (saved) setEditing(null);
                      });
                    }}
                  />
                ) : (
                  <>
                    <span className="min-w-0 flex-1 truncate py-1.5 text-fg" title={item.text}>
                      {single && <ListOrdered size={13} aria-hidden="true" className="mr-1.5 inline align-[-2px] text-fg-muted" />}
                      {line}
                      {names !== "" && <span className="text-fg-muted">{line === "" ? text.attachments(names) : ` · ${text.attachments(names)}`}</span>}
                      {skills !== "" && <span className="text-fg-muted">{` · ${text.skills(skills)}`}</span>}
                    </span>
                    <Button
                      variant="icon"
                      size="sm"
                      disabled={actions.busy || fixed}
                      aria-label={text.edit}
                      title={attachments.length > 0 ? text.noEditWithAttachments : skills !== "" ? text.noEditWithSkills : text.edit}
                      onClick={() => setEditing(item.id)}
                    >
                      <Pencil size={13} />
                    </Button>
                    <Button
                      variant="icon"
                      size="sm"
                      disabled={actions.busy || !running}
                      aria-label={text.steer}
                      title={running ? text.steer : text.noSteerWhenIdle}
                      onClick={() => void actions.steer(item.id)}
                    >
                      <CornerDownRight size={13} />
                    </Button>
                    <Button variant="icon" size="sm" disabled={actions.busy} aria-label={text.remove} title={text.remove} onClick={() => void actions.remove(item.id)}>
                      <X size={13} />
                    </Button>
                  </>
                )}
              </li>
            );
          })}
        </ol>
      )}
      {actions.error !== null && (
        <p role="alert" className="pt-1 text-danger">
          {errorLine(actions.error)}
        </p>
      )}
    </div>
  );
}

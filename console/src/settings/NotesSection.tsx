// 记忆页里的“自动记下”状态和每天的笔记（memory-capture 需求 3、4）：按日期列出，打开看全文（每段能跳回那一轮），能改、能删。
// 只读查看别人时能看，开关和编辑、删除都不给。

import { ChevronLeft } from "lucide-react";
import { useState } from "react";
import { useViewedUser } from "../api/viewing";
import type { NoteListed } from "../api/types";
import { navigate, tracePath } from "../app/router";
import { Markdown } from "../markdown/Markdown";
import { useText } from "../text";
import { errorLine } from "../text/errors";
import { relativeTime } from "../text/format";
import { Button } from "../ui/Button";
import { Dialog } from "../ui/Dialog";
import { useCapture, useDeleteNote, useNote, useNotes, useSaveNote } from "./useNotes";

/** “自动记下”现在开没开；开关在插件页“记忆”的参数里（memory-plugin 需求 3.2），这里只说明。 */
export function CaptureStatus() {
  const t = useText().memory.notes;
  const capture = useCapture();
  if (capture.data === undefined) return null;
  const line = !capture.data.server ? t.serverOff : capture.data.enabled ? t.captureHint : t.captureOff;
  return (
    <div className="flex flex-col gap-1 rounded-md border border-line px-3 py-2">
      <p className="text-sm text-fg">
        {t.capture}
        <span className="ml-2 text-xs text-fg-muted">{capture.data.server && capture.data.enabled ? t.on : t.off}</span>
      </p>
      <p className="text-xs text-fg-faint">{line}</p>
    </div>
  );
}

function NoteRow({ note, onOpen }: { note: NoteListed; onOpen: () => void }) {
  const t = useText().memory.notes;
  return (
    <button type="button" onClick={onOpen} className="flex w-full flex-col gap-1 px-3 py-2 text-left hover:bg-hover">
      <span className="flex items-center gap-2">
        <span className="min-w-0 truncate text-sm text-fg">{note.title === "" ? t.untitled : note.title}</span>
        <span className="shrink-0 text-xs text-fg-faint">{t.sections(note.sections)}</span>
        <span className="ml-auto shrink-0 text-xs text-fg-faint">{relativeTime(note.updated)}</span>
      </span>
      <span className="truncate text-xs text-fg-muted">{note.preview}</span>
    </button>
  );
}

export function NotesList({ onOpen }: { onOpen: (day: string, session: string) => void }) {
  const t = useText().memory.notes;
  const days = useNotes();
  return (
    <section aria-label={t.title} className="flex flex-col gap-2">
      <h3 className="text-sm font-medium text-fg">{t.title}</h3>
      <p className="text-xs text-fg-faint">{t.hint}</p>
      {days.isError && (
        <p role="alert" className="text-sm text-danger">
          {errorLine(days.error)}
        </p>
      )}
      {days.data !== undefined && days.data.length === 0 && <p className="text-sm text-fg-muted">{t.empty}</p>}
      {days.data?.map((day) => (
        <div key={day.day} className="flex flex-col gap-1">
          <h4 className="text-xs font-medium text-fg-muted">{day.day}</h4>
          <ul className="flex flex-col divide-y divide-line rounded-md border border-line">
            {day.notes.map((note) => (
              <li key={note.session}>
                <NoteRow note={note} onOpen={() => onOpen(day.day, note.session)} />
              </li>
            ))}
          </ul>
        </div>
      ))}
    </section>
  );
}

export function NoteView({ day, session, onBack }: { day: string; session: string; onBack: () => void }) {
  const text = useText();
  const t = text.memory.notes;
  const note = useNote(day, session);
  const save = useSaveNote(day, session);
  const remove = useDeleteNote(day, session);
  const viewing = useViewedUser() !== null;
  const [draft, setDraft] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);

  async function confirmDelete(): Promise<void> {
    await remove.mutateAsync();
    setConfirming(false);
    onBack();
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-2">
        <Button variant="ghost" size="sm" onClick={onBack}>
          <ChevronLeft size={14} aria-hidden="true" />
          {text.memory.back}
        </Button>
        <span className="ml-auto" />
        {!viewing && note.data !== undefined && draft === null && (
          <>
            <Button variant="secondary" size="sm" onClick={() => setDraft(note.data.text)}>
              {t.edit}
            </Button>
            <Button variant="danger" size="sm" onClick={() => setConfirming(true)}>
              {text.memory.delete}
            </Button>
          </>
        )}
      </div>
      {note.isPending && <p className="text-sm text-fg-faint">{text.common.loading}</p>}
      {note.isError && (
        <p role="alert" className="text-sm text-danger">
          {errorLine(note.error)}
        </p>
      )}
      {note.data !== undefined && (
        <>
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="text-sm font-medium text-fg">{note.data.title === "" ? t.untitled : note.data.title}</h3>
            <span className="text-xs text-fg-faint">{day}</span>
          </div>
          {draft !== null ? (
            <div className="flex flex-col gap-2">
              <textarea
                aria-label={t.edit}
                className="min-h-64 w-full rounded-md border border-line bg-raised p-2 font-mono text-xs text-fg focus-visible:outline-2 focus-visible:outline-focus"
                value={draft}
                onChange={(event) => setDraft(event.target.value)}
              />
              <div className="flex justify-end gap-2">
                <Button variant="secondary" size="sm" onClick={() => setDraft(null)}>
                  {text.common.cancel}
                </Button>
                <Button
                  variant="primary"
                  size="sm"
                  disabled={save.isPending}
                  onClick={() => void save.mutateAsync(draft).then(() => setDraft(null))}
                >
                  {save.isPending ? text.memory.saving : text.memory.save}
                </Button>
              </div>
              {save.isError && (
                <p role="alert" className="text-xs text-danger">
                  {errorLine(save.error)}
                </p>
              )}
            </div>
          ) : (
            <ol className="flex flex-col gap-3">
              {note.data.sections.map((section, index) => (
                <li key={section.run ?? index} className="rounded-md border border-line px-3 py-2">
                  <div className="flex items-center gap-2 text-xs text-fg-faint">
                    <span>{section.heading}</span>
                    {section.run !== undefined && (
                      <button
                        type="button"
                        className="ml-auto text-accent hover:underline"
                        onClick={() => navigate(tracePath(session, section.run as string))}
                      >
                        {t.openTurn}
                      </button>
                    )}
                  </div>
                  <Markdown text={section.body} />
                </li>
              ))}
            </ol>
          )}
        </>
      )}
      <Dialog open={confirming} onClose={() => setConfirming(false)} title={t.confirmDelete} className="max-w-md">
        <div className="flex flex-col gap-4 p-6">
          <p className="text-sm text-fg">{t.confirmDeleteBody}</p>
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={() => setConfirming(false)}>
              {text.common.cancel}
            </Button>
            <Button variant="danger" onClick={() => void confirmDelete()} disabled={remove.isPending}>
              {text.common.confirm}
            </Button>
          </div>
        </div>
      </Dialog>
    </div>
  );
}

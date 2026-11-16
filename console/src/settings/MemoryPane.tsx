import { ChevronLeft, Plus } from "lucide-react";
import type { FormEvent } from "react";
import { useState } from "react";
import type { MemorySummary, MemoryType } from "../api/types";
import { Markdown } from "../markdown/Markdown";
import { relativeTime } from "../text/format";
import { useText } from "../text";
import { Button } from "../ui/Button";
import { Dialog } from "../ui/Dialog";
import { useCreateMemory, useDeleteMemory, useForget, useMemories, useMemory } from "./useMemories";
import { CaptureStatus, NotesList, NoteView } from "./NotesSection";
import { NowSection } from "./NowSection";
import { PendingSection } from "./PendingSection";
import { Tabs } from "../ui/Tabs";
import { usePending } from "./useMemories";
import { usePlugins } from "../plugins/usePlugins";
import { errorLine } from "../text/errors";

type View = { kind: "list" } | { kind: "entry"; name: string } | { kind: "add" } | { kind: "note"; day: string; session: string };

const TYPES: MemoryType[] = ["user", "feedback", "project", "reference"];

type Tab = "now" | "pending" | "entries" | "notes";

/**
 * 记忆页（memory-final-plan 第 16 节的记忆页改版）：现在 · 待确认 · 档案 · 每天四栏。档案是长期记忆，看、删、手工新增；
 * 列表按更新时间倒序，正文按需加载。记忆插件关着或没装时只说明一句（memory-plugin 需求 3.1）。
 */
export function MemoryPane() {
  const t = useText();
  const plugins = usePlugins();
  const [view, setView] = useState<View>({ kind: "list" });
  const pending = usePending();
  const [chosen, setChosen] = useState<Tab | null>(null);
  // 打开时有待确认的就先看待确认；之后不跟着条数来回跳（确认完最后一条还留在这一页）
  const [opening, setOpening] = useState<Tab | null>(null);
  if (opening === null && pending.data !== undefined) setOpening(pending.data.length > 0 ? "pending" : "now");
  const tab: Tab = chosen ?? opening ?? "now";
  if (plugins.data !== undefined && !plugins.data.plugins.some((plugin) => plugin.name === "memory" && plugin.enabled)) {
    return <p className="text-sm text-fg-muted">{t.memory.pluginOff}</p>;
  }
  if (view.kind === "entry") return <EntryView name={view.name} onBack={() => setView({ kind: "list" })} />;
  if (view.kind === "add") return <AddForm onDone={() => setView({ kind: "list" })} />;
  if (view.kind === "note") return <NoteView day={view.day} session={view.session} onBack={() => setView({ kind: "list" })} />;
  const count = pending.data?.length ?? 0;
  return (
    <div className="flex flex-col gap-4">
      <CaptureStatus />
      <Tabs
        label={t.memory.title}
        className="border-b border-line"
        selected={tab}
        onSelect={(id) => setChosen(id as Tab)}
        tabs={[
          { id: "now", label: t.memory.tabs.now },
          { id: "pending", label: count > 0 ? `${t.memory.tabs.pending} ${count}` : t.memory.tabs.pending },
          { id: "entries", label: t.memory.tabs.entries },
          { id: "notes", label: t.memory.tabs.notes },
        ]}
      />
      <div role="tabpanel" aria-labelledby={`tab-${tab}`}>
        {tab === "now" && <NowSection />}
        {tab === "pending" && <PendingSection />}
        {tab === "entries" && <ListView onOpen={(name) => setView({ kind: "entry", name })} onAdd={() => setView({ kind: "add" })} />}
        {tab === "notes" && <NotesList onOpen={(day, session) => setView({ kind: "note", day, session })} />}
      </div>
    </div>
  );
}

function TypeTag({ type }: { type: MemoryType }) {
  const t = useText();
  return <span className="rounded-xs bg-muted px-1.5 py-0.5 font-mono text-[11px] text-fg-muted">{t.memory.types[type] ?? type}</span>;
}

function ListView({ onOpen, onAdd }: { onOpen: (name: string) => void; onAdd: () => void }) {
  const t = useText();
  const list = useMemories();
  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center justify-between gap-2">
        <h3 className="text-sm font-medium text-fg">{t.memory.title}</h3>
        {list.data !== undefined && <span className="text-xs text-fg-faint">{t.memory.used(list.data.used, list.data.max)}</span>}
      </div>
      <p className="text-xs text-fg-faint">{t.memory.hint}</p>
      {list.isPending && <p className="text-sm text-fg-faint">{t.common.loading}</p>}
      {list.isError && (
        <p role="alert" className="text-sm text-danger">
          {t.memory.loadFailed}
        </p>
      )}
      {list.data !== undefined && list.data.entries.length === 0 && <p className="text-sm text-fg-muted">{t.memory.empty}</p>}
      {list.data !== undefined && list.data.entries.length > 0 && (
        <ul className="flex flex-col divide-y divide-line rounded-md border border-line">
          {list.data.entries.map((entry) => (
            <li key={entry.name}>
              <EntryRow entry={entry} onOpen={() => onOpen(entry.name)} />
            </li>
          ))}
        </ul>
      )}
      <div>
        <Button variant="secondary" size="sm" onClick={onAdd}>
          <Plus size={14} aria-hidden="true" />
          {t.memory.add}
        </Button>
      </div>
      <ForgetForm />
    </div>
  );
}

/** 按一句话忘掉（memory-final-plan 第 10 节①）：意思一样的记忆、从它来的、笔记里复述它的都去掉，以后换说法也找不回来。 */
function ForgetForm() {
  const t = useText().memory.forget;
  const forget = useForget();
  const [text, setText] = useState("");
  function onSubmit(event: FormEvent) {
    event.preventDefault();
    if (text.trim() === "") return;
    forget.mutate(text.trim(), { onSuccess: () => setText("") });
  }
  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-1.5 border-t border-line pt-3">
      <label className="text-xs text-fg-muted" htmlFor="memory-forget">
        {t.label}
      </label>
      <div className="flex gap-2">
        <input
          id="memory-forget"
          className="min-w-0 flex-1 rounded-sm border border-line bg-raised px-2 py-1 text-sm text-fg"
          value={text}
          maxLength={2000}
          placeholder={t.placeholder}
          onChange={(event) => setText(event.target.value)}
        />
        <Button type="submit" size="sm" variant="secondary" disabled={forget.isPending || text.trim() === ""}>
          {t.submit}
        </Button>
      </div>
      {forget.data !== undefined && <p className="text-xs text-fg-faint">{t.done(forget.data.forgotten)}</p>}
      {forget.error !== null && (
        <p role="alert" className="text-xs text-danger">
          {errorLine(forget.error)}
        </p>
      )}
    </form>
  );
}

function EntryRow({ entry, onOpen }: { entry: MemorySummary; onOpen: () => void }) {
  return (
    <button type="button" onClick={onOpen} className="flex w-full flex-col gap-1 px-3 py-2 text-left hover:bg-hover">
      <span className="flex items-center gap-2">
        <span className="font-mono text-sm text-fg">{entry.name}</span>
        <TypeTag type={entry.type} />
        <span className="ml-auto text-xs text-fg-faint">{relativeTime(entry.updatedAt)}</span>
      </span>
      <span className="text-sm text-fg-muted">{entry.description}</span>
    </button>
  );
}

function EntryView({ name, onBack }: { name: string; onBack: () => void }) {
  const t = useText();
  const entry = useMemory(name);
  const remove = useDeleteMemory();
  const [confirming, setConfirming] = useState(false);

  async function confirmDelete(): Promise<void> {
    await remove.mutateAsync(name);
    setConfirming(false);
    onBack();
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-2">
        <Button variant="ghost" size="sm" onClick={onBack}>
          <ChevronLeft size={14} aria-hidden="true" />
          {t.memory.back}
        </Button>
        <span className="ml-auto" />
        <Button variant="danger" size="sm" onClick={() => setConfirming(true)}>
          {t.memory.delete}
        </Button>
      </div>
      {entry.isPending && <p className="text-sm text-fg-faint">{t.common.loading}</p>}
      {entry.isError && (
        <p role="alert" className="text-sm text-danger">
          {errorLine(entry.error)}
        </p>
      )}
      {entry.data !== undefined && (
        <>
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="font-mono text-sm font-medium text-fg">{entry.data.name}</h3>
            <TypeTag type={entry.data.type} />
            <span className="text-xs text-fg-faint">
              {t.memory.updatedAt} {relativeTime(entry.data.updatedAt)}
            </span>
          </div>
          <p className="text-sm text-fg-muted">{entry.data.description}</p>
          <div className="rounded-md border border-line px-4 py-2">
            <Markdown text={entry.data.content} />
          </div>
        </>
      )}
      {remove.isError && (
        <p role="alert" className="text-sm text-danger">
          {errorLine(remove.error)}
        </p>
      )}
      <Dialog open={confirming} onClose={() => setConfirming(false)} title={t.memory.confirmDelete} className="max-w-md">
        <div className="flex flex-col gap-4 p-6">
          <p className="text-sm text-fg">{t.memory.confirmDeleteBody(name)}</p>
          <div className="flex justify-end gap-2">
            <Button variant="secondary" onClick={() => setConfirming(false)}>
              {t.common.cancel}
            </Button>
            <Button variant="danger" onClick={() => void confirmDelete()} disabled={remove.isPending}>
              {t.common.confirm}
            </Button>
          </div>
        </div>
      </Dialog>
    </div>
  );
}

function AddForm({ onDone }: { onDone: () => void }) {
  const t = useText();
  const create = useCreateMemory();
  const [name, setName] = useState("");
  const [type, setType] = useState<MemoryType>("user");
  const [description, setDescription] = useState("");
  const [content, setContent] = useState("");

  async function submit(event: FormEvent): Promise<void> {
    event.preventDefault();
    try {
      await create.mutateAsync({ name: name.trim(), type, description: description.trim(), content });
      onDone();
    } catch {
      // 原因已在 create.error 里，表单下方显示。
    }
  }

  const field = "focus-plain w-full rounded-md border border-line bg-raised px-3 py-1.5 text-sm text-fg focus:border-line-strong";
  return (
    <form onSubmit={(event) => void submit(event)} className="flex flex-col gap-3">
      <div className="flex items-center gap-2">
        <Button variant="ghost" size="sm" onClick={onDone}>
          <ChevronLeft size={14} aria-hidden="true" />
          {t.memory.back}
        </Button>
        <h3 className="text-sm font-medium text-fg">{t.memory.add}</h3>
      </div>
      <label className="flex flex-col gap-1 text-xs text-fg-muted">
        {t.memory.name}
        <input className={field} value={name} onChange={(event) => setName(event.target.value)} placeholder={t.memory.nameHint} required />
      </label>
      <label className="flex flex-col gap-1 text-xs text-fg-muted">
        {t.memory.type}
        <select className={field} value={type} onChange={(event) => setType(event.target.value as MemoryType)}>
          {TYPES.map((option) => (
            <option key={option} value={option}>
              {t.memory.types[option]}
            </option>
          ))}
        </select>
      </label>
      <label className="flex flex-col gap-1 text-xs text-fg-muted">
        {t.memory.description}
        <input className={field} value={description} onChange={(event) => setDescription(event.target.value)} required />
      </label>
      <label className="flex flex-col gap-1 text-xs text-fg-muted">
        {t.memory.content}
        <textarea className={field} rows={6} value={content} onChange={(event) => setContent(event.target.value)} required />
      </label>
      {create.isError && (
        <p role="alert" className="text-sm text-danger">
          {errorLine(create.error)}
        </p>
      )}
      <div className="flex justify-end">
        <Button variant="primary" type="submit" disabled={create.isPending}>
          {create.isPending ? t.memory.saving : t.memory.save}
        </Button>
      </div>
    </form>
  );
}

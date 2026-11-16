import { BookOpen, ChevronDown, ChevronRight, Paperclip, TriangleAlert } from "lucide-react";
import { useState } from "react";
import type { AttachedNote, SkillNote } from "../api/types";
import { AttachmentView } from "../attachment/AttachmentView";
import type { ConversationStore } from "../store/ConversationStore";
import { useItem } from "../store/hooks";
import { clockTime } from "../text/format";
import { lookup, useText } from "../text";
import { CopyButton } from "../ui/CopyButton";
import { SessionNote } from "./SessionNote";

interface Props {
  store: ConversationStore;
  id: string;
}

/**
 * 右对齐的浅蓝气泡，圆角 20 px；附件在气泡上方；下方时间和复制。排队中的提交带标签。只有附件时没有文字气泡。
 * 文字里点名的技能在气泡下面各占一行：读到的能展开看全文，没读到的写明原因（skills-wiki 需求 5.7、5.8）。
 * 插件附的内容也各占一行，写明是哪个插件附的，能展开看全文（memory-plugin 需求 1.2）。
 */
export function UserBubble({ store, id }: Props) {
  const t = useText();
  const item = useItem(store, id);
  // 排队时被移除的只留在数据里占位
  if (item === undefined || item.kind !== "user" || item.removed === true) return null;
  // 另一个会话送来的不是用户说的，不画成气泡
  if (item.from !== undefined) return <SessionNote item={{ ...item, from: item.from }} />;
  return (
    <div data-turn={id} className="group flex scroll-mt-4 flex-col items-end gap-1 py-2">
      <AttachmentView attachments={item.attachments ?? []} />
      {item.text !== "" && (
        <div className="max-w-[85%] rounded-xl bg-bubble px-4 py-2.5 text-[15px] leading-relaxed whitespace-pre-wrap text-fg">
          {item.text}
        </div>
      )}
      {(item.skills ?? []).map((skill) => (
        <SkillLine key={skill.name} skill={skill} />
      ))}
      {(item.attached ?? []).map((note, index) => (
        <AttachedLine key={`${note.plugin}:${index}`} note={note} />
      ))}
      <div className="flex h-6 items-center gap-1 text-xs text-fg-faint">
        {item.queued && <span className="rounded-xs bg-muted px-1.5 py-0.5 text-[11px] text-fg-muted">{t.conversation.queued}</span>}
        <span>{clockTime(item.at)}</span>
        <CopyButton text={item.text} className="opacity-0 transition-opacity group-hover:opacity-100 focus-visible:opacity-100" />
      </div>
    </div>
  );
}

/** 气泡下面插件附的一段：默认收起，展开是纯文字。 */
function AttachedLine({ note }: { note: AttachedNote }) {
  const t = useText();
  const [open, setOpen] = useState(false);
  return (
    <div className="flex max-w-[85%] flex-col items-end gap-1">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
        className="flex items-center gap-1.5 rounded-xs px-1 py-0.5 text-xs text-fg-muted hover:bg-hover"
      >
        <Paperclip size={13} aria-hidden="true" className="shrink-0" />
        <span>{t.conversation.attachedBy(note.plugin)}</span>
        {open ? <ChevronDown size={13} aria-hidden="true" /> : <ChevronRight size={13} aria-hidden="true" />}
      </button>
      {open && (
        <pre
          aria-label={t.conversation.attachedContent(note.plugin)}
          className="max-h-80 w-full overflow-auto rounded-sm bg-code p-2 text-left font-mono text-xs whitespace-pre-wrap text-fg"
        >
          {note.text}
        </pre>
      )}
    </div>
  );
}

/** 气泡下面的一行技能。全文是技能文件的内容，原样按纯文字显示，不当成 Markdown 或 HTML 渲染。 */
function SkillLine({ skill }: { skill: SkillNote }) {
  const t = useText();
  const [open, setOpen] = useState(false);
  if (skill.content === undefined) {
    const code = skill.error ?? "";
    const reason = code === "timeout" ? t.conversation.skillTimeout : (lookup(t.plugins.local.skills.problems, code) ?? code);
    return (
      <p role="status" className="flex max-w-[85%] items-center gap-1.5 text-xs text-warning">
        <TriangleAlert size={13} aria-hidden="true" className="shrink-0" />
        <span className="min-w-0 break-words">{t.conversation.skillFailed(skill.name, reason)}</span>
      </p>
    );
  }
  return (
    <div className="flex max-w-[85%] flex-col items-end gap-1">
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen((current) => !current)}
        className="flex items-center gap-1.5 rounded-xs px-1 py-0.5 text-xs text-fg-muted hover:bg-hover"
      >
        <BookOpen size={13} aria-hidden="true" className="shrink-0" />
        <span>{t.conversation.skillUsed(skill.name)}</span>
        {open ? <ChevronDown size={13} aria-hidden="true" /> : <ChevronRight size={13} aria-hidden="true" />}
      </button>
      {open && (
        <pre
          aria-label={t.conversation.skillContent(skill.name)}
          className="max-h-80 w-full overflow-auto rounded-sm bg-code p-2 text-left font-mono text-xs whitespace-pre-wrap text-fg"
        >
          {skill.content}
        </pre>
      )}
    </div>
  );
}

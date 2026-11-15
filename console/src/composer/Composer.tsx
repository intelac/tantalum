import { ArrowUp, Paperclip, Square } from "lucide-react";
import type { ChangeEvent, ClipboardEvent, KeyboardEvent, ReactNode } from "react";
import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import { cancelRun, submit } from "../api/client";
import type { AttachmentLimits, AttachmentRef, MenuSkill, ModelChoice } from "../api/types";
import { AttachmentTray } from "../attachment/AttachmentTray";
import { DropZone } from "../attachment/DropZone";
import { draftAttachments } from "../attachment/drafts";
import { admit, DEFAULT_LIMITS, isImage } from "../attachment/limits";
import { errorLine } from "../text/errors";
import { formatBytes } from "../text/format";
import { useQueue } from "../attachment/useQueue";
import { applyMention, filterSkills, mentionAt } from "../skills/mention";
import { SkillMenu } from "../skills/SkillMenu";
import { useText } from "../text";
import { Button } from "../ui/Button";
import { cx } from "../ui/cx";
import { clearDraft, readDraft, saveDraft } from "./drafts";

interface Props {
  session: string;
  /** 当前运行；没有或已结束时为空。 */
  activeRun: string | null;
  modelName: string;
  cloud: boolean;
  /** 附件的上限（前端先查）和模型是否收图片；默认同服务器。 */
  limits?: AttachmentLimits;
  vision?: boolean;
  /** 输入框旁的模型选择器；没有时只写模型名（旧画面、测试）。 */
  modelPicker?: ReactNode;
  /** 随这次提交发出的模型选择（model-picker M6）；插话不带。 */
  choice?: ModelChoice | null;
  /** 提交成功：选择已经发出去了。 */
  onSubmitted?: (choice: ModelChoice | null) => void;
  /** 随提问发出的工作区（workspaces W3）；服务器只在会话的第一次提问时用它。 */
  project?: string | null;
  /** 随提问发出“不进记忆”（memory-final-plan 第 11 节）；同样只有第一次提问算数。 */
  offRecord?: boolean;
  /** 附件按钮右边的标签：工作区、电脑连接（workspaces 需求 7）。 */
  accessory?: ReactNode;
  /** 排队的消息有几条；输入框空着按 Ctrl/Cmd+Enter 时把它们都转成插话（input-area 需求 1.10）。 */
  queued?: number;
  onSteerQueue?: () => void;
  /** 能点名的技能（skills-wiki 需求 5.1）：打 `/` 时在输入框上方列出来。没有就没有菜单。 */
  skills?: readonly MenuSkill[];
  /** 开始打 `/` 了：让上面重新取一次清单。 */
  onSkillMenu?: () => void;
}

const NO_SKILLS: readonly MenuSkill[] = [];

const MAX_HEIGHT = 200;

/**
 * 大圆角输入卡片。Enter 发送、Shift+Enter 换行、输入法组字时 Enter 不发送。
 * 运行中 Enter 提交进入排队，Ctrl/Cmd+Enter 作为插话发给当前运行，并多一个停止按钮。被拒绝时保留内容和附件，重试用同一个 requestId。
 * 附件按钮、拖放、粘贴走同一条路径（需求 1.1–1.3）；发送时等附件都传完再提交（需求 1.7）。
 * 在开头或空白后打 `/` 弹出技能菜单：上下键选，Enter 或 Tab 填入 `/名字 `，Esc 关掉；菜单开着时 Enter 不发送，输入法组字时不弹。
 */
export function Composer({
  session,
  activeRun,
  modelName,
  cloud,
  limits = DEFAULT_LIMITS,
  vision = true,
  modelPicker,
  choice = null,
  onSubmitted,
  project = null,
  offRecord = false,
  accessory,
  queued = 0,
  onSteerQueue,
  skills = NO_SKILLS,
  onSkillMenu,
}: Props) {
  const t = useText();
  const [text, setText] = useState(() => readDraft(session));
  // 本地的提示是字符串；接口的失败存原样，显示时才变成文字（text/errors.ts）
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);
  const [waiting, setWaiting] = useState(false);
  const queue = draftAttachments(session);
  const pending = useQueue(queue);
  const picker = useRef<HTMLInputElement>(null);
  const requestId = useRef<string | null>(null);
  /** 上次失败时发送的内容；改过再发要换 requestId，否则服务端按重复提交丢掉改过的内容。 */
  const failedContent = useRef<string | null>(null);
  const area = useRef<HTMLTextAreaElement>(null);
  const running = activeRun !== null;
  const menuId = useId();
  /** 填入技能名之后光标该在的位置；文字渲染完就放过去。 */
  const placeCaret = useRef<number | null>(null);
  /** 光标位置：菜单只看光标前面的字。 */
  const [caret, setCaret] = useState(0);
  const [composing, setComposing] = useState(false);
  /** 按 Esc 关掉的那一次点名（`/` 的位置）：再打字也不弹，换个地方打 `/` 才弹。 */
  const [dismissed, setDismissed] = useState<number | null>(null);
  const [activeSkill, setActiveSkill] = useState(0);
  const mention = composing ? null : mentionAt(text, caret);
  const offered = mention === null || mention.start === dismissed ? [] : filterSkills(skills, mention.query);
  const menuOpen = offered.length > 0;
  const chosen = Math.min(activeSkill, Math.max(0, offered.length - 1));
  const mentionStart = mention?.start ?? null;
  const askedFor = useRef<number | null>(null);
  useEffect(() => {
    // 开始一次新的点名：清单可能变了，让上面再取一次；选中的回到第一项
    if (mentionStart !== null && askedFor.current !== mentionStart) onSkillMenu?.();
    askedFor.current = mentionStart;
    setActiveSkill(0);
    if (mentionStart === null) setDismissed(null);
    // 只跟着“这一次点名从哪开始”走；onSkillMenu 每次渲染都是新的函数，不放进依赖
  }, [mentionStart]);
  const query = mention?.query ?? "";
  useEffect(() => setActiveSkill(0), [query]);

  function pickSkill(skill: MenuSkill): void {
    if (mention === null) return;
    const next = applyMention(text, mention, caret, skill.name);
    setText(next.text);
    saveDraft(session, next.text);
    setCaret(next.caret);
    // 文字进了输入框之后再放光标（见下面的 useLayoutEffect）
    placeCaret.current = next.caret;
  }

  // 切换会话：存当前草稿，读目标会话的草稿，忘掉上一次失败的 requestId。
  useEffect(() => {
    setText(readDraft(session));
    setError(null);
    requestId.current = null;
    failedContent.current = null;
  }, [session]);

  useLayoutEffect(() => {
    const element = area.current;
    if (element === null) return;
    element.style.height = "auto";
    const next = Math.min(element.scrollHeight, MAX_HEIGHT);
    element.style.height = `${next}px`;
    element.style.overflowY = element.scrollHeight > MAX_HEIGHT ? "auto" : "hidden";
    if (placeCaret.current !== null) {
      element.focus();
      element.setSelectionRange(placeCaret.current, placeCaret.current);
      placeCaret.current = null;
    }
  }, [text]);

  function onChange(event: ChangeEvent<HTMLTextAreaElement>): void {
    setText(event.target.value);
    setCaret(event.target.selectionStart ?? event.target.value.length);
    saveDraft(session, event.target.value);
  }

  /** 三种添加方式的共同入口：先查限制，收下能收的，说明第一条没收下的原因。 */
  function addFiles(files: readonly File[]): void {
    if (files.length === 0) return;
    const existing = pending.map((item) => ({ name: item.name, size: item.size, image: item.image }));
    const incoming = files.map((file) => ({ name: file.name, size: file.size, image: isImage(file) }));
    const admission = admit(existing, incoming, limits, vision);
    queue.add(admission.accepted.map((index) => files[index] as File));
    setError(admission.reason ?? null);
  }

  /** 对话里拖回来的附件：已经在服务器上，不再上传。 */
  function addRef(ref: AttachmentRef): void {
    const existing = pending.map((item) => ({ name: item.name, size: item.size, image: item.image }));
    const admission = admit(existing, [{ name: ref.name, size: ref.bytes, image: ref.kind === "image" }], limits, vision);
    if (admission.accepted.length > 0) queue.addExisting(ref);
    setError(admission.reason ?? null);
  }

  function onPick(event: ChangeEvent<HTMLInputElement>): void {
    addFiles(Array.from(event.target.files ?? []));
    event.target.value = "";
  }

  /** 粘贴里有文件（截图、复制的图片）就作为附件；同时有文字时文字照常粘贴。 */
  function onPaste(event: ClipboardEvent<HTMLTextAreaElement>): void {
    const files = Array.from(event.clipboardData.files);
    if (files.length === 0) return;
    if (event.clipboardData.getData("text/plain") === "") event.preventDefault();
    addFiles(files);
  }

  /** `steer`：运行中作为插话发出，不排队；没有在运行时和普通发送一样。 */
  async function send(steer = false): Promise<void> {
    const content = text.trim();
    if ((content === "" && pending.length === 0) || busy) return;
    setBusy(true);
    setError(null);
    try {
      if (queue.busy()) {
        setWaiting(true);
        await queue.settled();
      }
      const attachments = queue.refs();
      if (attachments === null) {
        setError(t.attachment.notStaged);
        return;
      }
      // 内容（文字加附件）改过再发要换 requestId，否则服务端按重复提交丢掉改过的内容。
      const mode = !running ? "prompt" : steer ? "steer" : "follow-up";
      const signature = JSON.stringify([mode, content, attachments.map((ref) => ref.id)]);
      if (requestId.current === null || failedContent.current !== signature) requestId.current = crypto.randomUUID();
      try {
        // 插话并进当前运行，不换模型
        await submit(session, mode, content, requestId.current, attachments, mode === "steer" ? null : choice, project, offRecord);
      } catch (failure) {
        failedContent.current = signature;
        setError(failure);
        return;
      }
      requestId.current = null;
      failedContent.current = null;
      if (mode !== "steer") onSubmitted?.(choice);
      setText("");
      clearDraft(session);
      queue.clear();
    } finally {
      setWaiting(false);
      setBusy(false);
      area.current?.focus();
    }
  }

  function onKeyDown(event: KeyboardEvent<HTMLTextAreaElement>): void {
    if (menuOpen && !event.nativeEvent.isComposing && event.keyCode !== 229) {
      if (event.key === "ArrowDown" || event.key === "ArrowUp") {
        event.preventDefault();
        const step = event.key === "ArrowDown" ? 1 : offered.length - 1;
        setActiveSkill((chosen + step) % offered.length);
        return;
      }
      if (event.key === "Escape") {
        event.preventDefault();
        setDismissed(mentionStart);
        return;
      }
      if ((event.key === "Enter" && !event.shiftKey && !event.ctrlKey && !event.metaKey) || event.key === "Tab") {
        event.preventDefault();
        pickSkill(offered[chosen] as MenuSkill);
        return;
      }
    }
    if (event.key !== "Enter" || event.shiftKey) return;
    // 输入法还在组字时的 Enter 是选字，不是发送。
    if (event.nativeEvent.isComposing || event.keyCode === 229) return;
    event.preventDefault();
    const steer = running && (event.ctrlKey || event.metaKey);
    // 输入框空着：没有可发的，把排队的都转成插话
    if (steer && steerAll) {
      onSteerQueue?.();
      return;
    }
    void send(steer);
  }

  async function stop(): Promise<void> {
    if (activeRun === null) return;
    try {
      await cancelRun(activeRun);
    } catch (failure) {
      setError(failure);
    }
  }

  const canSend = (text.trim() !== "" || pending.length > 0) && !busy;
  /** 运行中、输入框和附件都空、有排队的：Ctrl/Cmd+Enter 是“全部转成插话”。 */
  const steerAll = running && queued > 0 && text.trim() === "" && pending.length === 0;
  const dropLimits = vision ? t.attachment.dropDesc(limits.maxImagesPerMessage, formatBytes(limits.maxImageBytes)) : t.attachment.modelUnsupported;

  return (
    <div className="flex flex-col gap-1.5">
      <DropZone limitsText={dropLimits} onFiles={addFiles} onRef={addRef} onRefused={setError} />
      {error !== null && (
        <p role="alert" className="px-3 text-xs text-danger">
          {errorLine(error)}
        </p>
      )}
      {waiting && (
        <p role="status" className="px-3 text-xs text-fg-muted">
          {t.attachment.waiting}
        </p>
      )}
      <div className="relative rounded-2xl border border-line bg-raised shadow-sm focus-within:border-line-strong">
        {menuOpen && <SkillMenu id={menuId} skills={offered} active={chosen} onPick={pickSkill} onHover={setActiveSkill} />}
        <AttachmentTray items={pending} onRemove={(key) => queue.remove(key)} onRetry={(key) => queue.retry(key)} />
        <textarea
          ref={area}
          rows={1}
          value={text}
          onChange={onChange}
          onKeyDown={onKeyDown}
          onPaste={onPaste}
          onSelect={(event) => setCaret(event.currentTarget.selectionStart ?? 0)}
          onCompositionStart={() => setComposing(true)}
          onCompositionEnd={(event) => {
            setComposing(false);
            setCaret(event.currentTarget.selectionStart ?? 0);
          }}
          // 还是普通的文本框（role 不变）：菜单开着时只告诉读屏选中的是哪一项
          aria-controls={menuOpen ? menuId : undefined}
          aria-activedescendant={menuOpen ? `${menuId}-${chosen}` : undefined}
          placeholder={steerAll ? t.composer.steerAllPlaceholder : t.composer.placeholder}
          aria-label={t.composer.placeholder}
          className="focus-plain block w-full resize-none bg-transparent px-4 pt-3.5 pb-1 text-[15px] leading-relaxed text-fg placeholder:text-fg-faint"
        />
        <div className="flex h-12 items-center gap-2 pr-2 pl-3">
          <Button variant="icon" size="sm" aria-label={t.attachment.add} title={t.attachment.add} onClick={() => picker.current?.click()}>
            <Paperclip size={16} />
          </Button>
          <input ref={picker} type="file" multiple hidden onChange={onPick} data-testid="attachment-picker" />
          {accessory}
          <span className="flex-1" />
          {modelPicker === undefined ? (
            <span className="max-w-56 truncate text-xs text-fg-muted" title={modelName}>
              {modelName}
              <span className="ml-1 text-fg-faint">{cloud ? t.composer.cloud : t.composer.local}</span>
            </span>
          ) : (
            <>
              {modelPicker}
              {/* 选的路线会不会把提示词发到云端，和选择器一起看（tech.md 安全） */}
              <span className="shrink-0 text-xs text-fg-faint">{cloud ? t.composer.cloud : t.composer.local}</span>
            </>
          )}
          {running && (
            <Button
              variant="icon"
              aria-label={t.composer.stop}
              title={t.composer.stopHint}
              onClick={() => void stop()}
              className="border border-line text-danger hover:bg-danger-soft"
            >
              <Square size={14} fill="currentColor" />
            </Button>
          )}
          <Button
            variant="icon"
            aria-label={running ? t.composer.followUp : t.composer.send}
            title={running ? t.composer.queueHint : t.composer.hint}
            disabled={!canSend}
            onClick={() => void send()}
            className={cx("bg-primary text-primary-fg hover:bg-primary hover:text-primary-fg hover:opacity-90", !canSend && "opacity-40")}
          >
            <ArrowUp size={18} />
          </Button>
        </div>
      </div>
    </div>
  );
}

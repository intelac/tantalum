import { ArrowUp, Paperclip, Square } from "lucide-react";
import type { ChangeEvent, ClipboardEvent, KeyboardEvent, ReactNode } from "react";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { cancelRun, submit } from "../api/client";
import type { AttachmentLimits, AttachmentRef, ModelChoice } from "../api/types";
import { AttachmentTray } from "../attachment/AttachmentTray";
import { DropZone } from "../attachment/DropZone";
import { draftAttachments } from "../attachment/drafts";
import { admit, DEFAULT_LIMITS, isImage } from "../attachment/limits";
import { errorLine } from "../text/errors";
import { formatBytes } from "../text/format";
import { useQueue } from "../attachment/useQueue";
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
}

const MAX_HEIGHT = 200;

/**
 * 大圆角输入卡片。Enter 发送、Shift+Enter 换行、输入法组字时 Enter 不发送。
 * 运行中提交进入排队，并多一个停止按钮。被拒绝时保留内容和附件，重试用同一个 requestId。
 * 附件按钮、拖放、粘贴走同一条路径（需求 1.1–1.3）；发送时等附件都传完再提交（需求 1.7）。
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
  }, [text]);

  function onChange(event: ChangeEvent<HTMLTextAreaElement>): void {
    setText(event.target.value);
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

  async function send(): Promise<void> {
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
      const signature = JSON.stringify([content, attachments.map((ref) => ref.id)]);
      if (requestId.current === null || failedContent.current !== signature) requestId.current = crypto.randomUUID();
      try {
        await submit(session, running ? "follow-up" : "prompt", content, requestId.current, attachments, choice);
      } catch (failure) {
        failedContent.current = signature;
        setError(failure);
        return;
      }
      requestId.current = null;
      failedContent.current = null;
      onSubmitted?.(choice);
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
    if (event.key !== "Enter" || event.shiftKey) return;
    // 输入法还在组字时的 Enter 是选字，不是发送。
    if (event.nativeEvent.isComposing || event.keyCode === 229) return;
    event.preventDefault();
    void send();
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
      <div className="rounded-2xl border border-line bg-raised shadow-sm focus-within:border-line-strong">
        <AttachmentTray items={pending} onRemove={(key) => queue.remove(key)} onRetry={(key) => queue.retry(key)} />
        <textarea
          ref={area}
          rows={1}
          value={text}
          onChange={onChange}
          onKeyDown={onKeyDown}
          onPaste={onPaste}
          placeholder={t.composer.placeholder}
          aria-label={t.composer.placeholder}
          className="focus-plain block w-full resize-none bg-transparent px-4 pt-3.5 pb-1 text-[15px] leading-relaxed text-fg placeholder:text-fg-faint"
        />
        <div className="flex h-12 items-center gap-2 pr-2 pl-3">
          <Button variant="icon" size="sm" aria-label={t.attachment.add} title={t.attachment.add} onClick={() => picker.current?.click()}>
            <Paperclip size={16} />
          </Button>
          <input ref={picker} type="file" multiple hidden onChange={onPick} data-testid="attachment-picker" />
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

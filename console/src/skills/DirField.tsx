import { useEffect, useId, useState } from "react";
import type { LocalDirState } from "../api/types";
import { lookup, useText } from "../text";
import { errorLine } from "../text/errors";
import { Button } from "../ui/Button";

const FIELD =
  "focus-plain min-w-0 flex-1 rounded-md border border-line bg-raised px-3 py-1.5 font-mono text-xs text-fg focus:border-line-strong disabled:bg-muted disabled:text-fg-muted";

/**
 * 插件页“技能”“知识库”共用的那一块：目录输入框（回车、失焦或点“保存”时存）、刷新、目录现在的情况一行。
 * 目录原样交给电脑上的连接程序解析，这里不猜它存不存在。
 */
export function DirField({ label, placeholder, dir, state, path, disabled, saving, error, failed, onSave, onRefresh, refreshing }: {
  label: string;
  placeholder: string;
  /** 服务器上存着的目录；还没取到时为 undefined。 */
  dir: string | undefined;
  state: LocalDirState | undefined;
  path?: string;
  disabled: boolean;
  saving: boolean;
  /** 保存失败的原因。 */
  error: unknown;
  /** 清单没取到。 */
  failed: boolean;
  onSave: (dir: string) => void;
  onRefresh: () => void;
  refreshing: boolean;
}) {
  const text = useText().plugins.local.dir;
  const id = useId();
  const [draft, setDraft] = useState(dir ?? "");
  // 服务器上的值变了（保存成功、别的页面改了）就跟着变；自己正在改的不覆盖
  const [seen, setSeen] = useState(dir);
  useEffect(() => {
    if (dir !== seen) {
      setSeen(dir);
      setDraft(dir ?? "");
    }
  }, [dir, seen]);
  const changed = dir !== undefined && draft.trim() !== dir;
  const save = () => {
    if (changed && !saving) onSave(draft.trim());
  };
  const note = state === undefined || state === "ok" || state === "off" ? undefined : lookup(text.states, state);
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-xs text-fg-muted">
        {label}
      </label>
      <div className="flex flex-wrap items-center gap-2">
        <input
          id={id}
          type="text"
          value={draft}
          placeholder={placeholder}
          disabled={disabled || dir === undefined}
          spellCheck={false}
          autoCapitalize="off"
          autoCorrect="off"
          className={FIELD}
          onChange={(event) => setDraft(event.target.value)}
          onBlur={save}
          onKeyDown={(event) => {
            if (event.key === "Enter" && !event.nativeEvent.isComposing) {
              event.preventDefault();
              save();
            }
          }}
        />
        <Button size="sm" variant="primary" disabled={disabled || !changed || saving} onClick={save}>
          {text.save}
        </Button>
        <Button size="sm" disabled={disabled || refreshing} onClick={onRefresh}>
          {text.refresh}
        </Button>
      </div>
      {error !== null && error !== undefined && (
        <p role="alert" className="text-xs text-danger">
          {text.saveFailed(errorLine(error))}
        </p>
      )}
      {failed && (
        <p role="alert" className="text-xs text-danger">
          {text.listFailed}
        </p>
      )}
      {note !== undefined && (
        <p role="status" className={state === "unset" ? "text-xs text-fg-faint" : "text-xs text-warning"}>
          {note}
        </p>
      )}
      {state === "ok" && path !== undefined && path !== dir && <p className="font-mono text-[11px] break-all text-fg-faint">{text.path(path)}</p>}
    </div>
  );
}

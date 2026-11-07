import type { FormEvent } from "react";
import { useState } from "react";
import { changePassword } from "../api/client";
import { useText } from "../text";
import { errorLine } from "../text/errors";
import { Button } from "../ui/Button";

const FIELD = "focus-plain w-full rounded-md border border-line bg-raised px-3 py-1.5 text-sm text-fg focus:border-line-strong";

/**
 * 自己改密码（ops-hardening-2 需求 3.1）：当前密码、新密码、确认。两次新密码不一样在本地就拦住；
 * 当前密码错是接口的 401 `auth/invalid-credentials`，按代码翻译。成功后清空表单、显示一句提示。
 */
export function PasswordForm() {
  const t = useText();
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");
  const [state, setState] = useState<{ kind: "idle" } | { kind: "saving" } | { kind: "done" } | { kind: "failed"; message: string }>({ kind: "idle" });

  async function submit(event: FormEvent): Promise<void> {
    event.preventDefault();
    if (next !== confirm) {
      setState({ kind: "failed", message: t.password.mismatch });
      return;
    }
    setState({ kind: "saving" });
    try {
      await changePassword(current, next);
      setCurrent("");
      setNext("");
      setConfirm("");
      setState({ kind: "done" });
    } catch (failure) {
      setState({ kind: "failed", message: errorLine(failure) });
    }
  }

  return (
    <form onSubmit={(event) => void submit(event)} className="flex flex-col gap-2 border-t border-line pt-3">
      <h4 className="text-sm font-medium text-fg">{t.password.title}</h4>
      <label className="flex flex-col gap-1 text-xs text-fg-muted">
        {t.password.current}
        <input className={FIELD} type="password" autoComplete="current-password" value={current} onChange={(event) => setCurrent(event.target.value)} required />
      </label>
      <label className="flex flex-col gap-1 text-xs text-fg-muted">
        {t.password.next}
        <input className={FIELD} type="password" autoComplete="new-password" value={next} onChange={(event) => setNext(event.target.value)} required />
      </label>
      <label className="flex flex-col gap-1 text-xs text-fg-muted">
        {t.password.confirm}
        <input className={FIELD} type="password" autoComplete="new-password" value={confirm} onChange={(event) => setConfirm(event.target.value)} required />
      </label>
      {state.kind === "failed" && (
        <p role="alert" className="text-sm text-danger">
          {state.message}
        </p>
      )}
      {state.kind === "done" && (
        <p role="status" className="text-sm text-fg-muted">
          {t.password.done}
        </p>
      )}
      <div>
        <Button variant="primary" size="sm" type="submit" disabled={state.kind === "saving"}>
          {state.kind === "saving" ? t.password.saving : t.password.save}
        </Button>
      </div>
    </form>
  );
}

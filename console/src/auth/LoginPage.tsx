import type { FormEvent } from "react";
import { useState } from "react";
import { ApiError, login } from "../api/client";
import type { User } from "../api/types";
import { Brand } from "../shell/Brand";
import { zh } from "../text/zh";
import { Button } from "../ui/Button";

interface Props {
  onLoggedIn: (user: User) => void;
}

const FIELD =
  "h-11 w-full rounded-md border border-line bg-canvas px-3 text-sm text-fg placeholder:text-fg-faint " +
  "focus:border-line-strong";

/** 居中卡片，28 px 圆角。失败只说“用户名或密码错误”。手机宽度下卡片占满，留 16 px 边距。 */
export function LoginPage({ onLoggedIn }: Props) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      onLoggedIn(await login(username.trim(), password));
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 429) setError(zh.login.throttled);
      else if (failure instanceof ApiError && (failure.status === 401 || failure.status === 400)) setError(zh.login.failed);
      else setError(zh.login.unavailable);
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="flex min-h-full items-center justify-center bg-sidebar p-4">
      <form
        onSubmit={(event) => void onSubmit(event)}
        className="flex w-full max-w-sm flex-col gap-5 rounded-2xl border border-line bg-raised p-8 shadow-sm"
        aria-labelledby="login-title"
      >
        <div className="flex flex-col items-center gap-3 pb-1">
          <Brand size="lg" symbolOnly />
          <h1 id="login-title" className="text-xl font-semibold text-fg">
            {zh.app.name}
          </h1>
          <p className="text-sm text-fg-muted">{zh.app.tagline}</p>
        </div>
        <label className="flex flex-col gap-1.5 text-sm text-fg">
          {zh.login.username}
          <input
            className={FIELD}
            name="username"
            autoComplete="username"
            autoFocus
            required
            value={username}
            onChange={(event) => setUsername(event.target.value)}
          />
        </label>
        <label className="flex flex-col gap-1.5 text-sm text-fg">
          {zh.login.password}
          <input
            className={FIELD}
            name="password"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
        </label>
        {error !== null && (
          <p role="alert" className="-mb-2 text-sm text-danger">
            {error}
          </p>
        )}
        <Button type="submit" variant="primary" size="lg" disabled={busy} className="w-full">
          {busy ? zh.login.submitting : zh.login.submit}
        </Button>
      </form>
    </main>
  );
}

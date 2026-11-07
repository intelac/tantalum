import { Plus } from "lucide-react";
import type { FormEvent } from "react";
import { useState } from "react";
import type { Account } from "../api/types";
import { useText } from "../text";
import { errorLine } from "../text/errors";
import { relativeTime } from "../text/format";
import { Button } from "../ui/Button";
import { useCreateUser, useResetUserPassword, useUpdateUser, useUsers } from "./useUsers";

const FIELD = "focus-plain w-full rounded-md border border-line bg-raised px-3 py-1.5 text-sm text-fg focus:border-line-strong";

interface Props {
  /** 当前登录的管理员：对自己的“停用”“取消管理员”不可用（ops-hardening-2 D2）。 */
  self: string;
}

/** 管理员管用户（需求 3.2）：列表、新建、停用/启用、设/取消管理员、重置密码。错误按代码翻译。 */
export function UsersPane({ self }: Props) {
  const t = useText();
  const list = useUsers();
  const [adding, setAdding] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  return (
    <div className="flex flex-col gap-3">
      <h3 className="text-sm font-medium text-fg">{t.users.title}</h3>
      <p className="text-xs text-fg-faint">{t.users.hint}</p>
      {list.isPending && <p className="text-sm text-fg-faint">{t.common.loading}</p>}
      {list.isError && (
        <p role="alert" className="text-sm text-danger">
          {t.users.loadFailed}
        </p>
      )}
      {list.data !== undefined && (
        <table className="w-full border-collapse text-sm">
          <thead>
            <tr className="text-left text-xs text-fg-muted">
              <th className="py-1 pr-3 font-normal">{t.users.columns.name}</th>
              <th className="py-1 pr-3 font-normal">{t.users.columns.role}</th>
              <th className="py-1 pr-3 font-normal">{t.users.columns.state}</th>
              <th className="py-1 pr-3 font-normal">{t.users.columns.createdAt}</th>
              <th className="py-1" />
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {list.data.users.map((account) => (
              <Row key={account.name} account={account} isSelf={account.name === self} onNotice={setNotice} />
            ))}
          </tbody>
        </table>
      )}
      {notice !== null && (
        <p role="status" className="text-sm text-fg-muted">
          {notice}
        </p>
      )}
      {adding ? (
        <AddForm
          onDone={(name) => {
            setAdding(false);
            setNotice(t.users.created(name));
          }}
          onCancel={() => setAdding(false)}
        />
      ) : (
        <div>
          <Button variant="secondary" size="sm" onClick={() => setAdding(true)}>
            <Plus size={14} aria-hidden="true" />
            {t.users.add}
          </Button>
        </div>
      )}
    </div>
  );
}

function Row({ account, isSelf, onNotice }: { account: Account; isSelf: boolean; onNotice: (text: string) => void }) {
  const t = useText();
  const update = useUpdateUser();
  const [resetting, setResetting] = useState(false);

  async function change(change: { disabled?: boolean; admin?: boolean }): Promise<void> {
    try {
      await update.mutateAsync({ name: account.name, change });
    } catch {
      // 原因在 update.error 里，行下方显示
    }
  }

  return (
    <>
      <tr>
        <td className="py-1.5 pr-3 font-mono text-fg">
          {account.name}
          {isSelf && <span className="ml-1 text-xs text-fg-faint">({t.users.you})</span>}
        </td>
        <td className="py-1.5 pr-3 text-fg-muted">{account.admin ? t.users.admin : t.users.user}</td>
        <td className="py-1.5 pr-3 text-fg-muted">{account.disabled ? t.users.disabled : t.users.enabled}</td>
        <td className="py-1.5 pr-3 text-fg-muted">{relativeTime(account.createdAt)}</td>
        <td className="py-1.5">
          <div role="group" aria-label={t.users.actionsFor(account.name)} className="flex flex-wrap justify-end gap-1">
            <Button size="sm" variant="ghost" disabled={isSelf || update.isPending} title={isSelf ? t.users.selfHint : undefined} onClick={() => void change({ disabled: !account.disabled })}>
              {account.disabled ? t.users.enable : t.users.disable}
            </Button>
            <Button size="sm" variant="ghost" disabled={(isSelf && account.admin) || update.isPending} title={isSelf ? t.users.selfHint : undefined} onClick={() => void change({ admin: !account.admin })}>
              {account.admin ? t.users.demote : t.users.promote}
            </Button>
            <Button size="sm" variant="ghost" onClick={() => setResetting((open) => !open)}>
              {t.users.resetPassword}
            </Button>
          </div>
        </td>
      </tr>
      {(update.isError || resetting) && (
        <tr>
          <td colSpan={5} className="pb-2">
            {update.isError && (
              <p role="alert" className="text-sm text-danger">
                {errorLine(update.error)}
              </p>
            )}
            {resetting && (
              <ResetForm
                name={account.name}
                onDone={() => {
                  setResetting(false);
                  onNotice(t.users.resetDone(account.name));
                }}
                onCancel={() => setResetting(false)}
              />
            )}
          </td>
        </tr>
      )}
    </>
  );
}

function ResetForm({ name, onDone, onCancel }: { name: string; onDone: () => void; onCancel: () => void }) {
  const t = useText();
  const reset = useResetUserPassword();
  const [password, setPassword] = useState("");

  async function submit(event: FormEvent): Promise<void> {
    event.preventDefault();
    try {
      await reset.mutateAsync({ name, password });
      onDone();
    } catch {
      // 原因在 reset.error 里
    }
  }

  return (
    <form onSubmit={(event) => void submit(event)} className="flex flex-col gap-2 pt-1">
      <label className="flex flex-col gap-1 text-xs text-fg-muted">
        {t.users.newPassword(name)}
        <input className={FIELD} type="password" autoComplete="new-password" value={password} onChange={(event) => setPassword(event.target.value)} required />
      </label>
      {reset.isError && (
        <p role="alert" className="text-sm text-danger">
          {errorLine(reset.error)}
        </p>
      )}
      <div className="flex gap-2">
        <Button variant="primary" size="sm" type="submit" disabled={reset.isPending}>
          {t.users.reset}
        </Button>
        <Button variant="ghost" size="sm" onClick={onCancel}>
          {t.common.cancel}
        </Button>
      </div>
    </form>
  );
}

function AddForm({ onDone, onCancel }: { onDone: (name: string) => void; onCancel: () => void }) {
  const t = useText();
  const create = useCreateUser();
  const [name, setName] = useState("");
  const [password, setPassword] = useState("");
  const [admin, setAdmin] = useState(false);

  async function submit(event: FormEvent): Promise<void> {
    event.preventDefault();
    try {
      const created = await create.mutateAsync({ name: name.trim(), password, admin });
      onDone(created.name);
    } catch {
      // 原因在 create.error 里，表单下方显示
    }
  }

  return (
    <form onSubmit={(event) => void submit(event)} className="flex flex-col gap-2 rounded-md border border-line p-3">
      <h4 className="text-sm font-medium text-fg">{t.users.add}</h4>
      <label className="flex flex-col gap-1 text-xs text-fg-muted">
        {t.users.name}
        <input className={FIELD} value={name} onChange={(event) => setName(event.target.value)} placeholder={t.users.nameHint} autoComplete="off" required />
      </label>
      <label className="flex flex-col gap-1 text-xs text-fg-muted">
        {t.users.initialPassword}
        <input className={FIELD} type="password" autoComplete="new-password" value={password} onChange={(event) => setPassword(event.target.value)} required />
      </label>
      <label className="flex items-center gap-2 text-sm text-fg">
        <input type="checkbox" checked={admin} onChange={(event) => setAdmin(event.target.checked)} />
        {t.users.makeAdmin}
      </label>
      {create.isError && (
        <p role="alert" className="text-sm text-danger">
          {errorLine(create.error)}
        </p>
      )}
      <div className="flex gap-2">
        <Button variant="primary" size="sm" type="submit" disabled={create.isPending}>
          {create.isPending ? t.users.creating : t.users.create}
        </Button>
        <Button variant="ghost" size="sm" onClick={onCancel}>
          {t.common.cancel}
        </Button>
      </div>
    </form>
  );
}

import { Plus } from "lucide-react";
import type { FormEvent } from "react";
import { useState } from "react";
import type { Account, DailyLimitSource, UsageWeight } from "../api/types";
import { setViewedUser } from "../api/viewing";
import { useText } from "../text";
import { errorLine } from "../text/errors";
import { relativeTime } from "../text/format";
import { ParamForm } from "../plugins/ParamForm";
import { usePluginDefaults } from "../plugins/usePlugins";
import { Button } from "../ui/Button";
import { DefaultLimit, LimitCell } from "./LimitCell";
import { PluginUsageSection } from "./PluginUsageSection";
import { UsageCell } from "./UsageCell";
import { useAssignedParams, useCreateUser, useResetUserPassword, useUpdateUser, useUsers } from "./useUsers";

const FIELD = "focus-plain w-full rounded-md border border-line bg-raised px-3 py-1.5 text-sm text-fg focus:border-line-strong";

interface Props {
  /** 当前登录的管理员：对自己的“停用”“取消管理员”不可用（ops-hardening-2 D2）。 */
  self: string;
}

/**
 * 运维（ops-hardening-2 需求 3.2，plugin-settings P14，qagent-align 需求 4、5）：一张表看每个人今天用了多少、离上限多远、
 * 有几个会话、现在有没有在跑；上限在表格里直接改；新建、停用/启用、设/取消管理员、重置密码照旧；
 * 有“只有管理员能定”的参数时，每人一个“参数”可以单独指定。页面开着时每 10 秒刷新一次。错误按代码翻译。
 */
export function OpsPane({ self }: Props) {
  const t = useText();
  const list = useUsers();
  // 没有一个插件声明管理员的参数时，不出现“参数”按钮；这份查不到（旧服务器）也一样
  const defaults = usePluginDefaults();
  const assignable = (defaults.data?.items ?? []).some((item) => item.params.some((param) => param.owner === "admin"));
  const [adding, setAdding] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const weighted = list.data?.weighted === true;
  // 没有任何插件报每日上限时，没有上限这一列
  const limits = (list.data?.users ?? []).some((account) => account.limit !== undefined);
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
      {list.data?.dailyLimit !== undefined && <DefaultLimit source={list.data.dailyLimit} />}
      {list.data !== undefined && (
        // 窄屏时表格在这个容器里横向滚动，页面本身不滚
        <div className="overflow-x-auto">
          <table className="w-full min-w-[640px] border-collapse text-sm">
            <thead>
              <tr className="text-left text-xs text-fg-muted">
                <th className="py-1 pr-3 font-normal">{t.users.columns.user}</th>
                <th className="py-1 pr-3 text-right font-normal" title={weighted ? t.users.weighedHint : undefined}>
                  {weighted ? t.users.columns.usageWeighed : t.users.columns.usage}
                </th>
                {limits && <th className="py-1 pr-3 text-right font-normal">{t.users.columns.limit}</th>}
                <th className="py-1 pr-3 text-right font-normal" title={t.users.sessionsHint}>
                  {t.users.columns.sessions}
                </th>
                <th className="py-1 pr-3 text-right font-normal" title={t.users.runningHint}>
                  {t.users.columns.running}
                </th>
                <th className="py-1 text-right font-normal">{t.users.columns.actions}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line">
              {list.data.users.map((account) => (
                <Row
                  key={account.name}
                  account={account}
                  isSelf={account.name === self}
                  assignable={assignable}
                  limits={limits}
                  source={list.data.dailyLimit}
                  viewable={list.data.viewAs === true}
                  onNotice={setNotice}
                />
              ))}
            </tbody>
          </table>
        </div>
      )}
      {(list.data?.weights ?? []).length > 0 && <Weights rows={list.data?.weights ?? []} />}
      {list.data !== undefined && <PluginUsageSection />}
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

/** 折算系数：服务器配置里写的，只读（qagent-align 需求 3.8）。 */
function Weights({ rows }: { rows: readonly UsageWeight[] }) {
  const t = useText();
  const text = t.users.weights;
  return (
    <section aria-label={text.title} className="flex flex-col gap-1.5">
      <h4 className="text-sm font-medium text-fg">{text.title}</h4>
      <p className="text-xs text-fg-faint">{text.hint}</p>
      <div className="overflow-x-auto">
        <table className="border-collapse text-sm">
          <thead>
            <tr className="text-left text-xs text-fg-muted">
              <th className="py-1 pr-4 font-normal">{text.route}</th>
              <th className="py-1 pr-4 font-normal">{text.model}</th>
              <th className="py-1 pr-4 text-right font-normal">{text.input}</th>
              <th className="py-1 pr-4 text-right font-normal">{text.output}</th>
              <th className="py-1 pr-4 text-right font-normal">{text.cacheRead}</th>
              <th className="py-1 text-right font-normal">{text.cacheWrite}</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-line">
            {rows.map((row) => (
              <tr key={`${row.route}/${row.model ?? ""}`}>
                <td className="py-1 pr-4 font-mono text-fg">{row.route}</td>
                <td className="py-1 pr-4 font-mono text-fg-muted">{row.model ?? text.whole}</td>
                <td className="py-1 pr-4 text-right tabular-nums">{row.input}</td>
                <td className="py-1 pr-4 text-right tabular-nums">{row.output}</td>
                <td className="py-1 pr-4 text-right tabular-nums">{row.cacheRead}</td>
                <td className="py-1 text-right tabular-nums">{row.cacheWrite}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function Row({ account, isSelf, assignable, limits, source, viewable, onNotice }: {
  account: Account;
  isSelf: boolean;
  assignable: boolean;
  /** 表格有没有“每日上限”这一列。 */
  limits: boolean;
  source: DailyLimitSource | undefined;
  /** 服务器开着“只读查看别人的会话”。 */
  viewable: boolean;
  onNotice: (text: string) => void;
}) {
  const t = useText();
  const update = useUpdateUser();
  const [resetting, setResetting] = useState(false);
  const [assigning, setAssigning] = useState(false);

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
        <td className="py-1.5 pr-3 align-top" title={t.users.createdAt(relativeTime(account.createdAt))}>
          <span className="font-mono text-fg">{account.name}</span>
          {isSelf && <span className="ml-1 text-xs text-fg-faint">({t.users.you})</span>}
          <span className="mt-0.5 flex flex-wrap gap-1 text-[11px]">
            <span className="rounded-xs bg-muted px-1.5 py-0.5 text-fg-muted">{account.admin ? t.users.admin : t.users.user}</span>
            {account.disabled && <span className="rounded-xs bg-muted px-1.5 py-0.5 text-warning">{t.users.disabled}</span>}
          </span>
        </td>
        <td className="py-1.5 pr-3 text-right align-top text-fg-muted">
          <UsageCell account={account} />
        </td>
        {limits && (
          <td className="py-1.5 pr-3 text-right align-top text-fg-muted">
            <LimitCell account={account} source={source} />
          </td>
        )}
        <td className="py-1.5 pr-3 text-right align-top text-fg-muted tabular-nums">{account.sessions ?? ""}</td>
        <td className={account.running !== undefined && account.running > 0 ? "py-1.5 pr-3 text-right align-top font-medium text-fg tabular-nums" : "py-1.5 pr-3 text-right align-top text-fg-muted tabular-nums"}>
          {account.running ?? ""}
        </td>
        <td className="w-48 py-1.5 align-top">
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
            {assignable && (
              <Button size="sm" variant="ghost" aria-expanded={assigning} onClick={() => setAssigning((open) => !open)}>
                {t.users.params}
              </Button>
            )}
            {viewable && !isSelf && (
              // 进入查看后整个工作区重新挂载，这个对话框也就关了
              <Button size="sm" variant="ghost" aria-label={t.viewing.viewFor(account.name)} title={t.viewing.viewHint} onClick={() => setViewedUser(account.name)}>
                {t.viewing.view}
              </Button>
            )}
          </div>
        </td>
      </tr>
      {(update.isError || resetting || assigning) && (
        <tr>
          <td colSpan={limits ? 6 : 5} className="pb-2">
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
            {assigning && <Assigned user={account.name} />}
          </td>
        </tr>
      )}
    </>
  );
}

/** 给一个人单独指定“只有管理员能定”的参数；清掉一项就回到默认。 */
function Assigned({ user }: { user: string }) {
  const t = useText();
  const assigned = useAssignedParams(user);
  return (
    <div className="flex flex-col gap-3 rounded-md border border-line p-3">
      <p className="text-xs text-fg-faint">{t.users.paramsHint(user)}</p>
      {assigned.query.isPending && <p className="text-sm text-fg-faint">{t.common.loading}</p>}
      {assigned.query.isError && (
        <p role="alert" className="text-sm text-danger">
          {t.users.paramsLoadFailed}
        </p>
      )}
      {(assigned.query.data?.items ?? []).map((item) => (
        <div key={`${item.kind}/${item.name}`} className="flex flex-col gap-2">
          <h4 className="font-mono text-xs text-fg">{item.name}</h4>
          <ParamForm name={item.name} params={item.params} mode="assigned" onSave={(values) => assigned.save(item.kind, item.name, values)} />
        </div>
      ))}
    </div>
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

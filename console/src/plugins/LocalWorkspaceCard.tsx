import { useState } from "react";
import type { LocalLink, LocalStatus, LocalSwitch, LocalValidity } from "../api/types";
import { lookup, type Text, useText } from "../text";
import { errorLine, sandboxText } from "../text/errors";
import { clockTime } from "../text/format";
import { Button } from "../ui/Button";
import { CopyButton } from "../ui/CopyButton";
import { cx } from "../ui/cx";
import { useNow } from "../ui/useNow";
import { Row, Section } from "./Rows";
import { useIssueToken, useLocal, useRevokeLocal, useToggleLocal } from "./useLocal";

const MASTER: LocalSwitch = "local-workspace";
const CHILDREN: LocalSwitch[] = ["fs-read", "git", "file-edit", "shell"];
const VALIDITIES: LocalValidity[] = ["30m", "1h", "4h", "1d"];
const NO_SHELL_FLAG = "--allow-shell was not given";

/**
 * 插件页的“本机工作区”（local-workspace L17，需求 6）：总开关和读文件、Git 查询、改文件、命令行四个子开关；连接状态和到期倒计时；
 * “连接这台电脑”生成令牌和一行命令（只显示这一次）；“撤销”断开电脑。页面开着时每 2 秒取一次状态。
 */
export function LocalWorkspaceCard({ open }: { open: boolean }) {
  const t = useText();
  const text = t.plugins.local;
  const local = useLocal(open);
  const toggle = useToggleLocal();
  const status = local.data;
  if (local.isError) {
    return (
      <p role="alert" className="text-sm text-danger">
        {text.loadFailed}
      </p>
    );
  }
  if (status === undefined) return null;
  const master = status.switches[MASTER];
  const set = (name: LocalSwitch, enabled: boolean) => toggle.mutate({ name, enabled });
  return (
    <Section title={text.title}>
      <Row
        name={
          <>
            {text.title}
            <span className="ml-2 font-mono text-[11px] text-fg-faint">{MASTER}</span>
          </>
        }
        detail={
          <>
            <span className="block">{text.detail}</span>
            <LinkState link={status.link} />
          </>
        }
        label={text.title}
        checked={master}
        onChange={(enabled) => set(MASTER, enabled)}
      />
      {CHILDREN.map((name) => {
        const [label, detail] = lookup(text.switches, name) ?? [name, ""];
        const on = status.switches[name];
        const refused = on && status.link !== null ? refusal(t, name, status.link) : null;
        return (
          <Row
            key={name}
            name={<span className="pl-4">{label}</span>}
            detail={
              <span className="block pl-4">
                {detail}
                {refused !== null && <span className="mt-0.5 block text-warning">{refused}</span>}
              </span>
            }
            label={label}
            checked={on}
            disabled={!master}
            onChange={(enabled) => set(name, enabled)}
          />
        );
      })}
      <li className="px-3 py-2.5">
        <Connect status={status} />
      </li>
    </Section>
  );
}

/** 子开关开着、电脑也连着，但连接程序没允许这一类时的说明。读文件只要连上就能用。 */
function refusal(t: Text, name: LocalSwitch, link: LocalLink): string | null {
  const text = t.plugins.local;
  if (name === "file-edit" && !link.write) return text.notAllowedWrite;
  if (name === "git" && !link.git) return text.noGit;
  if (name === "shell" && !link.shell.available) {
    const { code, params, reason } = link.shell;
    if (code === "shell/not-allowed") return text.sandbox["shell/not-allowed"];
    const known = code == null ? undefined : sandboxText(t, code, params ?? {});
    if (known !== undefined) return text.noSandbox(known);
    // 老版本连接程序只送原因文字：照原样显示
    const shown = reason ?? "";
    return shown === "" || shown === NO_SHELL_FLAG ? text.sandbox["shell/not-allowed"] : text.noSandbox(shown);
  }
  return null;
}

function osName(os: string, version: string): string {
  return `${os === "macos" ? "macOS" : os} ${version}`;
}

/** 已连接时：电脑、允许的目录、能不能改文件和跑命令、什么时候到期；没连接时一句提示。 */
function LinkState({ link }: { link: LocalLink | null }) {
  const text = useText().plugins.local;
  const now = useNow(link !== null, 1000);
  if (link === null) return <span className="mt-1 block text-fg-faint">{text.notConnected}</span>;
  const left = new Date(link.expiresAt).getTime() - now;
  return (
    <span className="mt-1 block text-fg" role="status">
      <span className="block">{text.connected(link.device.name, osName(link.device.os, link.device.osVersion))}</span>
      <span className="block font-mono text-[11px] break-all text-fg-muted">{text.dirs(link.roots.map((root) => root.path).join("、"))}</span>
      <span className="block text-fg-muted">
        {text.write(link.write)} · {text.shell(link.shell.available)}
      </span>
      <span className={cx("block", left <= 0 ? "text-warning" : "text-fg-muted")}>
        {left <= 0 ? text.expired : text.expires(clockTime(link.expiresAt), Math.max(1, Math.ceil(left / 60_000)))}
      </span>
    </span>
  );
}

/** 选有效期、生成令牌；生成的令牌和命令只在这里显示一次，关掉插件页就没了。 */
function Connect({ status }: { status: LocalStatus }) {
  const text = useText().plugins.local;
  const [validity, setValidity] = useState<LocalValidity>("1h");
  const issue = useIssueToken();
  const revoke = useRevokeLocal();
  const issued = issue.data;
  return (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2">
        <span id="local-validity" className="text-xs text-fg-muted">
          {text.validity}
        </span>
        <div role="radiogroup" aria-labelledby="local-validity" className="flex flex-wrap gap-1">
          {VALIDITIES.map((option) => (
            <button
              key={option}
              type="button"
              role="radio"
              aria-checked={option === validity}
              onClick={() => setValidity(option)}
              className={cx(
                "rounded-md border px-2 py-1 text-xs transition-colors duration-[var(--duration-fast)]",
                option === validity ? "border-fg bg-active text-fg" : "border-line bg-raised text-fg-muted hover:bg-hover",
              )}
            >
              {text.validities[option]}
            </button>
          ))}
        </div>
        <Button size="sm" variant="primary" disabled={issue.isPending} onClick={() => issue.mutate(validity)}>
          {text.connect}
        </Button>
        {(status.link !== null || issued !== undefined) && (
          <Button
            size="sm"
            variant="danger"
            title={text.revokeHint}
            disabled={revoke.isPending}
            onClick={() => revoke.mutate(undefined, { onSuccess: () => issue.reset() })}
          >
            {text.revoke}
          </Button>
        )}
      </div>
      {issue.isError && (
        <p role="alert" className="text-xs text-danger">
          {text.issueFailed(errorLine(issue.error))}
        </p>
      )}
      {revoke.isError && (
        <p role="alert" className="text-xs text-danger">
          {text.revokeFailed(errorLine(revoke.error))}
        </p>
      )}
      {issued !== undefined && (
        <div className="flex flex-col gap-1.5 rounded-md border border-line bg-muted p-2" aria-label={text.command}>
          <p className="text-xs text-fg">{text.issued(clockTime(issued.expiresAt))}</p>
          <div className="flex items-start gap-1" aria-label={text.token}>
            <span className="shrink-0 py-1 text-[11px] text-fg-muted">{text.token}</span>
            <code className="min-w-0 flex-1 rounded-xs bg-code px-2 py-1 font-mono text-[11px] break-all text-fg">{issued.token}</code>
            <CopyButton text={issued.token} />
          </div>
          <div className="flex items-start gap-1">
            <span className="shrink-0 py-1 text-[11px] text-fg-muted">{text.command}</span>
            <code className="min-w-0 flex-1 rounded-xs bg-code px-2 py-1 font-mono text-[11px] break-all text-fg">{issued.command}</code>
            <CopyButton text={issued.command} />
          </div>
          <ol aria-label={text.commandSteps} className="list-decimal pl-4 text-[11px] text-fg-muted">
            {text.commandHint.map((step) => (
              <li key={step}>{step}</li>
            ))}
          </ol>
        </div>
      )}
    </div>
  );
}

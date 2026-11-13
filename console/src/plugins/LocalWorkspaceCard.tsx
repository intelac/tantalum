import { useState } from "react";
import type { LocalLink, LocalMcpServer, LocalStatus, LocalSwitch, LocalValidity } from "../api/types";
import { lookup, type Text, useText } from "../text";
import { errorLine, sandboxText } from "../text/errors";
import { clockTime } from "../text/format";
import { Button } from "../ui/Button";
import { CopyButton } from "../ui/CopyButton";
import { cx } from "../ui/cx";
import { Switch } from "../ui/Switch";
import { useNow } from "../ui/useNow";
import { SkillsItem } from "../skills/SkillsItem";
import { WikiItem } from "../skills/WikiItem";
import { Row, Section } from "./Rows";
import { ChoicesList, ChoicesNotes, ChoicesToggle, useToolChoices } from "./ToolChoices";
import { useIssueToken, useLocal, useRevokeLocal, useToggleCommandApproval, useToggleLocal, useToggleLocalMcp } from "./useLocal";

const MASTER: LocalSwitch = "local-workspace";
const CHILDREN: LocalSwitch[] = ["fs-read", "git", "file-edit", "shell", "skills", "wiki", "browser", "local-mcp"];
/** 从这个协议版本起，连接程序会列技能和 wiki 页。 */
const SKILL_LISTS = 4;
/** 从这个协议版本起，连接程序能驱动电脑上的浏览器。 */
const BROWSER_TOOLS = 5;
/** 从这个协议版本起，连接程序是跨平台的 jar，报本机 MCP 服务器（connector-jar）。 */
const MCP_TOOLS = 6;
const VALIDITIES: LocalValidity[] = ["30m", "1h", "4h", "1d"];
const NO_SHELL_FLAG = "--allow-shell was not given";

/**
 * 插件页的“本机工作区”（local-workspace L17，需求 6）：总开关和读文件、Git 查询、改文件、命令行、技能、知识库、浏览器、本机 MCP
 * 八个子开关（技能、知识库各带一个目录，见 skills/；浏览器是电脑上已装的 Chrome 或 Edge，由本机的 Playwright MCP 驱动；命令行下面
 * 有“执行前先问我”；本机 MCP 下面每个服务器一个开关，见 connector-jar）；连接状态和到期倒计时、版本不一致的提示；
 * 照 QAgent 的三步：下载连接程序、生成令牌、按这台电脑的系统给出命令（只显示这一次）；“撤销”断开电脑。页面开着时每 2 秒取一次状态。
 */
export function LocalWorkspaceCard({ open }: { open: boolean }) {
  const t = useText();
  const text = t.plugins.local;
  const local = useLocal(open);
  const toggle = useToggleLocal();
  const status = local.data;
  // 一次也没取到时才整张报错；取到过以后某次轮询失败，照旧显示上一次的状态，顶上加一行（QAgent 问题 44：整张卡片消失）
  if (local.isError && status === undefined) {
    return (
      <p role="alert" className="text-sm text-danger">
        {text.loadFailed}
      </p>
    );
  }
  if (status === undefined) return null;
  const stale = local.isError;
  const master = status.switches[MASTER];
  const set = (name: LocalSwitch, enabled: boolean) => toggle.mutate({ name, enabled });
  return (
    <Section title={text.title}>
      {stale && (
        <li role="status" className="px-3 py-2 text-xs text-warning">
          {text.refreshFailed}
        </li>
      )}
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
      {CHILDREN.map((name) => (
        <Child key={name} name={name} status={status} open={open} onChange={(enabled) => set(name, enabled)} />
      ))}
      <li className="px-3 py-2.5">
        <Connect status={status} />
      </li>
    </Section>
  );
}

/** 一个子开关：说明、现在为什么用不了、（技能、知识库）目录、（浏览器）可以逐个勾的工具。 */
function Child({ name, status, open, onChange }: { name: LocalSwitch; status: LocalStatus; open: boolean; onChange: (enabled: boolean) => void }) {
  const t = useText();
  const text = t.plugins.local;
  const master = status.switches[MASTER];
  const [label, detail] = lookup(text.switches, name) ?? [name, ""];
  const on = status.switches[name];
  const refused = on && status.link !== null ? refusal(t, name, status.link) : null;
  // 浏览器能用时写明是哪个、有没有窗口（local-browser 需求 7.3）
  const ready = name === "browser" && on && status.link !== null && refused === null ? browserReady(t, status.link) : null;
  // 电脑连没连着、连接程序新不新、开关开没开：变了就重新向电脑要清单
  const signal = `${status.link === null ? "offline" : (status.link.protocol ?? 0) >= SKILL_LISTS ? "linked" : "old"}:${master && on}`;
  // 下面的工具可以逐个勾（qagent-align 需求 2）：总开关和这个子开关都开着才能勾，选择一直留着
  const choices = useToolChoices(status.tools?.[name]);
  const pickable = choices.any && master && on;
  return (
    <Row
      name={<span className="pl-4">{label}</span>}
      detail={
        <span className="block pl-4">
          {detail}
          {refused !== null && <span className="mt-0.5 block text-warning">{refused}</span>}
          {ready !== null && <span className="mt-0.5 block text-fg-muted">{ready}</span>}
          {pickable && (
            <span className="block">
              <ChoicesToggle
                open={choices.open}
                onToggle={() => choices.setOpen(!choices.open)}
                picked={choices.list.length - choices.off.length}
                total={choices.list.length}
              />
            </span>
          )}
        </span>
      }
      label={label}
      checked={on}
      disabled={!master}
      onChange={onChange}
      aside={pickable && choices.off.length > 0 ? <ChoicesNotes off={choices.off} label={label} /> : undefined}
    >
      {name === "shell" && <ShellItem status={status} />}
      {name === "local-mcp" && on && master && <McpItem link={status.link} />}
      {name === "skills" && on && <SkillsItem active={open && master} signal={signal} />}
      {name === "wiki" && on && <WikiItem active={open && master} signal={signal} />}
      {pickable && choices.open && <ChoicesList tools={choices.list} label={label} />}
    </Row>
  );
}

/** 子开关开着、电脑也连着，但连接程序没允许这一类时的说明。读文件只要连上就能用。 */
export function refusal(t: Text, name: LocalSwitch, link: LocalLink): string | null {
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
  if (name === "browser") {
    // 旧的连接程序不认浏览器，也不送这一项
    if ((link.protocol ?? 0) < BROWSER_TOOLS || link.browser == null) return text.browserOld;
    if (!link.browser.available) {
      if (link.browser.code === "browser/node-missing") return text.browserNode;
      return link.browser.code === "browser/not-found" ? text.browserNotFound : text.browserNotAllowed;
    }
  }
  if (name === "local-mcp" && (link.protocol ?? 0) < MCP_TOOLS) return text.mcp.old;
  return null;
}

/** 浏览器能用时的一句：哪个浏览器、有没有窗口。 */
function browserReady(t: Text, link: LocalLink): string | null {
  const browser = link.browser;
  if (browser == null || !browser.available) return null;
  return t.plugins.local.browserReady(browser.name ?? "", browser.headless === true);
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
      {link.connectorMatches === false ? (
        <span className="mt-1 block rounded-md bg-warning/10 px-2 py-1.5 text-warning" role="alert">
          {text.mismatch}
        </span>
      ) : (
        link.current === false && <span className="block text-warning">{text.outdated}</span>
      )}
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

/** “命令”一项下面：电脑上是哪种 shell、执行前先问我（内置的命令审批插件）。 */
function ShellItem({ status }: { status: LocalStatus }) {
  const text = useText().plugins.local;
  const toggle = useToggleCommandApproval();
  const shell = status.link?.shell;
  return (
    <div className="flex flex-col gap-1.5 pr-3 pb-2.5 pl-16 text-xs text-fg-muted">
      {shell?.available === true && shell.kind != null && shell.path != null && (
        <span className="font-mono text-[11px] break-all">{text.shellKind(shell.kind, shell.path)}</span>
      )}
      {status.commandApproval !== undefined && (
        <label className="flex items-start gap-2">
          <Switch checked={status.commandApproval} onChange={(enabled) => toggle.mutate(enabled)} label={text.askBefore} />
          <span>
            <span className="block text-fg">{text.askBefore}</span>
            <span className="block">{text.askBeforeHint}</span>
          </span>
        </label>
      )}
    </div>
  );
}

/** “本机 MCP”一项下面：电脑报的每个服务器，状态、工具，各自一个开关（connector-jar 需求 6.7）。 */
function McpItem({ link }: { link: LocalLink | null }) {
  const text = useText().plugins.local;
  const toggle = useToggleLocalMcp();
  if (link === null || (link.protocol ?? 0) < MCP_TOOLS) return null;
  const servers = link.mcp ?? [];
  // 配置文件在电脑上的哪里（N5：只写“mcp.json”，用户不知道去哪里加）；老的连接程序不报，就不写
  const file = link.mcpConfig === undefined ? null : <p className="font-mono text-[11px] break-all text-fg-faint">{text.mcp.file(link.mcpConfig)}</p>;
  if (servers.length === 0)
    return (
      <div className="pr-3 pb-2.5 pl-16 text-xs text-fg-faint">
        <p>{text.mcp.none}</p>
        {file}
      </div>
    );
  return (
    <div className="flex flex-col gap-2 pr-3 pb-2.5 pl-16">
      <ul aria-label={text.switches["local-mcp"][0]} className="flex flex-col gap-2">
        {servers.map((server) => (
          <McpServerRow key={server.name} server={server} onToggle={(enabled) => toggle.mutate({ server: server.name, enabled })} />
        ))}
      </ul>
      {file}
    </div>
  );
}

function McpServerRow({ server, onToggle }: { server: LocalMcpServer; onToggle: (enabled: boolean) => void }) {
  const text = useText().plugins.local;
  const [open, setOpen] = useState(false);
  const failed = server.state === "failed";
  return (
    <li className="flex flex-col gap-1 text-xs">
      <div className="flex flex-wrap items-center gap-2">
        <Switch checked={server.enabled} onChange={onToggle} label={text.mcp.toggle(server.name)} disabled={server.state === "disabled"} />
        <span className="font-mono text-fg">{server.name}</span>
        <span className={cx(failed ? "text-danger" : server.state === "ready" ? "text-fg-muted" : "text-warning")}>{text.mcp.states[server.state]}</span>
        {server.tools.length > 0 && (
          <button type="button" aria-expanded={open} onClick={() => setOpen(!open)} className="text-accent hover:underline">
            {text.mcp.tools(server.tools.length)}
          </button>
        )}
      </div>
      {server.reason != null && server.reason !== "" && (
        <span className={cx("block font-mono text-[11px] break-all whitespace-pre-wrap", failed ? "text-danger" : "text-fg-faint")}>
          {server.reason}
        </span>
      )}
      {open && (
        <ul aria-label={text.mcp.list(server.name)} className="flex flex-col gap-0.5 pl-2">
          {server.tools.map((tool) => (
            <li key={tool.name}>
              <span className="font-mono text-fg">{tool.name}</span>
              {tool.description !== "" && <span className="text-fg-faint"> — {tool.description}</span>}
            </li>
          ))}
        </ul>
      )}
    </li>
  );
}

/**
 * 看页面的这台电脑是不是 Windows：给 PowerShell 的命令写法（connector-jar 需求 3.2）。看 `navigator.platform`（Win32），不看
 * userAgent：自动化浏览器和一些设备模拟会把 userAgent 写成 Windows（Playwright 的 Desktop Chrome 在 macOS 上也是）。
 */
function windows(): boolean {
  return typeof navigator !== "undefined" && /^Win/i.test(navigator.platform ?? "");
}

/** 按系统给出运行连接程序的命令：令牌放在环境变量里，不放在参数里。 */
export function runCommand(token: string, server: string, onWindows: boolean): string {
  return onWindows
    ? `$env:TANTALUM_TOKEN="${token}"; java -jar tantalum-connect.jar --server ${server} --allow .`
    : `TANTALUM_TOKEN=${token} java -jar tantalum-connect.jar --server ${server} --allow .`;
}

/** 选有效期、生成令牌；生成的令牌和命令只在这里显示一次，关掉插件页就没了。 */
function Connect({ status }: { status: LocalStatus }) {
  const text = useText().plugins.local;
  const [validity, setValidity] = useState<LocalValidity>("1h");
  const issue = useIssueToken();
  const revoke = useRevokeLocal();
  const issued = issue.data;
  const connector = status.connector;
  const command = issued === undefined ? "" : issued.server === undefined ? issued.command : runCommand(issued.token, issued.server, windows());
  return (
    <div className="flex flex-col gap-2">
      <ol className="flex list-decimal flex-col gap-1 pl-4 text-xs text-fg-muted">
        <li>
          {connector?.available === false ? (
            <span className="text-warning">{text.steps.missing}</span>
          ) : (
            <>
              <a href="/api/local/connector.jar" download="tantalum-connect.jar" className="text-accent hover:underline">
                {text.steps.download}
              </a>
              {connector?.version !== undefined && <span className="ml-1 font-mono text-[11px] text-fg-faint">{connector.version}</span>}
              <span className="block">{text.steps.needs}</span>
            </>
          )}
        </li>
        <li>{text.steps.token}</li>
        <li>{text.steps.run}</li>
      </ol>
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
            <code className="min-w-0 flex-1 rounded-xs bg-code px-2 py-1 font-mono text-[11px] break-all text-fg">{command}</code>
            <CopyButton text={command} />
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

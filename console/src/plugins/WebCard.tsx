import type { WebPlugins, WebSwitch } from "../api/types";
import { lookup, useText } from "../text";
import { Row, Section } from "./Rows";

const MASTER: WebSwitch = "web";
const CHILDREN: Exclude<WebSwitch, "web">[] = ["web-fetch"];
/** 子开关名旁边灰色写的工具名。 */
const TOOL_NAMES: Record<string, string | undefined> = { "web-fetch": "web_fetch" };

/**
 * 插件页的“联网”（web-tools W10，需求 5.1、5.3）：总开关和“抓网页”子开关，下面一行写内网限制（只管抓网页）。数据跟着 /api/plugins 来，
 * 不轮询。管理员关掉抓网页时开关禁用、写“管理员已关闭”，总开关也禁用。浏览器不在这里：它在用户电脑上，归“本机工作区”（local-browser）。
 */
export function WebCard({ web, onChange }: { web: WebPlugins; onChange: (name: WebSwitch, enabled: boolean) => void }) {
  const text = useText().plugins.web;
  const anyAllowed = CHILDREN.some((name) => web.admin[name]);
  const master = web.switches[MASTER];
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
            <span className="block">
              {text.detail}
              {!anyAllowed && <span className="ml-1 text-fg-faint">{text.adminOff}</span>}
            </span>
            <span className="mt-0.5 block text-fg-faint">{web.blockPrivateNetwork ? text.privateBlocked : text.privateAllowed}</span>
          </>
        }
        label={text.title}
        checked={master && anyAllowed}
        disabled={!anyAllowed}
        onChange={(enabled) => onChange(MASTER, enabled)}
      />
      {CHILDREN.map((name) => {
        const [label, detail] = lookup(text.switches, name) ?? [name, ""];
        const allowed = web.admin[name];
        const tool = TOOL_NAMES[name];
        return (
          <Row
            key={name}
            name={
              <span className="pl-4">
                {label}
                {tool !== undefined && <span className="ml-2 font-mono text-[11px] text-fg-faint">{tool}</span>}
              </span>
            }
            detail={
              <span className="block pl-4">
                {detail}
                {!allowed && <span className="ml-1 text-fg-faint">{text.adminOff}</span>}
              </span>
            }
            label={label}
            checked={web.switches[name] && allowed}
            disabled={!master || !allowed}
            onChange={(enabled) => onChange(name, enabled)}
          />
        );
      })}
    </Section>
  );
}

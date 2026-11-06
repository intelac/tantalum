import { ChevronDown, ChevronRight } from "lucide-react";
import { useState } from "react";
import type { HookPlugin, McpServerView, PluginTool } from "../api/types";
import { zh } from "../text/zh";
import { cx } from "../ui/cx";
import { Dialog } from "../ui/Dialog";
import { LocalWorkspaceCard } from "./LocalWorkspaceCard";
import { Row, Section } from "./Rows";
import { type Toggle, usePlugins, useTogglePlugin } from "./usePlugins";
import { WebCard } from "./WebCard";

interface Props {
  open: boolean;
  onClose: () => void;
}

/** 插件页（core-tools 需求 7）：本机工作区、联网、内置工具、MCP 服务器、钩子插件五块，每项一个开关，只改自己的。 */
export function PluginsDialog({ open, onClose }: Props) {
  const plugins = usePlugins(open);
  const toggle = useTogglePlugin();
  const set = (change: Toggle) => toggle.mutate(change);
  const data = plugins.data;
  return (
    <Dialog open={open} onClose={onClose} title={zh.plugins.title} className="max-w-2xl">
      <div className="flex max-h-[75vh] flex-col gap-5 overflow-y-auto px-4 pt-2 pb-6 sm:px-6">
        {data !== undefined && (
          <p className="text-xs text-fg-faint">{data.midSessionChanges ? zh.plugins.hintNextQuestion : zh.plugins.hintNewSession}</p>
        )}
        {toggle.isError && (
          <p role="alert" className="text-sm text-danger">
            {zh.plugins.saveFailed(toggle.error.message)}
          </p>
        )}
        {plugins.isPending && <p className="text-sm text-fg-faint">{zh.common.loading}</p>}
        {plugins.isError && (
          <p role="alert" className="text-sm text-danger">
            {zh.plugins.loadFailed}
          </p>
        )}
        <LocalWorkspaceCard open={open} />
        {data !== undefined && (
          <>
            <WebCard web={data.web} onChange={(name, enabled) => set({ kind: "web", name, enabled })} />
            <Section title={zh.plugins.builtins}>
              {byUse(data.tools).map((tool) => (
                <ToolRow key={tool.name} tool={tool} onChange={(enabled) => set({ kind: "tool", name: tool.name, enabled })} />
              ))}
            </Section>
            <Section title={zh.plugins.mcp} empty={data.mcp.length === 0 ? zh.plugins.noMcp : undefined}>
              {data.mcp.map((server) => (
                <ServerRow key={server.name} server={server} onChange={set} />
              ))}
            </Section>
            <Section title={zh.plugins.hooks} empty={data.plugins.length === 0 ? zh.plugins.noHooks : undefined}>
              {data.plugins.map((plugin) => (
                <HookRow key={plugin.name} plugin={plugin} onChange={(enabled) => set({ kind: "plugin", name: plugin.name, enabled })} />
              ))}
            </Section>
          </>
        )}
      </div>
    </Dialog>
  );
}

/** 按用途排：中文文案里列出的顺序（读附件，然后记忆），其余按名字跟在后面。 */
function byUse(tools: PluginTool[]): PluginTool[] {
  const order = Object.keys(zh.plugins.tools);
  const rank = (tool: PluginTool) => (order.includes(tool.name) ? order.indexOf(tool.name) : order.length);
  return [...tools].sort((a, b) => rank(a) - rank(b) || a.name.localeCompare(b.name));
}

function toolText(tool: PluginTool): [string, string] {
  const known = zh.plugins.tools[tool.name];
  return known !== undefined ? [known[0], known[1]] : [tool.name, tool.description];
}

function ToolRow({ tool, onChange }: { tool: PluginTool; onChange: (enabled: boolean) => void }) {
  const [label, detail] = toolText(tool);
  return (
    <Row
      name={
        <>
          {label}
          {label !== tool.name && <span className="ml-2 font-mono text-[11px] text-fg-faint">{tool.name}</span>}
        </>
      }
      detail={detail}
      label={label}
      checked={tool.enabled}
      onChange={onChange}
    />
  );
}

function ServerRow({ server, onChange }: { server: McpServerView; onChange: (toggle: Toggle) => void }) {
  const [open, setOpen] = useState(false);
  const state = zh.plugins.states[server.state] ?? server.state;
  const Chevron = open ? ChevronDown : ChevronRight;
  return (
    <Row
      name={
        <span className="flex items-center gap-2">
          <span className="font-mono">{server.name}</span>
          <span className={cx("rounded-xs px-1.5 py-0.5 text-[11px]", server.state === "READY" ? "bg-accent-soft text-fg" : "bg-muted text-fg-muted")}>
            {state}
          </span>
        </span>
      }
      detail={
        <button type="button" className="inline-flex items-center gap-1 hover:text-fg" aria-expanded={open} onClick={() => setOpen(!open)}>
          <Chevron size={12} aria-hidden="true" />
          {open ? zh.plugins.hideTools : zh.plugins.showTools(server.tools.length)}
        </button>
      }
      label={server.name}
      checked={server.enabled}
      onChange={(enabled) => onChange({ kind: "mcp", name: server.name, enabled })}
    >
      {open && (
        <ul className="mx-3 mb-2 flex flex-col divide-y divide-line rounded-md border border-line">
          {server.tools.length === 0 && <li className="px-3 py-2 text-xs text-fg-muted">{zh.plugins.noServerTools}</li>}
          {server.tools.map((tool) => (
            <Row
              key={tool.name}
              name={<span className="font-mono text-xs">{tool.name}</span>}
              detail={tool.description === "" ? undefined : tool.description}
              label={tool.name}
              checked={tool.enabled}
              disabled={!server.enabled}
              onChange={(enabled) => onChange({ kind: "tool", name: tool.name, enabled })}
            />
          ))}
        </ul>
      )}
    </Row>
  );
}

function pointName(point: string): string {
  if (point.startsWith("work:")) return zh.plugins.work(point.slice("work:".length));
  return zh.plugins.pointNames[point] ?? point;
}

function HookRow({ plugin, onChange }: { plugin: HookPlugin; onChange: (enabled: boolean) => void }) {
  return (
    <Row
      name={
        <span className="flex items-center gap-2">
          <span className="font-mono">{plugin.name}</span>
          {plugin.mandatory && (
            <span className="rounded-xs bg-muted px-1.5 py-0.5 text-[11px] text-fg-muted" title={zh.plugins.mandatoryHint}>
              {zh.plugins.mandatory}
            </span>
          )}
        </span>
      }
      detail={
        <>
          {plugin.description !== "" && <span className="block">{plugin.description}</span>}
          <span className="block text-fg-faint">
            {zh.plugins.points}：{plugin.points.map(pointName).join("、")}
          </span>
        </>
      }
      label={plugin.name}
      checked={plugin.enabled}
      disabled={plugin.mandatory}
      onChange={onChange}
    />
  );
}

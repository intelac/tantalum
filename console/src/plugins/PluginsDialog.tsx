import { ChevronDown, ChevronRight } from "lucide-react";
import { useState } from "react";
import type { HookPlugin, McpServerView, ParamView, PluginKind, PluginTool } from "../api/types";
import { lookup, type Text, useLanguage, useText } from "../text";
import { errorLine } from "../text/errors";
import { pick } from "../text/localText";
import { cx } from "../ui/cx";
import { Dialog } from "../ui/Dialog";
import { Tabs } from "../ui/Tabs";
import { DefaultsPane, type Focus } from "./DefaultsPane";
import { anyLocked, splitLocked } from "./groups";
import { LocalWorkspaceCard } from "./LocalWorkspaceCard";
import { byUse, toolText } from "./names";
import { ParamForm } from "./ParamForm";
import { Group, Row, Section, SwitchNotes } from "./Rows";
import { type Toggle, useOwnParams, usePlugins, useResetPlugin, useTogglePlugin } from "./usePlugins";
import { useUnsavedState } from "./unsaved";
import { WebCard } from "./WebCard";
import { Button } from "../ui/Button";

interface Props {
  open: boolean;
  onClose: () => void;
  /** 管理员多一个“插件默认值”页签，锁定的行上多一个“去插件默认值改”。 */
  admin?: boolean;
}

/** 一行要用的动作：拨开关、恢复默认、（管理员）跳到默认值里的这一项。 */
interface Actions {
  set: (change: Toggle) => void;
  reset: (kind: PluginKind, name: string) => void;
  toDefaults?: (kind: PluginKind, name: string) => void;
}

/**
 * 插件页（core-tools 需求 7，plugin-settings 需求 6，qagent-align 需求 1）：规则、工具、工具输出处理三组，每项一个开关，
 * 只改自己的；锁住的另成一组“只有管理员能改”；
 * 有参数的在行下面带一个自己保存的表单。管理员另有“插件默认值”页签。
 */
export function PluginsDialog({ open, onClose, admin = false }: Props) {
  const t = useText();
  const plugins = usePlugins(open);
  const toggle = useTogglePlugin();
  const reset = useResetPlugin();
  const [tab, setTab] = useState<"mine" | "defaults">("mine");
  const [focus, setFocus] = useState<Focus | null>(null);
  // 有没保存的修改时，关对话框、切页签先问（QAgent 问题 17）；pending 是被拦下的那个动作
  const unsaved = useUnsavedState();
  const [pending, setPending] = useState<(() => void) | null>(null);
  const leave = (action: () => void) => {
    if (unsaved.dirty) setPending(() => action);
    else action();
  };
  const data = plugins.data;
  const actions: Actions = {
    set: (change) => toggle.mutate(change),
    reset: (kind, name) => reset.mutate({ kind, name }),
    toDefaults: admin
      ? (kind, name) => {
          leave(() => {
            setFocus({ kind, name });
            setTab("defaults");
          });
        }
      : undefined,
  };
  const failed = toggle.isError ? toggle.error : reset.isError ? reset.error : null;
  const split = splitLocked(data ?? { tools: [], mcp: [], plugins: [] });
  return unsaved.provider(
    <Dialog open={open} onClose={() => leave(onClose)} title={t.plugins.title} className="max-w-[960px]">
      {pending !== null && (
        <div role="alert" className="flex flex-wrap items-center gap-2 border-b border-line bg-danger-soft px-4 py-2 text-sm sm:px-6">
          <span className="text-fg">{t.plugins.leave.question}</span>
          <Button
            size="sm"
            variant="danger"
            onClick={() => {
              const action = pending;
              setPending(null);
              action();
            }}
          >
            {t.plugins.leave.discard}
          </Button>
          <Button size="sm" onClick={() => setPending(null)}>
            {t.plugins.leave.keep}
          </Button>
        </div>
      )}
      {admin && (
        <Tabs
          className="border-b border-line px-4 sm:px-6"
          label={t.plugins.tabs.label}
          tabs={[
            { id: "mine", label: t.plugins.tabs.mine },
            { id: "defaults", label: t.plugins.tabs.defaults },
          ]}
          selected={tab}
          onSelect={(id) => leave(() => setTab(id === "defaults" ? "defaults" : "mine"))}
        />
      )}
      <div className="flex max-h-[calc(100dvh-9rem)] min-h-[50dvh] flex-col gap-6 overflow-y-auto px-4 pt-3 pb-6 sm:px-6">
        {tab === "defaults" && admin ? (
          <DefaultsPane focus={focus} />
        ) : (
          <>
            {data !== undefined && (
              <p className="text-xs text-fg-faint">
                {data.midSessionChanges ? t.plugins.hintNextQuestion : t.plugins.hintNewSession} {admin && t.plugins.hintAdmin} {t.plugins.hintParams}
              </p>
            )}
            {failed !== null && (
              <p role="alert" className="text-sm text-danger">
                {t.plugins.saveFailed(errorLine(failed))}
              </p>
            )}
            {plugins.isPending && <p className="text-sm text-fg-faint">{t.common.loading}</p>}
            {plugins.isError && (
              <p role="alert" className="text-sm text-danger">
                {t.plugins.loadFailed}
              </p>
            )}
            {split.rule.length > 0 && (
              <Group title={t.plugins.groups.rule}>
                <HookList label={t.plugins.groups.rule} plugins={split.rule} actions={actions} />
              </Group>
            )}
            <Group title={t.plugins.groups.tool}>
              <LocalWorkspaceCard open={open} />
              {data !== undefined && (
                <>
                  <WebCard web={data.web} onChange={(name, enabled) => actions.set({ kind: "web", name, enabled })} />
                  {split.tools.length > 0 && (
                    <Section title={t.plugins.builtins}>
                      {byUse(split.tools, t.plugins).map((tool) => (
                        <ToolRow key={tool.name} tool={tool} actions={actions} />
                      ))}
                    </Section>
                  )}
                  {(split.servers.length > 0 || data.mcp.length === 0) && (
                    <Section title={t.plugins.mcp} empty={data.mcp.length === 0 ? t.plugins.noMcp : undefined}>
                      {split.servers.map((server) => (
                        <ServerRow key={server.name} server={server} actions={actions} />
                      ))}
                    </Section>
                  )}
                </>
              )}
            </Group>
            {split.output.length > 0 && (
              <Group title={t.plugins.groups.output}>
                <HookList label={t.plugins.groups.output} plugins={split.output} actions={actions} />
              </Group>
            )}
            {anyLocked(split) && (
              <Group title={t.plugins.groups.locked}>
                <Section label={t.plugins.groups.locked}>
                  {split.locked.rule.map((plugin) => (
                    <HookRow key={plugin.name} plugin={plugin} actions={actions} />
                  ))}
                  {byUse(split.locked.tools, t.plugins).map((tool) => (
                    <ToolRow key={tool.name} tool={tool} actions={actions} />
                  ))}
                  {split.locked.servers.map((server) => (
                    <ServerRow key={server.name} server={server} actions={actions} />
                  ))}
                  {split.locked.output.map((plugin) => (
                    <HookRow key={plugin.name} plugin={plugin} actions={actions} />
                  ))}
                </Section>
              </Group>
            )}
          </>
        )}
      </div>
    </Dialog>,
  );
}

/** 开着、又有参数的那一行下面的表单；关着时不显示（参数要打开才用得上）。 */
function OwnParams({ kind, name, label, params }: { kind: PluginKind; name: string; label: string; params: readonly ParamView[] | undefined }) {
  const own = useOwnParams(kind, name);
  if (params === undefined || params.length === 0) return null;
  return (
    <ParamForm
      className="pr-3 pb-3 pl-12 max-sm:pl-3"
      name={label}
      params={params}
      mode="own"
      onSave={own.save}
      onReset={own.reset}
      onClearSecret={own.clearSecret}
    />
  );
}

function ToolRow({ tool, actions }: { tool: PluginTool; actions: Actions }) {
  const t = useText();
  const [label, detail] = toolText(tool.name, t.plugins);
  const toDefaults = actions.toDefaults;
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
      disabled={tool.lock !== undefined}
      onChange={(enabled) => actions.set({ kind: "tool", name: tool.name, enabled })}
      aside={
        <SwitchNotes
          state={tool}
          label={label}
          onDefaults={toDefaults && (() => toDefaults("tool", tool.name))}
          onReset={() => actions.reset("tool", tool.name)}
        />
      }
    >
      {tool.enabled && <OwnParams kind="tool" name={tool.name} label={label} params={tool.params} />}
    </Row>
  );
}

function ServerRow({ server, actions }: { server: McpServerView; actions: Actions }) {
  const t = useText();
  const [open, setOpen] = useState(false);
  const state = lookup(t.plugins.states, server.state) ?? server.state;
  const Chevron = open ? ChevronDown : ChevronRight;
  const toDefaults = actions.toDefaults;
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
          {open ? t.plugins.hideTools : t.plugins.showTools(server.tools.length)}
        </button>
      }
      label={server.name}
      checked={server.enabled}
      disabled={server.lock !== undefined}
      onChange={(enabled) => actions.set({ kind: "mcp", name: server.name, enabled })}
      aside={
        <SwitchNotes
          state={server}
          label={server.name}
          onDefaults={toDefaults && (() => toDefaults("mcp", server.name))}
          onReset={() => actions.reset("mcp", server.name)}
        />
      }
    >
      {open && (
        <ul className="mx-3 mb-2 flex flex-col divide-y divide-line rounded-md border border-line">
          {server.tools.length === 0 && <li className="px-3 py-2 text-xs text-fg-muted">{t.plugins.noServerTools}</li>}
          {server.tools.map((tool) => (
            <Row
              key={tool.name}
              name={<span className="font-mono text-xs break-all">{tool.name}</span>}
              detail={tool.description === "" ? undefined : tool.description}
              label={tool.name}
              checked={tool.enabled}
              disabled={!server.enabled || tool.lock !== undefined}
              onChange={(enabled) => actions.set({ kind: "tool", name: tool.name, enabled })}
              aside={
                <SwitchNotes
                  state={tool}
                  label={tool.name}
                  onDefaults={toDefaults && (() => toDefaults("tool", tool.name))}
                  onReset={() => actions.reset("tool", tool.name)}
                />
              }
            />
          ))}
        </ul>
      )}
    </Row>
  );
}

function pointName(point: string, text: Text["plugins"]): string {
  if (point.startsWith("work:")) return text.work(point.slice("work:".length));
  return lookup(text.pointNames, point) ?? point;
}

function HookList({ label, plugins, actions }: { label: string; plugins: readonly HookPlugin[]; actions: Actions }) {
  return (
    <Section label={label}>
      {plugins.map((plugin) => (
        <HookRow key={plugin.name} plugin={plugin} actions={actions} />
      ))}
    </Section>
  );
}

function HookRow({ plugin, actions }: { plugin: HookPlugin; actions: Actions }) {
  const t = useText();
  const language = useLanguage();
  // 插件作者写的说明：按看的人的语言选，没有就用英文（console-i18n I10）
  const description = pick(plugin.description, language);
  const toDefaults = actions.toDefaults;
  return (
    <Row
      name={<span className="font-mono">{plugin.name}</span>}
      detail={
        <>
          {description !== "" && <span className="block">{description}</span>}
          <span className="block text-fg-faint">{t.plugins.pointsList(plugin.points.map((point) => pointName(point, t.plugins)))}</span>
        </>
      }
      label={plugin.name}
      checked={plugin.enabled}
      disabled={plugin.lock !== undefined}
      onChange={(enabled) => actions.set({ kind: "plugin", name: plugin.name, enabled })}
      aside={
        <SwitchNotes
          state={plugin}
          label={plugin.name}
          onDefaults={toDefaults && (() => toDefaults("plugin", plugin.name))}
          onReset={() => actions.reset("plugin", plugin.name)}
        />
      }
    >
      {plugin.enabled && <OwnParams kind="plugin" name={plugin.name} label={plugin.name} params={plugin.params} />}
    </Row>
  );
}

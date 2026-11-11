import type { ToolStepItem } from "../../api/types";
import { useText } from "../../text";
import { CopyButton } from "../../ui/CopyButton";
import { parseBash } from "./bashResult";

function args(step: ToolStepItem): Record<string, unknown> {
  const value = step.call.arguments;
  return value !== null && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
}

/** bash 这样的工具能不能画成终端卡片：要有命令，结果不能是合成的或被拦下的（那些文字不是命令的输出）。 */
export function hasTerminalView(step: ToolStepItem): boolean {
  if (step.call.name !== "bash" || typeof args(step).command !== "string") return false;
  if (step.status === "BLOCKED" || step.status === "WAITING") return false;
  return step.result?.code === undefined;
}

/** 折叠行上的退出码：只有失败且认得出退出码时才有。 */
export function exitCodeOf(step: ToolStepItem): number | undefined {
  if (!hasTerminalView(step) || step.result === undefined || !step.result.error) return undefined;
  return parseBash(step.result.text, true).exitCode;
}

/** 终端卡片：命令、在哪跑、输出、退出码。输出区最高 224px，自己滚动。 */
export function TerminalCard({ step }: { step: ToolStepItem }) {
  const t = useText();
  const { command, workdir, timeout } = args(step);
  const result = step.result === undefined ? undefined : parseBash(step.result.text, step.result.error);
  const failed = step.result?.error === true;
  const notes: string[] = [];
  if (typeof workdir === "string" && workdir !== "") notes.push(t.process.workdir(workdir));
  if (typeof timeout === "number") notes.push(t.process.timeLimit(timeout));

  return (
    <section aria-label={t.process.terminal} className="overflow-hidden rounded-sm border border-line bg-code font-mono text-xs">
      <div className="flex items-start gap-2 border-b border-line px-2 py-1.5">
        <pre className="min-w-0 flex-1 whitespace-pre-wrap break-words text-fg">
          {String(command)
            .split("\n")
            .map((line, index) => (
              <span key={index} className="block">
                <span className="select-none text-fg-faint">{index === 0 ? "$ " : "  "}</span>
                {line}
              </span>
            ))}
        </pre>
        {result !== undefined && !result.empty && <CopyButton text={result.output} className="shrink-0" />}
      </div>
      {notes.length > 0 && <p className="border-b border-line px-2 py-1 font-sans text-[11px] text-fg-faint">{notes.join(" · ")}</p>}
      {result === undefined ? (
        <p className="px-2 py-1.5 font-sans text-fg-faint">{t.process.toolRunning}…</p>
      ) : (
        <>
          {result.truncated !== undefined && (
            <p className="border-b border-line px-2 py-1 font-sans text-[11px] text-fg-faint">{t.process.lastLines(result.truncated.shown, result.truncated.total)}</p>
          )}
          {result.empty ? (
            <p className="px-2 py-1.5 font-sans text-fg-faint">{t.process.noOutput}</p>
          ) : (
            <pre data-testid="terminal-output" className="max-h-56 overflow-auto px-2 py-1.5 whitespace-pre-wrap break-words text-fg">
              {result.output}
            </pre>
          )}
          {failed && (
            <p className="flex flex-wrap gap-2 border-t border-line px-2 py-1 font-sans text-[11px]">
              {result.exitCode !== undefined && <span className="rounded-xs bg-danger-soft px-1.5 py-0.5 text-danger">{t.process.exitCode(result.exitCode)}</span>}
              {result.timedOut !== undefined && <span className="rounded-xs bg-danger-soft px-1.5 py-0.5 text-danger">{t.process.timedOut(result.timedOut)}</span>}
              {failed && result.exitCode === undefined && result.timedOut === undefined && (
                <span className="rounded-xs bg-danger-soft px-1.5 py-0.5 text-danger">{t.process.toolFailed}</span>
              )}
            </p>
          )}
        </>
      )}
    </section>
  );
}

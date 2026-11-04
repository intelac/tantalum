import type { Config } from "../api/types";
import { tokenCount } from "../composer/status";
import { zh } from "../text/zh";
import { CompactionPicker } from "./CompactionPicker";

interface Props {
  config: Config | undefined;
}

/** 供应商、模型名、是否外发到云端、上下文窗口和它从哪来（只读），以及这个用户的自动压缩点。没有任何输入 Key 的地方。 */
export function ModelPane({ config }: Props) {
  if (config === undefined) return <p className="text-sm text-fg-faint">{zh.common.loading}</p>;
  return (
    <div className="flex flex-col gap-3">
      <h3 className="text-sm font-medium text-fg">{zh.settings.model}</h3>
      <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 text-sm">
        <dt className="text-fg-muted">{zh.settings.provider}</dt>
        <dd className="font-mono text-fg">{config.provider}</dd>
        <dt className="text-fg-muted">{zh.settings.modelName}</dt>
        <dd className="font-mono break-all text-fg">{config.model}</dd>
        {config.contextWindow !== undefined && (
          <>
            <dt className="text-fg-muted">{zh.settings.contextWindow}</dt>
            <dd className="font-mono text-fg">{tokenCount(config.contextWindow)}</dd>
            {config.windowSource !== undefined && (
              <>
                <dt className="text-fg-muted">{zh.settings.windowSource}</dt>
                <dd className="text-fg">{config.windowSource === "model" ? zh.settings.windowFromModel : zh.settings.windowFromConfig}</dd>
              </>
            )}
          </>
        )}
      </dl>
      <CompactionPicker />
      <p className={config.cloud ? "rounded-md bg-warning/15 px-3 py-2 text-sm text-fg" : "rounded-md bg-muted px-3 py-2 text-sm text-fg-muted"}>
        {config.cloud ? zh.settings.cloudNotice : zh.settings.localNotice}
      </p>
      <p className="text-xs text-fg-faint">{zh.settings.keyNotice}</p>
    </div>
  );
}

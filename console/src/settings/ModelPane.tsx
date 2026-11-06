import type { Config, ModelChoice } from "../api/types";
import { tokenCount } from "../composer/status";
import { findModel, useModels } from "../composer/useModels";
import { useText } from "../text";
import { cx } from "../ui/cx";
import { CompactionPicker } from "./CompactionPicker";

interface Props {
  config: Config | undefined;
  /** 当前会话选的模型：压缩点的 token 数按它算（model-picker 需求 8.2）。 */
  choice?: ModelChoice | null;
}

/**
 * 能选的模型按路线列出：名字、窗口和它从哪来、收不收图片（model-picker 需求 8.1），下面是这个用户的自动压缩点。
 * 模型列表还没读到时照旧显示默认模型。没有任何输入 Key 的地方。
 */
export function ModelPane({ config, choice = null }: Props) {
  const t = useText();
  const models = useModels();
  if (config === undefined) return <p className="text-sm text-fg-faint">{t.common.loading}</p>;
  const current = findModel(models.data, choice);
  const cloud = models.data?.routes.some((route) => route.cloud) ?? config.cloud;
  return (
    <div className="flex flex-col gap-3">
      <h3 className="text-sm font-medium text-fg">{t.settings.model}</h3>
      {models.data === undefined ? (
        <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 text-sm">
          <dt className="text-fg-muted">{t.settings.provider}</dt>
          <dd className="font-mono text-fg">{config.provider}</dd>
          <dt className="text-fg-muted">{t.settings.modelName}</dt>
          <dd className="font-mono break-all text-fg">{config.model}</dd>
          {config.contextWindow !== undefined && (
            <>
              <dt className="text-fg-muted">{t.settings.contextWindow}</dt>
              <dd className="font-mono text-fg">{tokenCount(config.contextWindow)}</dd>
            </>
          )}
        </dl>
      ) : (
        <div className="flex flex-col gap-3">
          <h4 className="text-sm text-fg-muted">{t.settings.models}</h4>
          {models.data.routes.map((route) => (
            <section key={route.name} aria-label={route.displayName} className="flex flex-col gap-1">
              <p className={cx("text-xs", route.status === "unavailable" ? "text-fg-faint" : "text-fg-muted")}>
                {route.displayName}
                <span className="ml-1">{route.cloud ? t.composer.cloud : t.composer.local}</span>
                {route.status === "unavailable" && <span className="ml-1">{t.settings.routeUnavailable(route.error ?? "")}</span>}
              </p>
              <ul className="flex flex-col gap-0.5 text-sm">
                {route.models.map((model) => (
                  <li key={model.id} className="flex flex-wrap items-baseline gap-x-3">
                    <span className="text-fg">{model.name}</span>
                    <span className="font-mono text-xs text-fg-muted">
                      {model.window > 0 ? tokenCount(model.window) : "—"}
                    </span>
                    {model.window > 0 && (
                      <span className="text-xs text-fg-faint">
                        {model.windowSource === "model" ? t.settings.windowFromModel : t.settings.windowFromConfig}
                      </span>
                    )}
                    <span className="text-xs text-fg-faint">{model.vision ? t.settings.imagesYes : t.settings.imagesNo}</span>
                  </li>
                ))}
              </ul>
            </section>
          ))}
          <p className="text-xs text-fg-faint">{t.settings.modelsHint}</p>
        </div>
      )}
      <CompactionPicker points={current?.points} />
      {current !== undefined && <p className="text-xs text-fg-faint">{t.settings.compactionFor(current.name)}</p>}
      <p className={cloud ? "rounded-md bg-warning/15 px-3 py-2 text-sm text-fg" : "rounded-md bg-muted px-3 py-2 text-sm text-fg-muted"}>
        {cloud ? t.settings.cloudNotice : t.settings.localNotice}
      </p>
      <p className="text-xs text-fg-faint">{t.settings.keyNotice}</p>
    </div>
  );
}

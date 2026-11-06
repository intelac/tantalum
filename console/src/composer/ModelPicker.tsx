import { Check, ChevronDown } from "lucide-react";
import type { KeyboardEvent } from "react";
import { useEffect, useId, useMemo, useRef, useState } from "react";
import type { ModelChoice, ModelInfo, ModelsResponse } from "../api/types";
import { zh } from "../text/zh";
import { cx } from "../ui/cx";
import { findModel, levelFor } from "./useModels";

interface Props {
  models: ModelsResponse | undefined;
  /** 这个会话现在显示的选择（没发出的、会话的、上次的或默认的）。 */
  choice: ModelChoice | null;
  /** 选的和这个会话已经生效的不一样：按钮旁写“下一次提问生效”（M8）。 */
  pending: boolean;
  /** 这个会话中途改过工具：不支持会话中途工具变化的模型灰掉（M11）。 */
  toolChangesInHistory: boolean;
  onSelect: (choice: ModelChoice) => void;
}

/** 模型多于这么多个时上面有搜索框（DSH 是多于 4 个，`ModelSelect.tsx:115`）。 */
const SEARCH_FROM = 8;

/** 按钮上的字：“模型显示名 · 档位”；没有档位只写模型名（需求 3.1，照 DSH `ModelSelect.tsx:395-407`）。 */
export function choiceLabel(models: ModelsResponse | undefined, choice: ModelChoice | null): string {
  if (choice === null) return zh.composer.model;
  const name = findModel(models, choice)?.name ?? choice.model;
  return choice.effort == null ? name : `${name} · ${zh.composer.picker.level(choice.effort)}`;
}

/**
 * 输入框旁的模型和思考档位选择器（model-picker 任务 14）。点开是两栏：左边按路线分组的模型，右边是选中模型的档位，
 * 没有档位不显示右栏（需求 3.3）。“暂时连不上”的路线是灰色的一组；灰掉的模型鼠标移上去写原因。运行中照样能改。
 */
export function ModelPicker({ models, choice, pending, toolChangesInHistory, onSelect }: Props) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const root = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement>(null);
  const panelId = useId();
  const selected = findModel(models, choice);
  const total = models?.routes.reduce((sum, route) => sum + route.models.length, 0) ?? 0;

  useEffect(() => {
    if (!open) return;
    function onPointerDown(event: PointerEvent): void {
      if (root.current !== null && !root.current.contains(event.target as Node)) setOpen(false);
    }
    document.addEventListener("pointerdown", onPointerDown);
    return () => document.removeEventListener("pointerdown", onPointerDown);
  }, [open]);

  const routes = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return (models?.routes ?? []).map((route) => ({
      route,
      models: needle === "" ? route.models : route.models.filter((model) => `${model.name} ${model.id}`.toLowerCase().includes(needle)),
    }));
  }, [models, query]);

  function close(): void {
    setOpen(false);
    trigger.current?.focus();
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>): void {
    if (event.key === "Escape") {
      event.preventDefault();
      close();
    }
  }

  function pickModel(route: string, model: ModelInfo): void {
    onSelect({ route, model: model.id, effort: levelFor(model, choice?.effort) });
    // 没有档位就选完了；有档位的留着让用户接着选档位
    if (model.levels.length === 0) close();
  }

  const blocked = (model: ModelInfo) => toolChangesInHistory && !model.toolChanges;

  return (
    <div ref={root} className="relative flex min-w-0 items-center gap-1.5">
      {pending && <span className="hidden text-xs text-fg-faint sm:inline">{zh.composer.picker.nextTurn}</span>}
      <button
        ref={trigger}
        type="button"
        aria-haspopup="dialog"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        aria-label={`${zh.composer.picker.label}：${choiceLabel(models, choice)}`}
        title={pending ? zh.composer.picker.nextTurn : undefined}
        onClick={() => setOpen((current) => !current)}
        className="flex max-w-64 items-center gap-1 truncate rounded-sm px-1.5 py-0.5 text-xs text-fg-muted hover:bg-hover hover:text-fg"
      >
        <span className="truncate">{choiceLabel(models, choice)}</span>
        <ChevronDown size={12} aria-hidden="true" className="shrink-0" />
      </button>
      {open && (
        <div
          id={panelId}
          role="dialog"
          aria-label={zh.composer.picker.label}
          onKeyDown={onKeyDown}
          className="absolute right-0 bottom-full z-40 mb-2 flex max-h-96 w-[min(32rem,calc(100vw-2rem))] rounded-md border border-line bg-raised shadow-lg"
        >
          <div className="flex min-w-0 flex-1 flex-col overflow-y-auto p-1">
            {total > SEARCH_FROM && (
              <input
                type="search"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder={zh.composer.picker.search}
                aria-label={zh.composer.picker.search}
                className="mb-1 rounded-sm border border-line bg-transparent px-2 py-1 text-sm text-fg placeholder:text-fg-faint"
              />
            )}
            {routes.map(({ route, models: shown }) => (
              <div key={route.name} role="group" aria-label={route.displayName} className="py-1">
                <p className={cx("px-2 py-1 text-xs", route.status === "unavailable" ? "text-fg-faint" : "text-fg-muted")}>
                  {route.displayName}
                  {route.status === "unavailable" && <span className="ml-1">{zh.composer.picker.unavailable(route.error ?? "")}</span>}
                </p>
                {shown.map((model) => {
                  const active = choice?.route === route.name && choice.model === model.id;
                  const disabled = blocked(model);
                  return (
                    <button
                      key={model.id}
                      type="button"
                      aria-pressed={active}
                      disabled={disabled}
                      title={disabled ? zh.composer.picker.toolChangesUnsupported : model.id}
                      onClick={() => pickModel(route.name, model)}
                      className={cx(
                        "flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left text-sm",
                        disabled ? "cursor-not-allowed text-fg-faint" : "text-fg hover:bg-hover",
                        active && "bg-active",
                      )}
                    >
                      <span className="min-w-0 flex-1 truncate">{model.name}</span>
                      {active && <Check size={14} aria-hidden="true" />}
                    </button>
                  );
                })}
              </div>
            ))}
            {routes.every(({ models: shown }) => shown.length === 0) && query !== "" && (
              <p className="px-2 py-1 text-sm text-fg-faint">{zh.composer.picker.empty}</p>
            )}
          </div>
          {selected !== undefined && selected.levels.length > 0 && choice !== null && (
            <div role="group" aria-label={zh.composer.picker.levels} className="w-28 shrink-0 border-l border-line p-1">
              <p className="px-2 py-1 text-xs text-fg-muted">{zh.composer.picker.levels}</p>
              {selected.levels.map((level) => (
                <button
                  key={level}
                  type="button"
                  aria-pressed={choice.effort === level}
                  onClick={() => {
                    onSelect({ ...choice, effort: level });
                    close();
                  }}
                  className={cx("flex w-full rounded-sm px-2 py-1.5 text-left text-sm text-fg hover:bg-hover", choice.effort === level && "bg-active")}
                >
                  {zh.composer.picker.level(level)}
                </button>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

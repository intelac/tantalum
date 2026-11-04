import { tokenCount } from "../composer/status";
import { zh } from "../text/zh";
import { cx } from "../ui/cx";
import { useSetCompaction, useSettings } from "./useCompaction";

/**
 * 自动压缩点：四个选项，每项写出对应的 token 数（compaction-threshold T8）。选了立即保存，失败时退回原来的选择。
 * 窗口未知时不可选。
 */
export function CompactionPicker() {
  const settings = useSettings();
  const save = useSetCompaction();
  if (settings.data === undefined) return <p className="text-sm text-fg-faint">{zh.common.loading}</p>;
  const { percent, points } = settings.data.compaction;
  return (
    <div className="flex flex-col gap-2">
      <h4 id="compaction-label" className="text-sm text-fg-muted">
        {zh.settings.compaction}
      </h4>
      {points.length === 0 ? (
        <p className="text-sm text-fg-faint">{zh.settings.compactionUnknown}</p>
      ) : (
        <div role="radiogroup" aria-labelledby="compaction-label" className="grid grid-cols-2 gap-2 sm:grid-cols-4">
          {points.map((point) => {
            const selected = point.percent === percent;
            const label = point.percent === 100 ? zh.settings.compactionFull : `${point.percent}%`;
            return (
              <button
                key={point.percent}
                type="button"
                role="radio"
                aria-checked={selected}
                aria-label={label}
                onClick={() => save.mutate(point.percent)}
                className={cx(
                  "flex flex-col items-center justify-center gap-0.5 rounded-md border px-2 py-2 text-sm transition-colors duration-[var(--duration-fast)]",
                  selected ? "border-fg bg-active text-fg" : "border-line bg-raised text-fg-muted hover:bg-hover",
                )}
              >
                <span>{label}</span>
                <span className="font-mono text-xs text-fg-faint">{tokenCount(point.tokens)}</span>
              </button>
            );
          })}
        </div>
      )}
      {save.isError ? (
        <p role="alert" className="text-xs text-danger">
          {zh.settings.compactionFailed}
        </p>
      ) : (
        points.length > 0 && <p className="text-xs text-fg-faint">{zh.settings.compactionHint}</p>
      )}
    </div>
  );
}

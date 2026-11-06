import { Layers } from "lucide-react";
import type { ToolStepItem } from "../api/types";
import { useText } from "../text";
import { ToolRow } from "./ToolRow";

interface Props {
  steps: readonly ToolStepItem[];
}

/** 同一批并行调用显示为一组；只有一条时就是普通的一行。 */
export function ToolGroup({ steps }: Props) {
  const t = useText();
  const first = steps[0];
  if (first === undefined) return null;
  if (steps.length === 1) return <ToolRow step={first} />;
  return (
    <div className="my-1 rounded-sm border border-line pl-2" role="group" aria-label={t.process.parallel(steps.length)}>
      <div className="flex items-center gap-1.5 py-1 text-xs text-fg-faint">
        <Layers size={13} aria-hidden="true" />
        {t.process.parallel(steps.length)}
      </div>
      {steps.map((step) => (
        <ToolRow key={step.id} step={step} />
      ))}
    </div>
  );
}

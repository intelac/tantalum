import { type FormEvent, useEffect, useState } from "react";
import { useText } from "../text";
import { errorLine } from "../text/errors";
import { Button } from "../ui/Button";
import { Dialog } from "../ui/Dialog";

interface Props {
  open: boolean;
  title: string;
  hint?: string;
  initial: string;
  /** 会话可以留空（恢复自动标题），工作区不行。 */
  allowEmpty: boolean;
  onSave: (name: string) => Promise<unknown>;
  onClose: () => void;
}

/** 改名框：会话和工作区共用。保存失败时留着框，写明原因。 */
export function RenameDialog({ open, title, hint, initial, allowEmpty, onSave, onClose }: Props) {
  const t = useText();
  const [name, setName] = useState(initial);
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;
    setName(initial);
    setError(null);
  }, [open, initial]);

  async function save(event: FormEvent): Promise<void> {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await onSave(name.trim());
      onClose();
    } catch (failure) {
      setError(failure);
    } finally {
      setBusy(false);
    }
  }

  const canSave = !busy && (allowEmpty || name.trim() !== "");
  return (
    <Dialog open={open} onClose={onClose} title={title} className="max-w-md">
      <form className="flex flex-col gap-3 px-4 pt-2 pb-6 sm:px-6" onSubmit={(event) => void save(event)}>
        <label className="flex flex-col gap-1 text-xs text-fg-muted">
          {t.projects.name}
          <input
            className="focus-plain w-full rounded-md border border-line bg-raised px-3 py-1.5 text-sm text-fg focus:border-line-strong"
            value={name}
            maxLength={80}
            onChange={(event) => setName(event.target.value)}
          />
        </label>
        {hint !== undefined && <p className="text-xs text-fg-faint">{hint}</p>}
        {error !== null && (
          <p role="alert" className="text-xs text-danger">
            {errorLine(error)}
          </p>
        )}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            {t.common.cancel}
          </Button>
          <Button variant="primary" type="submit" disabled={!canSave}>
            {t.projects.save}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

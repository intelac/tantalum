import { cx } from "./cx";

interface Props {
  checked: boolean;
  onChange: (checked: boolean) => void;
  /** 读屏用的名字，开关旁边的文字通常就是它。 */
  label: string;
  disabled?: boolean;
}

/** 开关：role="switch"，空格和回车都能切换（按钮自带）。 */
export function Switch({ checked, onChange, label, disabled = false }: Props) {
  return (
    <button
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={cx(
        "relative inline-flex h-5 w-9 shrink-0 items-center rounded-full transition-colors focus-visible:outline-2 focus-visible:outline-focus",
        checked ? "bg-primary" : "bg-line-strong",
        disabled ? "cursor-not-allowed opacity-50" : "cursor-pointer",
      )}
    >
      <span
        aria-hidden="true"
        className={cx("inline-block size-4 rounded-full bg-raised shadow-sm transition-transform", checked ? "translate-x-4.5" : "translate-x-0.5")}
      />
    </button>
  );
}

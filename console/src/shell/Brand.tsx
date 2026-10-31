import { zh } from "../text/zh";
import { cx } from "../ui/cx";

interface Props {
  /** 只显示方格，不显示字样；收起的侧栏和登录页用。 */
  symbolOnly?: boolean;
  size?: "sm" | "md" | "lg";
  className?: string;
}

const SQUARE: Record<NonNullable<Props["size"]>, string> = {
  sm: "h-7 w-7 text-[13px] rounded-xs",
  md: "h-8 w-8 text-sm rounded-sm",
  lg: "h-14 w-14 text-2xl rounded-md",
};

const NUMBER: Record<NonNullable<Props["size"]>, string> = {
  sm: "hidden",
  md: "text-[7px] left-1 top-0.5",
  lg: "text-[10px] left-1.5 top-1",
};

const NAME: Record<NonNullable<Props["size"]>, string> = {
  sm: "text-sm",
  md: "text-[17px]",
  lg: "text-2xl",
};

/** 原创标志：元素周期表样式的 “Ta” 方格（钽，73 号），配 “Tantalum” 字样。深浅色都用前景色，不用品牌色。 */
export function Brand({ symbolOnly = false, size = "md", className }: Props) {
  return (
    <span className={cx("inline-flex items-center gap-2 text-fg", className)} aria-label={zh.app.name}>
      <span
        aria-hidden="true"
        className={cx(
          "relative inline-flex shrink-0 items-center justify-center border-2 border-current font-semibold leading-none tracking-tight",
          SQUARE[size],
        )}
      >
        <span className={cx("absolute font-medium leading-none opacity-70", NUMBER[size])}>73</span>
        {zh.app.symbol}
      </span>
      {!symbolOnly && <span className={cx("font-semibold tracking-tight", NAME[size])}>{zh.app.name}</span>}
    </span>
  );
}

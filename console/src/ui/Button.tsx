import type { ComponentProps } from "react";
import { cx } from "./cx";

export type ButtonVariant = "primary" | "secondary" | "ghost" | "danger" | "icon";
export type ButtonSize = "sm" | "md" | "lg";

/** React 19 里 ref 是普通属性，所以这里也带上。 */
export interface ButtonProps extends ComponentProps<"button"> {
  variant?: ButtonVariant;
  size?: ButtonSize;
}

const BASE =
  "inline-flex items-center justify-center gap-1.5 select-none whitespace-nowrap font-medium " +
  "transition-colors duration-[var(--duration-fast)] disabled:cursor-not-allowed disabled:opacity-50";

const VARIANTS: Record<ButtonVariant, string> = {
  primary: "rounded-md bg-primary text-primary-fg hover:opacity-90",
  secondary: "rounded-md border border-line bg-raised text-fg hover:bg-hover",
  ghost: "rounded-md text-fg hover:bg-hover",
  danger: "rounded-md border border-danger/40 bg-raised text-danger hover:bg-danger-soft",
  icon: "rounded-full text-fg-muted hover:bg-hover hover:text-fg",
};

const SIZES: Record<ButtonSize, string> = {
  sm: "h-7 px-2 text-xs",
  md: "h-9 px-3 text-sm",
  lg: "h-11 px-4 text-base",
};

const ICON_SIZES: Record<ButtonSize, string> = {
  sm: "h-7 w-7",
  md: "h-9 w-9",
  lg: "h-11 w-11",
};

/** 所有按钮都从这里出，焦点样式由全局 :focus-visible 统一。 */
export function Button({ variant = "secondary", size = "md", className, type = "button", ...rest }: ButtonProps) {
  const sizing = variant === "icon" ? ICON_SIZES[size] : SIZES[size];
  return <button type={type} className={cx(BASE, VARIANTS[variant], sizing, className)} {...rest} />;
}

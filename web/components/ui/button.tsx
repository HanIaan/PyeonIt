// 사용: <Button variant="secondary" loading={busy} onClick={save}>저장</Button> · 링크: <Link className={buttonClass("primary")} href="/x">이동</Link>
import type { ButtonHTMLAttributes } from "react";

type Variant = "primary" | "secondary" | "ghost";
const variants: Record<Variant, string> = {
  primary: "bg-lime text-ink hover:bg-lime/85",
  secondary: "border border-ink text-ink hover:bg-ink/5",
  ghost: "text-ink hover:bg-ink/5",
};

export function buttonClass(variant: Variant = "primary", className = "") {
  return `inline-flex min-h-11 items-center justify-center gap-2 rounded-control px-4 text-label font-semibold transition-transform active:scale-[0.98] disabled:cursor-not-allowed disabled:opacity-45 disabled:active:scale-100 ${variants[variant]} ${className}`;
}

export function Button({
  variant = "primary",
  loading = false,
  disabled,
  className,
  children,
  type = "button",
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: Variant;
  loading?: boolean;
}) {
  return (
    <button
      type={type}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={buttonClass(variant, className)}
      {...props}
    >
      {loading && (
        <span
          aria-hidden="true"
          className="size-4 animate-spin rounded-pill border-2 border-current border-t-transparent"
        />
      )}
      {children}
    </button>
  );
}

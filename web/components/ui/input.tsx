// 사용: <Input label="닉네임" value={v} onChange={…} error={err} />
import { useId, type InputHTMLAttributes } from "react";

export function Input({
  label,
  error,
  id,
  className = "",
  ...props
}: InputHTMLAttributes<HTMLInputElement> & { label: string; error?: string }) {
  const auto = useId();
  const inputId = id ?? auto;
  const errorId = `${inputId}-error`;
  return (
    <div className="space-y-1.5">
      <label htmlFor={inputId} className="block text-label font-medium">
        {label}
      </label>
      <input
        id={inputId}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? errorId : undefined}
        className={`min-h-11 w-full rounded-control border border-line-strong bg-surface px-3 text-body placeholder:text-ink-3 aria-invalid:border-danger disabled:opacity-45 ${className}`}
        {...props}
      />
      {error && (
        <p id={errorId} className="text-label text-danger">
          {error}
        </p>
      )}
    </div>
  );
}

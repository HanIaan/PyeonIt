"use client";
// 사용: <Segmented label="정렬" options={[{ value: "new", label: "최신" }, { value: "hot", label: "인기" }]} value={v} onChange={setV} />
// 탭으로 쓸 때 id="sort" 를 주고 패널에 id="sort-panel-<value>" role="tabpanel" aria-labelledby="sort-tab-<value>" 를 단다.
import { useRef } from "react";

export function Segmented<T extends string>({
  label,
  options,
  value,
  onChange,
  id,
}: {
  label: string;
  options: { value: T; label: string }[];
  value: T;
  onChange: (value: T) => void;
  id?: string;
}) {
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  const move = (index: number) => {
    const next = (index + options.length) % options.length;
    onChange(options[next].value);
    refs.current[next]?.focus();
  };
  return (
    <div
      role="tablist"
      aria-label={label}
      className="inline-flex max-w-full gap-1 overflow-x-auto rounded-pill border border-line bg-surface p-1"
    >
      {options.map((option, index) => {
        const selected = option.value === value;
        return (
          <button
            key={option.value}
            ref={(el) => {
              refs.current[index] = el;
            }}
            type="button"
            role="tab"
            id={id && `${id}-tab-${option.value}`}
            aria-controls={id && `${id}-panel-${option.value}`}
            aria-selected={selected}
            tabIndex={selected ? 0 : -1}
            onClick={() => onChange(option.value)}
            onKeyDown={(e) => {
              const step = { ArrowRight: 1, ArrowLeft: -1 }[e.key];
              if (step) move(index + step);
              else if (e.key === "Home") move(0);
              else if (e.key === "End") move(options.length - 1);
              else return;
              e.preventDefault();
            }}
            className="min-h-11 shrink-0 rounded-pill px-4 text-label font-semibold text-ink-2 transition-transform hover:text-ink active:scale-[0.98] aria-selected:bg-lime aria-selected:text-ink"
          >
            {option.label}
          </button>
        );
      })}
    </div>
  );
}

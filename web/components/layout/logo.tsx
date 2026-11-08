// 사용: <Logo /> (심볼+편잇) · <LogoSymbol className="size-6" />
// 임시 — 최종 벡터 로고로 교체 예정. 브랜드 보드 v02 의 꺾인 화살표+반쯤 감긴 눈을 단순화한 것.
export function LogoSymbol({ className = "size-7" }: { className?: string }) {
  return (
    <svg viewBox="0 0 48 48" aria-hidden="true" className={className}>
      <path
        fill="currentColor"
        d="M5 42V28C5 18 12 12 22 12h6V5l16 14-16 14v-7h-6c-3 0-4 2-4 5v11c0 1.1-.9 2-2 2H7c-1.1 0-2-.9-2-2Z"
      />
      <path
        fill="var(--color-paper)"
        d="M17.4 18a2.6 2.6 0 0 0 5.2 0Zm7 0a2.6 2.6 0 0 0 5.2 0Z"
      />
    </svg>
  );
}

export function Logo() {
  return (
    <span className="inline-flex items-center gap-1.5 text-ink">
      <LogoSymbol />
      {/* Black Han Sans 는 로고 임시 대체 서체(원본 레터링 아님) */}
      <span className="font-logo text-[1.375rem] leading-none">편잇</span>
    </span>
  );
}

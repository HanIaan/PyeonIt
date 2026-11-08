import Link from "next/link";
import { LogoSymbol } from "@/components/layout/logo";
import { buttonClass } from "@/components/ui/button";

const links = [
  ["/notices", "모집 공고"],
  ["/universities", "학교 찾기"],
  ["/calendar", "전형 일정"],
  ["/community", "커뮤니티"],
] as const;

export default function Page() {
  return (
    <div className="py-6 md:py-12">
      <LogoSymbol className="size-14 text-ink md:size-16" />
      <h1 className="mt-6 text-display font-bold">편입 정보는 정확하게</h1>
      <Link href="/notices" className={buttonClass("primary", "mt-6 px-6")}>
        모집 공고 보기
      </Link>
      <nav aria-label="바로가기" className="mt-12">
        <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {links.map(([href, label]) => (
            <li key={href}>
              <Link
                href={href}
                className="flex min-h-20 items-center justify-between rounded-card border border-line bg-surface px-5 text-title font-semibold transition-transform hover:border-ink active:scale-[0.99]"
              >
                {label}
                <span aria-hidden="true">→</span>
              </Link>
            </li>
          ))}
        </ul>
      </nav>
    </div>
  );
}

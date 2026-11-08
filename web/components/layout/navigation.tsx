"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useMe } from "@/components/auth/auth-provider";
import { Logo } from "./logo";

const icon = {
  home: "M4 10.5 12 4l8 6.5V20h-5v-6h-6v6H4z",
  book: "M5 4h10a4 4 0 0 1 4 4v12H9a4 4 0 0 1-4-4zM5 16a4 4 0 0 1 4-4h10",
  calendar: "M4 6h16v14H4zM4 10h16M8 3v5M16 3v5",
  chat: "M4 5h16v11H9l-5 4z",
  user: "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21a8 8 0 0 1 16 0",
  search: "M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM20 20l-4-4",
};
type IconName = keyof typeof icon;

function Icon({ name }: { name: IconName }) {
  return (
    <svg
      viewBox="0 0 24 24"
      aria-hidden="true"
      className="size-6"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d={icon[name]} />
    </svg>
  );
}

export function Navigation() {
  const { state } = useMe();
  const pathname = usePathname();
  const items: [string, string, IconName][] = [
    ["/", "홈", "home"],
    ["/universities", "요강", "book"],
    ["/calendar", "일정", "calendar"],
    ["/community", "커뮤니티", "chat"],
    // 로그인 전에는 MY 대신 로그인 화면으로 보낸다.
    [state === "anonymous" ? "/login" : "/me", "MY", "user"],
  ];
  const active = (href: string) =>
    href === "/"
      ? pathname === "/"
      : pathname === href || pathname.startsWith(`${href}/`);
  const search = (
    <Link
      href="/community/search"
      aria-label="검색"
      className="ml-auto inline-flex size-11 items-center justify-center rounded-pill hover:bg-ink/5"
    >
      <Icon name="search" />
    </Link>
  );

  return (
    <>
      <header className="sticky top-0 z-30 border-b border-line bg-paper">
        <div className="mx-auto flex h-(--header-h) max-w-5xl items-center gap-6 px-4">
          <Link href="/" className="rounded-control">
            <Logo />
          </Link>
          <nav aria-label="주 메뉴" className="hidden md:block">
            <ul className="flex items-center gap-1">
              {items.map(([href, label]) => (
                <li key={label}>
                  <Link
                    href={href}
                    aria-current={active(href) ? "page" : undefined}
                    className="inline-flex min-h-11 items-center rounded-pill px-4 text-label font-semibold text-ink-2 hover:text-ink aria-[current=page]:bg-lime aria-[current=page]:text-ink"
                  >
                    {label}
                  </Link>
                </li>
              ))}
            </ul>
          </nav>
          {search}
        </div>
      </header>
      <nav
        aria-label="하단 메뉴"
        className="fixed inset-x-0 bottom-0 z-30 border-t border-line bg-paper pb-(--safe-bottom) md:hidden"
      >
        <ul className="grid h-(--bottom-nav-h) grid-cols-5">
          {items.map(([href, label, name]) => (
            <li key={label}>
              <Link
                href={href}
                aria-current={active(href) ? "page" : undefined}
                className="group flex h-full flex-col items-center justify-center gap-0.5 text-caption font-medium text-ink-2 aria-[current=page]:font-semibold aria-[current=page]:text-ink"
              >
                <span className="inline-flex h-7 w-12 items-center justify-center rounded-pill group-aria-[current=page]:bg-lime">
                  <Icon name={name} />
                </span>
                {label}
              </Link>
            </li>
          ))}
        </ul>
      </nav>
    </>
  );
}

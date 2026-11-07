import type { Metadata } from "next";
import Link from "next/link";
import "./globals.css";

export const metadata: Metadata = {
  title: "PyeonIt",
  description: "편입 준비생 커뮤니티",
};

const navItems = [
  { href: "/notices", label: "공고" },
  { href: "/calendar", label: "캘린더" },
  { href: "/community", label: "커뮤니티" },
];

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="ko" className="h-full antialiased">
      <body className="min-h-full flex flex-col">
        <header className="border-b border-black/10 dark:border-white/15">
          <nav className="mx-auto flex max-w-5xl flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3 text-sm sm:gap-x-6 sm:text-base">
            <Link href="/" className="font-bold">
              PyeonIt
            </Link>
            {navItems.map((item) => (
              <Link key={item.href} href={item.href} className="hover:underline">
                {item.label}
              </Link>
            ))}
            <Link href="/login" className="ml-auto hover:underline">
              로그인
            </Link>
          </nav>
        </header>
        <main className="mx-auto w-full max-w-5xl flex-1 px-4 py-6">
          {children}
        </main>
      </body>
    </html>
  );
}

"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useState } from "react";
import { useMe } from "@/components/auth/auth-provider";
import { apiErrorMessage } from "@/lib/api";
import { Message } from "@/components/auth/auth-ui";

const items = [
  ["/notices", "공고"],
  ["/universities", "학교"],
  ["/calendar", "캘린더"],
  ["/community", "커뮤니티"],
];
export function Navigation() {
  const { me, state, logout } = useMe();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const pathname = usePathname();
  return (
    <header className="border-b border-current/15">
      <nav
        aria-label="주 메뉴"
        className="mx-auto flex max-w-5xl flex-wrap items-center gap-4 px-4 py-3 text-sm"
      >
        <Link href="/" className="mr-auto text-lg font-bold tracking-tight">
          PyeonIt
        </Link>
        <button
          className="min-h-11 rounded-lg border border-current/20 px-3 sm:hidden"
          aria-expanded={open}
          aria-controls="main-menu"
          onClick={() => setOpen(!open)}
        >
          메뉴 {open ? "닫기" : "열기"}
        </button>
        <div
          id="main-menu"
          onClick={(e) => {
            if ((e.target as HTMLElement).closest("a")) setOpen(false);
          }}
          className={`${open ? "flex" : "hidden"} w-full flex-col gap-1 sm:flex sm:w-auto sm:flex-row sm:items-center sm:gap-3`}
        >
          {items.map(([href, label]) => (
            <Link
              key={href}
              href={href}
              aria-current={pathname === href ? "page" : undefined}
              className="rounded-lg px-2 py-3 hover:underline"
            >
              {label}
            </Link>
          ))}
          {state === "loading" ? (
            <span role="status">확인 중…</span>
          ) : state === "pending" ? (
            <Link href="/signup" className="px-2 py-3 font-semibold">
              가입 완료하기
            </Link>
          ) : me ? (
            <>
              {me.role === "ADMIN" && (
                <Link href="/admin" className="px-2 py-3">
                  관리
                </Link>
              )}
              <Link href="/me" className="px-2 py-3 font-semibold">
                {me.nickname}
              </Link>
              <button
                className="min-h-11 px-2 text-left hover:underline disabled:opacity-50"
                disabled={busy}
                onClick={async () => {
                  setBusy(true);
                  setError("");
                  try {
                    await logout();
                  } catch (e) {
                    setError(apiErrorMessage(e));
                  } finally {
                    setBusy(false);
                  }
                }}
              >
                로그아웃
              </button>
            </>
          ) : (
            <Link href="/login" className="px-2 py-3 font-semibold">
              로그인
            </Link>
          )}
        </div>
        <Message>{error}</Message>
      </nav>
    </header>
  );
}

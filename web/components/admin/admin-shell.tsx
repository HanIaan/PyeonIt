"use client";

import Link from "next/link";
import { buttonClass } from "@/components/ui/button";
import { usePathname } from "next/navigation";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
} from "react";
import { useMe } from "@/lib/auth";
import { ApiError, apiErrorMessage } from "@/lib/api";
import { ADMIN_MOCK, mockAdmin } from "@/lib/admin-api";

const FailureContext = createContext<(error: unknown) => void>(() => {});
export function useAdminFailure() {
  return useContext(FailureContext);
}
export const button = buttonClass("primary");
export const secondary = buttonClass("secondary", "shrink-0");
export const input =
  "min-h-11 w-full min-w-0 rounded-control border border-line-strong bg-surface px-3 py-2 text-label text-ink disabled:opacity-45";
export const table =
  "w-full text-left text-sm [&_th]:whitespace-nowrap [&_th]:bg-paper [&_th]:p-3 [&_th]:font-medium [&_td]:border-b [&_td]:border-line [&_td]:p-3";
export function AdminShell({ children }: { children: React.ReactNode }) {
  const auth = useMe();
  const pathname = usePathname();
  const [denied, setDenied] = useState<"login" | "forbidden" | null>(null);
  const onFailure = useCallback((error: unknown) => {
    if (error instanceof ApiError && error.status === 401) setDenied("login");
    if (error instanceof ApiError && error.status === 403)
      setDenied("forbidden");
  }, []);
  const me = ADMIN_MOCK ? mockAdmin : auth.me;
  let notice = "";
  if (
    denied === "login" ||
    (!ADMIN_MOCK && ["anonymous", "pending"].includes(auth.state))
  )
    notice = "관리자 로그인이 필요합니다.";
  else if (denied === "forbidden" || (me && me.role !== "ADMIN"))
    notice = "접근할 수 없습니다. 관리자 전용 화면입니다.";
  else if (!ADMIN_MOCK && auth.state === "loading")
    notice = "로그인 상태 확인 중…";
  else if (!ADMIN_MOCK && auth.state === "error")
    notice = auth.error || "로그인 상태를 확인하지 못했습니다.";
  if (notice || !me)
    return (
      <section className="rounded-card border border-line p-6">
        <h1 className="text-xl font-bold">관리자 접근 안내</h1>
        <p role="status" className="my-4">
          {notice}
        </p>
        {(denied === "login" ||
          (!ADMIN_MOCK && ["anonymous", "pending"].includes(auth.state))) && (
          <Link href="/login" className={buttonClass("ghost")}>
            로그인으로 이동
          </Link>
        )}
        {auth.state === "error" && !ADMIN_MOCK && (
          <button className={secondary} onClick={() => void auth.refresh()}>
            다시 확인
          </button>
        )}
      </section>
    );
  return (
    <FailureContext.Provider value={onFailure}>
      <section className="min-w-0 space-y-6 rounded-card bg-paper p-4 text-ink sm:p-6">
        <header>
          <div className="text-xs font-bold tracking-widest text-ink">
            PYEONIT · ADMIN
          </div>
          <h1 className="mt-2 text-2xl font-bold">운영 관리</h1>
          <p className="mt-1 text-sm text-ink-2">
            요강 · 커뮤니티
          </p>
          {ADMIN_MOCK && (
            <p className="mt-2 text-xs text-warning">
              목 관리자 · 예시 데이터 (변경 내용은 현재 실행 중에만 유지됩니다)
            </p>
          )}
        </header>
        <nav
          aria-label="관리자 메뉴"
          className="flex flex-wrap gap-2 border-b border-line pb-4"
        >
          {[
            ["/admin", "요약"],
            ["/admin/documents", "요강 승인"],
            ["/admin/reports", "신고 처리"],
            ["/admin/sanctions", "회원 제재"],
          ].map(([href, label]) => (
            <Link
              key={href}
              href={href}
              aria-current={pathname === href ? "page" : undefined}
              className={`inline-flex min-h-11 items-center rounded-pill px-4 py-2 text-label font-semibold transition-transform active:scale-[0.98] ${pathname === href ? "bg-lime text-ink" : "bg-surface hover:bg-paper"}`}
            >
              {label}
            </Link>
          ))}
        </nav>
        {children}
      </section>
    </FailureContext.Provider>
  );
}
export function errorMessage(error: unknown) {
  if (error instanceof ApiError) {
    const messages: Record<string, string> = {
      DOCUMENT_ALREADY_REVIEWED:
        "이미 승인 또는 반려된 요강입니다. 목록을 새로고침해 주세요.",
      EXTRACTION_UNUSABLE:
        "사용할 수 없는 추출 결과입니다. 추출 누락·오류와 추출 이력을 확인해 주세요.",
      REPORT_ALREADY_HANDLED:
        "이미 처리된 신고입니다. 목록을 새로고침해 주세요.",
      SANCTION_NOT_ACTIVE:
        "이미 종료되었거나 활성 상태가 아닌 제재입니다. 목록을 새로고침해 주세요.",
      FORBIDDEN: "관리자 권한이 없습니다.",
    };
    return messages[error.code] ?? apiErrorMessage(error);
  }
  return apiErrorMessage(error);
}
export function useAdminResource<T>(load: () => Promise<T>) {
  const fail = useAdminFailure();
  const [state, setState] = useState<{
    data?: T;
    error?: string;
    loading: boolean;
  }>({ loading: true });
  const [version, setVersion] = useState(0);
  useEffect(() => {
    let active = true;
    load().then(
      (data) => {
        if (active) setState({ data, loading: false });
      },
      (error) => {
        if (active) {
          fail(error);
          setState({ error: errorMessage(error), loading: false });
        }
      },
    );
    return () => {
      active = false;
    };
  }, [load, version, fail]);
  const reload = () => {
    setState({ loading: true });
    setVersion((n) => n + 1);
  };
  return { ...state, reload };
}
export function ResourceState({
  loading,
  error,
  reload,
}: {
  loading: boolean;
  error?: string;
  reload: () => void;
}) {
  if (loading)
    return (
      <p role="status" className="p-6 text-ink-2">
        불러오는 중…
      </p>
    );
  if (error)
    return (
      <div
        role="alert"
        className="rounded-control border border-danger/30 bg-danger/5 p-4"
      >
        <p>{error}</p>
        <button className={`${secondary} mt-3`} onClick={reload}>
          다시 시도
        </button>
      </div>
    );
  return null;
}
export function Pagination({
  page,
  totalPages,
  onChange,
}: {
  page: number;
  totalPages: number;
  onChange: (page: number) => void;
}) {
  return (
    <div className="mt-4 flex items-center justify-end gap-3 text-sm">
      <button
        className={secondary}
        disabled={page === 0}
        onClick={() => onChange(page - 1)}
      >
        이전
      </button>
      <span>
        {page + 1} / {Math.max(totalPages, 1)}
      </span>
      <button
        className={secondary}
        disabled={page + 1 >= totalPages}
        onClick={() => onChange(page + 1)}
      >
        다음
      </button>
    </div>
  );
}
export function formatDate(value: string | null) {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : date.toLocaleString("ko-KR", { timeZone: "Asia/Seoul" });
}

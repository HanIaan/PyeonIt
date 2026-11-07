"use client";

import Link from "next/link";
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
export const button =
  "rounded-lg bg-teal-800 px-4 py-2 text-sm font-semibold text-white hover:bg-teal-900 disabled:cursor-not-allowed disabled:opacity-40";
export const secondary =
  "rounded-lg border border-slate-300 px-4 py-2 text-sm font-medium hover:bg-slate-100 disabled:opacity-40";
export const input =
  "w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900";
export const table =
  "w-full text-left text-sm [&_th]:whitespace-nowrap [&_th]:bg-slate-100 [&_th]:p-3 [&_th]:font-medium [&_td]:border-b [&_td]:border-slate-100 [&_td]:p-3";
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
      <section className="rounded-xl border border-slate-200 p-6">
        <h1 className="text-xl font-bold">관리자 접근 안내</h1>
        <p role="status" className="my-4">
          {notice}
        </p>
        {(denied === "login" ||
          (!ADMIN_MOCK && ["anonymous", "pending"].includes(auth.state))) && (
          <Link href="/login" className="underline">
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
      <section className="space-y-6 rounded-2xl bg-slate-50 p-4 text-slate-900 sm:p-6">
        <header>
          <div className="text-xs font-bold tracking-widest text-teal-800">
            PYEONIT · ADMIN
          </div>
          <h1 className="mt-2 text-2xl font-bold">운영 관리</h1>
          <p className="mt-1 text-sm text-slate-600">
            요강 검토와 커뮤니티 운영을 한곳에서 관리합니다.
          </p>
          {ADMIN_MOCK && (
            <p className="mt-2 text-xs text-amber-800">
              목 관리자 · 예시 데이터 (변경 내용은 현재 실행 중에만 유지됩니다)
            </p>
          )}
        </header>
        <nav
          aria-label="관리자 메뉴"
          className="flex flex-wrap gap-2 border-b border-slate-200 pb-4"
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
              className={`rounded-lg px-4 py-2 text-sm font-semibold ${pathname === href ? "bg-teal-800 text-white" : "bg-white hover:bg-slate-100"}`}
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
      <p role="status" className="p-6 text-slate-600">
        불러오는 중…
      </p>
    );
  if (error)
    return (
      <div
        role="alert"
        className="rounded-lg border border-red-200 bg-red-50 p-4"
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

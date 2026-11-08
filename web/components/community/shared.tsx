"use client";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMe } from "@/lib/auth";
import { ApiError, apiErrorMessage, redirectIfAuthRequired } from "@/lib/api";
import type { S } from "@/lib/community-api";
import { buttonClass } from "@/components/ui/button";
export const button = buttonClass("secondary");
export const primary = buttonClass("primary");
export const ghost = buttonClass("ghost");
export const input =
  "min-h-11 w-full rounded-control border border-line-strong bg-surface px-3 py-2.5 text-body placeholder:text-ink-3 disabled:opacity-45";
export const fieldLabel = "block space-y-1.5 text-label font-medium";
export function errorText(e: unknown) {
  if (e instanceof ApiError) {
    if (e.code === "ACCOUNT_SUSPENDED")
      return "정지 중이라 작성할 수 없습니다. 읽기는 가능합니다.";
    if (e.code === "ALREADY_REPORTED") return "이미 신고한 글입니다.";
    if (e.code === "REPLY_DEPTH_EXCEEDED")
      return "답글은 한 단계까지만 작성할 수 있습니다.";
    if (e.status === 413)
      return "이미지 크기가 너무 큽니다. 5MB 이하로 올려 주세요.";
    if (e.status === 422)
      return e.message || "입력값과 이미지 형식을 확인해 주세요.";
  }
  return e instanceof Error && !(e instanceof ApiError)
    ? e.message
    : apiErrorMessage(e);
}
export function useCommunityAuth() {
  const { me, state } = useMe();
  const router = useRouter();
  return {
    me,
    state,
    check: () => {
      if (state === "loading")
        throw new Error(
          "로그인 상태를 확인 중입니다. 잠시 후 다시 시도해 주세요.",
        );
      if (state === "error")
        throw new Error(
          "로그인 상태를 확인하지 못했습니다. 새로고침 후 다시 시도해 주세요.",
        );
      if (state === "pending") {
        router.push("/signup");
        return false;
      }
      if (!me) {
        router.push("/login");
        return false;
      }
      if (me.status === "SUSPENDED")
        throw new ApiError(
          403,
          "ACCOUNT_SUSPENDED",
          "정지 중이라 작성할 수 없습니다.",
        );
      return true;
    },
  };
}
export function handleError(e: unknown, set: (s: string) => void) {
  if (!redirectIfAuthRequired(e)) set(errorText(e));
}
export function Message({ children }: { children: React.ReactNode }) {
  return children ? (
    <p
      role="status"
      className="rounded-control border border-line-strong bg-surface p-4 text-label"
    >
      {children}
    </p>
  ) : null;
}
export function Frame({
  title,
  action,
  back = true,
  children,
}: {
  title: string;
  action?: React.ReactNode;
  back?: boolean;
  children: React.ReactNode;
}) {
  return (
    <section className="mx-auto max-w-3xl space-y-6">
      {back && (
        <Link
          href="/community"
          className="inline-flex min-h-11 items-center rounded-control text-label font-medium text-ink-2 hover:text-ink"
        >
          ← 커뮤니티
        </Link>
      )}
      <div className="flex items-start justify-between gap-4">
        <h1 className="min-w-0 break-words text-heading font-bold">{title}</h1>
        {action}
      </div>
      {children}
    </section>
  );
}
export function Tags({ schools }: { schools: S["UniversitySummary"][] }) {
  return (
    <span className="flex flex-wrap gap-2">
      {schools.map((u) => (
        <Link
          className="rounded-pill border border-line px-2.5 py-0.5 text-caption font-medium text-ink-2 hover:border-ink hover:text-ink"
          key={u.id}
          href={`/community?universityId=${u.id}`}
        >
          {u.name} {u.campus}
        </Link>
      ))}
    </span>
  );
}
export function authorName(author: S["UserSummary"] | null) {
  return author?.nickname ?? "탈퇴한 사용자";
}
export function dateLabel(value: string) {
  return new Date(value).toLocaleDateString("ko-KR", {
    timeZone: "Asia/Seoul",
  });
}
export function Pager({
  page,
  total,
  onChange,
}: {
  page: number;
  total: number;
  onChange: (n: number) => void;
}) {
  return (
    <nav
      aria-label="페이지 이동"
      className="flex items-center justify-center gap-3 py-4"
    >
      <button
        className={button}
        disabled={page <= 0}
        onClick={() => onChange(page - 1)}
      >
        이전
      </button>
      <span className="min-w-16 text-center text-label text-ink-2 tabular-nums">
        {page + 1} / {Math.max(1, total)}
      </span>
      <button
        className={button}
        disabled={page + 1 >= total}
        onClick={() => onChange(page + 1)}
      >
        다음
      </button>
    </nav>
  );
}

export function AuthorLink({ author }: { author: S["UserSummary"] | null }) {
  return author?.nickname != null ? (
    <Link
      className="font-medium text-ink-2 hover:text-ink hover:underline"
      href={`/users/${author.id}`}
    >
      {author.nickname}
    </Link>
  ) : (
    <span>탈퇴한 사용자</span>
  );
}
